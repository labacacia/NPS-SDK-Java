// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/**
 * Identity of one NWP node fronted by an inbound Bridge (NPS-CR-0010).
 *
 * @param name        required, unique per Bridge — namespaces resource URIs and MCP tool names
 * @param role        required; drives {@link #isQueryable()} / {@link #isInvokable()}
 * @param displayName optional human label
 * @param description optional human description
 */
public record NwpNodeDescriptor(String name, NwpNodeRole role,
                                String displayName, String description) {

    public NwpNodeDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("NwpNodeDescriptor.name is required");
        }
        if (role == null) role = NwpNodeRole.UNKNOWN;
    }

    public NwpNodeDescriptor(String name, NwpNodeRole role) {
        this(name, role, null, null);
    }

    /** Memory and Complex nodes answer queries — the source of {@code resources/*}. */
    public boolean isQueryable() {
        return role == NwpNodeRole.MEMORY || role == NwpNodeRole.COMPLEX;
    }

    /** Action and Complex nodes execute actions — the source of {@code tools/*}. */
    public boolean isInvokable() {
        return role == NwpNodeRole.ACTION || role == NwpNodeRole.COMPLEX;
    }
}
