// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** JSON-RPC 2.0 response emitted by the MCP / A2A inbound servers (NPS-CR-0010). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class BridgeJsonRpcResponse {

    @JsonProperty("jsonrpc") public String   jsonrpc = "2.0";
    /** Present, possibly JSON null, so that a parse failure can answer with {@code id: null}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonProperty("id")      public JsonNode id;
    @JsonProperty("result")  public JsonNode result;
    @JsonProperty("error")   public BridgeJsonRpcError error;

    public BridgeJsonRpcResponse() {}

    public static BridgeJsonRpcResponse ok(JsonNode id, JsonNode result) {
        BridgeJsonRpcResponse r = new BridgeJsonRpcResponse();
        r.id     = id;
        r.result = result;
        return r;
    }

    public static BridgeJsonRpcResponse fail(JsonNode id, int code, String message, JsonNode data) {
        BridgeJsonRpcResponse r = new BridgeJsonRpcResponse();
        r.id    = id;
        r.error = new BridgeJsonRpcError(code, message, data);
        return r;
    }

    public static BridgeJsonRpcResponse fail(JsonNode id, int code, String message) {
        return fail(id, code, message, null);
    }
}
