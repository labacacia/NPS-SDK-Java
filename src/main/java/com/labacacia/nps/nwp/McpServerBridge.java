// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.ncp.ErrorFrame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inbound MCP adapter that exposes local NPS actions as MCP tools. */
public final class McpServerBridge {

    private final BridgeServerOptions options;
    private final BridgeServerActionInvoker invoker;

    /** Create an MCP server bridge. */
    public McpServerBridge(BridgeServerOptions options, BridgeServerActionInvoker invoker) {
        this.options = options;
        this.invoker = invoker;
    }

    /** Dispatch one MCP JSON-RPC request. */
    public BridgeJsonRpc.Response dispatch(BridgeJsonRpc.Request request) {
        if (request == null) {
            throw new NullPointerException("request");
        }

        return switch (request.method == null ? "" : request.method) {
            case "initialize" -> BridgeJsonRpc.success(request, initialize());
            case "tools/list" -> BridgeJsonRpc.success(request, listTools());
            case "tools/call" -> callTool(request);
            case "ping" -> BridgeJsonRpc.success(request, new LinkedHashMap<>());
            default -> BridgeJsonRpc.error(
                request,
                BridgeJsonRpc.ErrorCodes.METHOD_NOT_FOUND,
                "MCP method '" + request.method + "' is not supported by NWP Bridge server.");
        };
    }

    private McpServerTypes.InitializeResult initialize() {
        McpServerTypes.InitializeResult result = new McpServerTypes.InitializeResult();
        result.serverInfo = new McpServerTypes.ServerInfo(options.serverName, options.serverVersion);
        result.capabilities = new McpServerTypes.ServerCapabilities();
        result.capabilities.tools = new McpServerTypes.ToolCapabilities();
        result.capabilities.tools.listChanged = false;
        return result;
    }

    private McpServerTypes.ToolListResult listTools() {
        McpServerTypes.ToolListResult result = new McpServerTypes.ToolListResult();
        List<McpServerTypes.Tool> tools = new ArrayList<>();
        for (BridgeServerOptions.Action action : options.actions) {
            McpServerTypes.Tool tool = new McpServerTypes.Tool();
            tool.name = action.effectiveToolName();
            tool.description = action.description;
            tool.inputSchema = action.inputSchema != null ? action.inputSchema : defaultInputSchema();
            tools.add(tool);
        }
        result.tools = tools;
        return result;
    }

    private BridgeJsonRpc.Response callTool(BridgeJsonRpc.Request request) {
        if (request.params == null || request.params.isNull()) {
            return BridgeJsonRpc.error(
                request, BridgeJsonRpc.ErrorCodes.INVALID_PARAMS, "MCP tools/call requires params.");
        }

        McpServerTypes.ToolCallParams call;
        try {
            call = BridgeJsonRpc.JSON.treeToValue(request.params, McpServerTypes.ToolCallParams.class);
        } catch (Exception ex) {
            return BridgeJsonRpc.error(request, BridgeJsonRpc.ErrorCodes.INVALID_PARAMS, ex.getMessage());
        }

        if (call == null || call.name == null || call.name.isBlank()) {
            return BridgeJsonRpc.error(
                request, BridgeJsonRpc.ErrorCodes.INVALID_PARAMS, "MCP tools/call params.name is required.");
        }

        BridgeServerOptions.Action action = resolveAction(call.name);
        if (action == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("error", BridgeErrorCodes.SERVER_TOOL_NOT_FOUND);
            data.put("tool", call.name);
            return BridgeJsonRpc.error(
                request, BridgeJsonRpc.ErrorCodes.TOOL_NOT_FOUND,
                "MCP tool '" + call.name + "' is not exposed by NWP Bridge server.", data);
        }

        ActionFrame frame = new ActionFrame(
            action.actionId, toParams(call.arguments), action.async, null, null);

        try {
            NpsFrame result = invoker.invoke(frame);
            return BridgeJsonRpc.success(request, toToolResult(result));
        } catch (Exception ex) {
            return BridgeJsonRpc.success(request, toToolResult(new ErrorFrame(
                "NPS-SERVER-ERROR",
                BridgeErrorCodes.SERVER_DISPATCH_FAILED,
                ex.getMessage(),
                null)));
        }
    }

    private BridgeServerOptions.Action resolveAction(String toolName) {
        for (BridgeServerOptions.Action action : options.actions) {
            if (action.effectiveToolName().equalsIgnoreCase(toolName)
                || action.actionId.equalsIgnoreCase(toolName)) {
                return action;
            }
        }
        return null;
    }

    private static McpServerTypes.ToolCallResult toToolResult(NpsFrame frame) {
        boolean isError = frame instanceof ErrorFrame;
        McpServerTypes.ToolCallResult result = new McpServerTypes.ToolCallResult();
        result.isError = isError;
        List<McpServerTypes.Content> content = new ArrayList<>();
        content.add(new McpServerTypes.Content("text", BridgeFrameJson.serialize(frame)));
        result.content = content;
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toParams(JsonNode arguments) {
        if (arguments == null || arguments.isNull() || !arguments.isObject()) {
            return null;
        }
        return BridgeJsonRpc.JSON.convertValue(arguments, Map.class);
    }

    private static JsonNode defaultInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        return BridgeJsonRpc.JSON.valueToTree(schema);
    }
}
