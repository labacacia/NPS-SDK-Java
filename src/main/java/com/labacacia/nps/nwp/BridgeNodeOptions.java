// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/** Options for the JDK-hosted Bridge Node middleware. */
public final class BridgeNodeOptions {

    /** Bridge Node identifier surfaced in {@code /.nwm}. */
    public String nodeId = "nps-bridge";

    /** Path prefix for the Bridge Node endpoints. Empty string means root. */
    public String pathPrefix = "";

    /** Action id accepted by {@code /invoke}. */
    public String actionId = "bridge.dispatch";

    /** Require the {@code X-NWP-Agent} header before dispatching. */
    public boolean requireAuth = false;

    /** Register HTTP/HTTPS, gRPC, MCP, and A2A dispatchers automatically. */
    public boolean registerBuiltInDispatchers = true;
}
