// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java parallel of the .NET {@code NipIdentVerifier} NPS-3 §7 six-step flow tests.
 * Exercises per-step failures, revocation (local CRL / callback / store / OCSP with
 * fail-open vs fail-closed against an ephemeral HttpServer), scope matching, and
 * capability checks.
 */
class NipIdentVerifierFlowTests {

    private static final String CA_NID = "urn:nps:org:ca.example.com";

    // ── Frame + option builders ──────────────────────────────────────────────

    private static KeyPair generate() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /** Build a signed v1 IdentFrame with the six-step fields carried in metadata. */
    private static IdentFrame frame(KeyPair caKp, String nid, Instant expiresAt,
                                    String serial, List<String> capabilities,
                                    List<String> scopeNodes) throws Exception {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("nodes", scopeNodes);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issued_by",   CA_NID);
        metadata.put("expires_at",  expiresAt.toString());
        metadata.put("serial",      serial);
        metadata.put("capabilities", capabilities);
        metadata.put("scope",       scope);

        String pubKey = "ed25519:AAAA";

        Map<String, Object> unsigned = new LinkedHashMap<>();
        unsigned.put("nid",      nid);
        unsigned.put("pub_key",  pubKey);
        unsigned.put("metadata", metadata);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(caKp.getPrivate());
        signer.update(NipCanonicalJson.canonicalize(unsigned));
        String sig = "ed25519:" + Base64.getEncoder().encodeToString(signer.sign());

        return new IdentFrame(nid, pubKey, metadata, sig);
    }

    private static NipVerifierOptions.Builder opts(PublicKey caPub) {
        return NipVerifierOptions.builder().trustedCaPublicKeys(Map.of(CA_NID, caPub));
    }

