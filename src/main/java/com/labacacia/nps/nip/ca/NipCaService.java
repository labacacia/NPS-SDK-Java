// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.AssuranceLevel;
import com.labacacia.nps.nip.IdentCertFormat;
import com.labacacia.nps.nip.NipErrorCodes;
import com.labacacia.nps.nip.NipIdentity;
import com.labacacia.nps.nip.ca.ra.AllowlistPolicy;
import com.labacacia.nps.nip.ca.ra.BootstrapTokenPolicy;
import com.labacacia.nps.nip.ca.ra.IBootstrapTokenStore;
import com.labacacia.nps.nip.ca.ra.IEnrollmentPolicy;
import com.labacacia.nps.nip.ca.ra.IPendingStore;
import com.labacacia.nps.nip.ca.ra.PendingQueuePolicy;
import com.labacacia.nps.nip.x509.Ed25519PublicKeys;
import com.labacacia.nps.nip.x509.NipX509Builder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Core CA business logic: issue, renew, revoke, and verify NID certificates
 * (NPS-3 §6–8, NPS-CR-0003, NPS-CR-0005). All signing uses the CA's Ed25519
 * private key. Faithful port of the .NET {@code NipCaService}.
 *
 * <p>The signed IdentFrame payload is canonicalised recursively (alphabetical
 * keys, snake_case, no whitespace) and the {@code assurance_level} / {@code lineage}
 * fields are omitted when absent so frames issued without those features stay
 * bit-compatible with pre-RFC-0003 / pre-CR-0003 verifiers.
 */
public final class NipCaService {

    // Lineage roles (NPS-CR-0003 §5.1.3).
    public static final String ROLE_GROUP   = "group";
    public static final String ROLE_SESSION = "session";

    private static final DateTimeFormatter ISO =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final NipCaOptions opts;
    private final INipCaStore  store;
    private final PrivateKey   caPriv;
    private final PublicKey    caPub;

    public NipCaService(NipCaOptions opts, INipCaStore store, PrivateKey caPriv, PublicKey caPub) {
        this.opts   = opts;
        this.store  = store;
        this.caPriv = caPriv;
        this.caPub  = caPub;
    }

    /** Convenience constructor using a {@link NipIdentity} for the CA key. */
    public NipCaService(NipCaOptions opts, INipCaStore store, NipIdentity caKey) {
        this(opts, store, caKey.privateKey(), caKey.pubKey());
    }

    // ── Register (Agent / Node) ───────────────────────────────────────────────

    public NipIssuedFrame register(
            String entityType, String identifier, String pubKey,
            List<String> capabilities, String scopeJson, String metadataJson) {
        String nid = buildNid(entityType, identifier);
        if (store.getByNid(nid) != null)
            throw new NipCaException("NID already exists: " + nid, NipErrorCodes.CA_NID_ALREADY_EXISTS);

        checkCapabilities(capabilities);

        int validDays = "node".equals(entityType) ? opts.nodeCertValidityDays : opts.agentCertValidityDays;
        Instant now = nowSecs();
        Instant expiresAt = now.plus(Duration.ofDays(validDays));
        String serial = store.nextSerial();

        NipIssuedFrame frame = issueFrame(nid, pubKey, capabilities, scopeJson,
            now, expiresAt, serial, metadataJson, null, null);

        store.save(NipCertRecord.builder()
            .nid(nid).entityType(entityType).serial(serial).pubKey(pubKey)
            .capabilities(capabilities).scopeJson(scopeJson).issuedBy(opts.caNid)
            .issuedAt(now).expiresAt(expiresAt).metadataJson(metadataJson).build());

        return frame;
    }

    // ── Register with RA gate (NPS-CR-0005) ───────────────────────────────────

