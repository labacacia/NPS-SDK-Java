// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;
import java.util.Set;

/** Declares which external protocols a Bridge Node deployment can reach. */
public final class BridgeNodeDescriptor {
    public final String nid;
    public final Set<String> supportedProtocols;
    public BridgeNodeDescriptor(String nid, Set<String> supportedProtocols) {
        this.nid = nid;
        this.supportedProtocols = supportedProtocols;
    }
}
