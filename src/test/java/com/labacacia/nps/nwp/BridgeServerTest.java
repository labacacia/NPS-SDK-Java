// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.ncp.CapsFrame;
import com.labacacia.nps.core.NpsFrame;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Inbound MCP/A2A Bridge server bridges on an ephemeral port. */
class BridgeServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<HttpServer> servers = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
        servers.clear();
    }

    private String start(BridgeServerOptions options) throws Exception {
        BridgeServerActionInvoker invoker = new BridgeServerActionInvoker.Default(options);
        BridgeServerMiddleware mw = new BridgeServerMiddleware(
            new McpServerBridge(options, invoker),
            new A2aServerBridge(options, invoker),
            options);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", mw);
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static BridgeServerOptions echoOptions() {
        BridgeServerOptions options = new BridgeServerOptions();
        options.requireAuth = false;
        options.addAction("echo.action", "Echoes params back");
        options.dispatch = frame -> {
            List<Map<String, Object>> data = new ArrayList<>();
            data.add(frame.params() == null ? Map.of() : frame.params());
            return new CapsFrame("nps://test/echo/v1", 1, data);
        };
        return options;
    }

    // ── MCP tools/list + tools/call → local action ────────────────────────────

    @Test
    void mcpToolsListReturnsExposedActions() throws Exception {
        String base = start(echoOptions());
        HttpResponse<String> resp = post(base + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertEquals(200, resp.statusCode());
        JsonNode result = MAPPER.readTree(resp.body()).get("result");
        assertEquals("echo_action", result.get("tools").get(0).get("name").asText());
    }

    @Test
    void mcpToolsCallDispatchesLocalAction() throws Exception {
        String base = start(echoOptions());
        HttpResponse<String> resp = post(base + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo_action\",\"arguments\":{\"hello\":\"world\"}}}");
        assertEquals(200, resp.statusCode());
        JsonNode result = MAPPER.readTree(resp.body()).get("result");
        assertFalse(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        // The tool result text carries the serialized CapsFrame with the echoed params.
        assertTrue(text.contains("\"hello\":\"world\""), text);
    }

    @Test
    void mcpToolCallUnknownToolReturnsToolNotFound() throws Exception {
        String base = start(echoOptions());
        HttpResponse<String> resp = post(base + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"nope\"}}");
        assertEquals(200, resp.statusCode());
        JsonNode error = MAPPER.readTree(resp.body()).get("error");
        assertEquals(BridgeJsonRpc.ErrorCodes.TOOL_NOT_FOUND, error.get("code").asInt());
        assertEquals(BridgeErrorCodes.SERVER_TOOL_NOT_FOUND, error.get("data").get("error").asText());
    }

    @Test
    void mcpUnsupportedMethodReturnsMethodNotFound() throws Exception {
        String base = start(echoOptions());
        HttpResponse<String> resp = post(base + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}");
        JsonNode error = MAPPER.readTree(resp.body()).get("error");
        assertEquals(BridgeJsonRpc.ErrorCodes.METHOD_NOT_FOUND, error.get("code").asInt());
    }

    // ── A2A tasks/send → local action ─────────────────────────────────────────

    @Test
    void a2aSendTaskDispatchesLocalAction() throws Exception {
        String base = start(echoOptions());
        HttpResponse<String> resp = post(base + "/a2a",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tasks/send\",\"params\":{"
                + "\"id\":\"t-1\",\"message\":{\"role\":\"user\",\"parts\":["
                + "{\"type\":\"data\",\"data\":{\"params\":{\"foo\":\"bar\"}}}]}}}");
        assertEquals(200, resp.statusCode());
        JsonNode result = MAPPER.readTree(resp.body()).get("result");
        assertEquals("t-1", result.get("id").asText());
        assertEquals("completed", result.get("status").get("state").asText());
        String artifact = result.get("artifacts").get(0).get("parts").get(0).get("data").toString();
        assertTrue(artifact.contains("\"foo\":\"bar\""), artifact);
    }

    @Test
    void a2aAgentCardExposesSkills() throws Exception {
        BridgeServerOptions options = echoOptions();
        String base = start(options);
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(base + "/.well-known/agent.json")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        JsonNode card = MAPPER.readTree(resp.body());
        assertEquals("nps-bridge-server", card.get("name").asText());
        assertEquals("echo.action", card.get("skills").get(0).get("id").asText());
    }

    // ── auth ──────────────────────────────────────────────────────────────────

    @Test
    void requireAuthRejectsMissingAgentHeader() throws Exception {
        BridgeServerOptions options = echoOptions();
        options.requireAuth = true;
        String base = start(options);
        HttpResponse<String> resp = post(base + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}");
        assertEquals(401, resp.statusCode());
    }

    @Test
    void requireAuthAcceptsWellFormedAgentNid() throws Exception {
        BridgeServerOptions options = echoOptions();
        options.requireAuth = true;
        String base = start(options);
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}"))
                .header("Content-Type", "application/json")
                .header(NwpHttpHeaders.AGENT, "urn:nps:agent:example.com:alice")
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    // ── dispatcher-missing default ────────────────────────────────────────────

    @Test
    void missingDispatcherSurfacesDispatcherMissingCode() throws Exception {
        BridgeServerOptions options = new BridgeServerOptions();
        options.requireAuth = false;
        options.addAction("echo.action", "no dispatcher wired");
        options.dispatch = null;
        String base = start(options);
        HttpResponse<String> resp = post(base + "/mcp",
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo_action\"}}");
        JsonNode result = MAPPER.readTree(resp.body()).get("result");
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains(BridgeErrorCodes.SERVER_DISPATCHER_MISSING), text);
    }
}
