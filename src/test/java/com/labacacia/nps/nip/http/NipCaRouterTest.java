// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.ca.InMemoryNipCaStore;
import com.labacacia.nps.nip.ca.NipCaOptions;
import com.labacacia.nps.nip.ca.NipCaService;
import com.labacacia.nps.nip.ca.EnrollmentTier;
import com.labacacia.nps.nip.ca.ra.IBootstrapTokenStore;
import com.labacacia.nps.nip.ca.ra.IPendingStore;
import com.labacacia.nps.nip.ca.ra.InMemoryBootstrapTokenStore;
import com.labacacia.nps.nip.ca.ra.InMemoryPendingStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end router tests over the JDK HttpServer on an ephemeral port. */
class NipCaRouterTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String PUBKEY = "ed25519:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private HttpServer server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    private void start(NipCaRouter router) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", router);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    private static NipCaOptions opts() {
        NipCaOptions o = new NipCaOptions();
        o.caNid = "urn:nps:org:ca.example.com";
        o.baseUrl = "https://ca.example.com";
        o.normalizeOcspResponseTime = false;
        return o;
    }

    private static NipCaService svc(NipCaOptions o) throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return new NipCaService(o, new InMemoryNipCaStore(), kp.getPrivate(), kp.getPublic());
    }

    private HttpResponse<String> post(String path, Object body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
            .header("Content-Type", "application/json");
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(HttpResponse<String> r) throws Exception {
        return M.readValue(r.body(), Map.class);
    }

    @Test
    void wellKnownAndCaCert() throws Exception {
        var o = opts();
        start(new NipCaRouter(o, svc(o)));

        var wk = get("/.well-known/nps-ca");
        assertEquals(200, wk.statusCode());
        var wkBody = json(wk);
        assertEquals("urn:nps:org:ca.example.com", wkBody.get("issuer"));
        assertTrue(((List<?>) wkBody.get("capabilities")).contains("ra-tier-1"));

        var cert = get("/v1/ca/cert");
        assertEquals(200, cert.statusCode());
        assertTrue(((String) json(cert).get("public_key")).startsWith("ed25519:"));
    }

    @Test
    void registerVerifyRenewFlowFailsRenewTooEarly() throws Exception {
        var o = opts();
        start(new NipCaRouter(o, svc(o)));

        var reg = post("/v1/agents/register",
            Map.of("identifier", "acme", "pub_key", PUBKEY, "capabilities", List.of("nwp:query")));
        assertEquals(201, reg.statusCode());
        String nid = (String) json(reg).get("nid");
        assertEquals("urn:nps:agent:ca.example.com:acme", nid);

        var verify = get("/v1/agents/" + java.net.URLEncoder.encode(nid, StandardCharsets.UTF_8) + "/verify");
        assertEquals(200, verify.statusCode());
        assertEquals(Boolean.TRUE, json(verify).get("valid"));

        // Renewal just after issuance is too early → 400.
        var renew = post("/v1/agents/" + java.net.URLEncoder.encode(nid, StandardCharsets.UTF_8) + "/renew", Map.of());
        assertEquals(400, renew.statusCode());
        assertEquals("NIP-CA-RENEWAL-TOO-EARLY", json(renew).get("error_code"));
    }

    @Test
    void duplicateRegisterConflict() throws Exception {
        var o = opts();
        start(new NipCaRouter(o, svc(o)));
        post("/v1/agents/register", Map.of("identifier", "dup", "pub_key", PUBKEY));
        var second = post("/v1/agents/register", Map.of("identifier", "dup", "pub_key", PUBKEY));
        assertEquals(409, second.statusCode());
        assertEquals("NIP-CA-NID-ALREADY-EXISTS", json(second).get("error_code"));
    }

    @Test
    void badPubKeyRejected() throws Exception {
        var o = opts();
        start(new NipCaRouter(o, svc(o)));
        var r = post("/v1/agents/register", Map.of("identifier", "x", "pub_key", "nope"));
        assertEquals(400, r.statusCode());
    }

    @Test
    void revokeThenVerifyRevoked() throws Exception {
        var o = opts();
        start(new NipCaRouter(o, svc(o)));
        post("/v1/agents/register", Map.of("identifier", "gone", "pub_key", PUBKEY));
        String nid = "urn:nps:agent:ca.example.com:gone";
        String enc = java.net.URLEncoder.encode(nid, StandardCharsets.UTF_8);

        var rev = post("/v1/agents/" + enc + "/revoke", Map.of("reason", "key_compromise"));
        assertEquals(200, rev.statusCode());
        assertEquals(nid, json(rev).get("target_nid"));

        var verify = get("/v1/agents/" + enc + "/verify");
        assertEquals(200, verify.statusCode());
        assertEquals(Boolean.FALSE, json(verify).get("valid"));
        assertEquals("NIP-CERT-REVOKED", json(verify).get("error_code"));
    }

    @Test
    void invalidRevocationReason() throws Exception {
        var o = opts();
        start(new NipCaRouter(o, svc(o)));
        post("/v1/agents/register", Map.of("identifier", "r", "pub_key", PUBKEY));
        String enc = java.net.URLEncoder.encode("urn:nps:agent:ca.example.com:r", StandardCharsets.UTF_8);
        var rev = post("/v1/agents/" + enc + "/revoke", Map.of("reason", "made_up"));
        assertEquals(400, rev.statusCode());
    }

    @Test
    void operatorAuthEnforced() throws Exception {
        var o = opts();
        o.operatorApiKey = "s3cret";
        start(new NipCaRouter(o, svc(o)));

        var noAuth = post("/v1/agents/register", Map.of("identifier", "a", "pub_key", PUBKEY));
        assertEquals(401, noAuth.statusCode());

        var withAuth = post("/v1/agents/register",
            Map.of("identifier", "a", "pub_key", PUBKEY), "Authorization", "Bearer s3cret");
        assertEquals(201, withAuth.statusCode());
    }

    @Test
    void groupRegisterIssueSessionAndCrl() throws Exception {
        var o = opts();
        start(new NipCaRouter(o, svc(o)));

        var grp = post("/v1/orchestrators/groups/register",
            Map.of("identifier", "group-g1", "pub_key", PUBKEY, "capabilities", List.of("nwp:query")));
        assertEquals(201, grp.statusCode());
        String groupNid = (String) json(grp).get("nid");
        String enc = java.net.URLEncoder.encode(groupNid, StandardCharsets.UTF_8);

        var issue = post("/v1/orchestrators/groups/" + enc + "/sessions/issue",
            Map.of("session_pub_key", PUBKEY, "validity_seconds", 1800));
        assertEquals(201, issue.statusCode());
        assertEquals("session", ((Map<?, ?>) json(issue).get("lineage")).get("role"));

        var sessions = get("/v1/orchestrators/groups/" + enc + "/sessions");
        assertEquals(200, sessions.statusCode());
        assertEquals(1, ((Number) json(sessions).get("count")).intValue());

        // Cascade revoke → CRL surfaces group + session.
        var rev = post("/v1/orchestrators/groups/" + enc + "/revoke", Map.of("reason", "superseded"));
        assertEquals(200, rev.statusCode());

        var crl = get("/v1/crl");
        assertEquals(200, crl.statusCode());
        assertEquals(2, ((List<?>) json(crl).get("entries")).size());
    }

    @Test
    void issueSessionViaGroupJws() throws Exception {
        // Group registered with a REAL Ed25519 key so JWS verification succeeds.
        var o = opts();
        NipCaService ca = svc(o);
        KeyPair groupKp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] rawPub = com.labacacia.nps.nip.x509.Ed25519PublicKeys.extractRaw(groupKp.getPublic());
        String groupPubKey = "ed25519:" + Base64.getUrlEncoder().withoutPadding().encodeToString(rawPub);
        var group = ca.registerGroup("group-jws", groupPubKey, List.of("nwp:query"), "{}", null, null, null);
        start(new NipCaRouter(o, ca));

        // Build a valid flattened group-JWS.
        var header = Map.<String, Object>of("alg", "EdDSA", "kid", group.nid(), "nps-purpose", "session-issue");
        var payload = Map.<String, Object>of("session_pub_key", PUBKEY, "iat", java.time.Instant.now().getEpochSecond());
        String prot = Base64.getUrlEncoder().withoutPadding().encodeToString(M.writeValueAsBytes(header));
        String pay = Base64.getUrlEncoder().withoutPadding().encodeToString(M.writeValueAsBytes(payload));
        java.security.Signature s = java.security.Signature.getInstance("Ed25519");
        s.initSign(groupKp.getPrivate());
        s.update((prot + "." + pay).getBytes(StandardCharsets.US_ASCII));
        String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());

        String enc = java.net.URLEncoder.encode(group.nid(), StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/v1/orchestrators/groups/" + enc + "/sessions/issue"))
            .header("Content-Type", "application/jose+json")
            .POST(HttpRequest.BodyPublishers.ofString(
                M.writeValueAsString(Map.of("protected", prot, "payload", pay, "signature", sig))))
            .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp.statusCode(), resp.body());
        assertEquals("session", ((Map<?, ?>) json(resp).get("lineage")).get("role"));
    }

    @Test
    void raBootstrapTokenEndpointFlow() throws Exception {
        var o = opts();
        o.enrollmentTier = EnrollmentTier.BOOTSTRAP_TOKEN;
        IBootstrapTokenStore tokens = new InMemoryBootstrapTokenStore();
        start(new NipCaRouter(o, svc(o), tokens, null));

        // No token → 401.
        var noTok = post("/v1/agents/register", Map.of("identifier", "n1", "pub_key", PUBKEY));
        assertEquals(401, noTok.statusCode());

        // Mint a token.
        var tok = post("/v1/enrollment/tokens", Map.of("ttl_seconds", 3600, "label", "ci"));
        assertEquals(201, tok.statusCode());
        String raw = (String) json(tok).get("token");
        assertTrue(raw.startsWith("nps-bootstrap-"));

        // Register with token → 201.
        var reg = post("/v1/agents/register",
            Map.of("identifier", "n1", "pub_key", PUBKEY), "X-NPS-Enrollment-Token", raw);
        assertEquals(201, reg.statusCode());
    }

    @Test
    void raPendingQueueEndpointFlow() throws Exception {
        var o = opts();
        o.enrollmentTier = EnrollmentTier.PENDING_QUEUE;
        IPendingStore pending = new InMemoryPendingStore(Duration.ofDays(7));
        start(new NipCaRouter(o, svc(o), null, pending));

        var queued = post("/v1/agents/register", Map.of("identifier", "q1", "pub_key", PUBKEY));
        assertEquals(202, queued.statusCode());
        String pendingId = (String) json(queued).get("pending_id");
        assertNotNull(pendingId);

        var list = get("/v1/enrollment/pending");
        assertEquals(200, list.statusCode());
        assertEquals(1, ((Number) json(list).get("count")).intValue());

        var approve = post("/v1/enrollment/pending/" + pendingId + "/approve", Map.of());
        assertEquals(201, approve.statusCode());
        assertEquals("urn:nps:agent:ca.example.com:q1", json(approve).get("nid"));
    }

    @Test
    void unknownRouteIs404() throws Exception {
        var o = opts();
        start(new NipCaRouter(o, svc(o)));
        assertEquals(404, get("/v1/nope").statusCode());
    }
}
