// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.net.http.HttpClient;

/** Built-in Bridge dispatcher for MCP JSON-RPC servers over HTTP POST. */
public final class McpBridgeDispatcher extends JsonRpcBridgeDispatcher {

    /** Anchor reference used for MCP bridge response records. */
    public static final String RESPONSE_ANCHOR_REF = "nps://bridge/mcp-jsonrpc-response/v1";

    /** Create an MCP bridge dispatcher over an existing client. */
    public McpBridgeDispatcher(HttpClient client) {
        super(client, "tools/call", RESPONSE_ANCHOR_REF);
    }

    @Override
    public String protocol() {
        return BridgeProtocols.MCP;
    }
}
