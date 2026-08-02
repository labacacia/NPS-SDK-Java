// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labacacia.nps.core.NpsStatusCodes;
import com.labacacia.nps.ncp.CapsFrame;
import com.labacacia.nps.ncp.ErrorFrame;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NPS-CR-0010 inbound Bridge servers — the six TC-N2-BridgeIn conformance cases plus the
 * reference implementation's additional MCP / A2A scenarios.
 */
class BridgeInboundTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF  = JsonNodeFactory.instance;
    private static final String NODE = "bridge-inbound-test";

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static BridgeInboundOptions options(List<NwpBackend> backends, String... inbound) {
        BridgeInboundOptions o = new BridgeInboundOptions();
        o.serverName = NODE;
        o.backends = List.copyOf(backends);
        o.inboundProtocols = new java.util.LinkedHashSet<>(List.of(inbound));
        o.outboundProtocols = new java.util.LinkedHashSet<>(List.of("http"));
        return o;
    }

    /** An Action Node exposing {@code orders.lookup}. */
    private static NwpBackend actionNode() { return actionNode(NODE); }

    private static NwpBackend actionNode(String name) {
        return new InProcessNwpBackend(
            new NwpNodeDescriptor(name, NwpNodeRole.ACTION, null, null),
            List.of(new NwpActionDescriptor("orders.lookup", "Look up an order")),
            frame -> {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("action", frame.actionId());
                row.put("params", frame.params());   // may be null when no arguments were sent
                return new CapsFrame("sha256:orders", 1, List.of(row));
            },
            null);
    }

    /** A Complex node — queryable AND invokable. */
    private static NwpBackend complexNode() {
        return new InProcessNwpBackend(
            new NwpNodeDescriptor(NODE, NwpNodeRole.COMPLEX, null, null),
            List.of(new NwpActionDescriptor("orders.lookup", "Look up an order")),
            frame -> new CapsFrame("sha256:orders", 1, List.of(Map.of("ok", true))),
            query -> new CapsFrame("sha256:rows", 2,
                List.of(Map.of("id", 1), Map.of("id", 2))));
    }

    private static BridgeJsonRpcRequest rpc(String method, ObjectNode params) {
        return new BridgeJsonRpcRequest(NF.numberNode(1), method, params);
    }

    // ── BridgeIn-01: MCP serves the full required method set ─────────────────

    @Test
    void mcpInboundServesTheFullRequiredMethodSet() {
        assertEquals(List.of("initialize", "ping", "tools/list", "tools/call",
            "resources/list", "resources/read"), McpInboundServer.REQUIRED_METHODS);

        var mcp = new McpInboundServer(options(List.of(complexNode()), "mcp"));

        assertNull(mcp.dispatch(rpc("initialize", null)).error);
        assertNull(mcp.dispatch(rpc("ping", null)).error);
        assertNull(mcp.dispatch(rpc("tools/list", null)).error);
        assertNull(mcp.dispatch(rpc("resources/list", null)).error);

        ObjectNode call = NF.objectNode();
        call.put("name", NODE + "__orders_lookup");
        call.set("arguments", NF.objectNode().put("id", 7));
        assertNull(mcp.dispatch(rpc("tools/call", call)).error);

        ObjectNode read = NF.objectNode();
        read.put("uri", "nwp://" + NODE + "/");
        assertNull(mcp.dispatch(rpc("resources/read", read)).error);
    }

    @Test
    void mcpInitializeAlwaysAdvertisesBothCapabilities() {
        // Even with no Memory Node behind — the requirement is on the methods.
        var mcp = new McpInboundServer(options(List.of(actionNode()), "mcp"));
        JsonNode result = mcp.dispatch(rpc("initialize", null)).result;

        assertEquals(NODE, result.get("serverInfo").get("name").asText());
        assertTrue(result.get("capabilities").has("tools"));
        assertTrue(result.get("capabilities").has("resources"));
    }

    @Test
    void mcpServesResourcesMethodsEvenWithNoMemoryNode() {
        var mcp = new McpInboundServer(options(List.of(actionNode()), "mcp"));

        var listed = mcp.dispatch(rpc("resources/list", null));
        assertNull(listed.error, "resources/list must not be 'method not found'");
        assertEquals(0, listed.result.get("resources").size());   // an empty set is conformant
    }

    @Test
    void mcpServesResourcesOverAQueryableNode() {
        var mcp = new McpInboundServer(options(List.of(complexNode()), "mcp"));

        JsonNode resources = mcp.dispatch(rpc("resources/list", null)).result.get("resources");
        assertEquals(1, resources.size());
        assertEquals("nwp://" + NODE + "/", resources.get(0).get("uri").asText());
        assertEquals("application/json", resources.get(0).get("mimeType").asText());

        ObjectNode read = NF.objectNode();
        read.put("uri", "nwp://" + NODE + "/");
        JsonNode contents = mcp.dispatch(rpc("resources/read", read)).result.get("contents");
        assertEquals("nwp://" + NODE + "/", contents.get(0).get("uri").asText());
        assertTrue(contents.get(0).get("text").asText().contains("\"count\":2"));
    }

    @Test
    void mcpListsQualifiedToolNamesAndDispatchesToolCall() {
        var mcp = new McpInboundServer(options(List.of(actionNode()), "mcp"));

        JsonNode tools = mcp.dispatch(rpc("tools/list", null)).result.get("tools");
        assertEquals(1, tools.size());
        assertEquals("bridge-inbound-test__orders_lookup", tools.get(0).get("name").asText());
        assertEquals("Look up an order", tools.get(0).get("description").asText());
        // No declared schema ⇒ the open object schema is advertised.
        assertEquals("object", tools.get(0).get("inputSchema").get("type").asText());
        assertTrue(tools.get(0).get("inputSchema").get("additionalProperties").asBoolean());

        ObjectNode params = NF.objectNode();
        params.put("name", "bridge-inbound-test__orders_lookup");
        params.set("arguments", NF.objectNode().put("id", 7));
        JsonNode result = mcp.dispatch(rpc("tools/call", params)).result;

        assertFalse(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("orders.lookup"));
    }

    @Test
    void mcpStillResolvesUnqualifiedToolNames() {
        var mcp = new McpInboundServer(options(List.of(actionNode()), "mcp"));

        // The bare, sanitised action segment resolves to the action id `orders.lookup`.
        ObjectNode params = NF.objectNode();
        params.put("name", "orders_lookup");
        JsonNode result = mcp.dispatch(rpc("tools/call", params)).result;
        assertFalse(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("orders.lookup"));

        // …and so does the raw action id.
        params.put("name", "orders.lookup");
        assertNull(mcp.dispatch(rpc("tools/call", params)).error);
    }

    // ── BridgeIn-04: bare id resolves, ambiguity is rejected ─────────────────

    @Test
    void bareActionIdResolvesWhileAmbiguityIsRejectedNamingBothCandidates() {
        NwpBackend a = new InProcessNwpBackend(
            new NwpNodeDescriptor("node-a", NwpNodeRole.ACTION),
            List.of(new NwpActionDescriptor("orders_lookup"), new NwpActionDescriptor("status")),
            f -> new CapsFrame("r", 1, List.of(Map.of("from", "a"))), null);
        NwpBackend b = new InProcessNwpBackend(
            new NwpNodeDescriptor("node-b", NwpNodeRole.ACTION),
            List.of(new NwpActionDescriptor("status")),
            f -> new CapsFrame("r", 1, List.of(Map.of("from", "b"))), null);

        var mcp = new McpInboundServer(options(List.of(a, b), "mcp"));

        // Unique bare id resolves.
        ObjectNode unique = NF.objectNode();
        unique.put("name", "orders_lookup");
        assertNull(mcp.dispatch(rpc("tools/call", unique)).error);

        // Ambiguous bare id is a deterministic error naming both qualified candidates.
        ObjectNode ambiguous = NF.objectNode();
        ambiguous.put("name", "status");
        var failed = mcp.dispatch(rpc("tools/call", ambiguous));
        assertNotNull(failed.error);
        assertEquals(BridgeErrorMap.METHOD_NOT_FOUND, failed.error.code);
        assertEquals(BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND,
            failed.error.data.get("error").asText());
        JsonNode candidates = failed.error.data.get("candidates");
        assertNotNull(candidates, "the ambiguous rejection must name both candidates");
        assertEquals(2, candidates.size());
        assertEquals("node-a__status", candidates.get(0).asText());
        assertEquals("node-b__status", candidates.get(1).asText());
    }

    @Test
    void unknownToolIsMethodNotFoundAndNeverTheRetired32002() {
        var mcp = new McpInboundServer(options(List.of(actionNode()), "mcp"));
        ObjectNode params = NF.objectNode();
        params.put("name", "nope");

        var failed = mcp.dispatch(rpc("tools/call", params));
        assertEquals(BridgeErrorMap.METHOD_NOT_FOUND, failed.error.code);
        assertNotEquals(-32002, failed.error.code);
        assertTrue(failed.error.message.contains("is not exposed by this Bridge Node"));
        assertEquals("nope", failed.error.data.get("tool").asText());
    }

    @Test
    void missingToolNameIsInvalidParams() {
        var mcp = new McpInboundServer(options(List.of(actionNode()), "mcp"));
        var failed = mcp.dispatch(rpc("tools/call", NF.objectNode()));
        assertEquals(BridgeErrorMap.INVALID_PARAMS, failed.error.code);
        assertEquals("MCP tools/call requires params.name.", failed.error.message);
    }

    @Test
    void unknownResourceHostIsInvalidParamsNotMethodNotFound() {
        var mcp = new McpInboundServer(options(List.of(complexNode()), "mcp"));
        ObjectNode read = NF.objectNode();
        read.put("uri", "nwp://nope/");

        var failed = mcp.dispatch(rpc("resources/read", read));
        assertEquals(BridgeErrorMap.INVALID_PARAMS, failed.error.code);
        assertEquals(BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND,
            failed.error.data.get("error").asText());
        assertEquals("nwp://nope/", failed.error.data.get("uri").asText());
    }

    @Test
    void nonNwpResourceUriIsInvalidParams() {
        var mcp = new McpInboundServer(options(List.of(complexNode()), "mcp"));
        ObjectNode read = NF.objectNode();
        read.put("uri", "https://example.com/x");

        var failed = mcp.dispatch(rpc("resources/read", read));
        assertEquals(BridgeErrorMap.INVALID_PARAMS, failed.error.code);
        assertTrue(failed.error.message.contains("must be of the form nwp://<node>/"));
    }

    @Test
    void unsupportedMcpMethodIsMethodNotFound() {
        var mcp = new McpInboundServer(options(List.of(actionNode()), "mcp"));
        var failed = mcp.dispatch(rpc("prompts/list", null));
        assertEquals(BridgeErrorMap.METHOD_NOT_FOUND, failed.error.code);
        assertEquals("MCP method 'prompts/list' is not supported by this Bridge Node.",
            failed.error.message);
        assertEquals(BridgeErrorCodes.NWP_BRIDGE_DIRECTION_UNSUPPORTED,
            failed.error.data.get("error").asText());
    }

    // ── BridgeIn-05: error mapping ───────────────────────────────────────────

    @Test
    void authFailureIsAProtocolErrorNotAnIsErrorResult() {
        NwpBackend denying = new InProcessNwpBackend(
            new NwpNodeDescriptor(NODE, NwpNodeRole.ACTION),
            List.of(new NwpActionDescriptor("orders.lookup")),
            f -> new ErrorFrame(NpsStatusCodes.NPS_AUTH_FORBIDDEN,
                NwpErrorCodes.NWP_AUTH_NID_SCOPE_VIOLATION, "scope violation", null),
            null);
        var mcp = new McpInboundServer(options(List.of(denying), "mcp"));

        ObjectNode params = NF.objectNode();
        params.put("name", NODE + "__orders_lookup");
        var response = mcp.dispatch(rpc("tools/call", params));

        assertNull(response.result, "an auth failure must NOT be a successful result");
        assertEquals(-32003, response.error.code);
        assertEquals(NpsStatusCodes.NPS_AUTH_FORBIDDEN, response.error.data.get("status").asText());
    }

    @Test
    void domainFailureStaysAnIsErrorResult() {
        NwpBackend failing = new InProcessNwpBackend(
            new NwpNodeDescriptor(NODE, NwpNodeRole.ACTION),
            List.of(new NwpActionDescriptor("orders.lookup")),
            f -> new ErrorFrame(NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE,
                NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, "bad params", null),
            null);
        var mcp = new McpInboundServer(options(List.of(failing), "mcp"));

        ObjectNode params = NF.objectNode();
        params.put("name", NODE + "__orders_lookup");
        var response = mcp.dispatch(rpc("tools/call", params));

        assertNull(response.error, "a tool-domain failure is what MCP's isError flag is for");
        assertTrue(response.result.get("isError").asBoolean());
        assertTrue(response.result.get("content").get(0).get("text").asText()
            .contains("NWP-ACTION-PARAMS-INVALID"));
    }

    @Test
    void missingDispatcherFailsLoudlyWithARegisteredCode() {
        NwpBackend noDispatcher = new InProcessNwpBackend(
            new NwpNodeDescriptor(NODE, NwpNodeRole.ACTION),
            List.of(new NwpActionDescriptor("orders.lookup")),
            null, null);
        var mcp = new McpInboundServer(options(List.of(noDispatcher), "mcp"));

        // The tool still appears, so the node does not look like it exposes nothing…
        assertEquals(1, mcp.dispatch(rpc("tools/list", null)).result.get("tools").size());

        ObjectNode params = NF.objectNode();
        params.put("name", NODE + "__orders_lookup");
        var response = mcp.dispatch(rpc("tools/call", params));

        assertEquals(BridgeErrorMap.INTERNAL_ERROR, response.error.code);
        String data = response.error.data.toString();
        assertTrue(data.contains(BridgeErrorCodes.NWP_BRIDGE_SERVER_DISPATCHER_MISSING), data);
        assertFalse(data.contains("NPS-SERVER-NOT-IMPLEMENTED"), data);
    }

    @Test
    void aThrowingDispatcherBecomesDispatchFailed() {
        NwpBackend boom = new InProcessNwpBackend(
            new NwpNodeDescriptor(NODE, NwpNodeRole.ACTION),
            List.of(new NwpActionDescriptor("orders.lookup")),
            f -> { throw new IllegalStateException("kaboom"); }, null);
        var mcp = new McpInboundServer(options(List.of(boom), "mcp"));

        ObjectNode params = NF.objectNode();
        params.put("name", NODE + "__orders_lookup");
        var response = mcp.dispatch(rpc("tools/call", params));

        assertEquals(BridgeErrorMap.INTERNAL_ERROR, response.error.code);
        assertEquals(BridgeErrorCodes.NWP_BRIDGE_SERVER_DISPATCH_FAILED,
            response.error.data.get("error").asText());
    }

    @Test
    void queryOnANonQueryableNodeIsToolNotFound() {
        NwpBackend action = actionNode();
        NwpResult r = action.query(NF.objectNode());
        assertFalse(r.ok());
        assertEquals(NpsStatusCodes.NPS_SERVER_UNSUPPORTED, r.npsStatus());
        assertEquals(BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND, r.nwpError());
    }

    // ── BridgeIn-06: undeclared direction is refused ─────────────────────────

    @Test
    void a2aRequestAgainstAnMcpOnlyBridgeIsRefusedWithBothDeclaredArraysInTheHint() {
        var options = options(List.of(actionNode()), "mcp");
        var a2a = new A2aInboundServer(options);

        ObjectNode params = NF.objectNode();
        params.put("id", "task-1");
        var failed = a2a.dispatch(rpc("tasks/send", params));

        assertEquals(BridgeErrorMap.METHOD_NOT_FOUND, failed.error.code);
        assertEquals(BridgeErrorCodes.NWP_BRIDGE_DIRECTION_UNSUPPORTED,
            failed.error.data.get("error").asText());
        JsonNode hint = failed.error.data.get("hint");
        assertNotNull(hint);
        assertEquals("[\"mcp\"]",  hint.get("bridge_inbound_protocols").toString());
        assertEquals("[\"http\"]", hint.get("bridge_protocols").toString());
    }

    @Test
    void mcpRequestAgainstAnA2aOnlyBridgeIsRefused() {
        var mcp = new McpInboundServer(options(List.of(actionNode()), "a2a"));
        var failed = mcp.dispatch(rpc("initialize", null));

        assertEquals(BridgeErrorMap.METHOD_NOT_FOUND, failed.error.code);
        assertEquals("This Bridge Node does not declare \"mcp\" in bridge_inbound_protocols.",
            failed.error.message);
    }

    @Test
    void directionGatingIsCaseInsensitiveAndGrpcIsNotInTheDefaultSet() {
        var defaults = new BridgeInboundOptions();
        assertTrue(defaults.servesInbound("MCP"));
        assertTrue(defaults.servesInbound("a2a"));
        assertFalse(defaults.servesInbound("grpc"));
        assertFalse(defaults.servesInbound(null));
    }

    // ── BridgeIn-03: A2A round trip ──────────────────────────────────────────

    @Test
    void a2aAgentCardListsFrontedActionsAsQualifiedSkills() {
        var a2a = new A2aInboundServer(options(List.of(actionNode()), "a2a"));
        ObjectNode card = a2a.buildAgentCard("https://bridge.example.com/a2a");

        assertEquals(NODE, card.get("name").asText());
        assertEquals("https://bridge.example.com/a2a", card.get("url").asText());
        assertEquals(A2aInboundServer.PROVIDER_ORGANIZATION,
            card.get("provider").get("organization").asText());
        assertEquals(A2aInboundServer.PROVIDER_URL, card.get("provider").get("url").asText());
        assertFalse(card.get("capabilities").get("streaming").asBoolean());
        assertFalse(card.get("capabilities").get("pushNotifications").asBoolean());
        assertFalse(card.get("capabilities").get("stateTransitionHistory").asBoolean());
        // requireAuth defaults true, so authentication is part of the protocol surface.
        assertEquals("apikey", card.get("authentication").get("schemes").get(0).asText());
        assertEquals("X-NWP-Agent", card.get("authentication").get("credentials").asText());

        JsonNode skill = card.get("skills").get(0);
        assertEquals("bridge-inbound-test__orders_lookup", skill.get("id").asText());
        assertEquals("Look up an order", skill.get("name").asText());
        assertEquals("[\"text\",\"data\"]", skill.get("inputModes").toString());
        assertEquals("[\"data\"]", skill.get("outputModes").toString());
    }

    @Test
    void a2aAgentCardNullsAuthenticationWhenAuthIsOff() {
        var options = options(List.of(actionNode()), "a2a");
        options.requireAuth = false;
        assertTrue(new A2aInboundServer(options)
            .buildAgentCard("https://x/a2a").get("authentication").isNull());
    }

    @Test
    void a2aTasksSendDispatchesTheActionAndReturnsAnArtifact() {
        var a2a = new A2aInboundServer(options(List.of(actionNode()), "a2a"));

        ObjectNode params = NF.objectNode();
        params.put("id", "task-1");
        params.put("sessionId", "sess-1");
        params.set("metadata", NF.objectNode()
            .put("action_id", "bridge-inbound-test__orders_lookup"));
        ObjectNode message = params.putObject("message");
        message.put("role", "user");
        ObjectNode part = message.putArray("parts").addObject();
        part.put("type", "data");
        part.set("data", NF.objectNode().put("id", 7));

        JsonNode task = a2a.dispatch(rpc("tasks/send", params)).result;

        assertEquals("task-1", task.get("id").asText());
        assertEquals("sess-1", task.get("sessionId").asText());
        assertEquals("completed", task.get("status").get("state").asText());
        assertTrue(task.get("status").get("message").isNull());
        JsonNode artifact = task.get("artifacts").get(0);
        assertEquals("nps-result", artifact.get("name").asText());
        assertEquals(0, artifact.get("index").asInt());
        assertEquals("data", artifact.get("parts").get(0).get("type").asText());
        assertEquals("sha256:orders",
            artifact.get("parts").get(0).get("data").get("anchor_ref").asText());
        assertEquals(1, task.get("history").size());
    }

    @Test
    void a2aResolvesTheSoleActionWhenNoSkillIsNamed() {
        var a2a = new A2aInboundServer(options(List.of(actionNode()), "a2a"));
        ObjectNode params = NF.objectNode();
        params.put("id", "task-1");
        assertNull(a2a.dispatch(rpc("tasks/send", params)).error);
    }

    @Test
    void a2aRejectsAnUnnamedSkillWhenMoreThanOneActionExists() {
        NwpBackend two = new InProcessNwpBackend(
            new NwpNodeDescriptor(NODE, NwpNodeRole.ACTION),
            List.of(new NwpActionDescriptor("a"), new NwpActionDescriptor("b")),
            f -> new CapsFrame("r", 0, List.of()), null);
        var a2a = new A2aInboundServer(options(List.of(two), "a2a"));

        ObjectNode params = NF.objectNode();
        params.put("id", "task-1");
        var failed = a2a.dispatch(rpc("tasks/send", params));

        assertEquals(BridgeErrorMap.INVALID_PARAMS, failed.error.code);
        assertEquals(BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND,
            failed.error.data.get("error").asText());
        assertEquals(2, failed.error.data.get("candidates").size());
    }

    @Test
    void a2aRequiresATaskId() {
        var a2a = new A2aInboundServer(options(List.of(actionNode()), "a2a"));
        var failed = a2a.dispatch(rpc("tasks/send", NF.objectNode()));
        assertEquals(BridgeErrorMap.INVALID_PARAMS, failed.error.code);
        assertEquals("A2A tasks/send params.id is required.", failed.error.message);
    }

    @Test
    void a2aServesOnlyTasksSend() {
        var a2a = new A2aInboundServer(options(List.of(actionNode()), "a2a"));
        var failed = a2a.dispatch(rpc("tasks/get", NF.objectNode()));
        assertEquals(BridgeErrorMap.METHOD_NOT_FOUND, failed.error.code);
        assertEquals("A2A method 'tasks/get' is not supported by this Bridge Node.",
            failed.error.message);
    }

    @Test
    void a2aMustNotDowngradeAnInfrastructureFailureToATask() {
        NwpBackend denying = new InProcessNwpBackend(
            new NwpNodeDescriptor(NODE, NwpNodeRole.ACTION),
            List.of(new NwpActionDescriptor("orders.lookup")),
            f -> new ErrorFrame(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
                NwpErrorCodes.NWP_AUTH_NID_EXPIRED, "expired", null),
            null);
        var a2a = new A2aInboundServer(options(List.of(denying), "a2a"));

        ObjectNode params = NF.objectNode();
        params.put("id", "task-1");
        var response = a2a.dispatch(rpc("tasks/send", params));

        assertNull(response.result, "an A2A peer retries failed tasks — this must be a JSON-RPC error");
        assertEquals(-32001, response.error.code);
    }

    @Test
    void a2aDomainFailureTerminatesTheTaskAsFailedWithTheCodePreserved() {
        NwpBackend failing = new InProcessNwpBackend(
            new NwpNodeDescriptor(NODE, NwpNodeRole.ACTION),
            List.of(new NwpActionDescriptor("orders.lookup")),
            f -> new ErrorFrame(NpsStatusCodes.NPS_CLIENT_NOT_FOUND,
                NwpErrorCodes.NWP_ACTION_NOT_FOUND, "no such order", null),
            null);
        var a2a = new A2aInboundServer(options(List.of(failing), "a2a"));

        ObjectNode params = NF.objectNode();
        params.put("id", "task-1");
        JsonNode task = a2a.dispatch(rpc("tasks/send", params)).result;

        assertEquals("failed", task.get("status").get("state").asText());
        assertEquals("agent", task.get("status").get("message").get("role").asText());
        assertEquals("no such order",
            task.get("status").get("message").get("parts").get(0).get("text").asText());
        assertEquals("nps-error", task.get("artifacts").get(0).get("name").asText());
        assertEquals(NwpErrorCodes.NWP_ACTION_NOT_FOUND,
            task.get("artifacts").get(0).get("parts").get(0).get("data").get("error").asText());
    }

    // ── stdio transport ──────────────────────────────────────────────────────

    @Test
    void mcpStdioHandlesLineDelimitedJsonRpc() throws Exception {
        var mcp = new McpInboundServer(options(List.of(actionNode()), "mcp"));

        String input = String.join("\n",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
            "",                                        // blank lines are skipped
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
            "not json at all") + "\n";

        var out = new ByteArrayOutputStream();
        mcp.runStdio(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);

        String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");
        assertEquals(3, lines.length);
        assertEquals(1, MAPPER.readTree(lines[0]).get("id").asInt());
        assertEquals(1, MAPPER.readTree(lines[1]).get("result").get("tools").size());
        JsonNode parseFailure = MAPPER.readTree(lines[2]);
        assertEquals(BridgeErrorMap.PARSE_ERROR, parseFailure.get("error").get("code").asInt());
        assertTrue(parseFailure.get("id").isNull());
    }

    // ── Tool-name encoding ───────────────────────────────────────────────────

    @Test
    void toolNameEncodingIsLossyAndDeliberatelyHasNoDecode() {
        assertEquals("bridge-inbound-test__orders_lookup",
            McpToolName.encode("bridge-inbound-test", "orders.lookup"));
        assertEquals("orders_lookup", McpToolName.encodeActionSegment("orders.lookup"));
        assertEquals("a_b", McpToolName.sanitize("a b"));
        assertEquals("a.b-c_d", McpToolName.sanitize("  a.b-c_d  "));
        assertEquals("x", McpToolName.sanitize("__x__"));
        assertEquals("node", McpToolName.sanitize("___"));
        assertEquals("node", McpToolName.sanitize(null));
        // `.` and `_` collapse onto the same output — hence resolution by re-encoding.
        assertEquals(McpToolName.encodeActionSegment("a.b"), McpToolName.encodeActionSegment("a_b"));
    }

    // ── gRPC inbound service logic ───────────────────────────────────────────

    @Test
    void grpcInboundRefusesUntilGrpcIsDeclared() {
        var grpc = new GrpcInboundService(options(List.of(actionNode()), "mcp", "a2a"));
        var ex = assertThrows(GrpcInboundException.class,
            () -> grpc.invoke(GrpcInboundService.UpstreamContext.EMPTY, "orders.lookup", null));

        assertEquals(GrpcStatusCode.UNIMPLEMENTED, ex.status);
        assertTrue(ex.getMessage().contains(BridgeErrorCodes.NWP_BRIDGE_DIRECTION_UNSUPPORTED));
        assertTrue(ex.getMessage().startsWith(NpsStatusCodes.NPS_SERVER_UNSUPPORTED));
    }

    @Test
    void grpcInboundRoundTripsAnInvoke() {
        var grpc = new GrpcInboundService(options(List.of(actionNode()), "grpc"));

        var response = grpc.invoke(GrpcInboundService.UpstreamContext.EMPTY, "orders.lookup",
            "{\"id\":7}".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.httpStatus());
        assertEquals("", response.taskId());
        assertTrue(new String(response.bodyJson(), StandardCharsets.UTF_8).contains("orders.lookup"));
    }

    @Test
    void grpcInboundResolvesTheSoleBackendWhenUpstreamIsEmpty() {
        var grpc = new GrpcInboundService(options(List.of(actionNode()), "grpc"));

        assertEquals("action",
            grpc.getManifest(GrpcInboundService.UpstreamContext.EMPTY).nodeType());
        assertTrue(new String(grpc.listActions(GrpcInboundService.UpstreamContext.EMPTY)
            .actionsJson(), StandardCharsets.UTF_8).contains("orders.lookup"));
    }

    @Test
    void grpcInboundNotFoundForAnUnknownUpstream() {
        var grpc = new GrpcInboundService(options(List.of(actionNode("a"), actionNode("b")), "grpc"));

        var ex = assertThrows(GrpcInboundException.class,
            () -> grpc.getManifest(new GrpcInboundService.UpstreamContext("nope", null, null, null)));
        assertEquals(GrpcStatusCode.NOT_FOUND, ex.status);
        assertTrue(ex.getMessage().contains(BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND));

        // Ambiguous: more than one backend and no upstream named.
        assertThrows(GrpcInboundException.class,
            () -> grpc.getManifest(GrpcInboundService.UpstreamContext.EMPTY));
    }

    @Test
    void grpcInboundRequiresAnActionId() {
        var grpc = new GrpcInboundService(options(List.of(actionNode()), "grpc"));
        var ex = assertThrows(GrpcInboundException.class,
            () -> grpc.invoke(GrpcInboundService.UpstreamContext.EMPTY, "", null));
        assertEquals(GrpcStatusCode.INVALID_ARGUMENT, ex.status);
        assertEquals("action_id is required", ex.getMessage());
    }

    @Test
    void grpcInboundSurfacesTheExactNpsFaultNotOnlyTheCoarseClass() {
        NwpBackend denying = new InProcessNwpBackend(
            new NwpNodeDescriptor(NODE, NwpNodeRole.ACTION),
            List.of(new NwpActionDescriptor("orders.lookup")),
            f -> new ErrorFrame(NpsStatusCodes.NPS_AUTH_FORBIDDEN,
                NwpErrorCodes.NWP_AUTH_NID_SCOPE_VIOLATION, "scope violation", null),
            null);
        var grpc = new GrpcInboundService(options(List.of(denying), "grpc"));

        var ex = assertThrows(GrpcInboundException.class,
            () -> grpc.invoke(GrpcInboundService.UpstreamContext.EMPTY, "orders.lookup", null));

        // 401 and 403 are NOT collapsed onto PERMISSION_DENIED together.
        assertEquals(GrpcStatusCode.PERMISSION_DENIED, ex.status);
        assertEquals("NPS-AUTH-FORBIDDEN NWP-AUTH-NID-SCOPE-VIOLATION: scope violation",
            ex.getMessage());
    }

    @Test
    void grpcQueryTreatsAnEmptyBodyAsAnEmptyObject() {
        var grpc = new GrpcInboundService(options(List.of(complexNode()), "grpc"));
        var response = grpc.query(GrpcInboundService.UpstreamContext.EMPTY, new byte[0]);
        assertEquals(200, response.httpStatus());
        assertTrue(new String(response.bodyJson(), StandardCharsets.UTF_8).contains("\"count\":2"));
    }

    // ── Backend materialisation ──────────────────────────────────────────────

    @Test
    void backendsAreMaterialisedForDeclaredActionsEvenWithoutADispatcher() {
        var options = new BridgeServerOptions();
        options.nodeId = NODE;
        options.actions.put("orders.lookup", new NwpActionDescriptor("orders.lookup"));

        List<NwpBackend> backends = BridgeServerBackends.create(options, null);
        assertEquals(1, backends.size());
        assertEquals(NODE, backends.get(0).getDescriptor().name());

        // …and nothing at all when there is nothing to expose.
        assertTrue(BridgeServerBackends.create(new BridgeServerOptions(), null).isEmpty());
    }

    @Test
    void upstreamsWithoutAnHttpClientAreRejected() {
        var options = new BridgeServerOptions();
        options.upstreams.add(new NwpUpstream("remote", "https://example.com/nwp"));
        assertThrows(IllegalStateException.class, () -> BridgeServerBackends.create(options, null));
    }

    // ── Hosting-layer NID syntax gate ────────────────────────────────────────

    @Test
    void agentNidSyntacticValidation() {
        assertTrue(BridgeServerHandler.isSyntacticallyValidAgentNid("urn:nps:agent:ex.com:a-1"));
        assertTrue(BridgeServerHandler.isSyntacticallyValidAgentNid("urn:nps:agent:ex-1.com:a/b@c~d"));
        assertFalse(BridgeServerHandler.isSyntacticallyValidAgentNid("urn:nps:node:ex.com:a"));
        assertFalse(BridgeServerHandler.isSyntacticallyValidAgentNid("urn:nps:agent:ex.com"));
        assertFalse(BridgeServerHandler.isSyntacticallyValidAgentNid("urn:nps:agent::a"));
        assertFalse(BridgeServerHandler.isSyntacticallyValidAgentNid("urn:nps:agent:ex com:a"));
        assertFalse(BridgeServerHandler.isSyntacticallyValidAgentNid(
            "urn:nps:agent:ex.com:" + "x".repeat(600)));
        assertFalse(BridgeServerHandler.isSyntacticallyValidAgentNid(null));
    }

    @Test
    void hostingDefaultsAreFailClosed() {
        var options = new BridgeServerOptions();
        assertTrue(options.requireAuth);
        assertNull(options.verifier, "no verifier configured ⇒ every request is denied");
        assertEquals(1024L * 1024L, options.maxRequestBodyBytes);
        assertEquals(30_000, options.dispatchTimeoutMs);
        assertEquals(100, options.resourceReadLimit);
        assertEquals("/mcp", options.mcpPath);
        assertEquals("/mcp/sse", options.mcpSsePath);
        assertEquals("/a2a", options.a2aPath);
        assertEquals("/.well-known/agent.json", options.a2aAgentCardPath);
        assertEquals(NwpNodeRole.ACTION, options.nodeRole);
    }

    // ── Backend abstraction basics ───────────────────────────────────────────

    @Test
    void nodeRoleParsingAndDerivedFlags() {
        assertEquals(NwpNodeRole.MEMORY,  NwpNodeRole.parseRole("Memory"));
        assertEquals(NwpNodeRole.COMPLEX, NwpNodeRole.parseRole("complex"));
        assertEquals(NwpNodeRole.UNKNOWN, NwpNodeRole.parseRole("nonsense"));
        assertEquals(NwpNodeRole.UNKNOWN, NwpNodeRole.parseRole(null));
        assertEquals("", NwpNodeRole.UNKNOWN.wire());

        assertTrue(new NwpNodeDescriptor("n", NwpNodeRole.MEMORY).isQueryable());
        assertFalse(new NwpNodeDescriptor("n", NwpNodeRole.MEMORY).isInvokable());
        assertTrue(new NwpNodeDescriptor("n", NwpNodeRole.ACTION).isInvokable());
        assertTrue(new NwpNodeDescriptor("n", NwpNodeRole.COMPLEX).isQueryable());
        assertTrue(new NwpNodeDescriptor("n", NwpNodeRole.COMPLEX).isInvokable());
        assertFalse(new NwpNodeDescriptor("n", NwpNodeRole.ANCHOR).isQueryable());
        assertThrows(IllegalArgumentException.class, () -> new NwpNodeDescriptor(" ", NwpNodeRole.ACTION));
    }

    @Test
    void inProcessBackendManifestAndActionGating() {
        NwpBackend memory = new InProcessNwpBackend(
            new NwpNodeDescriptor("mem", NwpNodeRole.MEMORY, "Memory", "rows"),
            List.of(new NwpActionDescriptor("never.exposed")),
            null, q -> new CapsFrame("r", 0, List.of()));

        JsonNode manifest = memory.getManifest().payload();
        assertEquals("memory", manifest.get("node_type").asText());
        assertEquals("Memory", manifest.get("display_name").asText());
        assertEquals("rows",   manifest.get("description").asText());
        // Not invokable ⇒ no actions regardless of what was declared.
        assertTrue(memory.getActions().isEmpty());
    }
}
