// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.ncp.ErrorFrame;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inbound A2A adapter that exposes local NPS actions as A2A skills. */
public final class A2aServerBridge {

    private final BridgeServerOptions options;
    private final BridgeServerActionInvoker invoker;

    /** Create an A2A server bridge. */
    public A2aServerBridge(BridgeServerOptions options, BridgeServerActionInvoker invoker) {
        this.options = options;
        this.invoker = invoker;
    }

    /** Build the A2A AgentCard for the hosted Bridge server. */
    public A2aServerTypes.AgentCard buildAgentCard(String endpointUrl) {
        A2aServerTypes.AgentCard card = new A2aServerTypes.AgentCard();
        card.name = options.serverName;
        card.description = options.description;
        card.url = endpointUrl;

        A2aServerTypes.AgentProvider provider = new A2aServerTypes.AgentProvider();
        provider.organization = "LabAcacia / INNO LOTUS PTY LTD";
        provider.url = "https://github.com/labacacia/nps";
        card.provider = provider;

        card.version = options.serverVersion;

        A2aServerTypes.AgentCapabilities caps = new A2aServerTypes.AgentCapabilities();
        caps.streaming = false;
        caps.pushNotifications = false;
        caps.stateTransitionHistory = false;
        card.capabilities = caps;

        if (options.requireAuth) {
            A2aServerTypes.AgentAuthentication auth = new A2aServerTypes.AgentAuthentication();
            auth.schemes = List.of("apikey");
            auth.credentials = "X-NWP-Agent";
            card.authentication = auth;
        }

        List<A2aServerTypes.AgentSkill> skills = new ArrayList<>();
        for (BridgeServerOptions.Action action : options.actions) {
            A2aServerTypes.AgentSkill skill = new A2aServerTypes.AgentSkill();
            skill.id = action.actionId;
            skill.name = action.effectiveDisplayName();
            skill.description = action.description;
            skill.tags = action.tags;
            skill.inputModes = List.of("text", "data");
            skill.outputModes = List.of("data");
            skills.add(skill);
        }
        card.skills = skills;
        return card;
    }

    /** Dispatch one A2A JSON-RPC request. */
    public BridgeJsonRpc.Response dispatch(BridgeJsonRpc.Request request) {
        if (request == null) {
            throw new NullPointerException("request");
        }

        return switch (request.method == null ? "" : request.method) {
            case "tasks/send" -> sendTask(request);
            default -> BridgeJsonRpc.error(
                request,
                BridgeJsonRpc.ErrorCodes.METHOD_NOT_FOUND,
                "A2A method '" + request.method + "' is not supported by NWP Bridge server.");
        };
    }

    private BridgeJsonRpc.Response sendTask(BridgeJsonRpc.Request request) {
        if (request.params == null || request.params.isNull()) {
            return BridgeJsonRpc.error(
                request, BridgeJsonRpc.ErrorCodes.INVALID_PARAMS, "A2A tasks/send requires params.");
        }

        A2aServerTypes.SendTaskParams task;
        try {
            task = BridgeJsonRpc.JSON.treeToValue(request.params, A2aServerTypes.SendTaskParams.class);
        } catch (Exception ex) {
            return BridgeJsonRpc.error(request, BridgeJsonRpc.ErrorCodes.INVALID_PARAMS, ex.getMessage());
        }

        if (task == null || task.id == null || task.id.isBlank()) {
            return BridgeJsonRpc.error(
                request, BridgeJsonRpc.ErrorCodes.INVALID_PARAMS, "A2A tasks/send params.id is required.");
        }

        BridgeServerOptions.Action action = resolveAction(task);
        if (action == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("error", BridgeErrorCodes.SERVER_TOOL_NOT_FOUND);
            return BridgeJsonRpc.error(
                request, BridgeJsonRpc.ErrorCodes.INVALID_PARAMS,
                "A2A task metadata must identify an exposed NPS action when multiple actions exist.",
                data);
        }

        ActionFrame frame = new ActionFrame(
            action.actionId, extractActionParams(task), action.async, null, null, null, null, task.id);

        try {
            NpsFrame result = invoker.invoke(frame);
            return BridgeJsonRpc.success(request, toTask(task, result));
        } catch (Exception ex) {
            return BridgeJsonRpc.success(request, toTask(task, new ErrorFrame(
                "NPS-SERVER-ERROR",
                BridgeErrorCodes.SERVER_DISPATCH_FAILED,
                ex.getMessage(),
                null)));
        }
    }

