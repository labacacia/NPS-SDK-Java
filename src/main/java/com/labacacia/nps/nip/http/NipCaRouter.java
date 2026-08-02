// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.AssuranceLevel;
import com.labacacia.nps.nip.NipErrorCodes;
import com.labacacia.nps.nip.RevokeFrame;
import com.labacacia.nps.nip.ca.NipCaException;
import com.labacacia.nps.nip.ca.NipCaOptions;
import com.labacacia.nps.nip.ca.NipCaService;
import com.labacacia.nps.nip.ca.NipCertRecord;
import com.labacacia.nps.nip.ca.NipGroupJws;
import com.labacacia.nps.nip.ca.NipVerifyResult;
import com.labacacia.nps.nip.ca.ra.IBootstrapTokenStore;
import com.labacacia.nps.nip.ca.ra.IEnrollmentPolicy;
import com.labacacia.nps.nip.ca.ra.IPendingStore;
import com.labacacia.nps.nip.ca.ra.NipRaPendingException;
import com.labacacia.nps.nip.x509.Ed25519PublicKeys;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CA HTTP routes as a JDK {@link HttpHandler} (com.sun.net.httpserver, zero
 * third-party server deps), mirroring {@code AnchorNodeServer}. Faithful port of
 * the .NET {@code NipCaRouter}: same paths, wire field names, error codes, and
 * HTTP status codes (NPS-3 §8, NPS-CR-0003, NPS-CR-0005).
 *
 * <p>Mount at a context (typically {@code "/"}). Routes honour
 * {@link NipCaOptions#routePrefix}.
 */
public final class NipCaRouter implements HttpHandler {

    private static final ObjectMapper MAPPER =
        new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final DateTimeFormatter ISO =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final Pattern IDENTIFIER_RE = Pattern.compile("^[a-zA-Z0-9._:@/\\-]{1,256}$");
    private static final Set<String> VALID_REVOCATION_REASONS = Set.of(
        "key_compromise", "ca_compromise", "affiliation_changed",
        "superseded", "cessation_of_operation", "parent_revoked");

    private final NipCaOptions         opts;
    private final NipCaService         ca;
    private final IBootstrapTokenStore bootstrapTokenStore;
    private final IPendingStore        pendingStore;
    private final IEnrollmentPolicy    enrollmentPolicy;
    private final String               pfx;

    public NipCaRouter(NipCaOptions opts, NipCaService ca) {
        this(opts, ca, null, null);
    }

    public NipCaRouter(NipCaOptions opts, NipCaService ca,
                       IBootstrapTokenStore bootstrapTokenStore, IPendingStore pendingStore) {
        this.opts = opts;
        this.ca = ca;
        this.bootstrapTokenStore = bootstrapTokenStore;
        this.pendingStore = pendingStore;
        this.enrollmentPolicy = NipCaService.createEnrollmentPolicy(opts, bootstrapTokenStore, pendingStore);
        this.pfx = opts.routePrefix == null ? "" : opts.routePrefix.replaceAll("/+$", "");
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (pfx.length() > 0 && path.startsWith(pfx)) path = path.substring(pfx.length());

            if (path.equals("/.well-known/nps-ca") && method.equals("GET")) { wellKnown(ex); return; }
            if (path.equals("/v1/ca/cert") && method.equals("GET")) { caCert(ex); return; }
            if (path.equals("/v1/crl") && method.equals("GET")) { crl(ex); return; }
            if (path.equals("/v1/certificates") && method.equals("GET")) {
                certificates(ex); return;
            }

            // /v1/{agents|nodes}/register
            if ((path.equals("/v1/agents/register") || path.equals("/v1/nodes/register")) && method.equals("POST")) {
                registerEntity(ex, path.contains("/agents/") ? "agent" : "node"); return;
            }
            // /v1/{agents|nodes}/register-x509
            if ((path.equals("/v1/agents/register-x509") || path.equals("/v1/nodes/register-x509")) && method.equals("POST")) {
                registerX509(ex, path.contains("/agents/") ? "agent" : "node"); return;
            }

            // /v1/{agents|nodes}/{nid}/{renew|revoke|verify}
            Map.Entry<String, String> ev = matchEntityNidAction(path);
            if (ev != null) {
                String action = ev.getKey();
                String nid = ev.getValue();
                switch (action) {
                    case "renew"  -> { if (method.equals("POST")) { renew(ex, nid); return; } }
                    case "revoke" -> { if (method.equals("POST")) { revoke(ex, nid); return; } }
                    case "verify" -> { if (method.equals("GET")) { verify(ex, nid); return; } }
                    default -> { }
                }
            }

            // ── Orchestrator groups (NPS-CR-0003) ──────────────────────────────
            if (path.equals("/v1/orchestrators/groups/register") && method.equals("POST")) {
                registerGroup(ex); return;
            }
            if (path.startsWith("/v1/orchestrators/groups/")) {
                String rest = path.substring("/v1/orchestrators/groups/".length());
                if (rest.endsWith("/sessions/issue") && method.equals("POST")) {
                    issueSession(ex, decode(rest.substring(0, rest.length() - "/sessions/issue".length()))); return;
                }
                if (rest.endsWith("/sessions") && method.equals("GET")) {
                    listSessions(ex, decode(rest.substring(0, rest.length() - "/sessions".length()))); return;
                }
                if (rest.endsWith("/revoke") && method.equals("POST")) {
                    revoke(ex, decode(rest.substring(0, rest.length() - "/revoke".length()))); return;
                }
            }

            // ── Enrollment (NPS-CR-0005) ───────────────────────────────────────
            if (path.equals("/v1/enrollment/tokens") && method.equals("POST")) { createToken(ex); return; }
            if (path.equals("/v1/enrollment/pending") && method.equals("GET")) { listPending(ex); return; }
            if (path.startsWith("/v1/enrollment/pending/") && method.equals("POST")) {
                String rest = path.substring("/v1/enrollment/pending/".length());
                if (rest.endsWith("/approve")) { approvePending(ex, rest.substring(0, rest.length() - "/approve".length())); return; }
                if (rest.endsWith("/reject"))  { rejectPending(ex, rest.substring(0, rest.length() - "/reject".length())); return; }
            }

            writeJson(ex, 404, Map.of("error_code", "NIP-CA-NOT-FOUND", "message", "no CA route at this path."));
        } finally {
            ex.close();
        }
    }

    /** Matches {@code /v1/(agents|nodes)/<nid>/(renew|revoke|verify)} → (action, decoded nid). */
    private Map.Entry<String, String> matchEntityNidAction(String path) {
        for (String kind : new String[]{"/v1/agents/", "/v1/nodes/"}) {
            if (!path.startsWith(kind)) continue;
            String rest = path.substring(kind.length());
            for (String action : new String[]{"renew", "revoke", "verify"}) {
                String suffix = "/" + action;
                if (rest.endsWith(suffix) && rest.length() > suffix.length()) {
                    String nid = decode(rest.substring(0, rest.length() - suffix.length()));
                    return Map.entry(action, nid);
                }
            }
        }
        return null;
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    private void wellKnown(HttpExchange ex) throws IOException {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("register", opts.baseUrl + pfx + "/v1/agents/register");
        endpoints.put("verify", opts.baseUrl + pfx + "/v1/agents/{nid}/verify");
        endpoints.put("ocsp", opts.baseUrl + pfx + "/v1/agents/{nid}/verify");
        endpoints.put("node_ocsp", opts.baseUrl + pfx + "/v1/nodes/{nid}/verify");
        endpoints.put("crl", opts.baseUrl + pfx + "/v1/crl");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nps_ca", "0.1");
        body.put("issuer", opts.caNid);
        body.put("display_name", opts.displayName);
        body.put("public_key", ca.getCaPublicKey());
        body.put("algorithms", opts.algorithms);
        body.put("endpoints", endpoints);
        body.put("capabilities", List.of("agent", "node", "orchestrator-group",
            "ra-tier-" + opts.enrollmentTier.tier()));
        body.put("max_cert_validity_days", opts.agentCertValidityDays);
        writeJson(ex, 200, body);
    }

    private void caCert(HttpExchange ex) throws IOException {
        writeJson(ex, 200, Map.of("public_key", ca.getCaPublicKey(), "algorithm", "ed25519"));
    }

    private void crl(HttpExchange ex) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<NipCertRecord> revoked = new ArrayList<>(ca.getCrl());
        revoked.sort(java.util.Comparator
            .comparing(NipCertRecord::revokedAt,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
            .thenComparing(NipCertRecord::serial)
            .thenComparing(NipCertRecord::nid));
        for (NipCertRecord r : revoked) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("nid", r.nid());
            e.put("serial", r.serial());
            e.put("revoked_at", r.revokedAt() != null ? ISO.format(r.revokedAt()) : null);
            e.put("reason", r.revokeReason());
            entries.add(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issued_by", opts.caNid);
        body.put("issued_at", ISO.format(Instant.now()));
        body.put("entries", entries);
        body.put("signature", ca.signArtifact(body));
        writeJson(ex, 200, body);
    }

    private void certificates(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { unauthorized(ex); return; }
        List<NipCertRecord> records = new ArrayList<>(ca.listCertificates());
        records.sort(java.util.Comparator
            .comparing(NipCertRecord::issuedAt)
            .thenComparing(NipCertRecord::serial));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (NipCertRecord r : records) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("nid", r.nid());
            entry.put("entity_type", r.entityType());
            entry.put("serial", r.serial());
            entry.put("pub_key", r.pubKey());
            entry.put("capabilities", r.capabilities());
            entry.put("scope", MAPPER.readValue(r.scopeJson(), Object.class));
            entry.put("issued_by", r.issuedBy());
            entry.put("issued_at", ISO.format(r.issuedAt()));
            entry.put("expires_at", ISO.format(r.expiresAt()));
            entry.put("revoked_at",
                r.revokedAt() != null ? ISO.format(r.revokedAt()) : null);
            entry.put("revoke_reason", r.revokeReason());
            entry.put("nid_role", r.nidRole());
            entry.put("parent_nid", r.parentNid());
            entries.add(entry);
        }
        writeJson(ex, 200, Map.of("entries", entries));
    }

    // ── Register agent / node ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void registerEntity(HttpExchange ex, String entityType) throws IOException {
        if (!authorized(ex)) { unauthorized(ex); return; }
        Map<String, Object> req = readJson(ex);
        if (req == null) { badRequest(ex, "Invalid JSON body."); return; }

        String identifier = (String) req.get("identifier");
        String pubKey = (String) req.get("pub_key");
        String err = validateRegister(identifier, pubKey);
        if (err != null) { badRequest(ex, err); return; }

        List<String> caps = req.get("capabilities") instanceof List<?> l ? (List<String>) l
            : ("node".equals(entityType) ? List.of("nwp:query", "nwp:stream") : List.of());
        String scopeJson = toJsonOr(req.get("scope"), "{}");
        String metadataJson = req.get("metadata") != null ? toJsonOr(req.get("metadata"), null) : null;
        String enrollToken = ex.getRequestHeaders().getFirst("X-NPS-Enrollment-Token");

        try {
            var frame = ca.registerWithRa(entityType, identifier, pubKey, caps, scopeJson,
                metadataJson, enrollToken, enrollmentPolicy);
            writeJson(ex, 201, frame.toDict());
        } catch (NipRaPendingException pe) {
            writeJson(ex, 202, Map.of("pending_id", pe.pendingId(), "status", "queued"));
        } catch (NipCaException ce) {
            errorResult(ex, ce);
        }
    }

    // ── Register X.509 ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void registerX509(HttpExchange ex, String entityType) throws IOException {
        if (!authorized(ex)) { unauthorized(ex); return; }
        Map<String, Object> req = readJson(ex);
        if (req == null) { badRequest(ex, "Invalid JSON body."); return; }

        String identifier = (String) req.get("identifier");
        String pubKey = (String) req.get("pub_key");
        String err = validateRegister(identifier, pubKey);
        if (err != null) { badRequest(ex, err); return; }

        List<String> caps = req.get("capabilities") instanceof List<?> l ? (List<String>) l
            : ("node".equals(entityType) ? List.of("nwp:query", "nwp:stream") : List.of());
        String scopeJson = toJsonOr(req.get("scope"), "{}");
        String metadataJson = req.get("metadata") != null ? toJsonOr(req.get("metadata"), null) : null;
        AssuranceLevel level = parseAssuranceLevel((String) req.get("assurance_level"));

        try {
            var frame = ca.registerX509(entityType, identifier, pubKey, caps, scopeJson,
                null, level, metadataJson);
            writeJson(ex, 201, frame.toDict());
        } catch (NipCaException ce) {
            errorResult(ex, ce);
        }
    }

    // ── Renew / Revoke / Verify ───────────────────────────────────────────────

    private void renew(HttpExchange ex, String nid) throws IOException {
        if (!authorized(ex)) { unauthorized(ex); return; }
        try {
            writeJson(ex, 200, ca.renew(nid).toDict());
        } catch (NipCaException ce) { errorResult(ex, ce); }
    }

    private void revoke(HttpExchange ex, String nid) throws IOException {
        if (!authorized(ex)) { unauthorized(ex); return; }
        Map<String, Object> req = readJson(ex);
        String reason = req != null && req.get("reason") instanceof String s ? s : "cessation_of_operation";
        if (!VALID_REVOCATION_REASONS.contains(reason)) {
            badRequest(ex, "Invalid revocation reason '" + reason + "'. Allowed: "
                + String.join(", ", VALID_REVOCATION_REASONS) + ".");
            return;
        }
        try {
            RevokeFrame frame = ca.revoke(nid, reason);
            writeJson(ex, 200, frame.toDict());
        } catch (NipCaException ce) { errorResult(ex, ce); }
    }

    private void verify(HttpExchange ex, String nid) throws IOException {
        NipVerifyResult r = verifyWithTiming(nid);
        if (r.valid()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("valid", true);
            body.put("nid", r.record().nid());
            body.put("expires_at", ISO.format(r.record().expiresAt()));
            body.put("serial", r.record().serial());
            writeJson(ex, 200, body);
        } else {
            int status = NipErrorCodes.CA_NID_NOT_FOUND.equals(r.errorCode()) ? 404 : 200;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("valid", false);
            body.put("error_code", r.errorCode());
            body.put("message", r.message());
            writeJson(ex, status, body);
        }
    }

    private NipVerifyResult verifyWithTiming(String nid) {
        if (!opts.normalizeOcspResponseTime) return ca.verify(nid);
        long start = System.nanoTime();
        NipVerifyResult r = ca.verify(nid);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long delay = 200 - elapsedMs;
        if (delay > 0) {
            try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        return r;
    }

    // ── Orchestrator group: register ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void registerGroup(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { unauthorized(ex); return; }
        Map<String, Object> req = readJson(ex);
        if (req == null) { badRequest(ex, "Invalid JSON body."); return; }

        String identifier = (String) req.get("identifier");
        String pubKey = (String) req.get("pub_key");
        if (identifier != null && !identifier.isEmpty() && !IDENTIFIER_RE.matcher(identifier).matches()) {
            badRequest(ex, "identifier contains invalid characters. Allowed: a-z A-Z 0-9 . _ : @ / -"); return;
        }
        if (pubKey == null || !pubKey.startsWith("ed25519:") || pubKey.length() <= 8) {
            badRequest(ex, "pub_key must be 'ed25519:<base64url>'."); return;
        }
        List<String> caps = req.get("capabilities") instanceof List<?> l ? (List<String>) l : List.of();
        String scopeJson = toJsonOr(req.get("scope"), "{}");
        String metadataJson = req.get("metadata") != null ? toJsonOr(req.get("metadata"), null) : null;

        try {
            var frame = ca.registerGroup(identifier, pubKey, caps, scopeJson,
                (String) req.get("owner_user_id"), (String) req.get("owner_key_id"), metadataJson);
            writeJson(ex, 201, frame.toDict());
        } catch (NipCaException ce) { errorResult(ex, ce); }
    }

    // ── Orchestrator group: issue session (operator key OR group-JWS) ─────────

    @SuppressWarnings("unchecked")
    private void issueSession(HttpExchange ex, String groupNid) throws IOException {
        String ctype = ex.getRequestHeaders().getFirst("Content-Type");
        boolean isJwsBody = ctype != null && ctype.toLowerCase().contains("jose+json");

        String sessionPubKey, purpose = null, scopeJson = "{}", metadataJson = null;
        List<String> caps = null;
        Integer validitySeconds = null;

        if (isJwsBody) {
            Map<String, Object> jwsMap = readJson(ex);
            if (jwsMap == null) { badRequest(ex, "Invalid JWS body."); return; }

            NipCertRecord group = ca.getCert(groupNid);
            if (group == null) { errorResult(ex, new NipCaException("Group " + groupNid + " not found.", NipErrorCodes.CA_PARENT_NOT_FOUND)); return; }
            if (!NipCaService.ROLE_GROUP.equals(group.nidRole())) { errorResult(ex, new NipCaException("NID " + groupNid + " is not a group.", NipErrorCodes.CA_PARENT_NOT_GROUP)); return; }
            if (group.isRevoked()) { errorResult(ex, new NipCaException("Group " + groupNid + " revoked.", NipErrorCodes.CA_GROUP_REVOKED)); return; }

            PublicKey groupPub = decodePublicKey(group.pubKey());
            if (groupPub == null) { jwsError(ex, NipErrorCodes.CA_JWS_INVALID, "Group public key could not be decoded."); return; }

            NipGroupJws.FlattenedJws jws = new NipGroupJws.FlattenedJws(
                (String) jwsMap.get("protected"), (String) jwsMap.get("payload"), (String) jwsMap.get("signature"));
            NipGroupJws.Result vr = NipGroupJws.verify(jws, groupPub);
            if (!vr.ok()) { jwsError(ex, vr.errorCode(), "Group-JWS verification failed."); return; }
            if (!groupNid.equals(vr.kid())) { jwsError(ex, NipErrorCodes.CA_JWS_INVALID, "JWS kid '" + vr.kid() + "' does not match URL group_nid '" + groupNid + "'."); return; }

            Map<String, Object> payload;
            try { payload = MAPPER.readValue(vr.payloadJson(), Map.class); }
            catch (Exception e) { jwsError(ex, NipErrorCodes.CA_JWS_INVALID, "JWS payload is not valid JSON."); return; }
            if (payload == null) { jwsError(ex, NipErrorCodes.CA_JWS_INVALID, "JWS payload missing."); return; }

            long skewSec = opts.sessionJwsClockSkew.getSeconds();
            long nowEpoch = Instant.now().getEpochSecond();
            long iat = payload.get("iat") instanceof Number n ? n.longValue() : 0;
            if (iat == 0 || Math.abs(nowEpoch - iat) > skewSec) {
                jwsError(ex, NipErrorCodes.CA_JWS_EXPIRED, "JWS iat outside ±" + skewSec + "s window."); return;
            }

            sessionPubKey = (String) payload.get("session_pub_key");
            purpose = (String) payload.get("purpose");
            validitySeconds = payload.get("validity_seconds") instanceof Number n ? n.intValue() : null;
            caps = payload.get("capabilities") instanceof List<?> l ? (List<String>) l : null;
            if (payload.get("scope") != null) scopeJson = toJsonOr(payload.get("scope"), "{}");
            else scopeJson = null;
            if (payload.get("metadata") != null) metadataJson = toJsonOr(payload.get("metadata"), null);
        } else {
            if (!authorized(ex)) { unauthorized(ex); return; }
            Map<String, Object> req = readJson(ex);
            if (req == null) { badRequest(ex, "Invalid JSON body."); return; }
            sessionPubKey = (String) req.get("session_pub_key");
            purpose = (String) req.get("purpose");
            validitySeconds = req.get("validity_seconds") instanceof Number n ? n.intValue() : null;
            caps = req.get("capabilities") instanceof List<?> l ? (List<String>) l : null;
            scopeJson = req.get("scope") != null ? toJsonOr(req.get("scope"), "{}") : null;
            metadataJson = req.get("metadata") != null ? toJsonOr(req.get("metadata"), null) : null;
        }

        if (sessionPubKey == null || !sessionPubKey.startsWith("ed25519:") || sessionPubKey.length() <= 8) {
            badRequest(ex, "session_pub_key must be 'ed25519:<base64url>'."); return;
        }
        Duration validity = validitySeconds != null && validitySeconds > 0
            ? Duration.ofSeconds(validitySeconds) : null;

        try {
            var frame = ca.issueSession(groupNid, sessionPubKey, validity, purpose, caps, scopeJson, metadataJson);
            writeJson(ex, 201, frame.toDict());
        } catch (NipCaException ce) { errorResult(ex, ce); }
    }

    private void listSessions(HttpExchange ex, String groupNid) throws IOException {
        if (!authorized(ex)) { unauthorized(ex); return; }
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (NipCertRecord s : ca.listSessions(groupNid)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nid", s.nid());
            m.put("serial", s.serial());
            m.put("issued_at", ISO.format(s.issuedAt()));
            m.put("expires_at", ISO.format(s.expiresAt()));
            m.put("revoked_at", s.revokedAt() != null ? ISO.format(s.revokedAt()) : null);
            m.put("revoke_reason", s.revokeReason());
            sessions.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group_nid", groupNid);
        body.put("count", sessions.size());
        body.put("sessions", sessions);
        writeJson(ex, 200, body);
    }

    // ── Enrollment (NPS-CR-0005) ──────────────────────────────────────────────

    private void createToken(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { unauthorized(ex); return; }
        if (bootstrapTokenStore == null) {
            writeJson(ex, 400, Map.of("error_code", "NIP-CA-BAD-REQUEST",
                "message", "Bootstrap token enrollment is not enabled on this CA.")); return;
        }
        Map<String, Object> req = readJson(ex);
        long ttlSec = req != null && req.get("ttl_seconds") instanceof Number n ? n.longValue() : 0;
        Duration ttl = ttlSec > 0 ? Duration.ofSeconds(ttlSec) : opts.bootstrapTokenMaxTtl;
        if (ttl.compareTo(opts.bootstrapTokenMaxTtl) > 0) ttl = opts.bootstrapTokenMaxTtl;
        String label = req != null ? (String) req.get("label") : null;
        Instant expiresAt = Instant.now().plus(ttl);
        String raw = bootstrapTokenStore.create(label, expiresAt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", raw);
        body.put("expires_at", ISO.format(expiresAt));
        body.put("label", label);
        writeJson(ex, 201, body);
    }

    private void listPending(HttpExchange ex) throws IOException {
        if (!authorized(ex)) { unauthorized(ex); return; }
        if (pendingStore == null) {
            writeJson(ex, 400, Map.of("error_code", "NIP-CA-BAD-REQUEST",
                "message", "Pending-queue enrollment is not enabled on this CA.")); return;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        var records = pendingStore.list();
        for (var r : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("entity_type", r.entityType());
            m.put("identifier", r.identifier());
            m.put("pub_key", r.pubKey());
            m.put("capabilities", r.capabilities());
            m.put("scope_json", r.scopeJson());
            m.put("requested_at", ISO.format(r.requestedAt()));
            m.put("status", r.status().name().toLowerCase());
            m.put("reject_reason", r.rejectReason());
            items.add(m);
        }
        writeJson(ex, 200, Map.of("count", records.size(), "items", items));
    }

    private void approvePending(HttpExchange ex, String id) throws IOException {
        if (!authorized(ex)) { unauthorized(ex); return; }
        if (pendingStore == null) {
            writeJson(ex, 400, Map.of("error_code", "NIP-CA-BAD-REQUEST",
                "message", "Pending-queue enrollment is not enabled on this CA.")); return;
        }
        var record = pendingStore.get(id);
        if (record == null) {
            writeJson(ex, 404, Map.of("error_code", NipErrorCodes.CA_NID_NOT_FOUND,
                "message", "Pending registration '" + id + "' not found.")); return;
        }
        if (record.status() != IPendingStore.PendingStatus.PENDING) {
            writeJson(ex, 409, Map.of("error_code", "NIP-CA-BAD-REQUEST",
                "message", "Record '" + id + "' is already " + record.status().name().toLowerCase() + ".")); return;
        }
        try {
            var frame = ca.register(record.entityType(), record.identifier(), record.pubKey(),
                record.capabilities(), record.scopeJson(), record.metadataJson());
            pendingStore.approve(id);
            writeJson(ex, 201, frame.toDict());
        } catch (NipCaException ce) { errorResult(ex, ce); }
    }

    private void rejectPending(HttpExchange ex, String id) throws IOException {
        if (!authorized(ex)) { unauthorized(ex); return; }
        if (pendingStore == null) {
            writeJson(ex, 400, Map.of("error_code", "NIP-CA-BAD-REQUEST",
                "message", "Pending-queue enrollment is not enabled on this CA.")); return;
        }
        Map<String, Object> req = readJson(ex);
        String reason = req != null && req.get("reason") instanceof String s ? s : "rejected_by_operator";
        boolean ok = pendingStore.reject(id, reason);
        if (!ok) {
            var record = pendingStore.get(id);
            String msg = record == null ? "Pending registration '" + id + "' not found."
                : "Record '" + id + "' is already " + record.status().name().toLowerCase() + ".";
            writeJson(ex, record == null ? 404 : 409,
                Map.of("error_code", "NIP-CA-BAD-REQUEST", "message", msg));
            return;
        }
        writeJson(ex, 200, Map.of("id", id, "status", "rejected", "reason", reason));
    }

    // ── Auth / validation ─────────────────────────────────────────────────────

    private boolean authorized(HttpExchange ex) {
        if (opts.operatorApiKey == null) return true;
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        String provided = header.substring(7).trim();
        return MessageDigest.isEqual(
            provided.getBytes(StandardCharsets.UTF_8),
            opts.operatorApiKey.getBytes(StandardCharsets.UTF_8));
    }

    private static String validateRegister(String identifier, String pubKey) {
        if (identifier == null || identifier.isEmpty() || pubKey == null || pubKey.isEmpty())
            return "identifier and pub_key are required.";
        if (!IDENTIFIER_RE.matcher(identifier).matches())
            return "identifier contains invalid characters. Allowed: a-z A-Z 0-9 . _ : @ / -";
        if (!pubKey.startsWith("ed25519:") || pubKey.length() <= 8)
            return "pub_key must be 'ed25519:<base64url>'.";
        return null;
    }

    private static AssuranceLevel parseAssuranceLevel(String raw) {
        if (raw == null) return AssuranceLevel.ANONYMOUS;
        return switch (raw.toLowerCase()) {
            case "attested" -> AssuranceLevel.ATTESTED;
            case "verified" -> AssuranceLevel.VERIFIED;
            default -> AssuranceLevel.ANONYMOUS;
        };
    }

    private static PublicKey decodePublicKey(String encoded) {
        if (encoded == null || !encoded.startsWith("ed25519:")) return null;
        String body = encoded.substring("ed25519:".length());
        try {
            byte[] raw;
            if (body.length() == 64 && body.matches("[0-9a-fA-F]+")) {
                raw = java.util.HexFormat.of().parseHex(body);       // ed25519:<hex>
            } else {
                raw = Base64.getUrlDecoder().decode(body);           // ed25519:<base64url>
            }
            if (raw.length != 32) return null;
            return Ed25519PublicKeys.fromRaw(raw);
        } catch (RuntimeException e) { return null; }
    }

    // ── Responses ─────────────────────────────────────────────────────────────

    private void errorResult(HttpExchange ex, NipCaException e) throws IOException {
        int status = switch (e.errorCode()) {
            case NipErrorCodes.CA_NID_NOT_FOUND, NipErrorCodes.CA_PARENT_NOT_FOUND -> 404;
            case NipErrorCodes.CA_NID_ALREADY_EXISTS, NipErrorCodes.CA_SERIAL_DUPLICATE -> 409;
            case NipErrorCodes.CA_RENEWAL_TOO_EARLY, NipErrorCodes.CA_SESSION_VALIDITY_INVALID,
                 NipErrorCodes.CA_PARENT_NOT_GROUP -> 400;
            case NipErrorCodes.CA_SCOPE_EXPANSION_DENIED, NipErrorCodes.CERT_CAPABILITY_MISSING,
                 NipErrorCodes.CA_GROUP_REVOKED, NipErrorCodes.RA_NID_NOT_ALLOWED,
                 NipErrorCodes.RA_PENDING_REJECTED -> 403;
            case NipErrorCodes.CA_JWS_INVALID, NipErrorCodes.CA_JWS_EXPIRED,
                 NipErrorCodes.CERT_EXPIRED, NipErrorCodes.CERT_REVOKED,
                 NipErrorCodes.CERT_PARENT_REVOKED, NipErrorCodes.RA_TOKEN_INVALID,
                 NipErrorCodes.RA_TOKEN_EXPIRED -> 401;
            default -> 400;
        };
        writeJson(ex, status, Map.of("error_code", e.errorCode(), "message", e.getMessage()));
    }

    private void jwsError(HttpExchange ex, String code, String message) throws IOException {
        writeJson(ex, 401, Map.of("error_code", code, "message", message));
    }

    private void badRequest(HttpExchange ex, String msg) throws IOException {
        writeJson(ex, 400, Map.of("error_code", "NIP-CA-BAD-REQUEST", "message", msg));
    }

    private void unauthorized(HttpExchange ex) throws IOException {
        writeJson(ex, 401, Map.of("error_code", "NIP-CA-UNAUTHORIZED",
            "message", "Valid operator Bearer token required."));
    }

    // ── IO helpers ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange ex) {
        try {
            byte[] raw = ex.getRequestBody().readAllBytes();
            if (raw.length == 0) return null;
            return MAPPER.readValue(raw, Map.class);
        } catch (Exception e) { return null; }
    }

    private void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String toJsonOr(Object o, String fallback) {
        if (o == null) return fallback;
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return fallback; }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
