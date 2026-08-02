// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** JSON-RPC 2.0 error object. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class BridgeJsonRpcError {

    @JsonProperty("code")    public int      code;
    @JsonProperty("message") public String   message;
    @JsonProperty("data")    public JsonNode data;

    public BridgeJsonRpcError() {}

    public BridgeJsonRpcError(int code, String message, JsonNode data) {
        this.code    = code;
        this.message = message;
        this.data    = data;
    }

    public BridgeJsonRpcError(int code, String message) {
        this(code, message, null);
    }
}
