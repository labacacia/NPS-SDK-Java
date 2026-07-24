// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.net.http.HttpClient;

/** Built-in Bridge dispatcher for A2A JSON-RPC endpoints over HTTP POST. */
public final class A2aBridgeDispatcher extends JsonRpcBridgeDispatcher {

    /** Anchor reference used for A2A bridge response records. */
    public static final String RESPONSE_ANCHOR_REF = "nps://bridge/a2a-jsonrpc-response/v1";

    /** Create an A2A bridge dispatcher over an existing client. */
    public A2aBridgeDispatcher(HttpClient client) {
        super(client, "tasks/send", RESPONSE_ANCHOR_REF);
    }

    @Override
    public String protocol() {
        return BridgeProtocols.A2A;
    }
}
