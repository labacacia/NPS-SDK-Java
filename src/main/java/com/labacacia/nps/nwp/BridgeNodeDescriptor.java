// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;
import java.util.Set;

/**
 * Declares which external protocols a Bridge Node bridges, in each direction.
 *
 * <ul>
 *   <li>{@code supportedProtocols} &rarr; {@code Announce.bridge_protocols} (OUTBOUND: NPS &rarr; external)</li>
 *   <li>{@code inboundProtocols} &rarr; {@code Announce.bridge_inbound_protocols} (INBOUND: external &rarr; NPS)</li>
 * </ul>
 *
 * <p>An empty {@code inboundProtocols} means the node exposes no inbound surface — an
 * outbound-only Bridge Node, the only kind that existed through alpha.15. A Bridge Node
 * MUST have at least one of the two non-empty. (NPS-CR-0010)
 */
public final class BridgeNodeDescriptor {
    public final String nid;
    public final Set<String> supportedProtocols;
    public final Set<String> inboundProtocols;

    public BridgeNodeDescriptor(String nid, Set<String> supportedProtocols) {
        this(nid, supportedProtocols, Set.of());
    }

    public BridgeNodeDescriptor(String nid, Set<String> supportedProtocols, Set<String> inboundProtocols) {
        this.nid = nid;
        this.supportedProtocols = supportedProtocols;
        this.inboundProtocols = inboundProtocols;
    }
}
