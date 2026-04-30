// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.acme.AcmeClient;
import com.labacacia.nps.nip.acme.AcmeJws;
import com.labacacia.nps.nip.acme.AcmeMessages;
import com.labacacia.nps.nip.acme.AcmeMessages.Account;
import com.labacacia.nps.nip.acme.AcmeMessages.Authorization;
import com.labacacia.nps.nip.acme.AcmeMessages.Identifier;
import com.labacacia.nps.nip.acme.AcmeMessages.NewAccountPayload;
import com.labacacia.nps.nip.acme.AcmeMessages.NewOrderPayload;
import com.labacacia.nps.nip.acme.AcmeMessages.Order;
import com.labacacia.nps.nip.acme.AcmeMessages.ProblemDetail;
import com.labacacia.nps.nip.acme.AcmeServer;
import com.labacacia.nps.nip.acme.AcmeWire;
import com.labacacia.nps.nip.x509.Ed25519PublicKeys;
import com.labacacia.nps.nip.x509.NipX509Builder;
import com.labacacia.nps.nip.x509.NipX509VerifyResult;
import com.labacacia.nps.nip.x509.NipX509Verifier;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java parallel of .NET {@code AcmeAgent01Tests.cs} per NPS-RFC-0002 §4.4.
 *
 * <p>End-to-end agent-01 round-trip plus tampered-signature negative path.
 */
