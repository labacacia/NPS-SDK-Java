// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.ncp.CapsFrame;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NPS-CR-0010 §7 — the inbound Bridge hosting layer on
 * {@link com.sun.net.httpserver.HttpHandler}: auth gate, bounded body, dispatch timeout,
 * method gating, and the AgentCard route.
 */
class BridgeServerHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AGENT = "urn:nps:agent:ex.com:tester";

    private HttpServer server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    private String start(BridgeServerOptions options) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new BridgeServerHandler(options));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        return base;
    }

    private static BridgeServerOptions options() {
        var o = new BridgeServerOptions();
        o.serverName = "bridge-inbound-test";
        o.nodeId     = "bridge-inbound-test";
        o.nodeRole   = NwpNodeRole.ACTION;
        o.actions.put("orders.lookup", new NwpActionDescriptor("orders.lookup", "Look up an order"));
        o.dispatch   = frame -> new CapsFrame("sha256:orders", 1, List.of(Map.of("ok", true)));
        o.verifier   = (nid, exchange) -> AGENT.equals(nid);
        o.backends   = BridgeServerBackends.create(o, null);
        return o;
    }

    private HttpResponse<String> post(String path, String body, boolean withAgent) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (withAgent) b.header(NwpHttpHeaders.AGENT, AGENT);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    void mcpPostDispatchesThroughTheHostingLayer() throws Exception {
        start(options());
        var response = post("/mcp", PING, true);

        assertEquals(200, response.statusCode());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals(1, body.get("id").asInt());
        assertNotNull(body.get("result"));
    }

    @Test
    void agentCardIsServedOnTheWellKnownPath() throws Exception {
        start(options());
        var response = client.send(HttpRequest.newBuilder(
                URI.create(base + "/.well-known/agent.json"))
                .header(NwpHttpHeaders.AGENT, AGENT).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JsonNode card = MAPPER.readTree(response.body());
        assertEquals("bridge-inbound-test", card.get("name").asText());
        assertEquals("bridge-inbound-test__orders_lookup",
            card.get("skills").get(0).get("id").asText());
    }

    // ── Auth gate ────────────────────────────────────────────────────────────

    @Test
    void missingAgentHeaderIs401WithAJsonRpcErrorBody() throws Exception {
        start(options());
        var response = post("/mcp", PING, false);

        assertEquals(401, response.statusCode());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals(BridgeErrorMap.INVALID_REQUEST, body.get("error").get("code").asInt());
        assertTrue(body.get("id").isNull());
    }

    @Test
    void syntacticallyInvalidNidIs401() throws Exception {
        start(options());
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .header(NwpHttpHeaders.AGENT, "not-a-nid")
                .POST(HttpRequest.BodyPublishers.ofString(PING)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void missingVerifierDeniesEveryRequest() throws Exception {
        var options = options();
        options.verifier = null;   // fail closed
        start(options);
        assertEquals(401, post("/mcp", PING, true).statusCode());
    }

    @Test
    void verifierRejectionIs401() throws Exception {
        var options = options();
        options.verifier = (nid, exchange) -> false;
        start(options);
        assertEquals(401, post("/mcp", PING, true).statusCode());
    }

    @Test
    void authCanBeDisabled() throws Exception {
        var options = options();
        options.requireAuth = false;
        options.verifier = null;
        start(options);
        assertEquals(200, post("/mcp", PING, false).statusCode());
    }

    // ── Bounded body ─────────────────────────────────────────────────────────

    @Test
    void bodyOverTheLimitIs413() throws Exception {
        var options = options();
        options.maxRequestBodyBytes = 64;
        start(options);

        String big = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\""
            + "x".repeat(4096) + "\"}}";
        var response = post("/mcp", big, true);

        assertEquals(413, response.statusCode());
        assertEquals(BridgeErrorMap.INVALID_REQUEST,
            MAPPER.readTree(response.body()).get("error").get("code").asInt());
    }

    @Test
    void aZeroLimitDisablesTheBodyCap() throws Exception {
        var options = options();
        options.maxRequestBodyBytes = 0;
        start(options);
        assertEquals(200, post("/mcp", PING, true).statusCode());
    }

    // ── Dispatch timeout ─────────────────────────────────────────────────────

    @Test
    void dispatchTimeoutIs504WithUpstreamError() throws Exception {
        var options = options();
        options.dispatchTimeoutMs = 50;
        options.dispatch = frame -> {
            try { Thread.sleep(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new CapsFrame("r", 0, List.of());
        };
        options.backends = BridgeServerBackends.create(options, null);
        start(options);

        var response = post("/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"orders.lookup\"}}", true);

        assertEquals(504, response.statusCode());
        assertEquals(BridgeErrorMap.UPSTREAM_ERROR,
            MAPPER.readTree(response.body()).get("error").get("code").asInt());
    }

    // ── Method gating / routing ──────────────────────────────────────────────

    @Test
    void nonPostOnMcpIs405AndNonGetOnTheAgentCardIs405() throws Exception {
        start(options());

        var get = client.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .header(NwpHttpHeaders.AGENT, AGENT).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode());

        var post = client.send(HttpRequest.newBuilder(URI.create(base + "/.well-known/agent.json"))
                .header(NwpHttpHeaders.AGENT, AGENT)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
    }

    @Test
    void unknownPathIs404() throws Exception {
        start(options());
        assertEquals(404, post("/nope", PING, true).statusCode());
    }

    @Test
    void malformedBodyIsAParseError() throws Exception {
        start(options());
        var response = post("/mcp", "{not json", true);
        assertEquals(400, response.statusCode());
        assertEquals(BridgeErrorMap.PARSE_ERROR,
            MAPPER.readTree(response.body()).get("error").get("code").asInt());
    }

    @Test
    void sseAliasRoutesToTheSameMcpServer() throws Exception {
        start(options());
        assertEquals(200, post("/mcp/sse", PING, true).statusCode());
    }
}
