// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.reputation.IncidentType;
import com.labacacia.nps.nip.reputation.ReputationLogEntry;
import com.labacacia.nps.nip.reputation.Severity;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class AnchorNodeServerTest {

    private static final String PREFIX = "/gw";
    private static final String NID = "urn:nps:node:anchor.example.com:svc";
    private static final String AGENT = "urn:nps:agent:tester";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String start(AnchorNodeServer app) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", app);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        return base;
    }

    private static AnchorNodeServer.Options baseOpts() {
        var o = new AnchorNodeServer.Options();
        o.nodeId = NID;
        o.pathPrefix = PREFIX;
        o.actions = new java.util.LinkedHashMap<>();
        o.actions.put("orders.create", new AnchorNodeServer.ActionSpec().withResultAnchor("nps:orders:result", 10));
        return o;
    }

    private static List<MemberInfo> members() {
        var m1 = new MemberInfo("urn:nps:node:w1", List.of("worker"), "resident", null, null, null, null, null, null, null);
        var m2 = new MemberInfo("urn:nps:node:w2", List.of("worker"), "ephemeral", null, null, List.of("gpu"), null, null, null, null);
        return List.of(m1, m2);
    }

    private HttpResponse<String> get(String path, String agent, Map<String, String> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (agent != null) b.header("X-NWP-Agent", agent);
        if (headers != null) headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Object body, String agent, Map<String, String> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path))
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (agent != null) b.header("X-NWP-Agent", agent);
        if (headers != null) headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(HttpResponse<String> resp) throws IOException {
        return MAPPER.readValue(resp.body(), Map.class);
    }

    // ── Manifest + auth ──────────────────────────────────────────────────────────

    @Test
    void manifestAndSplice() throws Exception {
        var o = baseOpts();
        o.displayName = "Svc";
        o.cgnLimit = 500;
        o.trustAnchors = List.of("urn:nps:org:root");
        var policy = new ReputationPolicy(true, List.of("https://log"), "anonymous", 300, 3600, "allow",
            List.of(), List.of(), List.of(new ReputationPolicyRule("*", ">=critical")));
        o.reputationPolicy = policy;
        start(new AnchorNodeServer(o, new AnchorNodeServer.Deps()));

        var resp = get(PREFIX + "/.nwm", AGENT, null);
        assertEquals(200, resp.statusCode());
        assertEquals("application/nwp-manifest+json", resp.headers().firstValue("content-type").orElse(""));
        var m = json(resp);
        assertEquals("0.4", m.get("nwp"));
        assertEquals("anchor", m.get("node_type"));
        assertEquals("nip-cert", ((Map<?, ?>) m.get("auth")).get("identity_type"));
        assertEquals(500, ((Map<?, ?>) m.get("token_budget")).get("cgn_limit"));
        assertEquals(List.of("https://log"), ((Map<?, ?>) m.get("reputation_policy")).get("log_sources"));
        assertEquals(List.of("urn:nps:org:root"), m.get("trust_anchors"));
    }

    @Test
    void authGate() throws Exception {
        start(new AnchorNodeServer(baseOpts(), new AnchorNodeServer.Deps()));
        var resp = get(PREFIX + "/.nwm", null, null);
        assertEquals(401, resp.statusCode());
        assertEquals(NwpErrorCodes.NWP_AUTH_NID_SCOPE_VIOLATION, json(resp).get("error"));
    }

    @Test
    void invokeTimeoutReturns504() throws Exception {
        // A handler that overruns the effective timeout must yield 504 (parity with the Python
        // port's asyncio.wait_for), not hang the response.
        var o = baseOpts();
        o.actions.put("slow", new AnchorNodeServer.ActionSpec(null, null, null, null, 100, 100, null, false));
        AnchorNodeServer.InvokeHandler handler = (id, params, ctx) -> {
            try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return Map.of("ok", true);
        };
        start(new AnchorNodeServer(o, new AnchorNodeServer.Deps(handler, null, null, null)));
        var resp = post(PREFIX + "/invoke", Map.of("action_id", "slow"), AGENT, null);
        assertEquals(504, resp.statusCode());
    }

    @Test
    void unknownPathIs404BeforeAuth() throws Exception {
        // An unknown sub-path must be 404 regardless of auth — a missing X-NWP-Agent on a route
        // with no resource must NOT leak a 401 (auth state). Note: no agent header is sent.
        start(new AnchorNodeServer(baseOpts(), new AnchorNodeServer.Deps()));
        var resp = get(PREFIX + "/nope", null, null);
        assertEquals(404, resp.statusCode());
        assertEquals(NwpErrorCodes.NWP_ACTION_NOT_FOUND, json(resp).get("error"));
    }

    // ── Topology via real AnchorNodeClient ────────────────────────────────────────

    @Test
    void snapshotViaClient() throws Exception {
        var o = baseOpts();
        o.requireAuth = false;
        var topo = new AnchorNodeServer.InMemoryTopologyService(NID, members(), 7, List.of());
        start(new AnchorNodeServer(o, new AnchorNodeServer.Deps(null, topo, null, null)));

        var anchorClient = new AnchorNodeClient(base, PREFIX, null);
        var snap = anchorClient.getSnapshot();
        assertEquals(7, snap.version);
        assertEquals(NID, snap.anchorNid);
        assertEquals(2, snap.clusterSize);
        assertEquals(2, snap.members.size());
    }

    @Test
    void streamViaClient() throws Exception {
        var o = baseOpts();
        o.requireAuth = false;
        List<TopologyEvent> events = List.of(
            new MemberJoinedEvent(8, members().get(0)),
            new ResyncRequiredEvent("rebased"));
        var topo = new AnchorNodeServer.InMemoryTopologyService(NID, members(), 1, events);
        start(new AnchorNodeServer(o, new AnchorNodeServer.Deps(null, topo, null, null)));

        var anchorClient = new AnchorNodeClient(base, PREFIX, null);
        List<TopologyEvent> received = new ArrayList<>();
        for (TopologyEvent ev : anchorClient.subscribe()) received.add(ev);
        assertEquals(2, received.size());
        assertInstanceOf(MemberJoinedEvent.class, received.get(0));
        assertInstanceOf(ResyncRequiredEvent.class, received.get(1));
    }

    @Test
    void topologyErrors() throws Exception {
        var topo = new AnchorNodeServer.InMemoryTopologyService(NID, members(), 1, List.of());
        start(new AnchorNodeServer(baseOpts(), new AnchorNodeServer.Deps(null, topo, null, null)));

        var r1 = post(PREFIX + "/query", Map.of("type", "topology.bogus", "topology", Map.of()), AGENT, null);
        assertEquals(501, r1.statusCode());
        assertEquals(NwpErrorCodes.NWP_RESERVED_TYPE_UNSUPPORTED, json(r1).get("error"));

        var r2 = post(PREFIX + "/query", Map.of("type", "topology.snapshot", "topology", Map.of("scope", "member")), AGENT, null);
        assertEquals(400, r2.statusCode());
        assertEquals(NwpErrorCodes.NWP_TOPOLOGY_UNSUPPORTED_SCOPE, json(r2).get("error"));
    }

    @Test
    void noTopologyService() throws Exception {
        start(new AnchorNodeServer(baseOpts(), new AnchorNodeServer.Deps()));
        var r = post(PREFIX + "/query", Map.of("type", "topology.snapshot", "topology", Map.of("scope", "cluster")), AGENT, null);
        assertEquals(501, r.statusCode());
        assertEquals(NwpErrorCodes.NWP_NODE_UNAVAILABLE, json(r).get("error"));
    }

    @Test
    void capabilityGate() throws Exception {
        var o = baseOpts();
        o.requireTopologyCapability = true;
        var topo = new AnchorNodeServer.InMemoryTopologyService(NID, members(), 1, List.of());
        start(new AnchorNodeServer(o, new AnchorNodeServer.Deps(null, topo, null, null)));

        var denied = post(PREFIX + "/query", Map.of("type", "topology.snapshot", "topology", Map.of()), AGENT, null);
        assertEquals(403, denied.statusCode());
        assertEquals(NwpErrorCodes.NWP_TOPOLOGY_UNAUTHORIZED, json(denied).get("error"));
        var ok = post(PREFIX + "/query", Map.of("type", "topology.snapshot", "topology", Map.of()), AGENT,
            Map.of("X-NWP-Capabilities", "topology:read"));
        assertEquals(200, ok.statusCode());
    }

    // ── Invoke ────────────────────────────────────────────────────────────────────

    private static AnchorNodeServer.InvokeHandler okHandler() {
        return (actionId, params, ctx) -> Map.of("order_id", "o-123", "action", actionId, "agent", ctx.agentNid());
    }

    @Test
    void syncInvoke() throws Exception {
        start(new AnchorNodeServer(baseOpts(), new AnchorNodeServer.Deps(okHandler(), null, null, null)));
        var r = post(PREFIX + "/invoke", Map.of("action_id", "orders.create", "params", Map.of("x", 1)), AGENT, null);
        assertEquals(200, r.statusCode());
        assertEquals("application/nwp-capsule", r.headers().firstValue("content-type").orElse(""));
        var m = json(r);
        assertEquals(1, m.get("count"));
        var data = (Map<?, ?>) ((List<?>) m.get("data")).get(0);
        assertEquals("o-123", data.get("order_id"));
        assertEquals(AGENT, data.get("agent"));
    }

    @Test
    void unknownAction() throws Exception {
        start(new AnchorNodeServer(baseOpts(), new AnchorNodeServer.Deps(okHandler(), null, null, null)));
        var r = post(PREFIX + "/invoke", Map.of("action_id", "nope.verb"), AGENT, null);
        assertEquals(404, r.statusCode());
        assertEquals(NwpErrorCodes.NWP_ACTION_NOT_FOUND, json(r).get("error"));
    }

    @Test
    void cgnLimitPreCheck() throws Exception {
        start(new AnchorNodeServer(baseOpts(), new AnchorNodeServer.Deps(okHandler(), null, null, null)));
        var r = post(PREFIX + "/invoke", Map.of("action_id", "orders.create"), AGENT, Map.of("X-NWP-Budget", "5"));
        assertEquals(400, r.statusCode());
        assertEquals(NwpErrorCodes.NWP_CGN_LIMIT_EXCEEDED, json(r).get("error"));
    }

    @Test
    void noHandler() throws Exception {
        start(new AnchorNodeServer(baseOpts(), new AnchorNodeServer.Deps()));
        var r = post(PREFIX + "/invoke", Map.of("action_id", "orders.create"), AGENT, null);
        assertEquals(501, r.statusCode());
    }

    @Test
    void handlerErrorEnvelope() throws Exception {
        AnchorNodeServer.InvokeHandler bad = (a, p, c) -> {
            throw new AnchorNodeServer.ActionException(422, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, "bad", null);
        };
        start(new AnchorNodeServer(baseOpts(), new AnchorNodeServer.Deps(bad, null, null, null)));
        var r = post(PREFIX + "/invoke", Map.of("action_id", "orders.create"), AGENT, null);
        assertEquals(422, r.statusCode());
        assertEquals(NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, json(r).get("error"));
    }

    @Test
    void reputationBanBlocksInvoke() throws Exception {
        var ev = new DefaultReputationPolicyEvaluator();
        var entry = ReputationLogEntry.builder()
            .v(1).logId("l").seq(1).timestamp(Instant.now().toString())
            .subjectNid(AGENT).incident(IncidentType.IMPERSONATION_CLAIM).severity(Severity.CRITICAL).issuerNid("i").build();
        ev.primeCache(AGENT, List.of(entry), Duration.ofHours(1));
        var o = baseOpts();
        var policy = new ReputationPolicy(true, List.of(), "anonymous", 300, 3600, "allow",
            List.of(), List.of(), List.of(new ReputationPolicyRule("*", ">=critical")));
        o.reputationPolicy = policy;
        start(new AnchorNodeServer(o, new AnchorNodeServer.Deps(okHandler(), null, ev, null)));

        var r = post(PREFIX + "/invoke", Map.of("action_id", "orders.create"), AGENT, null);
        assertEquals(403, r.statusCode());
        assertEquals(NwpErrorCodes.NWP_REPUTATION_BANNED, json(r).get("error"));
    }
}
