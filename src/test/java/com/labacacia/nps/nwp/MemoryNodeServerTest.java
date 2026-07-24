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

class MemoryNodeServerTest {

    private static final String PREFIX = "/mem";
    private static final String NID = "urn:nps:node:api.example.com:mem";
    private static final String AGENT = "urn:nps:agent:tester";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    private String start(MemoryNodeServer app) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", app);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        return base;
    }

    private static MemoryNodeServer.Schema schema() {
        var s = new MemoryNodeServer.Schema();
        s.tableName = "orders";
        s.primaryKey = "id";
        s.fields = new ArrayList<>(List.of(
            new MemoryNodeServer.Field("id", "string", false),
            new MemoryNodeServer.Field("region", "string"),
            new MemoryNodeServer.Field("amount", "number")));
        return s;
    }

    private static List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("o-1", "us", 100));
        rows.add(row("o-2", "eu", 50));
        rows.add(row("o-3", "us", 250));
        return rows;
    }

    private static Map<String, Object> row(String id, String region, int amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("region", region);
        m.put("amount", amount);
        return m;
    }

    private static MemoryNodeServer.Options baseOpts() {
        var o = new MemoryNodeServer.Options();
        o.nodeId = NID;
        o.pathPrefix = PREFIX;
        o.schema = schema();
        return o;
    }

    private MemoryNodeServer server() {
        return new MemoryNodeServer(baseOpts(), new MemoryNodeServer.InMemoryProvider(rows()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> r) throws Exception {
        return MAPPER.readValue(r.body(), Map.class);
    }

    private HttpResponse<String> post(String path, Object b, String agent, Map<String, String> headers) throws Exception {
        var rb = HttpRequest.newBuilder(URI.create(base + path))
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(b)));
        if (agent != null) rb.header("X-NWP-Agent", agent);
        if (headers != null) headers.forEach(rb::header);
        return client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ── query ─────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void queryReturnsAllRows() throws Exception {
        start(server());
        var resp = post("/mem/query", Map.of(), AGENT, null);
        assertEquals(200, resp.statusCode());
        assertEquals("memory", resp.headers().firstValue("X-NWP-Node-Type").orElse(null));
        assertNotNull(resp.headers().firstValue("X-NWP-Schema").orElse(null));
        assertNotNull(resp.headers().firstValue("X-NWP-Tokens").orElse(null));
        var b = body(resp);
        assertEquals(3, ((Number) b.get("count")).intValue());
        assertTrue(((String) b.get("anchor_ref")).startsWith("sha256:"));
        assertEquals(3, ((List<Object>) b.get("data")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryEqualityFilter() throws Exception {
        start(server());
        var resp = post("/mem/query", Map.of("filter", Map.of("region", "us")), AGENT, null);
        assertEquals(200, resp.statusCode());
        var b = body(resp);
        assertEquals(2, ((Number) b.get("count")).intValue());
        for (Object o : (List<Object>) b.get("data"))
            assertEquals("us", ((Map<String, Object>) o).get("region"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryComparisonOperator() throws Exception {
        start(server());
        var resp = post("/mem/query",
            Map.of("filter", Map.of("amount", Map.of("$gte", 100))), AGENT, null);
        assertEquals(200, resp.statusCode());
        var b = body(resp);
        assertEquals(2, ((Number) b.get("count")).intValue());
    }

    @Test
    void queryUnknownFieldIs400() throws Exception {
        start(server());
        var resp = post("/mem/query",
            Map.of("filter", Map.of("nope", "x")), AGENT, null);
        assertEquals(400, resp.statusCode());
        assertEquals("NWP-QUERY-FIELD-UNKNOWN", body(resp).get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryLimitAndCursor() throws Exception {
        start(server());
        var resp = post("/mem/query", Map.of("limit", 2), AGENT, null);
        assertEquals(200, resp.statusCode());
        var b = body(resp);
        assertEquals(2, ((Number) b.get("count")).intValue());
        assertEquals("2", b.get("next_cursor"));
    }

    @Test
    void missingAgentIs401WhenAuthRequired() throws Exception {
        var o = baseOpts();
        o.requireAuth = true;
        start(new MemoryNodeServer(o, new MemoryNodeServer.InMemoryProvider(rows())));
        var resp = post("/mem/query", Map.of(), null, null);
        assertEquals(401, resp.statusCode());
        assertEquals("NWP-AUTH-NID-SCOPE-VIOLATION", body(resp).get("error"));
    }

    @Test
    void getOnQueryIs405() throws Exception {
        start(server());
        var req = HttpRequest.newBuilder(URI.create(base + "/mem/query")).GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
    }

    @Test
    void schemaAndManifestServed() throws Exception {
        start(server());
        var sReq = HttpRequest.newBuilder(URI.create(base + "/mem/.schema")).GET().build();
        var sResp = client.send(sReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sResp.statusCode());
        assertNotNull(sResp.headers().firstValue("X-NWP-Schema").orElse(null));

        var nReq = HttpRequest.newBuilder(URI.create(base + "/mem/.nwm")).GET().build();
        var nResp = client.send(nReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, nResp.statusCode());
        assertEquals("memory", nResp.headers().firstValue("X-NWP-Node-Type").orElse(null));
        assertEquals("memory", body(nResp).get("node_type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void budgetTrimsRows() throws Exception {
        start(server());
        // A tiny budget forces trimming; token_est is CGN = ceil(bytes/4) per row.
        var resp = post("/mem/query", Map.of(), AGENT, Map.of("X-NWP-Budget", "1"));
        assertEquals(200, resp.statusCode());
        var b = body(resp);
        // With a 1-CGN budget the first row already exceeds it, so nothing fits.
        assertEquals(0, ((Number) b.get("count")).intValue());
        assertEquals(0, ((Number) b.get("token_est")).intValue());
    }
}
