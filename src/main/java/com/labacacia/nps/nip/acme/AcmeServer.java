// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.acme;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.AssuranceLevel;
import com.labacacia.nps.nip.NipErrorCodes;
import com.labacacia.nps.nip.acme.AcmeMessages.Authorization;
import com.labacacia.nps.nip.acme.AcmeMessages.Challenge;
import com.labacacia.nps.nip.acme.AcmeMessages.Directory;
import com.labacacia.nps.nip.acme.AcmeMessages.FinalizePayload;
import com.labacacia.nps.nip.acme.AcmeMessages.Identifier;
import com.labacacia.nps.nip.acme.AcmeMessages.NewAccountPayload;
import com.labacacia.nps.nip.acme.AcmeMessages.NewOrderPayload;
import com.labacacia.nps.nip.acme.AcmeMessages.Order;
import com.labacacia.nps.nip.acme.AcmeMessages.ProblemDetail;
import com.labacacia.nps.nip.x509.Ed25519PublicKeys;
import com.labacacia.nps.nip.x509.NipX509Builder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * In-process ACME server implementing the {@code agent-01} challenge for NPS-RFC-0002 §4.4.
 * <p>
 * Backed by JDK's {@link HttpServer} (no external Spring/Netty dependency), suitable for
 * tests and reference deployments. State is kept in memory; suitable for a single test run
 * or a small private CA.
 *
 * <p>Routing:
 * <pre>
 * GET  /directory          → directory document
 * HEAD /new-nonce          → 200 + Replay-Nonce
 * POST /new-account        → 201 + Location: account URL
 * POST /new-order          → 201 + order body
 * POST /authz/{id}         → authorization body (POST-as-GET)
 * POST /chall/{id}         → respond to challenge
 * POST /finalize/{id}      → submit CSR
 * POST /cert/{id}          → fetch issued PEM (POST-as-GET)
 * POST /order/{id}         → poll order state (POST-as-GET)
 * </pre>
 */