    public NipIssuedFrame registerWithRa(
            String entityType, String identifier, String pubKey,
            List<String> capabilities, String scopeJson, String metadataJson,
            String enrollmentToken, IEnrollmentPolicy enrollmentPolicy) {
        if (enrollmentPolicy != null) {
            enrollmentPolicy.check(entityType, identifier, pubKey,
                capabilities, scopeJson, metadataJson, enrollmentToken);
        }
        return register(entityType, identifier, pubKey, capabilities, scopeJson, metadataJson);
    }

    /** Builds the {@link IEnrollmentPolicy} selected by {@link NipCaOptions#enrollmentTier}. */
    public static IEnrollmentPolicy createEnrollmentPolicy(
            NipCaOptions opts, IBootstrapTokenStore bootstrapTokenStore, IPendingStore pendingStore) {
        switch (opts.enrollmentTier) {
            case ALLOWLIST:
                return new AllowlistPolicy(opts.enrollmentAllowlistPatterns);
            case BOOTSTRAP_TOKEN:
                if (bootstrapTokenStore == null)
                    throw new IllegalStateException(
                        "EnrollmentTier.BOOTSTRAP_TOKEN requires an IBootstrapTokenStore.");
                return new BootstrapTokenPolicy(bootstrapTokenStore);
            case PENDING_QUEUE:
                if (pendingStore == null)
                    throw new IllegalStateException(
                        "EnrollmentTier.PENDING_QUEUE requires an IPendingStore.");
                return new PendingQueuePolicy(pendingStore, opts.pendingQueueMaxSize);
            default:
                throw new IllegalStateException("Unknown EnrollmentTier: " + opts.enrollmentTier);
        }
    }

    // ── Register X.509 (NPS-RFC-0002 prototype) ───────────────────────────────

    public NipIssuedFrame registerX509(
            String entityType, String identifier, String pubKey,
            List<String> capabilities, String scopeJson,
            X509Certificate rootCert, AssuranceLevel assuranceLevel, String metadataJson) {
        if (rootCert == null) rootCert = caRootCert();
        AssuranceLevel level = assuranceLevel != null ? assuranceLevel : AssuranceLevel.ANONYMOUS;

        String nid = buildNid(entityType, identifier);
        if (store.getByNid(nid) != null)
            throw new NipCaException("NID already exists: " + nid, NipErrorCodes.CA_NID_ALREADY_EXISTS);

        checkCapabilities(capabilities);

        int validDays = "node".equals(entityType) ? opts.nodeCertValidityDays : opts.agentCertValidityDays;
        Instant now = nowSecs();
        Instant expiresAt = now.plus(Duration.ofDays(validDays));
        String serial = store.nextSerial();

        // v1 frame first — carries the CA Ed25519 signature + assurance level.
        NipIssuedFrame v1 = issueFrame(nid, pubKey, capabilities, scopeJson,
            now, expiresAt, serial, metadataJson, level, null);

        byte[] subjectRaw = extractEd25519Raw(pubKey);
        BigInteger leafSerial = parseSerialBytes(serial);
        NipX509Builder.LeafRole role = "node".equals(entityType)
            ? NipX509Builder.LeafRole.NODE : NipX509Builder.LeafRole.AGENT;

        X509Certificate leaf = NipX509Builder.issueLeaf(
            nid, subjectRaw, caPriv, opts.caNid, role, level, now, expiresAt, leafSerial);

        List<String> chain;
        try {
            chain = List.of(b64url(leaf.getEncoded()), b64url(rootCert.getEncoded()));
        } catch (Exception e) {
            throw new NipCaException("Failed to encode X.509 chain: " + e.getMessage(),
                NipErrorCodes.CERT_FORMAT_INVALID);
        }

        store.save(NipCertRecord.builder()
            .nid(nid).entityType(entityType).serial(serial).pubKey(pubKey)
            .capabilities(capabilities).scopeJson(scopeJson).issuedBy(opts.caNid)
            .issuedAt(now).expiresAt(expiresAt).metadataJson(metadataJson).build());

        Map<String, Object> dict = new LinkedHashMap<>(v1.toDict());
        dict.put("cert_format", IdentCertFormat.V2_X509);
        dict.put("cert_chain", chain);
        return new NipIssuedFrame(dict);
    }

