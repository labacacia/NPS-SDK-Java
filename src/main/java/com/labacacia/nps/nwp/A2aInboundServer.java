// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Inbound A2A (JSON-RPC 2.0) server for a Bridge Node — NPS-CR-0010, NPS-2 §16.1.
 *
 * <p>Serves the AgentCard at {@code /.well-known/agent.json} and exactly one JSON-RPC
 * method, {@code tasks/send}.</p>
 */
public final class A2aInboundServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF  = JsonNodeFactory.instance;

    public static final String PROVIDER_ORGANIZATION = "LabAcacia / INNO LOTUS PTY LTD";
    public static final String PROVIDER_URL          = "https://github.com/labacacia/nps";

    /** Metadata keys searched, in order, when resolving which action a task names. */
    private static final List<String> SKILL_KEYS =
        List.of("action_id", "actionId", "skill_id", "skillId", "skill");

    private final BridgeInboundOptions options;

    public A2aInboundServer(BridgeInboundOptions options) {
        if (options == null) throw new IllegalArgumentException("options is required");
        this.options = options;
    }

    // ── AgentCard ────────────────────────────────────────────────────────────

    public ObjectNode buildAgentCard(String endpointUrl) {
        ObjectNode card = NF.objectNode();
        card.put("name", options.serverName);
        card.put("description", options.description != null ? options.description
            : "NPS Bridge Node exposing NWP nodes as A2A skills.");
        card.put("url", endpointUrl);

        ObjectNode provider = card.putObject("provider");
        provider.put("organization", PROVIDER_ORGANIZATION);
        provider.put("url", PROVIDER_URL);

        card.put("version", options.serverVersion);

        ObjectNode caps = card.putObject("capabilities");
        caps.put("streaming", false);
        caps.put("pushNotifications", false);
        caps.put("stateTransitionHistory", false);

        if (options.requireAuth) {
            // Advertised, so authentication is part of the protocol surface, not just host config.
            ObjectNode auth = card.putObject("authentication");
            auth.putArray("schemes").add("apikey");
            auth.put("credentials", NwpHttpHeaders.AGENT);
        } else {
            card.putNull("authentication");
        }

        ArrayNode skills = card.putArray("skills");
        for (NwpBackend backend : backends()) {
            NwpNodeDescriptor d = backend.getDescriptor();
            if (!d.isInvokable()) continue;
            for (NwpActionDescriptor a : backend.getActions()) {
                ObjectNode skill = skills.addObject();
                skill.put("id", McpToolName.encode(d.name(), a.actionId()));
                skill.put("name", a.description() != null ? a.description() : a.actionId());
                if (a.description() != null) skill.put("description", a.description());
                if (a.tags() != null) skill.set("tags", MAPPER.valueToTree(a.tags()));
                skill.putArray("inputModes").add("text").add("data");
                skill.putArray("outputModes").add("data");
            }
        }
        return card;
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    public BridgeJsonRpcResponse dispatch(BridgeJsonRpcRequest request) {
        if (request == null) {
            return BridgeJsonRpcResponse.fail(NF.nullNode(),
                BridgeErrorMap.INVALID_REQUEST, "JSON-RPC request is required.");
        }
        JsonNode id = request.id == null ? NF.nullNode() : request.id;

        // §16.1.2 MUST-5: the direction gate is checked first thing.
        if (!options.servesInbound(BridgeProtocols.A2A)) {
            ObjectNode data = NF.objectNode();
            data.put("error", BridgeErrorCodes.NWP_BRIDGE_DIRECTION_UNSUPPORTED);
            ObjectNode hint = data.putObject("hint");
            hint.set("bridge_inbound_protocols", MAPPER.valueToTree(options.declaredInbound()));
            hint.set("bridge_protocols",         MAPPER.valueToTree(options.declaredOutbound()));
            return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.METHOD_NOT_FOUND,
                "This Bridge Node does not declare \"a2a\" in bridge_inbound_protocols.", data);
        }

        // Exactly one method is served.
        if (!"tasks/send".equals(request.method)) {
            ObjectNode data = NF.objectNode();
            data.put("error", BridgeErrorCodes.NWP_BRIDGE_DIRECTION_UNSUPPORTED);
            return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.METHOD_NOT_FOUND,
                "A2A method '" + request.method + "' is not supported by this Bridge Node.", data);
        }

        JsonNode params = request.params;
        if (params == null || !params.isObject()) {
            return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.INVALID_PARAMS,
                "A2A tasks/send requires a params object.");
        }
        String taskId = text(params, "id");
        if (taskId == null || taskId.isBlank()) {
            return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.INVALID_PARAMS,
                "A2A tasks/send params.id is required.");
        }

        Resolution hit = resolveSkill(params);
        if (hit == null) {
            ObjectNode data = NF.objectNode();
            data.put("error", BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND);
            List<String> candidates = allQualifiedNames();
            if (candidates.size() > 1) data.set("candidates", MAPPER.valueToTree(candidates));
            return BridgeJsonRpcResponse.fail(id, BridgeErrorMap.INVALID_PARAMS,
                "A2A task metadata must identify an exposed NPS action when more than one is available.",
                data);
        }

        NwpResult result = hit.backend.invoke(hit.actionId, extractArguments(params), false);

        // §16.3: infrastructure-class failures become JSON-RPC errors, not task objects —
        // reporting them as a (failed) task hands the peer something it will retry.
        if (!result.ok() && BridgeErrorMap.mustBeProtocolError(result.npsStatus())) {
            return BridgeJsonRpcResponse.fail(id,
                BridgeErrorMap.toJsonRpc(result.npsStatus(), false),
                result.message() != null ? result.message() : result.npsStatus(),
                McpInboundServer.failureData(result));
        }
        return BridgeJsonRpcResponse.ok(id, toTask(taskId, params, result));
    }

    // ── Response projection ──────────────────────────────────────────────────

    private ObjectNode toTask(String taskId, JsonNode params, NwpResult result) {
        ObjectNode task = NF.objectNode();
        task.put("id", taskId);
        String sessionId = text(params, "sessionId");
        if (sessionId != null) task.put("sessionId", sessionId); else task.putNull("sessionId");

        ObjectNode status = task.putObject("status");
        status.put("state", result.ok() ? "completed" : "failed");
        status.put("timestamp", Instant.now().toString());
        if (result.ok()) {
            status.putNull("message");
        } else {
            ObjectNode message = status.putObject("message");
            message.put("role", "agent");
            ObjectNode part = message.putArray("parts").addObject();
            part.put("type", "text");
            // The NPS code is preserved verbatim in the failure detail.
            part.put("text", result.message() != null ? result.message()
                : result.nwpError() != null ? result.nwpError() : result.npsStatus());
        }

        ObjectNode artifact = task.putArray("artifacts").addObject();
        artifact.put("name", result.ok() ? "nps-result" : "nps-error");
        ObjectNode part = artifact.putArray("parts").addObject();
        part.put("type", "data");
        part.set("data", result.ok() ? result.payload() : McpInboundServer.failureData(result));
        artifact.put("index", 0);

        ArrayNode history = task.putArray("history");
        JsonNode requestMessage = params.get("message");
        if (requestMessage != null && !requestMessage.isNull()) history.add(requestMessage);

        return task;
    }

    // ── Skill resolution ─────────────────────────────────────────────────────

    private record Resolution(NwpBackend backend, String actionId) {}

    private Resolution resolveSkill(JsonNode params) {
        String named = findSkillName(params);
        if (named != null) {
            for (NwpBackend backend : backends()) {
                NwpNodeDescriptor d = backend.getDescriptor();
                if (!d.isInvokable()) continue;
                for (NwpActionDescriptor a : backend.getActions()) {
                    if (McpToolName.encode(d.name(), a.actionId()).equalsIgnoreCase(named)
                        || a.actionId().equalsIgnoreCase(named)) {
                        return new Resolution(backend, a.actionId());
                    }
                }
            }
            return null;
        }
        // No skill named: accept only if exactly one action is exposed in total.
        List<Resolution> all = new ArrayList<>();
        for (NwpBackend backend : backends()) {
            if (!backend.getDescriptor().isInvokable()) continue;
            for (NwpActionDescriptor a : backend.getActions()) {
                all.add(new Resolution(backend, a.actionId()));
            }
        }
        return all.size() == 1 ? all.get(0) : null;
    }

    /** {@code task.metadata} → {@code task.message.metadata} → per-part metadata then data. */
    private static String findSkillName(JsonNode params) {
        String v = lookupKeys(params.get("metadata"));
        if (v != null) return v;
        JsonNode message = params.get("message");
        if (message != null && message.isObject()) {
            v = lookupKeys(message.get("metadata"));
            if (v != null) return v;
            JsonNode parts = message.get("parts");
            if (parts != null && parts.isArray()) {
                for (JsonNode part : parts) {
                    v = lookupKeys(part.get("metadata"));
                    if (v != null) return v;
                    v = lookupKeys(part.get("data"));
                    if (v != null) return v;
                }
            }
        }
        return null;
    }

    private static String lookupKeys(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        for (String key : SKILL_KEYS) {
            JsonNode v = node.get(key);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    // ── Argument extraction ──────────────────────────────────────────────────

    private static JsonNode extractArguments(JsonNode params) {
        JsonNode v = paramsOrArguments(params.get("metadata"));
        if (v != null) return v;
        JsonNode message = params.get("message");
        if (message != null && message.isObject()) {
            v = paramsOrArguments(message.get("metadata"));
            if (v != null) return v;
            JsonNode parts = message.get("parts");
            if (parts != null && parts.isArray()) {
                for (JsonNode part : parts) {
                    v = paramsOrArguments(part.get("data"));
                    if (v != null) return v;
                }
                for (JsonNode part : parts) {
                    String type = text(part, "type");
                    if ("data".equals(type) && part.get("data") != null) return part.get("data");
                    if ("text".equals(type) && part.get("text") != null) {
                        ObjectNode wrapped = NF.objectNode();
                        wrapped.put("text", part.get("text").asText());
                        return wrapped;
                    }
                }
            }
        }
        return null;
    }

    private static JsonNode paramsOrArguments(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        if (node.get("params") != null && !node.get("params").isNull())    return node.get("params");
        if (node.get("arguments") != null && !node.get("arguments").isNull()) return node.get("arguments");
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<String> allQualifiedNames() {
        List<String> out = new ArrayList<>();
        for (NwpBackend backend : backends()) {
            NwpNodeDescriptor d = backend.getDescriptor();
            if (!d.isInvokable()) continue;
            for (NwpActionDescriptor a : backend.getActions()) {
                out.add(McpToolName.encode(d.name(), a.actionId()));
            }
        }
        return List.copyOf(out);
    }

    private List<NwpBackend> backends() {
        return options.backends == null ? List.of() : options.backends;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }
}
