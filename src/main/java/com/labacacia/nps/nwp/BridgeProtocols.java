// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;
import java.util.List;

/** Standard bridge_protocols wire-string constants (NPS-CR-0001 §3). */
public final class BridgeProtocols {
    public static final String HTTP = "http";
    public static final String GRPC = "grpc";
    public static final String MCP  = "mcp";
    public static final String A2A  = "a2a";
    public static final List<String> STANDARD = List.of(HTTP, GRPC, MCP, A2A);
    private BridgeProtocols() {}
}
