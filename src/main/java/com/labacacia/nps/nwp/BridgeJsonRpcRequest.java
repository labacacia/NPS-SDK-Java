// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** JSON-RPC 2.0 request as received by the MCP / A2A inbound servers (NPS-CR-0010). */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BridgeJsonRpcRequest {

    @JsonProperty("jsonrpc") public String   jsonrpc = "2.0";
    @JsonProperty("id")      public JsonNode id;
    @JsonProperty("method")  public String   method;
    @JsonProperty("params")  public JsonNode params;

    public BridgeJsonRpcRequest() {}

    public BridgeJsonRpcRequest(JsonNode id, String method, JsonNode params) {
        this.id     = id;
        this.method = method;
        this.params = params;
    }
}
