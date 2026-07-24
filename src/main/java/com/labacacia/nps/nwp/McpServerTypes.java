// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** MCP protocol types implemented by the Bridge server adapter. */
public final class McpServerTypes {

    private McpServerTypes() {}

    /** MCP protocol version implemented by the Bridge server adapter. */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class InitializeResult {
        public String protocolVersion = PROTOCOL_VERSION;
        public ServerInfo serverInfo;
        public ServerCapabilities capabilities;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ServerInfo {
        public String name;
        public String version;

        public ServerInfo() {}

        public ServerInfo(String name, String version) {
            this.name = name;
            this.version = version;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ServerCapabilities {
        public ToolCapabilities tools;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ToolCapabilities {
        public boolean listChanged;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Tool {
        public String name;
        public String description;
        public JsonNode inputSchema;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ToolListResult {
        public List<Tool> tools;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ToolCallParams {
        public String name;
        public JsonNode arguments;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ToolCallResult {
        public List<Content> content;
        public boolean isError;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Content {
        public String type;
        public String text;

        public Content() {}

        public Content(String type, String text) {
            this.type = type;
            this.text = text;
        }
    }
}