public final class AcmeServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DEC = Base64.getUrlDecoder();
    private static final SecureRandom RNG = new SecureRandom();

    private final HttpServer server;
    private final java.security.PrivateKey caPrivKey;
    private final byte[] caPubKeyRaw;
    private final String caNid;
    private final X509Certificate caRootCert;
    private final Duration certValidity;

    // ── In-memory state ──────────────────────────────────────────────────────
    private final Map<String, byte[]>          nonces      = new ConcurrentHashMap<>();
    private final Map<String, AcmeJws.Jwk>     accountJwks = new ConcurrentHashMap<>();
    private final Map<String, OrderState>      orders      = new ConcurrentHashMap<>();
    private final Map<String, AuthzState>      authzs      = new ConcurrentHashMap<>();
    private final Map<String, ChallengeState>  challenges  = new ConcurrentHashMap<>();
    private final Map<String, String>          certs       = new ConcurrentHashMap<>();

    /**
     * Build a server bound to an ephemeral loopback port. The server is bound but not started;
     * call {@link #start()} to begin accepting requests. Use {@link #baseUrl()} to discover the
     * resolved URL after construction.
     */
    public AcmeServer(
            String              caNid,
            java.security.PrivateKey caPrivKey,
            byte[]              caPubKeyRaw,
            X509Certificate     caRootCert,
            Duration            certValidity) throws IOException {
        this.caNid        = caNid;
        this.caPrivKey    = caPrivKey;
        this.caPubKeyRaw  = caPubKeyRaw.clone();
        this.caRootCert   = caRootCert;
        this.certValidity = certValidity;

        // bind to ephemeral port — server.getAddress() is final after this returns.
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        registerRoutes();
    }

    /** Begin accepting requests. Idempotent in effect — HttpServer ignores re-start. */
    public AcmeServer start() {
        server.start();
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public URI directoryUrl() { return URI.create(baseUrl() + "/directory"); }

    @Override public void close() { server.stop(0); }

    // ── Routes ───────────────────────────────────────────────────────────────

    private void registerRoutes() {
        server.createContext("/directory",   this::handleDirectory);
        server.createContext("/new-nonce",   this::handleNewNonce);
        server.createContext("/new-account", this::handleNewAccount);
        server.createContext("/new-order",   this::handleNewOrder);
        server.createContext("/authz/",      this::handleAuthz);
        server.createContext("/chall/",      this::handleChallenge);
        server.createContext("/finalize/",   this::handleFinalize);
        server.createContext("/cert/",       this::handleCert);
        server.createContext("/order/",      this::handleOrder);
    }

    private void handleDirectory(HttpExchange ex) throws IOException {
        Directory dir = new Directory(
            baseUrl() + "/new-nonce",
            baseUrl() + "/new-account",
            baseUrl() + "/new-order",
            null, null, null);
        sendJson(ex, 200, dir);
    }

    private void handleNewNonce(HttpExchange ex) throws IOException {
        String n = mintNonce();
        ex.getResponseHeaders().add("Replay-Nonce", n);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders("HEAD".equals(ex.getRequestMethod()) ? 200 : 204, -1);
        ex.close();
    }

    private void handleNewAccount(HttpExchange ex) throws IOException {
        AcmeJws.Envelope env = readEnvelope(ex);
        AcmeJws.ProtectedHeader header = parseHeader(env);
        if (header.jwk() == null) {
            problem(ex, 400, "urn:ietf:params:acme:error:malformed",
                "newAccount must include a 'jwk' member");
            return;
        }
        if (!consumeNonce(header.nonce())) {
            problem(ex, 400, "urn:ietf:params:acme:error:badNonce", "invalid nonce");
            return;
        }
        PublicKey pub = AcmeJws.publicKeyFromJwk(header.jwk());
        if (AcmeJws.verify(env, pub) == null) {
            problem(ex, 400, "urn:ietf:params:acme:error:malformed", "JWS signature verify failed");
            return;
        }

        String accountId = "acc-" + randomId();
        String accountUrl = baseUrl() + "/account/" + accountId;
        accountJwks.put(accountUrl, header.jwk());

        ex.getResponseHeaders().add("Location", accountUrl);
        ex.getResponseHeaders().add("Replay-Nonce", mintNonce());
        sendJson(ex, 201,
            new AcmeMessages.Account(AcmeMessages.Status.VALID, null, null));
    }

    private void handleNewOrder(HttpExchange ex) throws IOException {
        AcmeJws.Envelope env = readEnvelope(ex);
        AcmeJws.ProtectedHeader header = parseHeader(env);
        if (!consumeNonce(header.nonce())) {
            problem(ex, 400, "urn:ietf:params:acme:error:badNonce", "invalid nonce");
            return;
        }
        AcmeJws.Jwk jwk = accountJwks.get(header.kid());
        if (jwk == null) {
            problem(ex, 401, "urn:ietf:params:acme:error:accountDoesNotExist",
                "unknown account kid: " + header.kid());
            return;
        }
        if (AcmeJws.verify(env, AcmeJws.publicKeyFromJwk(jwk)) == null) {
            problem(ex, 400, "urn:ietf:params:acme:error:malformed", "JWS signature verify failed");
            return;
        }

        NewOrderPayload payload = AcmeJws.decodePayload(env, NewOrderPayload.class);
        if (payload == null || payload.identifiers() == null || payload.identifiers().isEmpty()) {
            problem(ex, 400, "urn:ietf:params:acme:error:malformed", "missing identifiers");
            return;
        }
        Identifier id = payload.identifiers().get(0);

        String orderId = "ord-" + randomId();
        String authzId = "az-"  + randomId();
        String challId = "ch-"  + randomId();
        String token   = URL_ENC.encodeToString(randomBytes(32));

        String orderUrl    = baseUrl() + "/order/"    + orderId;
        String authzUrl    = baseUrl() + "/authz/"    + authzId;
        String challUrl    = baseUrl() + "/chall/"    + challId;
        String finalizeUrl = baseUrl() + "/finalize/" + orderId;

        ChallengeState chState = new ChallengeState(
            challId, AcmeWire.CHALLENGE_AGENT_01, AcmeMessages.Status.PENDING,
            token, authzId, header.kid());
        challenges.put(challId, chState);

        AuthzState azState = new AuthzState(authzId, id, AcmeMessages.Status.PENDING,
            List.of(challId), header.kid());
        authzs.put(authzId, azState);

        OrderState orderState = new OrderState(orderId, id, AcmeMessages.Status.PENDING,
            authzId, finalizeUrl, null, header.kid());
        orders.put(orderId, orderState);

        ex.getResponseHeaders().add("Location", orderUrl);
        ex.getResponseHeaders().add("Replay-Nonce", mintNonce());
        sendJson(ex, 201, orderToWire(orderState, List.of(authzUrl)));
    }

    private void handleAuthz(HttpExchange ex) throws IOException {
        AcmeJws.Envelope env = readEnvelope(ex);
        AcmeJws.ProtectedHeader header = parseHeader(env);
        if (!consumeNonce(header.nonce())) {
            problem(ex, 400, "urn:ietf:params:acme:error:badNonce", "invalid nonce"); return;
        }
        if (!verifyAccount(env, header)) {
            problem(ex, 401, "urn:ietf:params:acme:error:unauthorized", "bad sig"); return;
        }
        String id = ex.getRequestURI().getPath().replaceFirst("^/authz/", "");
        AuthzState az = authzs.get(id);
        if (az == null) { problem(ex, 404, "urn:ietf:params:acme:error:malformed", "no authz"); return; }

        List<Challenge> chs = az.challengeIds.stream().map(cid -> {
            ChallengeState cs = challenges.get(cid);
            return new Challenge(cs.type, baseUrl() + "/chall/" + cs.id, cs.status, cs.token, null, null);
        }).toList();

        ex.getResponseHeaders().add("Replay-Nonce", mintNonce());
        sendJson(ex, 200,
            new Authorization(az.status, null, az.identifier, chs));
    }

    private void handleChallenge(HttpExchange ex) throws IOException {
        AcmeJws.Envelope env = readEnvelope(ex);
        AcmeJws.ProtectedHeader header = parseHeader(env);
        if (!consumeNonce(header.nonce())) {
            problem(ex, 400, "urn:ietf:params:acme:error:badNonce", "invalid nonce"); return;
        }
        AcmeJws.Jwk jwk = accountJwks.get(header.kid());
        if (jwk == null) {
            problem(ex, 401, "urn:ietf:params:acme:error:accountDoesNotExist", "unknown kid"); return;
        }
        PublicKey accountPub = AcmeJws.publicKeyFromJwk(jwk);
        if (AcmeJws.verify(env, accountPub) == null) {
            problem(ex, 400, "urn:ietf:params:acme:error:malformed", "JWS sig fail"); return;
        }

        String id = ex.getRequestURI().getPath().replaceFirst("^/chall/", "");
        ChallengeState ch = challenges.get(id);
        if (ch == null) { problem(ex, 404, "urn:ietf:params:acme:error:malformed", "no chall"); return; }

        AcmeMessages.ChallengeRespondPayload resp =
            AcmeJws.decodePayload(env, AcmeMessages.ChallengeRespondPayload.class);
        if (resp == null || resp.agentSignature() == null) {
            ch.status = AcmeMessages.Status.INVALID;
            problem(ex, 400, NipErrorCodes.ACME_CHALLENGE_FAILED,
                "missing agent_signature in challenge response");
            return;
        }

        try {
            byte[] sigBytes = URL_DEC.decode(resp.agentSignature());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(accountPub);
            verifier.update(ch.token.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(sigBytes)) {
                ch.status = AcmeMessages.Status.INVALID;
                problem(ex, 400, NipErrorCodes.ACME_CHALLENGE_FAILED,
                    "agent-01 signature did not verify");
                return;
            }
        } catch (Exception e) {
            ch.status = AcmeMessages.Status.INVALID;
            problem(ex, 400, NipErrorCodes.ACME_CHALLENGE_FAILED,
                "agent-01 verification error: " + e.getMessage());
            return;
        }

        ch.status = AcmeMessages.Status.VALID;
        AuthzState az = authzs.get(ch.authzId);
        if (az != null) az.status = AcmeMessages.Status.VALID;
        // Move the order to "ready" so the client can finalize.
        for (OrderState o : orders.values()) {
            if (o.authzId.equals(ch.authzId)) o.status = AcmeMessages.Status.READY;
        }

        ex.getResponseHeaders().add("Replay-Nonce", mintNonce());
        sendJson(ex, 200,
            new Challenge(ch.type, baseUrl() + "/chall/" + ch.id, ch.status, ch.token, null, null));
    }

    private void handleFinalize(HttpExchange ex) throws IOException {
        AcmeJws.Envelope env = readEnvelope(ex);
        AcmeJws.ProtectedHeader header = parseHeader(env);
        if (!consumeNonce(header.nonce())) {
            problem(ex, 400, "urn:ietf:params:acme:error:badNonce", "invalid nonce"); return;
        }
        if (!verifyAccount(env, header)) {
            problem(ex, 401, "urn:ietf:params:acme:error:unauthorized", "bad sig"); return;
        }

        String orderId = ex.getRequestURI().getPath().replaceFirst("^/finalize/", "");
        OrderState os = orders.get(orderId);
        if (os == null) { problem(ex, 404, "urn:ietf:params:acme:error:malformed", "no order"); return; }
        if (!AcmeMessages.Status.READY.equals(os.status)) {
            problem(ex, 403, "urn:ietf:params:acme:error:orderNotReady",
                "order is in state '" + os.status + "', not 'ready'");
            return;
        }

        FinalizePayload fp = AcmeJws.decodePayload(env, FinalizePayload.class);
        if (fp == null || fp.csr() == null) {
            problem(ex, 400, "urn:ietf:params:acme:error:malformed", "missing csr"); return;
        }

        try {
            byte[] csrDer = URL_DEC.decode(fp.csr());
            PKCS10CertificationRequest csr = new PKCS10CertificationRequest(csrDer);

            String subjectCn = null;
            for (String rdn : csr.getSubject().toString().split(",")) {
                String t = rdn.trim();
                if (t.startsWith("CN=")) { subjectCn = t.substring(3); break; }
            }
            if (!os.identifier.value().equals(subjectCn)) {
                problem(ex, 400, NipErrorCodes.CERT_SUBJECT_NID_MISMATCH,
                    "CSR subject CN '" + subjectCn + "' does not match order identifier '" + os.identifier.value() + "'");
                return;
            }

            // Extract subject pubkey raw bytes from CSR's SubjectPublicKeyInfo.
            byte[] rawPub = csr.getSubjectPublicKeyInfo().getPublicKeyData().getBytes();

            BigInteger serial = new BigInteger(160, RNG);
            X509Certificate leaf = NipX509Builder.issueLeaf(
                os.identifier.value(), rawPub,
                caPrivKey, caNid,
                NipX509Builder.LeafRole.AGENT,
                AssuranceLevel.ANONYMOUS,
                Instant.now().minus(Duration.ofMinutes(1)),
                Instant.now().plus(certValidity),
                serial);

            String certId = "crt-" + randomId();
            String certUrl = baseUrl() + "/cert/" + certId;
            String pem = pem(leaf) + pem(caRootCert);
            certs.put(certId, pem);

            os.status = AcmeMessages.Status.VALID;
            os.certificateUrl = certUrl;

            ex.getResponseHeaders().add("Replay-Nonce", mintNonce());
            sendJson(ex, 200, orderToWire(os, List.of(baseUrl() + "/authz/" + os.authzId)));
        } catch (Exception e) {
            problem(ex, 400, "urn:ietf:params:acme:error:badCSR",
                "CSR processing failed: " + e.getMessage());
        }
    }

    private void handleCert(HttpExchange ex) throws IOException {
        AcmeJws.Envelope env = readEnvelope(ex);
        AcmeJws.ProtectedHeader header = parseHeader(env);
        if (!consumeNonce(header.nonce())) {
            problem(ex, 400, "urn:ietf:params:acme:error:badNonce", "invalid nonce"); return;
        }
        if (!verifyAccount(env, header)) {
            problem(ex, 401, "urn:ietf:params:acme:error:unauthorized", "bad sig"); return;
        }
        String certId = ex.getRequestURI().getPath().replaceFirst("^/cert/", "");
        String pem = certs.get(certId);
        if (pem == null) { problem(ex, 404, "urn:ietf:params:acme:error:malformed", "no cert"); return; }

        ex.getResponseHeaders().add("Content-Type", AcmeWire.CONTENT_TYPE_PEM_CERT);
        ex.getResponseHeaders().add("Replay-Nonce", mintNonce());
        byte[] body = pem.getBytes(StandardCharsets.US_ASCII);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void handleOrder(HttpExchange ex) throws IOException {
        AcmeJws.Envelope env = readEnvelope(ex);
        AcmeJws.ProtectedHeader header = parseHeader(env);
        if (!consumeNonce(header.nonce())) {
            problem(ex, 400, "urn:ietf:params:acme:error:badNonce", "invalid nonce"); return;
        }
        if (!verifyAccount(env, header)) {
            problem(ex, 401, "urn:ietf:params:acme:error:unauthorized", "bad sig"); return;
        }
        String id = ex.getRequestURI().getPath().replaceFirst("^/order/", "");
        OrderState os = orders.get(id);
        if (os == null) { problem(ex, 404, "urn:ietf:params:acme:error:malformed", "no order"); return; }
        ex.getResponseHeaders().add("Replay-Nonce", mintNonce());
        sendJson(ex, 200, orderToWire(os, List.of(baseUrl() + "/authz/" + os.authzId)));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean verifyAccount(AcmeJws.Envelope env, AcmeJws.ProtectedHeader header) {
        if (header.kid() == null) return false;
        AcmeJws.Jwk jwk = accountJwks.get(header.kid());
        if (jwk == null) return false;
        return AcmeJws.verify(env, AcmeJws.publicKeyFromJwk(jwk)) != null;
    }

    private Order orderToWire(OrderState os, List<String> authzUrls) {
        return new Order(os.status, null,
            List.of(os.identifier),
            authzUrls,
            os.finalizeUrl,
            os.certificateUrl,
            null);
    }

    private String mintNonce() {
        byte[] n = randomBytes(16);
        String s = URL_ENC.encodeToString(n);
        nonces.put(s, n);
        return s;
    }

    private boolean consumeNonce(String nonce) {
        return nonce != null && nonces.remove(nonce) != null;
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private AcmeJws.Envelope readEnvelope(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return MAPPER.readValue(is, AcmeJws.Envelope.class);
        }
    }

    private AcmeJws.ProtectedHeader parseHeader(AcmeJws.Envelope env) {
        try {
            return MAPPER.readValue(URL_DEC.decode(env.protectedB64u()),
                AcmeJws.ProtectedHeader.class);
        } catch (Exception e) {
            throw new RuntimeException("malformed protected header: " + e.getMessage(), e);
        }
    }

    private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] json = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, json.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(json); }
    }

    private void problem(HttpExchange ex, int status, String type, String detail) throws IOException {
        ProblemDetail body = new ProblemDetail(type, detail, status);
        byte[] json = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", AcmeWire.CONTENT_TYPE_PROBLEM);
        ex.sendResponseHeaders(status, json.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(json); }
    }

    private static String pem(X509Certificate cert) {
        try {
            String b64 = Base64.getMimeEncoder(64,
                System.lineSeparator().getBytes(StandardCharsets.US_ASCII))
                .encodeToString(cert.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + b64
                + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new RuntimeException("PEM encode failed", e);
        }
    }

    // ── State records ────────────────────────────────────────────────────────

    private static final class OrderState {
        final String     id;
        final Identifier identifier;
        String           status;
        final String     authzId;
        final String     finalizeUrl;
        String           certificateUrl;
        final String     accountUrl;
        OrderState(String id, Identifier identifier, String status, String authzId,
                   String finalizeUrl, String certificateUrl, String accountUrl) {
            this.id = id; this.identifier = identifier; this.status = status;
            this.authzId = authzId; this.finalizeUrl = finalizeUrl;
            this.certificateUrl = certificateUrl; this.accountUrl = accountUrl;
        }
    }

    private static final class AuthzState {
        final String       id;
        final Identifier   identifier;
        String             status;
        final List<String> challengeIds;
        final String       accountUrl;
        AuthzState(String id, Identifier identifier, String status,
                   List<String> challengeIds, String accountUrl) {
            this.id = id; this.identifier = identifier; this.status = status;
            this.challengeIds = challengeIds; this.accountUrl = accountUrl;
        }
    }

    private static final class ChallengeState {
        final String id;
        final String type;
        String       status;
        final String token;
        final String authzId;
        final String accountUrl;
        ChallengeState(String id, String type, String status,
                       String token, String authzId, String accountUrl) {
            this.id = id; this.type = type; this.status = status;
            this.token = token; this.authzId = authzId; this.accountUrl = accountUrl;
        }
    }
}
