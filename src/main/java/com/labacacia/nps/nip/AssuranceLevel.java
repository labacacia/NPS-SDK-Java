// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

/**
 * Agent identity assurance level per NPS-RFC-0003 §5.1.1.
 * Ordering is significant: ANONYMOUS &lt; ATTESTED &lt; VERIFIED.
 */
public enum AssuranceLevel {
    ANONYMOUS("anonymous", 0),
    ATTESTED ("attested",  1),
    VERIFIED ("verified",  2);

    private final String wire;
    private final int    rank;

    AssuranceLevel(String wire, int rank) {
        this.wire = wire;
        this.rank = rank;
    }

    /** Wire-form string (lowercase): "anonymous" / "attested" / "verified". */
    public String wire() { return wire; }

    /** Numeric rank for ordering and ASN.1 ENUMERATED encoding (0..2). */
    public int rank() { return rank; }

    /** Returns true if this level meets or exceeds {@code required}. */
    public boolean meetsOrExceeds(AssuranceLevel required) {
        return this.rank >= required.rank;
    }

    /**
     * Parse a wire string.  {@code null} or {@code ""} → {@link #ANONYMOUS}
     * (backward compat per NPS-RFC-0003 §5.1.1).  Any other unrecognised
     * non-empty value throws — callers MUST surface it as
     * {@code NIP-ASSURANCE-UNKNOWN}.
     */
    public static AssuranceLevel fromWire(String wire) {
        if (wire == null || wire.isEmpty()) return ANONYMOUS;
        for (AssuranceLevel a : values()) {
            if (a.wire.equals(wire)) return a;
        }
        throw new IllegalArgumentException("Unknown assurance_level: " + wire);
    }

    public static AssuranceLevel fromRank(int rank) {
        for (AssuranceLevel a : values()) {
            if (a.rank == rank) return a;
        }
        throw new IllegalArgumentException("Unknown assurance_level rank: " + rank);
    }
}
