// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.core.NpsFrame;

import java.util.ArrayList;
import java.util.List;

/** Options for inbound MCP/A2A Bridge server hosting. */
public final class BridgeServerOptions {

    /** Dispatch delegate used by inbound Bridge server adapters. */
    @FunctionalInterface
    public interface ActionDispatcher {
        /** Invoke a local NPS action and return its frame response. */
        NpsFrame dispatch(ActionFrame frame) throws Exception;
    }

    /** Action exposed by inbound MCP/A2A Bridge server adapters. */
    public static final class Action {
        /** NPS action identifier dispatched to the local node. */
        public final String actionId;
        /** Protocol-safe MCP tool name. Defaults to a sanitized {@link #actionId}. */
        public final String toolName;
        /** Human-readable display name for A2A AgentCard entries. */
        public final String displayName;
        /** Short action/tool description. */
        public final String description;
        /** JSON Schema describing input arguments. */
        public final JsonNode inputSchema;
        /** Whether generated {@link ActionFrame} values should request async execution. */
        public final boolean async;
        /** Optional A2A skill tags. */
        public final List<String> tags;

        public Action(String actionId, String toolName, String displayName, String description,
                      JsonNode inputSchema, boolean async, List<String> tags) {
            this.actionId = actionId;
            this.toolName = toolName;
            this.displayName = displayName;
            this.description = description;
            this.inputSchema = inputSchema;
            this.async = async;
            this.tags = tags;
        }

        /** Effective MCP tool name for this action. */
        public String effectiveToolName() {
            return (toolName == null || toolName.isBlank()) ? toToolName(actionId) : toolName;
        }

        /** Effective display name for A2A AgentCard skills. */
        public String effectiveDisplayName() {
            return (displayName == null || displayName.isBlank()) ? actionId : displayName;
        }

        /** Return a protocol-safe MCP tool name for an NPS action id. */
        public static String toToolName(String actionId) {
            if (actionId == null || actionId.isBlank()) {
                return "action";
            }
            StringBuilder sb = new StringBuilder();
            for (char ch : actionId.trim().toCharArray()) {
                sb.append(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' ? ch : '_');
            }
            String name = trim(sb.toString(), '_');
            return name.isBlank() ? "action" : name;
        }

        private static String trim(String s, char c) {
            int start = 0;
            int end = s.length();
            while (start < end && s.charAt(start) == c) start++;
            while (end > start && s.charAt(end - 1) == c) end--;
            return s.substring(start, end);
        }
    }

    /** Bridge server identifier surfaced in protocol metadata. */
    public String nodeId = "nps-bridge-server";

    /** Path prefix for inbound Bridge server endpoints. Empty string means root. */
    public String pathPrefix = "";

    /** MCP HTTP endpoint under {@link #pathPrefix}. */
    public String mcpPath = "/mcp";

    /** A2A JSON-RPC endpoint under {@link #pathPrefix}. */
    public String a2aPath = "/a2a";

    /** A2A AgentCard endpoint under {@link #pathPrefix}. */
    public String a2aAgentCardPath = "/.well-known/agent.json";

    /** Require a valid {@code X-NWP-Agent} NID header before dispatching requests. */
    public boolean requireAuth = true;

    /** Server name returned by MCP initialize and A2A AgentCard. */
    public String serverName = "nps-bridge-server";

    /** Server version returned by MCP initialize and A2A AgentCard. */
    public String serverVersion = "1.0.0-alpha.15";

    /** Server description returned by A2A AgentCard. */
    public String description = "NPS Bridge server ingress.";

    /** Actions exposed as MCP tools and A2A skills. */
    public final List<Action> actions = new ArrayList<>();

    /** Local NPS action dispatcher used by inbound Bridge server adapters. */
    public ActionDispatcher dispatch;

    /** Maximum inbound JSON-RPC request body size in bytes. Set to 0 to disable. */
    public long maxRequestBodyBytes = 1L * 1024 * 1024;

    /** Maximum time allowed for MCP/A2A dispatch in ms. Set to 0 to disable. */
    public long dispatchTimeoutMs = 30_000;

    /** Add an exposed local action and return these options for chaining. */
    public BridgeServerOptions addAction(String actionId, String description, JsonNode inputSchema,
                                         String toolName, boolean async, String displayName,
                                         List<String> tags) {
        actions.add(new Action(actionId, toolName, displayName, description, inputSchema, async, tags));
        return this;
    }

    /** Add an exposed local action with only an id and description. */
    public BridgeServerOptions addAction(String actionId, String description) {
        return addAction(actionId, description, null, null, false, null, null);
    }
}
