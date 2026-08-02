// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Inbound MCP (JSON-RPC 2.0) server for a Bridge Node — NPS-CR-0010, NPS-2 §16.1.
 *
 * <p>A plain MCP client with no NPS knowledge talks to this; the Bridge translates every
 * call into NWP frames via the {@link NwpBackend} abstraction.</p>
 */
public final class McpInboundServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF  = JsonNodeFactory.instance;

    /**
     * The normative required method set (§16.1.2 MUST-3). An inbound MCP Bridge that omits
     * {@code resources/*} is not conformant; serving them over an <em>empty set</em> IS
     * conformant — the requirement is on the methods, not on a Memory Node existing.
     */
    public static final List<String> REQUIRED_METHODS = List.of(
        "initialize", "ping", "tools/list", "tools/call", "resources/list", "resources/read");

    private final BridgeInboundOptions options;

    public McpInboundServer(BridgeInboundOptions options) {
        if (options == null) throw new IllegalArgumentException("options is required");
        this.options = options;
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    public BridgeJsonRpcResponse dispatch(BridgeJsonRpcRequest request) {
        if (request == null) {
            return BridgeJsonRpcResponse.fail(NF.nullNode(),
                BridgeErrorMap.INVALID_REQUEST, "JSON-RPC request is required.");
        }
        JsonNode id = request.id == null ? NF.nullNode() : request.id;

        // §16.1.2 MUST-5: the direction gate is checked first thing.
        if (!options.servesInbound(BridgeProtocols.MCP)) {
            return directionUnsupported(id, BridgeProtocols.MCP);
        }

        try {
            return switch (request.method == null ? "" : request.method) {
                case "initialize"     -> BridgeJsonRpcResponse.ok(id, initializeResult());
                case "ping"           -> BridgeJsonRpcResponse.ok(id, NF.objectNode());
                case "tools/list"     -> BridgeJsonRpcResponse.ok(id, toolsList());
                case "tools/call"     -> toolsCall(id, request.params);
                case "resources/list" -> BridgeJsonRpcResponse.ok(id, resourcesList());
                case "resources/read" -> resourcesRead(id, request.params);
                default -> {
                    ObjectNode data = NF.objectNode();
                    data.put("error", BridgeErrorCodes.NWP_BRIDGE_DIRECTION_UNSUPPORTED);
                    yield BridgeJsonRpcResponse.fail(id, BridgeErrorMap.METHOD_NOT_FOUND,
                        "MCP method '" + request.method + "' is not supported by this Bridge Node.",
                        data);
                }
            };
        } catch (IllegalArgumentException e) {
            return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.INVALID_PARAMS, e.getMessage());
        }
    }

    private BridgeJsonRpcResponse directionUnsupported(JsonNode id, String protocol) {
        ObjectNode data = NF.objectNode();
        data.put("error", BridgeErrorCodes.NWP_BRIDGE_DIRECTION_UNSUPPORTED);
        // §16.1.2 MUST-5 SHOULD-clause: carry both declared arrays in `hint`.
        ObjectNode hint = data.putObject("hint");
        hint.set("bridge_inbound_protocols", MAPPER.valueToTree(options.declaredInbound()));
        hint.set("bridge_protocols",         MAPPER.valueToTree(options.declaredOutbound()));
        return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.METHOD_NOT_FOUND,
            "This Bridge Node does not declare \"" + protocol + "\" in bridge_inbound_protocols.",
            data);
    }

    // ── initialize ───────────────────────────────────────────────────────────

    private ObjectNode initializeResult() {
        ObjectNode result = NF.objectNode();
        ObjectNode server = result.putObject("serverInfo");
        server.put("name", options.serverName);
        server.put("version", options.serverVersion);
        // BOTH capabilities are always advertised, even with no Memory Node behind.
        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("tools");
        caps.putObject("resources");
        return result;
    }

    // ── tools/list ───────────────────────────────────────────────────────────

    private ObjectNode toolsList() {
        ObjectNode result = NF.objectNode();
        ArrayNode tools = result.putArray("tools");
        for (NwpBackend backend : backends()) {
            NwpNodeDescriptor d = backend.getDescriptor();
            if (!d.isInvokable()) continue;
            for (NwpActionDescriptor a : backend.getActions()) {
                ObjectNode tool = tools.addObject();
                tool.put("name", McpToolName.encode(d.name(), a.actionId()));
                if (a.description() != null) tool.put("description", a.description());
                tool.set("inputSchema", a.effectiveInputSchema());
            }
        }
        return result;
    }

    // ── tools/call ───────────────────────────────────────────────────────────

    private BridgeJsonRpcResponse toolsCall(JsonNode id, JsonNode params) {
        String name = params == null ? null : text(params, "name");
        if (name == null || name.isBlank()) {
            return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.INVALID_PARAMS,
                "MCP tools/call requires params.name.");
        }
        Resolution hit = resolveTool(name);
        if (hit == null) {
            ObjectNode data = NF.objectNode();
            data.put("error", BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND);
            data.put("tool", name);
            // TC-N2-BridgeIn-04 wants the ambiguous rejection to name both candidates.
            List<String> candidates = qualifiedCandidatesFor(name);
            if (!candidates.isEmpty()) data.set("candidates", MAPPER.valueToTree(candidates));
            return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.METHOD_NOT_FOUND,
                candidates.isEmpty()
                    ? "MCP tool '" + name + "' is not exposed by this Bridge Node."
                    : "MCP tool '" + name + "' is ambiguous across " + candidates.size()
                        + " exposed actions; call it by its qualified name.",
                data);
        }

        JsonNode arguments = params.get("arguments");
        NwpResult result = hit.backend.invoke(hit.actionId, arguments, false);

        if (!result.ok() && BridgeErrorMap.mustBeProtocolError(result.npsStatus())) {
            // §16.3: an infrastructure failure is a protocol error, never a successful
            // result carrying isError — otherwise a client mistakes a 403 for unhappy text.
            return BridgeJsonRpcResponse.fail(id,
                BridgeErrorMap.toJsonRpc(result.npsStatus(), false),
                result.message() != null ? result.message() : result.npsStatus(),
                failureData(result));
        }

        ObjectNode out = NF.objectNode();
        out.put("isError", !result.ok());
        ArrayNode content = out.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", result.ok() ? writeJson(result.payload()) : writeJson(failureData(result)));
        return BridgeJsonRpcResponse.ok(id, out);
    }

    // ── resources/list ───────────────────────────────────────────────────────

    private ObjectNode resourcesList() {
        ObjectNode result = NF.objectNode();
        ArrayNode resources = result.putArray("resources");
        for (NwpBackend backend : backends()) {
            NwpNodeDescriptor d = backend.getDescriptor();
            if (!d.isQueryable()) continue;
            ObjectNode r = resources.addObject();
            r.put("uri", "nwp://" + d.name() + "/");
            r.put("name", d.displayName() != null ? d.displayName() : d.name());
            r.put("description", d.description() != null ? d.description()
                : "NWP " + d.role().wire() + " Node '" + d.name() + "' — read to query.");
            r.put("mimeType", "application/json");
        }
        return result;   // an empty array is conformant
    }

    // ── resources/read ───────────────────────────────────────────────────────

    private BridgeJsonRpcResponse resourcesRead(JsonNode id, JsonNode params) {
        String uri = params == null ? null : text(params, "uri");
        if (uri == null || uri.isBlank()) {
            return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.INVALID_PARAMS,
                "MCP resources/read requires params.uri.");
        }
        String host;
        try {
            URI parsed = URI.create(uri);
            if (!parsed.isAbsolute() || !"nwp".equalsIgnoreCase(parsed.getScheme())) {
                throw new IllegalArgumentException("scheme");
            }
            host = parsed.getHost() != null ? parsed.getHost() : parsed.getAuthority();
            if (host == null || host.isBlank()) throw new IllegalArgumentException("host");
        } catch (IllegalArgumentException e) {
            return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.INVALID_PARAMS,
                "Resource URI '" + uri + "' must be of the form nwp://<node>/.");
        }

        NwpBackend backend = null;
        for (NwpBackend b : backends()) {
            NwpNodeDescriptor d = b.getDescriptor();
            if (d.isQueryable() && d.name().equalsIgnoreCase(host)) { backend = b; break; }
        }
        if (backend == null) {
            // An unknown resource URI is a bad PARAM (-32602), not a missing method.
            ObjectNode data = NF.objectNode();
            data.put("error", BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND);
            data.put("uri", uri);
            return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.INVALID_PARAMS,
                "Resource URI '" + uri + "' does not name a queryable NWP node fronted by this Bridge Node.",
                data);
        }

        ObjectNode query = NF.objectNode();
        query.put("limit", options.resourceReadLimit);
        NwpResult result = backend.query(query);

        if (!result.ok()) {
            return BridgeJsonRpcResponse.fail(id,
                BridgeErrorMap.toJsonRpc(result.npsStatus(), true),
                result.message() != null ? result.message() : result.npsStatus(),
                failureData(result));
        }

        ObjectNode out = NF.objectNode();
        ObjectNode entry = out.putArray("contents").addObject();
        entry.put("uri", uri);
        entry.put("mimeType", "application/json");
        entry.put("text", writeJson(result.payload()));
        return BridgeJsonRpcResponse.ok(id, out);
    }

    // ── §5.1 name resolution: canonical on output, forgiving on input ────────

    private record Resolution(NwpBackend backend, String actionId) {}

    /**
     * Resolve a tool name. A qualified {@code node__action} match wins immediately; a
     * bare action id resolves only when exactly one candidate exists across all invokable
     * backends. Two nodes exposing the same action id must be disambiguated by the
     * caller, not guessed at here.
     */
    private Resolution resolveTool(String toolName) {
        List<Resolution> unqualified = new ArrayList<>();
        for (NwpBackend backend : backends()) {
            NwpNodeDescriptor d = backend.getDescriptor();
            if (!d.isInvokable()) continue;
            for (NwpActionDescriptor a : backend.getActions()) {
                if (McpToolName.encode(d.name(), a.actionId()).equalsIgnoreCase(toolName)) {
                    return new Resolution(backend, a.actionId());
                }
                if (a.actionId().equalsIgnoreCase(toolName)
                    || McpToolName.encodeActionSegment(a.actionId()).equalsIgnoreCase(toolName)) {
                    unqualified.add(new Resolution(backend, a.actionId()));
                }
            }
        }
        return unqualified.size() == 1 ? unqualified.get(0) : null;
    }

    /** Qualified names an ambiguous bare id could have meant. */
    private List<String> qualifiedCandidatesFor(String toolName) {
        List<String> out = new ArrayList<>();
        for (NwpBackend backend : backends()) {
            NwpNodeDescriptor d = backend.getDescriptor();
            if (!d.isInvokable()) continue;
            for (NwpActionDescriptor a : backend.getActions()) {
                if (a.actionId().equalsIgnoreCase(toolName)
                    || McpToolName.encodeActionSegment(a.actionId()).equalsIgnoreCase(toolName)) {
                    out.add(McpToolName.encode(d.name(), a.actionId()));
                }
            }
        }
        return out.size() > 1 ? List.copyOf(out) : List.of();
    }

    // ── stdio transport (part of the inbound profile, not an extra) ──────────

    /**
     * Line-delimited JSON-RPC: one request per line in, one line of JSON-RPC out per
     * request. Blank lines are skipped; a null line ends the loop; the output is flushed
     * after every response.
     */
    public void runStdio(InputStream input, OutputStream output) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        while (true) {
            String line = reader.readLine();
            if (line == null) return;                       // EOF ends the loop
            if (line.isBlank()) continue;                   // blank lines are skipped

            BridgeJsonRpcResponse response;
            try {
                BridgeJsonRpcRequest request = MAPPER.readValue(line, BridgeJsonRpcRequest.class);
                response = request == null
                    ? BridgeJsonRpcResponse.fail(NF.nullNode(),
                        BridgeErrorMap.INVALID_REQUEST, "JSON-RPC request is required.")
                    : dispatch(request);
            } catch (Exception e) {
                response = BridgeJsonRpcResponse.fail(NF.nullNode(),
                    BridgeErrorMap.PARSE_ERROR, e.getMessage());
            }
            output.write(MAPPER.writeValueAsBytes(response));
            output.write('\n');
            output.flush();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<NwpBackend> backends() {
        return options.backends == null ? List.of() : options.backends;
    }

    static ObjectNode failureData(NwpResult result) {
        ObjectNode data = NF.objectNode();
        data.put("status",  result.npsStatus());
        data.put("error",   result.nwpError());
        data.put("message", result.message());
        return data;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static String writeJson(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node == null ? NF.nullNode() : node);
        } catch (Exception e) {
            return "null";
        }
    }
}