    private static Instant future() { return Instant.now().plus(1, ChronoUnit.HOURS); }
    private static Instant past()   { return Instant.now().minus(1, ChronoUnit.HOURS); }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void allStepsPass() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:a", future(), "0x01",
            List.of("nwp:query"), List.of("nwp://api.myapp.com/*"));

        NipVerifyContext ctx = NipVerifyContext.builder()
            .requiredCapabilities(List.of("nwp:query"))
            .targetNodePath("nwp://api.myapp.com/products")
            .build();

        NipIdentVerifyResult r = new NipIdentVerifier(opts(ca.getPublic()).build())
            .verify(f, CA_NID, ctx);
        assertTrue(r.valid(), () -> "step " + r.stepFailed() + " " + r.errorCode() + " " + r.message());
    }

    // ── Step 1: expiry ────────────────────────────────────────────────────────

    @Test
    void step1_expired() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:a", past(), "0x01", List.of(), List.of("*"));
        NipIdentVerifyResult r = new NipIdentVerifier(opts(ca.getPublic()).build())
            .verify(f, CA_NID, NipVerifyContext.empty());
        assertFalse(r.valid());
        assertEquals(1, r.stepFailed());
        assertEquals(NipErrorCodes.CERT_EXPIRED, r.errorCode());
    }

    @Test
    void step1_asOfClockOverride() throws Exception {
        KeyPair ca = generate();
        Instant exp = Instant.now().plus(30, ChronoUnit.MINUTES);
        IdentFrame f = frame(ca, "urn:nps:agent:a", exp, "0x01", List.of(), List.of("*"));
        // asOf after expiry ⇒ expired.
        NipVerifyContext ctx = NipVerifyContext.builder()
            .asOf(exp.plus(1, ChronoUnit.MINUTES)).build();
        NipIdentVerifyResult r = new NipIdentVerifier(opts(ca.getPublic()).build())
            .verify(f, CA_NID, ctx);
        assertFalse(r.valid());
        assertEquals(1, r.stepFailed());
    }

    // ── Step 2: trusted issuer ────────────────────────────────────────────────

    @Test
    void step2_untrustedIssuer() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:a", future(), "0x01", List.of(), List.of("*"));
        NipIdentVerifyResult r = new NipIdentVerifier(opts(ca.getPublic()).build())
            .verify(f, "urn:nps:org:unknown", NipVerifyContext.empty());
        assertFalse(r.valid());
        assertEquals(2, r.stepFailed());
        assertEquals(NipErrorCodes.CERT_UNTRUSTED_ISSUER, r.errorCode());
    }

    // ── Step 3: signature ─────────────────────────────────────────────────────

    @Test
    void step3_badSignature() throws Exception {
        KeyPair ca = generate();
        KeyPair wrong = generate();
        // Signed by CA, but verifier trusts a DIFFERENT key for CA_NID.
        IdentFrame f = frame(ca, "urn:nps:agent:a", future(), "0x01", List.of(), List.of("*"));
        NipIdentVerifyResult r = new NipIdentVerifier(opts(wrong.getPublic()).build())
            .verify(f, CA_NID, NipVerifyContext.empty());
        assertFalse(r.valid());
        assertEquals(3, r.stepFailed());
        assertEquals(NipErrorCodes.CERT_SIGNATURE_INVALID, r.errorCode());
    }

    // ── Step 4: revocation ────────────────────────────────────────────────────

    @Test
    void step4_localCrl() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:a", future(), "0x0A3F9C", List.of(), List.of("*"));
        NipVerifierOptions o = opts(ca.getPublic())
            .localRevokedSerials(Set.of("0x0A3F9C")).build();
        NipIdentVerifyResult r = new NipIdentVerifier(o).verify(f, CA_NID, NipVerifyContext.empty());
        assertFalse(r.valid());
        assertEquals(4, r.stepFailed());
        assertEquals(NipErrorCodes.CERT_REVOKED, r.errorCode());
    }

    @Test
    void step4_revocationCallbackRejects() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:a", future(), "0x01", List.of(), List.of("*"));
        NipVerifierOptions o = opts(ca.getPublic())
            .revocationCheck(fr -> NipIdentVerifyResult.fail(4, NipErrorCodes.CERT_REVOKED,
                "revoked by callback"))
            .build();
        NipIdentVerifyResult r = new NipIdentVerifier(o).verify(f, CA_NID, NipVerifyContext.empty());
        assertFalse(r.valid());
        assertEquals(4, r.stepFailed());
        assertEquals(NipErrorCodes.CERT_REVOKED, r.errorCode());
    }

    @Test
    void step4_revocationCallbackNullContinues() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:a", future(), "0x01", List.of(), List.of("*"));
        NipVerifierOptions o = opts(ca.getPublic())
            .revocationCheck(fr -> null) // null ⇒ continue, pass-through
            .build();
        NipIdentVerifyResult r = new NipIdentVerifier(o).verify(f, CA_NID, NipVerifyContext.empty());
        assertTrue(r.valid());
    }

    @Test
    void step4_revocationStoreRejects() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:a", future(), "0xBEEF", List.of(), List.of("*"));
        NipVerifierOptions o = opts(ca.getPublic())
            .revocationStore(serial -> serial.equals("0xBEEF")
                ? new NipRevocationStore.Record(serial, Instant.now().toString(), "key_compromise")
                : null)
            .build();
        NipIdentVerifyResult r = new NipIdentVerifier(o).verify(f, CA_NID, NipVerifyContext.empty());
        assertFalse(r.valid());
        assertEquals(4, r.stepFailed());
        assertEquals(NipErrorCodes.CERT_REVOKED, r.errorCode());
    }

    @Test
    void step4_revocationStoreUnknownSerialPasses() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:a", future(), "0x01", List.of(), List.of("*"));
        NipVerifierOptions o = opts(ca.getPublic())
            .revocationStore(serial -> null)
            .build();
        NipIdentVerifyResult r = new NipIdentVerifier(o).verify(f, CA_NID, NipVerifyContext.empty());
        assertTrue(r.valid());
    }

    @Test
    void step4_ocspValid() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:ocsp", future(), "0x01", List.of(), List.of("*"));
        HttpServer server = ocspServer(200, "{\"valid\": true}");
        try {
            NipVerifierOptions o = opts(ca.getPublic()).ocspUrl(baseUrl(server)).build();
            NipIdentVerifyResult r = new NipIdentVerifier(o).verify(f, CA_NID, NipVerifyContext.empty());
            assertTrue(r.valid(), () -> r.errorCode() + " " + r.message());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void step4_ocspRevoked() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:ocsp", future(), "0x01", List.of(), List.of("*"));
        HttpServer server = ocspServer(200,
            "{\"valid\": false, \"error_code\": \"NIP-CERT-REVOKED\"}");
        try {
            NipVerifierOptions o = opts(ca.getPublic()).ocspUrl(baseUrl(server)).build();
            NipIdentVerifyResult r = new NipIdentVerifier(o).verify(f, CA_NID, NipVerifyContext.empty());
            assertFalse(r.valid());
            assertEquals(4, r.stepFailed());
            assertEquals(NipErrorCodes.CERT_REVOKED, r.errorCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void step4_ocspHttpErrorFailsClosed() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:ocsp", future(), "0x01", List.of(), List.of("*"));
        HttpServer server = ocspServer(500, "boom");
        try {
            NipVerifierOptions o = opts(ca.getPublic()).ocspUrl(baseUrl(server)).build();
            NipIdentVerifyResult r = new NipIdentVerifier(o).verify(f, CA_NID, NipVerifyContext.empty());
            assertFalse(r.valid());
            assertEquals(4, r.stepFailed());
            assertEquals(NipErrorCodes.OCSP_UNAVAILABLE, r.errorCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void step4_ocspTransportFailClosed() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:ocsp", future(), "0x01", List.of(), List.of("*"));
        // Point at an unused port (nothing listening) ⇒ connection failure.
        NipVerifierOptions o = opts(ca.getPublic())
            .ocspUrl("http://127.0.0.1:1")
            .ocspFailOpen(false)
            .build();
        NipIdentVerifyResult r = new NipIdentVerifier(o).verify(f, CA_NID, NipVerifyContext.empty());
        assertFalse(r.valid());
        assertEquals(4, r.stepFailed());
        assertEquals(NipErrorCodes.OCSP_UNAVAILABLE, r.errorCode());
    }

    @Test
    void step4_ocspTransportFailOpen() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:ocsp", future(), "0x01", List.of(), List.of("*"));
        NipVerifierOptions o = opts(ca.getPublic())
            .ocspUrl("http://127.0.0.1:1")
            .ocspFailOpen(true)
            .build();
        NipIdentVerifyResult r = new NipIdentVerifier(o).verify(f, CA_NID, NipVerifyContext.empty());
        assertTrue(r.valid(), () -> "fail-open should pass; got " + r.errorCode() + " " + r.message());
    }

    @Test
    void step4_unconfiguredPassThrough() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:a", future(), "0x01", List.of(), List.of("*"));
        NipIdentVerifyResult r = new NipIdentVerifier(opts(ca.getPublic()).build())
            .verify(f, CA_NID, NipVerifyContext.empty());
        assertTrue(r.valid());
    }

    // ── Step 5: capabilities ──────────────────────────────────────────────────

    @Test
    void step5_missingCapability() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:a", future(), "0x01",
            List.of("nwp:query"), List.of("*"));
        NipVerifyContext ctx = NipVerifyContext.builder()
            .requiredCapabilities(List.of("nwp:query", "nwp:mutate")).build();
        NipIdentVerifyResult r = new NipIdentVerifier(opts(ca.getPublic()).build())
            .verify(f, CA_NID, ctx);
        assertFalse(r.valid());
        assertEquals(5, r.stepFailed());
        assertEquals(NipErrorCodes.CERT_CAPABILITY_MISSING, r.errorCode());
    }

    // ── Step 6: scope ─────────────────────────────────────────────────────────

    @Test
    void step6_scopeViolation() throws Exception {
        KeyPair ca = generate();
        IdentFrame f = frame(ca, "urn:nps:agent:a", future(), "0x01",
            List.of(), List.of("nwp://other.com/*"));
        NipVerifyContext ctx = NipVerifyContext.builder()
            .targetNodePath("nwp://api.myapp.com/products").build();
        NipIdentVerifyResult r = new NipIdentVerifier(opts(ca.getPublic()).build())
            .verify(f, CA_NID, ctx);
        assertFalse(r.valid());
        assertEquals(6, r.stepFailed());
        assertEquals(NipErrorCodes.CERT_SCOPE_VIOLATION, r.errorCode());
    }

    // ── Scope pattern matching unit tests (mirror .NET NwpPathMatches) ────────

    @Test
    void nwpPathMatches_rules() {
        assertTrue(NipIdentVerifier.nwpPathMatches("*", "nwp://anything/path"));
        assertTrue(NipIdentVerifier.nwpPathMatches("nwp://api.myapp.com/*", "nwp://api.myapp.com/products"));
        assertTrue(NipIdentVerifier.nwpPathMatches("nwp://api.myapp.com/*", "nwp://api.myapp.com"));
        assertFalse(NipIdentVerifier.nwpPathMatches("nwp://api.myapp.com/*", "nwp://api.myapp.com-evil/x"));
        assertTrue(NipIdentVerifier.nwpPathMatches("nwp://API.myapp.com/x", "nwp://api.myapp.com/x")); // case-insensitive
        assertFalse(NipIdentVerifier.nwpPathMatches("nwp://api.myapp.com/x", "nwp://api.myapp.com/y"));
    }

    // ── OCSP test server helpers ──────────────────────────────────────────────

    private static HttpServer ocspServer(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
