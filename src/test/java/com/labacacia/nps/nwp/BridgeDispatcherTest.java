// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.ncp.CapsFrame;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Outbound Bridge dispatchers against an ephemeral HttpServer stub. */
class BridgeDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<HttpServer> servers = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
        servers.clear();
    }

    private String startEcho(String contentType, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, raw.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(raw);
            }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ── target parsing ────────────────────────────────────────────────────────

    @Test
    void parsesBridgeTargetFromNestedParam() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("protocol", "http");
        target.put("endpoint", "https://example.com/api");
        target.put("method", "GET");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bridge_target", target);
        ActionFrame frame = new ActionFrame("bridge.dispatch", params, null, null, null);

        BridgeTarget bt = BridgeTargetParser.fromActionFrame(frame);
        assertEquals("http", bt.protocol);
        assertEquals("https://example.com/api", bt.endpoint);
        assertEquals("GET", BridgeTargetParser.getString(bt, "method"));
    }

    @Test
    void targetMissingProtocolThrows() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bridge_target", Map.of("endpoint", "https://x.example.com"));
        ActionFrame frame = new ActionFrame("bridge.dispatch", params, null, null, null);
        BridgeDispatchException ex = assertThrows(BridgeDispatchException.class,
            () -> BridgeTargetParser.fromActionFrame(frame));
        assertEquals(BridgeErrorCodes.TARGET_INVALID, ex.errorCode());
    }

    // ── HTTP dispatcher ───────────────────────────────────────────────────────

    @Test
    void httpDispatcherMapsJsonResponse() throws Exception {
        String base = startEcho("application/json", "{\"ok\":true,\"n\":42}");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bridge_target", Map.of(
            "protocol", "http", "endpoint", base + "/x", "method", "GET", "reject_private", false));
        ActionFrame frame = new ActionFrame("bridge.dispatch", params, null, null, null);

        CapsFrame caps = new HttpBridgeDispatcher(client).dispatch(frame, BridgeTargetParser.fromActionFrame(frame));
        assertEquals(HttpBridgeDispatcher.RESPONSE_ANCHOR_REF, caps.anchorRef());
        assertEquals(1, caps.count());
        Map<String, Object> record = caps.data().get(0);
        assertEquals(200, record.get("status_code"));
        assertEquals(Boolean.TRUE, record.get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) record.get("body");
        assertEquals(Boolean.TRUE, respBody.get("ok"));
        assertEquals(42, respBody.get("n"));
    }

    @Test
    void httpDispatcherKeepsBodyTextForNonJson() throws Exception {
        String base = startEcho("text/plain", "hello world");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bridge_target", Map.of(
            "protocol", "http", "endpoint", base + "/x", "method", "GET", "reject_private", false));
        ActionFrame frame = new ActionFrame("bridge.dispatch", params, null, null, null);

        CapsFrame caps = new HttpBridgeDispatcher(client).dispatch(frame, BridgeTargetParser.fromActionFrame(frame));
        assertEquals("hello world", caps.data().get(0).get("body_text"));
    }

    // ── JSON-RPC / MCP dispatcher ─────────────────────────────────────────────

    @Test
    void mcpDispatcherPostsJsonRpcAndMapsResult() throws Exception {
        // Echo server that captures the request body and returns a JSON-RPC result.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] captured = new String[1];
        server.createContext("/", ex -> {
            captured[0] = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] raw = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"echo\":true}}"
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, raw.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(raw); }
        });
        server.start();
        servers.add(server);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bridge_target", Map.of(
            "protocol", "mcp", "endpoint", base + "/mcp", "reject_private", false,
            "rpc_method", "tools/call"));
        params.put("name", "do_thing");
        ActionFrame frame = new ActionFrame("bridge.dispatch", params, null, null, null);

        CapsFrame caps = new McpBridgeDispatcher(client).dispatch(frame, BridgeTargetParser.fromActionFrame(frame));
        assertEquals(McpBridgeDispatcher.RESPONSE_ANCHOR_REF, caps.anchorRef());
        Map<String, Object> record = caps.data().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) record.get("result");
        assertEquals(Boolean.TRUE, result.get("echo"));

        // Verify the outbound JSON-RPC envelope.
        Map<?, ?> sent = MAPPER.readValue(captured[0], Map.class);
        assertEquals("2.0", sent.get("jsonrpc"));
        assertEquals("tools/call", sent.get("method"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sentParams = (Map<String, Object>) sent.get("params");
        assertEquals("do_thing", sentParams.get("name"));
    }

    @Test
    void a2aDispatcherUsesDefaultMethod() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] captured = new String[1];
        server.createContext("/", ex -> {
            captured[0] = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] raw = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, raw.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(raw); }
        });
        server.start();
        servers.add(server);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bridge_target", Map.of(
            "protocol", "a2a", "endpoint", base + "/a2a", "reject_private", false));
        ActionFrame frame = new ActionFrame("bridge.dispatch", params, null, null, null);

        CapsFrame caps = new A2aBridgeDispatcher(client).dispatch(frame, BridgeTargetParser.fromActionFrame(frame));
        assertEquals(A2aBridgeDispatcher.RESPONSE_ANCHOR_REF, caps.anchorRef());
        Map<?, ?> sent = MAPPER.readValue(captured[0], Map.class);
        assertEquals("tasks/send", sent.get("method"));
    }

    // ── SSRF / endpoint validation ────────────────────────────────────────────

    @Test
    void endpointValidationRejectsPrivateHost() {
        BridgeTarget target = new BridgeTarget("http", "http://127.0.0.1:8080/x",
            Map.of("reject_private", true));
        BridgeDispatchException ex = assertThrows(BridgeDispatchException.class,
            () -> BridgeEndpointValidator.parseHttpEndpoint(target));
        assertEquals(BridgeErrorCodes.ENDPOINT_INVALID, ex.errorCode());
    }

    @Test
    void endpointValidationRejectsNonHttpScheme() {
        BridgeTarget target = new BridgeTarget("http", "ftp://example.com/x", null);
        BridgeDispatchException ex = assertThrows(BridgeDispatchException.class,
            () -> BridgeEndpointValidator.parseHttpEndpoint(target));
        assertEquals(BridgeErrorCodes.ENDPOINT_INVALID, ex.errorCode());
    }

    @Test
    void endpointValidationEnforcesAllowedPrefixes() {
        BridgeTarget target = new BridgeTarget("http", "https://evil.example.com/x",
            Map.of("allowed_prefixes", List.of("https://good.example.com/")));
        BridgeDispatchException ex = assertThrows(BridgeDispatchException.class,
            () -> BridgeEndpointValidator.parseHttpEndpoint(target));
        assertEquals(BridgeErrorCodes.ENDPOINT_INVALID, ex.errorCode());
    }

    // ── registry / protocol-unsupported ───────────────────────────────────────

    @Test
    void registryCreateDefaultRegistersAllBuiltIns() {
        BridgeDispatcherRegistry registry = BridgeDispatcherRegistry.createDefault(client);
        assertTrue(registry.protocols().containsAll(List.of("http", "grpc", "mcp", "a2a")));
    }

    @Test
    void bridgeNodeRejectsUnsupportedProtocol() {
        BridgeDispatcherRegistry registry = new BridgeDispatcherRegistry()
            .register(new HttpBridgeDispatcher(client));
        BridgeNode node = new BridgeNode(registry);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bridge_target", Map.of("protocol", "smtp", "endpoint", "https://x.example.com"));
        ActionFrame frame = new ActionFrame("bridge.dispatch", params, null, null, null);

        BridgeDispatchException ex = assertThrows(BridgeDispatchException.class, () -> node.dispatch(frame));
        assertEquals(BridgeErrorCodes.PROTOCOL_UNSUPPORTED, ex.errorCode());
    }
}