    /** Self-signed root certificate for this CA (RFC-0002 §4.1, X.509 registration path). */
    public X509Certificate caRootCert() {
        byte[] serial = new byte[16];
        RNG.nextBytes(serial);
        serial[0] &= 0x7F;
        if (serial[0] == 0) serial[0] = 0x01;
        Instant now = nowSecs();
        byte[] caPubRaw = Ed25519PublicKeys.extractRaw(caPub);
        return NipX509Builder.issueRoot(opts.caNid, caPriv, caPubRaw,
            now, now.plus(Duration.ofDays(3650)), new BigInteger(1, serial));
    }

    // ── Register Group (NPS-CR-0003) ──────────────────────────────────────────

    public NipIssuedFrame registerGroup(
            String identifier, String pubKey, List<String> capabilities, String scopeJson,
            String ownerUserId, String ownerKeyId, String metadataJson) {
        if (identifier == null || identifier.isEmpty()) {
            identifier = "group-" + HexFormat.of().formatHex(randomBytes(16));
        } else if (!identifier.startsWith("group-")) {
            throw new NipCaException(
                "Group identifier MUST start with reserved prefix 'group-' (got '" + identifier + "'). NPS-3 §3.1.",
                NipErrorCodes.CA_NID_ALREADY_EXISTS);
        }

        String nid = buildNid("agent", identifier);
        if (store.getByNid(nid) != null)
            throw new NipCaException("NID already exists: " + nid, NipErrorCodes.CA_NID_ALREADY_EXISTS);

        checkCapabilities(capabilities);

        Instant now = nowSecs();
        Instant expiresAt = now.plus(Duration.ofDays(opts.groupCertValidityDays));
        String serial = store.nextSerial();

        Map<String, Object> lineage = lineage(ROLE_GROUP, null, null, null, null, ownerUserId, ownerKeyId);
        String lineageJson = toJson(lineage);

        NipIssuedFrame frame = issueFrame(nid, pubKey, capabilities, scopeJson,
            now, expiresAt, serial, metadataJson, null, lineage);

        store.save(NipCertRecord.builder()
            .nid(nid).entityType("agent").serial(serial).pubKey(pubKey)
            .capabilities(capabilities).scopeJson(scopeJson).issuedBy(opts.caNid)
            .issuedAt(now).expiresAt(expiresAt).metadataJson(metadataJson)
            .nidRole(ROLE_GROUP).parentNid(null).lineageJson(lineageJson).build());

        return frame;
    }

    // ── Issue Session (NPS-CR-0003) ───────────────────────────────────────────

