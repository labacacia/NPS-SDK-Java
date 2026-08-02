// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.util.Locale;

/** NWP node role behind an inbound Bridge backend (NPS-CR-0010). */
public enum NwpNodeRole {
    UNKNOWN,
    MEMORY,
    ACTION,
    COMPLEX,
    ANCHOR,
    BRIDGE;

    /**
     * Map a {@code node_type} wire string onto a role. Case-insensitive; anything
     * unrecognised (including null) is {@link #UNKNOWN} — an unreachable upstream is
     * simply projected onto nothing rather than taking the Bridge down.
     */
    public static NwpNodeRole parseRole(String nodeType) {
        if (nodeType == null) return UNKNOWN;
        return switch (nodeType.toLowerCase(Locale.ROOT)) {
            case "memory"  -> MEMORY;
            case "action"  -> ACTION;
            case "complex" -> COMPLEX;
            case "anchor"  -> ANCHOR;
            case "bridge"  -> BRIDGE;
            default        -> UNKNOWN;
        };
    }

    /** Lower-case wire form, or the empty string for {@link #UNKNOWN}. */
    public String wire() {
        return this == UNKNOWN ? "" : name().toLowerCase(Locale.ROOT);
    }
}