    private BridgeServerOptions.Action resolveAction(A2aServerTypes.SendTaskParams task) {
        String requested = firstNonEmpty(
            tryGetString(task.metadata, "action_id", "actionId", "skill_id", "skillId", "skill"),
            task.message != null
                ? tryGetString(task.message.metadata, "action_id", "actionId", "skill_id", "skillId", "skill")
                : null);

        if (isBlank(requested) && task.message != null && task.message.parts != null) {
            for (A2aServerTypes.Part part : task.message.parts) {
                requested = firstNonEmpty(
                    tryGetString(part.metadata, "action_id", "actionId", "skill_id", "skillId", "skill"),
                    tryGetString(part.data, "action_id", "actionId", "skill_id", "skillId", "skill"));
                if (!isBlank(requested)) {
                    break;
                }
            }
        }

        if (isBlank(requested) && options.actions.size() == 1) {
            return options.actions.get(0);
        }

        final String req = requested;
        for (BridgeServerOptions.Action action : options.actions) {
            if (action.actionId.equalsIgnoreCase(req)
                || action.effectiveToolName().equalsIgnoreCase(req)) {
                return action;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractActionParams(A2aServerTypes.SendTaskParams task) {
        JsonNode fromMetadata = tryGetElement(task.metadata, "params", "arguments");
        if (fromMetadata == null && task.message != null) {
            fromMetadata = tryGetElement(task.message.metadata, "params", "arguments");
        }
        if (fromMetadata != null) {
            return toMap(fromMetadata);
        }

        if (task.message != null && task.message.parts != null) {
            for (A2aServerTypes.Part part : task.message.parts) {
                JsonNode nested = tryGetElement(part.data, "params", "arguments");
                if (nested != null) {
                    return toMap(nested);
                }

                if ("data".equalsIgnoreCase(part.type) && part.data != null && !part.data.isNull()) {
                    return toMap(part.data);
                }

                if ("text".equalsIgnoreCase(part.type) && part.text != null && !part.text.isBlank()) {
                    Map<String, Object> textParams = new LinkedHashMap<>();
                    textParams.put("text", part.text);
                    return textParams;
                }
            }
        }

        return null;
    }

    private static A2aServerTypes.Task toTask(A2aServerTypes.SendTaskParams request, NpsFrame frame) {
        boolean isError = frame instanceof ErrorFrame;
        String timestamp = Instant.now().toString();
        JsonNode payload = BridgeFrameJson.toNode(frame);

        A2aServerTypes.Task task = new A2aServerTypes.Task();
        task.id = request.id;
        task.sessionId = request.sessionId;

        A2aServerTypes.TaskStatus status = new A2aServerTypes.TaskStatus();
        status.state = isError ? A2aServerTypes.TaskState.FAILED : A2aServerTypes.TaskState.COMPLETED;
        status.timestamp = timestamp;
        if (isError) {
            A2aServerTypes.Message message = new A2aServerTypes.Message();
            message.role = "agent";
            A2aServerTypes.Part part = new A2aServerTypes.Part();
            part.type = "text";
            ErrorFrame error = (ErrorFrame) frame;
            part.text = error.message() != null ? error.message() : error.error();
            message.parts = List.of(part);
            status.message = message;
        }
        task.status = status;

        A2aServerTypes.Artifact artifact = new A2aServerTypes.Artifact();
        artifact.name = isError ? "nps-error" : "nps-result";
        A2aServerTypes.Part dataPart = new A2aServerTypes.Part();
        dataPart.type = "data";
        dataPart.data = payload;
        artifact.parts = List.of(dataPart);
        artifact.index = 0;
        task.artifacts = List.of(artifact);

        if (request.message != null) {
            task.history = List.of(request.message);
        }
        return task;
    }

    private static String tryGetString(JsonNode source, String... names) {
        JsonNode value = tryGetElement(source, names);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static JsonNode tryGetElement(JsonNode source, String... names) {
        if (source == null || !source.isObject()) {
            return null;
        }
        for (String name : names) {
            if (source.has(name)) {
                return source.get(name);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return BridgeJsonRpc.JSON.convertValue(node, Map.class);
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("value", BridgeJsonRpc.JSON.convertValue(node, Object.class));
        return wrapped;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