    public NipIssuedFrame issueSession(
            String groupNid, String sessionPubKey, Duration validity, String purpose,
            List<String> capabilities, String scopeJson, String metadataJson) {
        NipCertRecord group = store.getByNid(groupNid);
        if (group == null)
            throw new NipCaException("Group NID not found: " + groupNid + ".",
                NipErrorCodes.CA_PARENT_NOT_FOUND);
        if (!ROLE_GROUP.equals(group.nidRole()))
            throw new NipCaException(
                "NID '" + groupNid + "' is not registered as a group (role='"
                    + (group.nidRole() == null ? "<null>" : group.nidRole()) + "').",
                NipErrorCodes.CA_PARENT_NOT_GROUP);
        if (group.isRevoked())
            throw new NipCaException(
                "Group " + groupNid + " was revoked; cannot issue new sessions.",
                NipErrorCodes.CA_GROUP_REVOKED);
        if (Instant.now().isAfter(group.expiresAt()))
            throw new NipCaException(
                "Group " + groupNid + " expired; cannot issue new sessions.",
                NipErrorCodes.CERT_EXPIRED);

        Duration v = validity != null ? validity : opts.sessionDefaultValidity;
        if (v.compareTo(opts.sessionMinValidity) < 0 || v.compareTo(opts.sessionMaxValidity) > 0)
            throw new NipCaException(
                "Session validity must be in [" + opts.sessionMinValidity + ", "
                    + opts.sessionMaxValidity + "]; got " + v + ".",
                NipErrorCodes.CA_SESSION_VALIDITY_INVALID);

        List<String> sessionCaps = capabilities != null ? capabilities : group.capabilities();
        if (capabilities != null) {
            Set<String> groupCaps = new HashSet<>(group.capabilities());
            List<String> expansion = new ArrayList<>();
            for (String c : sessionCaps) if (!groupCaps.contains(c)) expansion.add(c);
            if (!expansion.isEmpty())
                throw new NipCaException(
                    "Session capabilities not in parent group: " + String.join(", ", expansion) + ".",
                    NipErrorCodes.CA_SCOPE_EXPANSION_DENIED);
        }
        String sessionScopeJson = scopeJson != null ? scopeJson : group.scopeJson();

        long unixSeconds = Instant.now().getEpochSecond();
        String sessionId = "session-" + unixSeconds + "-" + HexFormat.of().formatHex(randomBytes(8));
        String sessionNid = buildNid("agent", sessionId);

        Instant now = nowSecs();
        Instant expiresAt = now.plus(v);
        String serial = store.nextSerial();

        Map<String, Object> lineage = lineage(ROLE_SESSION, groupNid, groupNid, sessionId, purpose,
            extractLineageString(group.lineageJson(), "owner_user_id"),
            extractLineageString(group.lineageJson(), "owner_key_id"));
        String lineageJson = toJson(lineage);

        NipIssuedFrame frame = issueFrame(sessionNid, sessionPubKey, sessionCaps, sessionScopeJson,
            now, expiresAt, serial, metadataJson, null, lineage);

        store.save(NipCertRecord.builder()
            .nid(sessionNid).entityType("agent").serial(serial).pubKey(sessionPubKey)
            .capabilities(sessionCaps).scopeJson(sessionScopeJson).issuedBy(opts.caNid)
            .issuedAt(now).expiresAt(expiresAt).metadataJson(metadataJson)
            .nidRole(ROLE_SESSION).parentNid(groupNid).lineageJson(lineageJson).build());

        return frame;
    }

    /** Lists every session NID issued under {@code groupNid} (NPS-CR-0003 §8). */
    public List<NipCertRecord> listSessions(String groupNid) {
        return store.getByParentNid(groupNid);
    }

    /** Returns the persisted record for {@code nid}, or null. */
    public NipCertRecord getCert(String nid) {
        return store.getByNid(nid);
    }

    // ── Renew ─────────────────────────────────────────────────────────────────

    public NipIssuedFrame renew(String nid) {
        NipCertRecord record = store.getByNid(nid);
        if (record == null)
            throw new NipCaException("NID not found: " + nid, NipErrorCodes.CA_NID_NOT_FOUND);
        if (record.isRevoked())
            throw new NipCaException("NID is revoked: " + nid, NipErrorCodes.CERT_REVOKED);

        Instant now = nowSecs();
        Instant renewWindowStart = record.expiresAt().minus(Duration.ofDays(opts.renewalWindowDays));
        if (now.isBefore(renewWindowStart))
            throw new NipCaException(
                "Renewal window opens " + ISO.format(renewWindowStart) + ". Too early to renew.",
                NipErrorCodes.CA_RENEWAL_TOO_EARLY);

        int validDays = "node".equals(record.entityType()) ? opts.nodeCertValidityDays : opts.agentCertValidityDays;
        Instant expiresAt = now.plus(Duration.ofDays(validDays));
        String serial = store.nextSerial();

        NipIssuedFrame frame = issueFrame(nid, record.pubKey(), record.capabilities(), record.scopeJson(),
            now, expiresAt, serial, record.metadataJson(), null, null);

        store.save(NipCertRecord.builder()
            .nid(nid).entityType(record.entityType()).serial(serial).pubKey(record.pubKey())
            .capabilities(record.capabilities()).scopeJson(record.scopeJson()).issuedBy(opts.caNid)
            .issuedAt(now).expiresAt(expiresAt).metadataJson(record.metadataJson()).build());

        return frame;
    }

