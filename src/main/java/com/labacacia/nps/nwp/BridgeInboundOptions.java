// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Transport-independent configuration of an inbound Bridge (NPS-CR-0010).
 *
 * <p>Deliberately separate from {@link BridgeServerOptions}, which adds the HTTP hosting
 * concerns (paths, verifier, limits). The MCP / A2A / gRPC servers are written against
 * <em>this</em> type only, so they never touch an HTTP context and can be driven from
 * stdio or a plain unit test with no web host.</p>
 */
public class BridgeInboundOptions {

    /** Server identity reported by MCP {@code initialize} and the A2A AgentCard. */
    public String serverName    = "nps-bridge-server";
    public String serverVersion = "1.0.0";
    public String nodeId        = "nps-bridge-server";
    public String description;

    /**
     * The NDP {@code bridge_inbound_protocols} set. Default {@code ["mcp","a2a"]} —
     * gRPC is deliberately NOT in the default set, so the gRPC service refuses until
     * {@code "grpc"} is added.
     */
    public Set<String> inboundProtocols =
        new LinkedHashSet<>(List.of(BridgeProtocols.MCP, BridgeProtocols.A2A));

    /** The outbound {@code bridge_protocols} set, reported in direction-refusal hints. */
    public Set<String> outboundProtocols = new LinkedHashSet<>();

    /** The fronted nodes. */
    public List<NwpBackend> backends = new ArrayList<>();

    /** Rows per {@code resources/read}. */
    public int resourceReadLimit = 100;

    /** Advertised on the A2A AgentCard, so authentication is part of the protocol surface. */
    public boolean requireAuth = true;

    /** Case-insensitive membership test over {@link #inboundProtocols} (§16.1.2 MUST-5). */
    public boolean servesInbound(String protocol) {
        if (protocol == null || inboundProtocols == null) return false;
        for (String p : inboundProtocols) {
            if (p != null && p.equalsIgnoreCase(protocol)) return true;
        }
        return false;
    }

    /** Declared inbound protocols, lower-cased, for the §16.1.2 MUST-5 {@code hint}. */
    public List<String> declaredInbound() { return lowerAll(inboundProtocols); }

    /** Declared outbound protocols, lower-cased, for the §16.1.2 MUST-5 {@code hint}. */
    public List<String> declaredOutbound() { return lowerAll(outboundProtocols); }

    private static List<String> lowerAll(Set<String> in) {
        if (in == null) return List.of();
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) if (s != null) out.add(s.toLowerCase(Locale.ROOT));
        return List.copyOf(out);
    }
}
