// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON-RPC 2.0 helper used by MCP and A2A Bridge servers.
 *
 * <p>Nested here are the request/response/error envelopes and the standard +
 * Bridge-specific error codes. {@code id}, {@code params}, {@code result}, and
 * error {@code data} travel as Jackson {@link JsonNode} trees.
 */
public final class BridgeJsonRpc {

    /** Shared serializer: omits nulls, matches the .NET web defaults. */
    public static final ObjectMapper JSON =
        new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private BridgeJsonRpc() {}

    /** JSON-RPC 2.0 request envelope used by MCP and A2A Bridge servers. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Request {
        public String jsonrpc = "2.0";
        /** Request id. {@code null} indicates a notification. */
        public JsonNode id;
        public String method;
        public JsonNode params;

        public Request() {}

        public Request(String method, JsonNode id, JsonNode params) {
            this.method = method;
            this.id = id;
            this.params = params;
        }
    }

    /** JSON-RPC 2.0 response envelope used by MCP and A2A Bridge servers. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Response {
        public String jsonrpc = "2.0";
        public JsonNode id;
        public JsonNode result;
        public Error error;

        public Response() {}
    }

    /** JSON-RPC 2.0 error object. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Error {
        public int code;
        public String message;
        public JsonNode data;

        public Error() {}

        public Error(int code, String message, JsonNode data) {
            this.code = code;
            this.message = message;
            this.data = data;
        }
    }

    /** Standard JSON-RPC error codes plus Bridge server application codes. */
    public static final class ErrorCodes {
        private ErrorCodes() {}

        public static final int PARSE_ERROR = -32700;
        public static final int INVALID_REQUEST = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS = -32602;
        public static final int INTERNAL_ERROR = -32603;
        public static final int UPSTREAM_ERROR = -32000;
        public static final int TOOL_NOT_FOUND = -32002;
    }

    /** Build a success response cloning the request id. */
    public static Response success(Request request, Object result) {
        Response response = new Response();
        response.id = clone(request.id);
        response.result = JSON.valueToTree(result);
        return response;
    }

    /** Build an error response cloning the request id. */
    public static Response error(Request request, int code, String message) {
        return error(request, code, message, null);
    }

    /** Build an error response cloning the request id, with error data. */
    public static Response error(Request request, int code, String message, Object data) {
        return error(request == null ? null : request.id, code, message, data);
    }

    /** Build an error response with an explicit id. */
    public static Response error(JsonNode id, int code, String message) {
        return error(id, code, message, null);
    }

    /** Build an error response with an explicit id and error data. */
    public static Response error(JsonNode id, int code, String message, Object data) {
        Response response = new Response();
        response.id = clone(id);
        response.error = new Error(code, message, data == null ? null : JSON.valueToTree(data));
        return response;
    }

    /** Defensive copy of a JSON id node (JsonNode is immutable, so returns as-is). */
    public static JsonNode clone(JsonNode node) {
        return node == null ? null : node.deepCopy();
    }
}