    // ── Revoke ────────────────────────────────────────────────────────────────

    public com.labacacia.nps.nip.RevokeFrame revoke(String nid, String reason) {
        NipCertRecord record = store.getByNid(nid);
        if (record == null)
            throw new NipCaException("NID not found: " + nid, NipErrorCodes.CA_NID_NOT_FOUND);

        Instant now = nowSecs();
        if (!store.revoke(nid, reason, now))
            throw new NipCaException("Failed to revoke " + nid + ".", NipErrorCodes.CA_NID_NOT_FOUND);

        // Cascade revoke live sessions if this is a group.
        if (ROLE_GROUP.equals(record.nidRole())) {
            for (NipCertRecord child : store.getByParentNid(nid)) {
                if (child.isRevoked()) continue;
                store.revoke(child.nid(), "parent_revoked", now);
            }
        }

        String revokedAtStr = ISO.format(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("frame", "0x22");
        payload.put("target_nid", nid);
        payload.put("serial", record.serial());
        payload.put("reason", reason);
        payload.put("revoked_at", revokedAtStr);
        payload.put("signer_nid", opts.caNid);
        String signature = signCanonical(payload);

        return new com.labacacia.nps.nip.RevokeFrame(
            nid, record.serial(), reason, revokedAtStr, null, opts.caNid, signature);
    }

    // ── Verify (OCSP) ─────────────────────────────────────────────────────────

    public NipVerifyResult verify(String nid) {
        NipCertRecord record = store.getByNid(nid);
        if (record == null)
            return NipVerifyResult.fail(NipErrorCodes.CA_NID_NOT_FOUND, "NID not found.");
        if (record.isRevoked())
            return NipVerifyResult.fail(NipErrorCodes.CERT_REVOKED,
                "Revoked at " + ISO.format(record.revokedAt()) + ": " + record.revokeReason());
        if (Instant.now().isAfter(record.expiresAt()))
            return NipVerifyResult.fail(NipErrorCodes.CERT_EXPIRED,
                "Expired at " + ISO.format(record.expiresAt()) + ".");

        // Chain check — NPS-3 §7 step 3a (NPS-CR-0003).
        if (record.parentNid() != null && !record.parentNid().isEmpty()) {
            NipCertRecord parent = store.getByNid(record.parentNid());
            if (parent == null)
                return NipVerifyResult.fail(NipErrorCodes.CERT_PARENT_REVOKED,
                    "Parent NID " + record.parentNid() + " not found.");
            if (parent.isRevoked())
                return NipVerifyResult.fail(NipErrorCodes.CERT_PARENT_REVOKED,
                    "Parent " + record.parentNid() + " revoked: " + parent.revokeReason());
            if (Instant.now().isAfter(parent.expiresAt()))
                return NipVerifyResult.fail(NipErrorCodes.CERT_PARENT_REVOKED,
                    "Parent " + record.parentNid() + " expired.");
        }

        return NipVerifyResult.ok(record);
    }

    // ── CRL / listing / signing ────────────────────────────────────────────────

    public List<NipCertRecord> getCrl()          { return store.getRevoked(); }
    public List<NipCertRecord> listCertificates() { return store.list(); }

    /** Signs an arbitrary CA-owned artifact with the CA Ed25519 key ({@code ed25519:<b64url>}). */
    public String signArtifact(Map<String, Object> artifact) { return signCanonical(artifact); }

    /** Returns the CA public key in {@code ed25519:<base64url>} form (raw 32-byte key). */
    public String getCaPublicKey() {
        return "ed25519:" + B64URL.encodeToString(Ed25519PublicKeys.extractRaw(caPub));
    }

    // ── NID builder ─────────────────────────────────────────────────────────────

    /** Builds a NID from the CA issuer domain and an entity-specific identifier. */
    public String buildNid(String entityType, String identifier) {
        String[] parts = opts.caNid.split(":");
        String domain = parts.length >= 4 ? parts[3] : opts.caNid;
        return "urn:nps:" + entityType + ":" + domain + ":" + identifier;
    }

    // ── Private: frame issuance + signing ────────────────────────────────────────

    private NipIssuedFrame issueFrame(
            String nid, String pubKey, List<String> capabilities, String scopeJson,
            Instant issuedAt, Instant expiresAt, String serial, String metadataJson,
            AssuranceLevel assuranceLevel, Map<String, Object> lineage) {
        Object scope = parseJson(scopeJson);
        String issuedAtStr = ISO.format(issuedAt);
        String expiresAtStr = ISO.format(expiresAt);

        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("capabilities", capabilities);
        signed.put("expires_at", expiresAtStr);
        signed.put("frame", "0x20");
        signed.put("issued_at", issuedAtStr);
        signed.put("issued_by", opts.caNid);
        signed.put("nid", nid);
        signed.put("pub_key", pubKey);
        signed.put("scope", scope);
        signed.put("serial", serial);
        if (assuranceLevel != null) signed.put("assurance_level", assuranceLevel.wire());
        if (lineage != null) signed.put("lineage", lineage);
        String signature = signCanonical(signed);

        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("frame", "0x20");
        dict.put("nid", nid);
        dict.put("pub_key", pubKey);
        dict.put("capabilities", capabilities);
        dict.put("scope", scope);
        dict.put("issued_by", opts.caNid);
        dict.put("issued_at", issuedAtStr);
        dict.put("expires_at", expiresAtStr);
        dict.put("serial", serial);
        dict.put("signature", signature);
        if (assuranceLevel != null) dict.put("assurance_level", assuranceLevel.wire());
        if (lineage != null) dict.put("lineage", lineage);
        if (metadataJson != null) dict.put("metadata", parseJson(metadataJson));
        return new NipIssuedFrame(dict);
    }

    /**
     * Signs {@code payload} with the CA key over its canonical JSON form:
     * recursive alphabetical key ordering, {@code signature}/{@code metadata}/
     * {@code frame}/{@code cert_format}/{@code cert_chain} excluded, no
     * whitespace — matching the .NET {@code NipSigner}.
     */
    private String signCanonical(Map<String, Object> payload) {
        try {
            byte[] data = canonicalJson(payload).getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(caPriv);
            signer.update(data);
            return "ed25519:" + B64URL.encodeToString(signer.sign());
        } catch (Exception e) {
            throw new RuntimeException("CA canonical sign failed", e);
        }
    }

    private static final Set<String> EXCLUDED =
        Set.of("signature", "frame", "metadata", "cert_format", "cert_chain", "health", "last_seen");

    /** Recursive canonical JSON (alphabetical keys, excluded fields dropped, compact). */
    private static String canonicalJson(Object obj) {
        JsonNode node = MAPPER.valueToTree(obj);
        StringBuilder sb = new StringBuilder();
        writeCanonical(node, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonNode el, StringBuilder sb) {
        if (el.isObject()) {
            sb.append('{');
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            el.fieldNames().forEachRemaining(n -> {
                if (!EXCLUDED.contains(n)) sorted.put(n, el.get(n));
            });
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(e.getKey())).append(':');
                writeCanonical(e.getValue(), sb);
            }
            sb.append('}');
        } else if (el.isArray()) {
            sb.append('[');
            for (int i = 0; i < el.size(); i++) {
                if (i > 0) sb.append(',');
                writeCanonical(el.get(i), sb);
            }
            sb.append(']');
        } else {
            sb.append(el.toString()); // Jackson emits valid JSON scalar text
        }
    }

