// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ComplexNodeServerTest {

    private static final String PREFIX = "/graph";
    private static final String NID = "urn:nps:node:api.example.com:graph";
    private static final String AGENT = "urn:nps:agent:tester";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<HttpServer> servers = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() { servers.forEach(s -> s.stop(0)); servers.clear(); }

    /** Start a handler on an ephemeral port and return its base URL. */
    private String startAt(String contextPath, com.sun.net.httpserver.HttpHandler app) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(contextPath, app);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static MemoryNodeServer.Schema schema() {
        var s = new MemoryNodeServer.Schema();
        s.tableName = "orders";
        s.primaryKey = "id";
        s.fields = new ArrayList<>(List.of(
            new MemoryNodeServer.Field("id", "string", false),
            new MemoryNodeServer.Field("region", "string")));
        return s;
    }

    private static Map<String, Object> row(String id, String region) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("region", region);
        return m;
    }

    private static List<Map<String, Object>> localRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("root-1", "us"));
        return rows;
    }

    private static ComplexNodeServer.Options baseOpts() {
        var o = new ComplexNodeServer.Options();
        o.nodeId = NID;
        o.pathPrefix = PREFIX;
        o.schema = schema();
        o.graphMaxDepth = 2;
        return o;
    }

    private static MemoryNodeServer.Provider provider(List<Map<String, Object>> rows) {
        return new MemoryNodeServer.InMemoryProvider(rows);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> r) throws Exception {
        return MAPPER.readValue(r.body(), Map.class);
    }

    private HttpResponse<String> post(String base, String path, Object b, String agent, Map<String, String> headers) throws Exception {
        var rb = HttpRequest.newBuilder(URI.create(base + path))
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(b)));
        if (agent != null) rb.header("X-NWP-Agent", agent);
        if (headers != null) headers.forEach(rb::header);
        return client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ── local query (no expansion) ──────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void localQueryNoDepth() throws Exception {
        var opt = baseOpts();
        var base = startAt("/", new ComplexNodeServer(opt, provider(localRows()), (f, c) -> null));
        var resp = post(base, "/graph/query", Map.of(), AGENT, null);
        assertEquals(200, resp.statusCode());
        assertEquals("complex", resp.headers().firstValue("X-NWP-Node-Type").orElse(null));
        var b = body(resp);
        assertEquals(1, ((Number) b.get("count")).intValue());
        assertNull(b.get("graph"), "no graph without depth");
    }

    // ── graph expansion (depth > 0, real child memory node) ─────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void graphExpandsChildAtDepth() throws Exception {
        // Real child = Memory Node on its own ephemeral server.
        var childOpt = new MemoryNodeServer.Options();
        childOpt.nodeId = "urn:nps:node:child";
        childOpt.pathPrefix = "/child";
        childOpt.schema = schema();
        List<Map<String, Object>> childRows = new ArrayList<>(List.of(row("child-1", "eu")));
        var childBase = startAt("/", new MemoryNodeServer(childOpt, provider(childRows)));

        var opt = baseOpts();
        // allowHttp so the http://127.0.0.1 child is dereferenceable; disable SSRF for loopback.
        opt.allowHttpChildUrls = true;
        opt.rejectPrivateChildUrls = false;
        opt.graph.add(new ComplexNodeServer.GraphRef("orders", childBase + "/child"));

        var base = startAt("/", new ComplexNodeServer(opt, provider(localRows()), (f, c) -> null));
        var resp = post(base, "/graph/query", Map.of(), AGENT, Map.of("X-NWP-Depth", "1"));
        assertEquals(200, resp.statusCode());
        var b = body(resp);
        assertEquals(1, ((Number) b.get("count")).intValue());
        var graph = (List<Object>) b.get("graph");
        assertNotNull(graph, "graph must be present at depth 1");
        assertEquals(1, graph.size());
        var childEntry = (Map<String, Object>) graph.get(0);
        assertEquals("orders", childEntry.get("rel"));
        assertNull(childEntry.get("error"), "child fetch should succeed");
        var childCaps = (Map<String, Object>) childEntry.get("data");
        assertEquals(1, ((Number) childCaps.get("count")).intValue());
    }

    // ── cycle detection via X-NWP-Trace ─────────────────────────────────────────

    @Test
    void cycleDetectedWhenSelfInTrace() throws Exception {
        var opt = baseOpts();
        var base = startAt("/", new ComplexNodeServer(opt, provider(localRows()), (f, c) -> null));
        var resp = post(base, "/graph/query", Map.of(), AGENT,
            Map.of("X-NWP-Depth", "1", "X-NWP-Trace", "urn:nps:node:other," + NID));
        assertEquals(422, resp.statusCode());
        assertEquals("NWP-GRAPH-CYCLE", body(resp).get("error"));
    }

    // ── depth clamp ─────────────────────────────────────────────────────────────

    @Test
    void depthOverNodeMaxIs400() throws Exception {
        var opt = baseOpts();       // graphMaxDepth = 2
        var base = startAt("/", new ComplexNodeServer(opt, provider(localRows()), (f, c) -> null));
        var resp = post(base, "/graph/query", Map.of(), AGENT, Map.of("X-NWP-Depth", "3"));
        assertEquals(400, resp.statusCode());
        assertEquals("NWP-DEPTH-EXCEEDED", body(resp).get("error"));
    }

    @Test
    void depthNonNumericIs400() throws Exception {
        var opt = baseOpts();
        var base = startAt("/", new ComplexNodeServer(opt, provider(localRows()), (f, c) -> null));
        var resp = post(base, "/graph/query", Map.of(), AGENT, Map.of("X-NWP-Depth", "abc"));
        assertEquals(400, resp.statusCode());
        assertEquals("NWP-DEPTH-EXCEEDED", body(resp).get("error"));
    }

    @Test
    void graphMaxDepthOverAbsoluteCapRejectedAtConstruction() {
        var opt = baseOpts();
        opt.graphMaxDepth = 6;      // ABSOLUTE_MAX_DEPTH = 5
        assertThrows(IllegalStateException.class,
            () -> new ComplexNodeServer(opt, provider(localRows()), (f, c) -> null));
    }

    // ── child-URL SSRF (private/loopback rejected -> child error, query still 200) ─

    @Test
    @SuppressWarnings("unchecked")
    void childSsrfProducesChildError() throws Exception {
        var opt = baseOpts();
        // Private host rejected by default; child entry carries an error, query itself is 200.
        opt.graph.add(new ComplexNodeServer.GraphRef("bad", "https://10.0.0.5/child"));
        var base = startAt("/", new ComplexNodeServer(opt, provider(localRows()), (f, c) -> null));
        var resp = post(base, "/graph/query", Map.of(), AGENT, Map.of("X-NWP-Depth", "1"));
        assertEquals(200, resp.statusCode());
        var graph = (List<Object>) body(resp).get("graph");
        assertNotNull(graph);
        var entry = (Map<String, Object>) graph.get(0);
        var err = (Map<String, Object>) entry.get("error");
        assertNotNull(err, "SSRF-rejected child must surface an error object");
        assertEquals("NWP-AUTH-NID-SCOPE-VIOLATION", err.get("code"));
    }

    // ── invoke (sync action) ────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void invokeSyncAction() throws Exception {
        var opt = baseOpts();
        opt.actions.put("orders.summarize",
            new ActionNodeServer.ActionSpec().withResultAnchor("nps:orders:summary"));
        var base = startAt("/", new ComplexNodeServer(opt, provider(localRows()),
            (f, c) -> new ActionNodeServer.ActionExecutionResult(Map.of("total", 1), null, 2)));
        var resp = post(base, "/graph/invoke", Map.of("action_id", "orders.summarize"), AGENT, null);
        assertEquals(200, resp.statusCode());
        assertEquals("complex", resp.headers().firstValue("X-NWP-Node-Type").orElse(null));
        var b = body(resp);
        assertEquals("nps:orders:summary", b.get("anchor_ref"));
        assertEquals(1, ((Number) b.get("count")).intValue());
    }

    @Test
    void invokeAsyncRejected() throws Exception {
        var opt = baseOpts();
        opt.actions.put("orders.summarize", new ActionNodeServer.ActionSpec());
        var base = startAt("/", new ComplexNodeServer(opt, provider(localRows()),
            (f, c) -> new ActionNodeServer.ActionExecutionResult(null)));
        var resp = post(base, "/graph/invoke",
            Map.of("action_id", "orders.summarize", "async", true), AGENT, null);
        assertEquals(400, resp.statusCode());
        assertEquals("NWP-ACTION-PARAMS-INVALID", body(resp).get("error"));
    }
}
