// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.x509.NipX509VerifyResult;
import com.labacacia.nps.nip.x509.NipX509Verifier;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node-side verifier for an {@link IdentFrame} received from an Agent.
 *
 * <p>The legacy {@link #verify(IdentFrame, String)} method retains the
 * NPS-RFC-0002 dual-trust step model (1 = Ed25519 signature, 2 = assurance,
 * 3 = X.509 chain) for backward compatibility.
 *
 * <p>The {@link #verify(IdentFrame, String, NipVerifyContext)} method implements
 * the full NPS-3 §7 six-step flow to reach parity with the .NET reference
 * ({@code NipIdentVerifier}). All six steps MUST pass:
 * <ol>
 *   <li>Expiry: {@code expires_at > now} (context {@code asOf} or now).</li>
 *   <li>Trusted issuer: {@code issued_by} is a key in
 *       {@link NipVerifierOptions#trustedCaPublicKeys()}.</li>
 *   <li>Signature: Ed25519 signature verifies against the issuer CA's public key,
 *       PLUS the X.509 chain when {@code cert_format == "v2-x509"} and trusted
 *       X.509 roots are configured (v1-only verifiers ignore {@code cert_chain}).</li>
 *   <li>Revocation: local CRL → {@code revocationCheck} callback →
 *       {@code revocationStore} → OCSP {@code GET {ocspUrl}/{nid}}. OCSP transport
 *       failures honour {@code ocspFailOpen}. Pass-through when unconfigured.</li>
 *   <li>Capabilities: the frame's capability set contains all of
 *       {@link NipVerifyContext#requiredCapabilities()}.</li>
 *   <li>Scope: {@code scope.nodes} patterns cover
 *       {@link NipVerifyContext#targetNodePath()}.</li>
 * </ol>
 *
 * <p>The Java {@link IdentFrame} carries {@code issued_by}, {@code expires_at},
 * {@code serial}, {@code capabilities}, and {@code scope} inside its
 * {@link IdentFrame#metadata()} map (that is where the frame model stores them);
 * this verifier reads them from there using the canonical wire field names.
 */
public final class NipIdentVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NipVerifierOptions options;

    public NipIdentVerifier(NipVerifierOptions options) {
        this.options = options;
    }

    // ── Legacy dual-trust flow (unchanged step model) ────────────────────────

    /**
     * NPS-RFC-0002 dual-trust check: Ed25519 signature (step 1), assurance minimum
     * (step 2), and X.509 chain (step 3). Preserved for backward compatibility.
     *
     * @param frame     The IdentFrame to verify.
     * @param issuerNid The asserted issuer NID — used to look up the CA public key.
     */
    public NipIdentVerifyResult verify(IdentFrame frame, String issuerNid) {
        NipIdentVerifyResult sig = verifySignature(frame, issuerNid, 1);
        if (!sig.valid()) return sig;

        // Step 2: minimum assurance level.
        NipIdentVerifyResult assurance = checkAssurance(frame, options.minAssuranceLevel(), 2);
        if (!assurance.valid()) return assurance;

        // Step 3: X.509 chain (only if configured + frame opts in).
        NipIdentVerifyResult x509 = verifyX509Chain(frame, 3);
        if (!x509.valid()) return x509;

        return NipIdentVerifyResult.ok();
    }

    // ── Full NPS-3 §7 six-step flow (parity with .NET) ───────────────────────

    /**
     * Full NPS-3 §7 verification flow. See the class Javadoc for the six steps.
     *
     * @param frame     The IdentFrame to verify.
     * @param issuerNid The asserted issuer NID (used to look up the CA public key).
     * @param context   Per-request context; pass {@link NipVerifyContext#empty()}
     *                  (or {@code null}) to skip the optional capability/scope checks.
     */
    public NipIdentVerifyResult verify(IdentFrame frame, String issuerNid, NipVerifyContext context) {
        NipVerifyContext ctx = context == null ? NipVerifyContext.empty() : context;
        Instant now = ctx.asOf() != null ? ctx.asOf() : Instant.now();

        // ── Step 1: Expiry ───────────────────────────────────────────────────
        String expiresAtRaw = str(meta(frame, "expires_at"));
        Instant expiresAt = parseInstant(expiresAtRaw);
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            return NipIdentVerifyResult.fail(1, NipErrorCodes.CERT_EXPIRED,
                "Certificate expired at " + expiresAtRaw + ".");
        }

        // ── Step 2: Trusted issuer ───────────────────────────────────────────
        String issuedBy = issuerNid != null ? issuerNid : str(meta(frame, "issued_by"));
        if (issuedBy == null || !options.trustedCaPublicKeys().containsKey(issuedBy)) {
            return NipIdentVerifyResult.fail(2, NipErrorCodes.CERT_UNTRUSTED_ISSUER,
                "Issuer '" + issuedBy + "' is not in the trusted issuers list.");
        }

        // ── Step 3: Signature (+ X.509 chain when v2) ────────────────────────
        NipIdentVerifyResult sig = verifySignature(frame, issuedBy, 3);
        if (!sig.valid()) return sig;

        NipIdentVerifyResult minAssurance = checkAssurance(frame, ctx.minAssuranceLevel(), 3);
        if (!minAssurance.valid()) return minAssurance;

        NipIdentVerifyResult x509 = verifyX509Chain(frame, 3);
        if (!x509.valid()) return x509;

        // ── Step 4: Revocation ───────────────────────────────────────────────
        NipIdentVerifyResult revocation = checkRevocation(frame);
        if (!revocation.valid()) return revocation;

        // ── Step 5: Capabilities ─────────────────────────────────────────────
        List<String> required = ctx.requiredCapabilities();
        if (required != null && !required.isEmpty()) {
            Set<String> frameCaps = frameCapabilities(frame);
            List<String> missing = required.stream().filter(c -> !frameCaps.contains(c)).toList();
            if (!missing.isEmpty()) {
                return NipIdentVerifyResult.fail(5, NipErrorCodes.CERT_CAPABILITY_MISSING,
                    "Certificate is missing required capabilities: " + String.join(", ", missing) + ".");
            }
        }

        // ── Step 6: Scope ────────────────────────────────────────────────────
        if (ctx.targetNodePath() != null) {
            NipIdentVerifyResult scope = checkScope(frame, ctx.targetNodePath());
            if (!scope.valid()) return scope;
        }

        return NipIdentVerifyResult.ok();
    }

    // ── Shared building blocks ────────────────────────────────────────────────

    private NipIdentVerifyResult verifySignature(IdentFrame frame, String issuerNid, int step) {
        PublicKey caPub = options.trustedCaPublicKeys().get(issuerNid);
        if (caPub == null) {
            return NipIdentVerifyResult.fail(step, NipErrorCodes.CERT_UNTRUSTED_ISSUER,
                "no trusted CA public key for issuer: " + issuerNid);
        }
        if (frame.signature() == null || !frame.signature().startsWith("ed25519:")) {
            return NipIdentVerifyResult.fail(step, NipErrorCodes.CERT_SIGNATURE_INVALID,
                "missing or malformed signature");
        }
        try {
            byte[] sigBytes = Base64.getDecoder().decode(
                frame.signature().substring("ed25519:".length()));
            byte[] message  = NipCanonicalJson.canonicalize(frame.unsignedDict());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(caPub);
            verifier.update(message);
            if (!verifier.verify(sigBytes)) {
                return NipIdentVerifyResult.fail(step, NipErrorCodes.CERT_SIGNATURE_INVALID,
                    "v1 Ed25519 signature did not verify against issuer CA key");
            }
        } catch (Exception e) {
            return NipIdentVerifyResult.fail(step, NipErrorCodes.CERT_SIGNATURE_INVALID,
                "v1 signature verification error: " + e.getMessage());
        }
        return NipIdentVerifyResult.ok();
    }

    private static NipIdentVerifyResult checkAssurance(IdentFrame frame, AssuranceLevel min, int step) {
        if (min == null) return NipIdentVerifyResult.ok();
        AssuranceLevel got = frame.assuranceLevel() == null
            ? AssuranceLevel.ANONYMOUS : frame.assuranceLevel();
        if (!got.meetsOrExceeds(min)) {
            return NipIdentVerifyResult.fail(step, NipErrorCodes.ASSURANCE_MISMATCH,
                "assurance_level (" + got.wire()
                + ") below required minimum (" + min.wire() + ")");
        }
        return NipIdentVerifyResult.ok();
    }

    private NipIdentVerifyResult verifyX509Chain(IdentFrame frame, int step) {
        List<?> trustedRoots = options.trustedX509Roots();
        boolean hasV2Trust = trustedRoots != null && !trustedRoots.isEmpty();
        boolean isV2Frame  = IdentCertFormat.V2_X509.equals(frame.certFormat());
        if (hasV2Trust && isV2Frame) {
            NipX509VerifyResult x509 = NipX509Verifier.verify(
                frame.certChain() == null ? List.of() : frame.certChain(),
                frame.nid(),
                frame.assuranceLevel(),
                options.trustedX509Roots());
            if (!x509.valid()) {
                return NipIdentVerifyResult.fail(step, x509.errorCode(), x509.message());
            }
            if (options.phase3Enforcement()) {
                return NipPhase3Enforcer.enforce(frame, x509.leaf(), Instant.now());
            }
        }
        return NipIdentVerifyResult.ok();
    }

    // ── Revocation (Step 4) ───────────────────────────────────────────────────

    private NipIdentVerifyResult checkRevocation(IdentFrame frame) {
        String serial = str(meta(frame, "serial"));
        NipRevocationPolicy evaluation = new NipRevocationPolicy(
            options.revocationMode(), options.ocspFailOpen());

        // 1) Local CRL check first (fast, no network).
        if (options.localRevokedSerials() != null) {
            NipIdentVerifyResult result = evaluation.observe(
                NipRevocationPolicy.Source.LOCAL_CRL,
                serial != null && options.localRevokedSerials().contains(serial)
                    ? NipRevocationPolicy.Outcome.REVOKED
                    : NipRevocationPolicy.Outcome.GOOD);
            if (result != null) return result;
        }

        // 2) Pluggable live revocation callback.
        if (options.revocationCheck() != null) {
            NipIdentVerifyResult result;
            try {
                NipIdentVerifyResult callbackResult =
                    options.revocationCheck().check(frame);
                if (callbackResult != null && !callbackResult.valid()) {
                    return callbackResult;
                }
                result = evaluation.observe(
                    NipRevocationPolicy.Source.CALLBACK,
                    NipRevocationPolicy.Outcome.GOOD);
            } catch (RuntimeException ex) {
                result = evaluation.observe(
                    NipRevocationPolicy.Source.CALLBACK,
                    NipRevocationPolicy.Outcome.UNAVAILABLE);
            }
            if (result != null) return result;
        }

        // 3) Revocation store lookup by serial.
        if (options.revocationStore() != null) {
            NipIdentVerifyResult result;
            try {
                if (serial == null) throw new IllegalStateException(
                    "Certificate serial is missing.");
                NipRevocationStore.Record record =
                    options.revocationStore().getBySerial(serial);
                result = evaluation.observe(
                    NipRevocationPolicy.Source.CA_STORE,
                    record != null && record.revokedAt() != null
                        ? NipRevocationPolicy.Outcome.REVOKED
                        : NipRevocationPolicy.Outcome.GOOD);
            } catch (RuntimeException ex) {
                result = evaluation.observe(
                    NipRevocationPolicy.Source.CA_STORE,
                    NipRevocationPolicy.Outcome.UNAVAILABLE);
            }
            if (result != null) return result;
        }

        // 4) OCSP call to the CA server (optional).
        if (options.ocspUrl() != null) {
            NipIdentVerifyResult ocsp = ocspCheck(frame.nid());
            if (!ocsp.valid()) return ocsp;
            NipIdentVerifyResult result = evaluation.observe(
                NipRevocationPolicy.Source.OCSP,
                NipRevocationPolicy.Outcome.GOOD);
            if (result != null) return result;
        }

        return evaluation.complete();
    }

    private NipIdentVerifyResult ocspCheck(String nid) {
        HttpClient client = options.httpClient() != null
            ? options.httpClient()
            : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        try {
            String base = options.ocspUrl();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String url = base + "/" + URLEncoder.encode(nid, StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                if (options.ocspFailOpen()) return NipIdentVerifyResult.ok();
                return NipIdentVerifyResult.fail(4, NipErrorCodes.OCSP_UNAVAILABLE,
                    "OCSP endpoint returned " + resp.statusCode() + ".");
            }

            JsonNode json = MAPPER.readTree(resp.body());
            boolean isValid = json.has("valid") && json.get("valid").asBoolean(false);
            if (!isValid) {
                String errorCode = json.has("error_code") && !json.get("error_code").isNull()
                    ? json.get("error_code").asText(NipErrorCodes.CERT_REVOKED)
                    : NipErrorCodes.CERT_REVOKED;
                return NipIdentVerifyResult.fail(4, errorCode,
                    "OCSP check failed for NID " + nid + ".");
            }
            return NipIdentVerifyResult.ok();
        } catch (Exception ex) {
            // Transport failure (connection refused, timeout, malformed body, ...).
            if (options.ocspFailOpen()) {
                return NipIdentVerifyResult.ok();
            }
            return NipIdentVerifyResult.fail(4, NipErrorCodes.OCSP_UNAVAILABLE,
                "OCSP call failed for NID " + nid + ": " + ex.getMessage());
        }
    }

    // ── Scope check (Step 6) ──────────────────────────────────────────────────

    private static NipIdentVerifyResult checkScope(IdentFrame frame, String targetPath) {
        List<String> nodes = scopeNodes(frame);
        if (nodes == null) {
            return NipIdentVerifyResult.fail(6, NipErrorCodes.CERT_SCOPE_VIOLATION,
                "IdentFrame scope is missing 'nodes' field.");
        }
        for (String pattern : nodes) {
            if (pattern != null && nwpPathMatches(pattern, targetPath)) {
                return NipIdentVerifyResult.ok();
            }
        }
        return NipIdentVerifyResult.fail(6, NipErrorCodes.CERT_SCOPE_VIOLATION,
            "Target path '" + targetPath + "' is not covered by the certificate scope.");
    }

    /**
     * Matches a NWP path against a scope pattern.
     * <ul>
     *   <li>A bare {@code *} matches any path.</li>
     *   <li>A trailing {@code /*} matches the prefix and any path under it (at a {@code /} boundary).</li>
     *   <li>All other patterns are exact case-insensitive matches.</li>
     * </ul>
     */
    public static boolean nwpPathMatches(String pattern, String path) {
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2); // strip "/*"
            return path.regionMatches(true, 0, prefix, 0, prefix.length())
                && (path.length() == prefix.length() || path.charAt(prefix.length()) == '/');
        }
        return pattern.equalsIgnoreCase(path);
    }

    // ── metadata helpers ──────────────────────────────────────────────────────

    private static Object meta(IdentFrame frame, String key) {
        Map<String, Object> m = frame.metadata();
        return m == null ? null : m.get(key);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> frameCapabilities(IdentFrame frame) {
        Object caps = meta(frame, "capabilities");
        Set<String> out = new LinkedHashSet<>();
        if (caps instanceof List<?> list) {
            for (Object o : list) if (o != null) out.add(o.toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> scopeNodes(IdentFrame frame) {
        Object scope = meta(frame, "scope");
        if (scope instanceof Map<?, ?> map) {
            Object nodes = map.get("nodes");
            if (nodes instanceof List<?> list) {
                return list.stream().map(o -> o == null ? null : o.toString()).toList();
            }
            return null;
        }
        if (scope instanceof JsonNode node && node.has("nodes") && node.get("nodes").isArray()) {
            List<String> out = new java.util.ArrayList<>();
            node.get("nodes").forEach(n -> out.add(n.isNull() ? null : n.asText()));
            return out;
        }
        return null;
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(raw);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