    private static String quote(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s + "\"";
        }
    }

    // ── Private: helpers ─────────────────────────────────────────────────────────

    private void checkCapabilities(List<String> capabilities) {
        if (opts.allowedCapabilities == null) return;
        List<String> disallowed = new ArrayList<>();
        for (String c : capabilities) if (!opts.allowedCapabilities.contains(c)) disallowed.add(c);
        if (!disallowed.isEmpty())
            throw new NipCaException(
                "Capabilities not permitted by this CA: " + String.join(", ", disallowed),
                NipErrorCodes.CERT_CAPABILITY_MISSING);
    }

    private static Map<String, Object> lineage(
            String role, String parentNid, String groupNid, String sessionId,
            String purpose, String ownerUserId, String ownerKeyId) {
        // snake_case keys; absent fields omitted (parity with .NET SnakeCase + WhenWritingNull).
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        if (parentNid != null)   m.put("parent_nid", parentNid);
        if (groupNid != null)    m.put("group_nid", groupNid);
        if (sessionId != null)   m.put("session_id", sessionId);
        if (purpose != null)     m.put("purpose", purpose);
        if (ownerUserId != null) m.put("owner_user_id", ownerUserId);
        if (ownerKeyId != null)  m.put("owner_key_id", ownerKeyId);
        return m;
    }

    private static String extractLineageString(String lineageJson, String field) {
        if (lineageJson == null || lineageJson.isEmpty()) return null;
        try {
            JsonNode v = MAPPER.readTree(lineageJson).get(field);
            return (v != null && v.isTextual()) ? v.asText() : null;
        } catch (Exception e) { return null; }
    }

    private static Object parseJson(String json) {
        if (json == null) return null;
        try {
            return MAPPER.convertValue(MAPPER.readTree(json), Object.class);
        } catch (Exception e) {
            throw new NipCaException("Invalid JSON: " + e.getMessage(), NipErrorCodes.CERT_FORMAT_INVALID);
        }
    }

    private static String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException("JSON serialize failed", e); }
    }

    private static byte[] extractEd25519Raw(String encoded) {
        String prefix = "ed25519:";
        if (!encoded.startsWith(prefix))
            throw new NipCaException(
                "X.509 issuance requires an ed25519:* pubkey; got '" + encoded + "'.",
                NipErrorCodes.CERT_FORMAT_INVALID);
        byte[] raw = base64UrlDecode(encoded.substring(prefix.length()));
        if (raw.length != 32)
            throw new NipCaException(
                "Ed25519 pubkey must be 32 bytes; got " + raw.length + ".",
                NipErrorCodes.CERT_FORMAT_INVALID);
        return raw;
    }

    private static BigInteger parseSerialBytes(String serial) {
        String hex = serial.regionMatches(true, 0, "0x", 0, 2) ? serial.substring(2) : serial;
        if (hex.length() % 2 != 0) hex = "0" + hex;
        byte[] bytes = HexFormat.of().parseHex(hex);
        if (bytes.length == 0) bytes = new byte[]{0x01};
        return new BigInteger(1, bytes); // always positive
    }

    private static byte[] base64UrlDecode(String s) {
        // Accept both padded and unpadded base64url.
        try { return Base64.getUrlDecoder().decode(s); }
        catch (RuntimeException e) {
            throw new NipCaException("Invalid base64url: " + s, NipErrorCodes.CERT_FORMAT_INVALID);
        }
    }

    private static String b64url(byte[] data) { return B64URL.encodeToString(data); }

    private static byte[] randomBytes(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }

    private static Instant nowSecs() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }
}