class AcmeAgent01Tests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DEC = Base64.getUrlDecoder();

    /**
     * Drive the full ACME agent-01 flow client-side; verify the issued PEM chain via NipX509Verifier.
     */
    @Test
    void issueAgentCert_RoundTrip_ReturnsValidPemChain() throws Exception {
        Fixture fx = Fixture.create();
        try (AcmeServer server = fx.server.start()) {
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();

            AcmeClient client = new AcmeClient(http, server.directoryUrl(), fx.agentKp);
            String pem = client.issueAgentCert(fx.agentNid);

            assertNotNull(pem);
            assertTrue(pem.contains("BEGIN CERTIFICATE"));

            // Parse the leaf out of the PEM and verify against the trusted root.
            List<X509Certificate> chain = parsePemChain(pem);
            assertFalse(chain.isEmpty(), "PEM chain must contain at least the leaf");

            // Verify chain via NipX509Verifier (encode each cert as base64url DER first).
            List<String> chainB64 = chain.stream().map(c -> {
                try { return URL_ENC.encodeToString(c.getEncoded()); }
                catch (Exception e) { throw new RuntimeException(e); }
            }).toList();

            NipX509VerifyResult result = NipX509Verifier.verify(
                chainB64, fx.agentNid, AssuranceLevel.ANONYMOUS, List.of(fx.caRoot));
            assertTrue(result.valid(), () -> "leaf must verify; got "
                + result.errorCode() + " " + result.message());
            assertEquals(fx.agentNid, extractCn(result.leaf().getSubjectX500Principal().getName()));
        }
    }

    /** Tampered agent_signature triggers NIP-ACME-CHALLENGE-FAILED. */
    @Test
    void respondAgent01_TamperedSignature_ServerReturnsChallengeFailed() throws Exception {
        Fixture fx = Fixture.create();
        try (AcmeServer server = fx.server.start()) {
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();

            // Step 1: directly fetch directory, then drive newNonce/newAccount/newOrder by hand
            // so we can splice in a forged challenge response.
            AcmeMessages.Directory dir = MAPPER.readValue(
                http.send(HttpRequest.newBuilder(server.directoryUrl()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body(),
                AcmeMessages.Directory.class);

            String nonce = http.send(
                HttpRequest.newBuilder(URI.create(dir.newNonce()))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding())
                .headers().firstValue("Replay-Nonce").orElseThrow();

            // newAccount.
            AcmeJws.Jwk jwk = AcmeJws.jwkFromPublicKey(fx.agentKp.getPublic());
            AcmeJws.Envelope acctEnv = AcmeJws.sign(
                new AcmeJws.ProtectedHeader(AcmeJws.ALG_EDDSA, nonce, dir.newAccount(), jwk, null),
                new NewAccountPayload(true, null, null),
                fx.agentKp.getPrivate());
            HttpResponse<String> acctResp = http.send(
                HttpRequest.newBuilder(URI.create(dir.newAccount()))
                    .header("Content-Type", AcmeWire.CONTENT_TYPE_JOSE_JSON)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(acctEnv))).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(201, acctResp.statusCode());
            String accountUrl = acctResp.headers().firstValue("Location").orElseThrow();
            nonce = acctResp.headers().firstValue("Replay-Nonce").orElseThrow();

            // newOrder.
            AcmeJws.Envelope orderEnv = AcmeJws.sign(
                new AcmeJws.ProtectedHeader(AcmeJws.ALG_EDDSA, nonce, dir.newOrder(), null, accountUrl),
                new NewOrderPayload(List.of(new Identifier(AcmeWire.IDENTIFIER_TYPE_NID, fx.agentNid)), null, null),
                fx.agentKp.getPrivate());
            HttpResponse<String> orderResp = http.send(
                HttpRequest.newBuilder(URI.create(dir.newOrder()))
                    .header("Content-Type", AcmeWire.CONTENT_TYPE_JOSE_JSON)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(orderEnv))).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(201, orderResp.statusCode());
            Order order = MAPPER.readValue(orderResp.body(), Order.class);
            nonce = orderResp.headers().firstValue("Replay-Nonce").orElseThrow();

            // POST-as-GET on authz to discover challenge URL + token.
            AcmeJws.Envelope authzEnv = AcmeJws.sign(
                new AcmeJws.ProtectedHeader(AcmeJws.ALG_EDDSA, nonce, order.authorizations().get(0), null, accountUrl),
                null,
                fx.agentKp.getPrivate());
            HttpResponse<String> authzResp = http.send(
                HttpRequest.newBuilder(URI.create(order.authorizations().get(0)))
                    .header("Content-Type", AcmeWire.CONTENT_TYPE_JOSE_JSON)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(authzEnv))).build(),
                HttpResponse.BodyHandlers.ofString());
            Authorization authz = MAPPER.readValue(authzResp.body(), Authorization.class);
            nonce = authzResp.headers().firstValue("Replay-Nonce").orElseThrow();

            AcmeMessages.Challenge ch = authz.challenges().stream()
                .filter(c -> AcmeWire.CHALLENGE_AGENT_01.equals(c.type())).findFirst().orElseThrow();

            // ★ Tampered: sign challenge token with a *different* keypair, but submit JWS
            //   under the registered account JWK. Server verifies the JWS sig (passes with account
            //   key) but then verifies the agent_signature against the same account key (fails).
            KeyPair forgerKp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            java.security.Signature forge = java.security.Signature.getInstance("Ed25519");
            forge.initSign(forgerKp.getPrivate());
            forge.update(ch.token().getBytes(StandardCharsets.UTF_8));
            String forgedSig = URL_ENC.encodeToString(forge.sign());

            AcmeJws.Envelope chEnv = AcmeJws.sign(
                new AcmeJws.ProtectedHeader(AcmeJws.ALG_EDDSA, nonce, ch.url(), null, accountUrl),
                new AcmeMessages.ChallengeRespondPayload(forgedSig),
                fx.agentKp.getPrivate());
            HttpResponse<String> chResp = http.send(
                HttpRequest.newBuilder(URI.create(ch.url()))
                    .header("Content-Type", AcmeWire.CONTENT_TYPE_JOSE_JSON)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(chEnv))).build(),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(400, chResp.statusCode());
            ProblemDetail problem = MAPPER.readValue(chResp.body(), ProblemDetail.class);
            assertEquals(NipErrorCodes.ACME_CHALLENGE_FAILED, problem.type());
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private static final class Fixture {
        final KeyPair        caKp;
        final KeyPair        agentKp;
        final String         caNid;
        final String         agentNid;
        final X509Certificate caRoot;
        final AcmeServer     server;

        private Fixture(KeyPair caKp, KeyPair agentKp, String caNid, String agentNid,
                        X509Certificate caRoot, AcmeServer server) {
            this.caKp = caKp; this.agentKp = agentKp;
            this.caNid = caNid; this.agentNid = agentNid;
            this.caRoot = caRoot; this.server = server;
        }

        static Fixture create() throws Exception {
            String caNid    = "urn:nps:ca:acme-test";
            String agentNid = "urn:nps:agent:acme-test:1";

            KeyPair caKp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] caPubRaw = Ed25519PublicKeys.extractRaw(caKp.getPublic());
            X509Certificate caRoot = NipX509Builder.issueRoot(
                caNid, caKp.getPrivate(), caPubRaw,
                Instant.now().minus(Duration.ofMinutes(1)),
                Instant.now().plus(Duration.ofDays(365)),
                BigInteger.valueOf(1));

            KeyPair agentKp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

            AcmeServer server = new AcmeServer(
                caNid, caKp.getPrivate(), caPubRaw, caRoot, Duration.ofDays(30));

            return new Fixture(caKp, agentKp, caNid, agentNid, caRoot, server);
        }
    }

    // ── PEM helpers ──────────────────────────────────────────────────────────

    private static List<X509Certificate> parsePemChain(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        java.util.ArrayList<X509Certificate> out = new java.util.ArrayList<>();
        String marker = "-----BEGIN CERTIFICATE-----";
        int idx = 0;
        while ((idx = pem.indexOf(marker, idx)) >= 0) {
            int endMarker = pem.indexOf("-----END CERTIFICATE-----", idx);
            if (endMarker < 0) break;
            String b64 = pem.substring(idx + marker.length(), endMarker)
                .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(b64);
            out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            idx = endMarker + "-----END CERTIFICATE-----".length();
        }
        return out;
    }

    private static String extractCn(String dn) {
        for (String rdn : dn.split(",")) {
            String t = rdn.trim();
            if (t.startsWith("CN=")) return t.substring(3);
        }
        return null;
    }
}
