// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.reputation;

/**
 * Ordered severity enum for NPS-RFC-0004 reputation log entries.
 * Unknown wire strings throw {@link IllegalArgumentException} — no forward-compat alias.
 */
public enum Severity {
    INFO("info", 0),
    MINOR("minor", 1),
    MODERATE("moderate", 2),
    MAJOR("major", 3),
    CRITICAL("critical", 4);

    public final String wire;
    public final int level;

    Severity(String wire, int level) {
        this.wire  = wire;
        this.level = level;
    }

    /**
     * Resolve a wire string to a {@link Severity}.
     *
     * @throws IllegalArgumentException if the wire string is not recognised
     */
    public static Severity fromWire(String s) {
        for (Severity sv : values()) {
            if (sv.wire.equals(s)) return sv;
        }
        throw new IllegalArgumentException("Unknown severity: " + s);
    }
}
