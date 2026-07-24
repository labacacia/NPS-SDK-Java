// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

/**
 * SyncFrame result aggregation strategies (NPS-5 §3.3.2).
 *
 * <p>Constant values are the canonical NPS-5 wire strings and are interop-compatible
 * with the .NET reference SDK ({@code NPS.NOP.Models.AggregateStrategy}).
 */
public final class AggregateStrategy {
    private AggregateStrategy() {}

    /** Merge all successful sub-task results into a single object (last-write-wins). */
    public static final String MERGE = "merge";

    /** Take the first successful result. */
    public static final String FIRST = "first";

    /** Keep all results as an array. */
    public static final String ALL = "all";

    /** Take the fastest {@code min_required} results in array format. */
    public static final String FASTEST_K = "fastest_k";

    /**
     * Take the {@code min_required} fastest results, weighted by node priority
     * (NPS-5 §3.3.2, NOP v0.6). Requires per-node weight metadata.
     */
    public static final String WEIGHTED_FIRST_K = "weighted_first_k";

    /**
     * Merge all results (including from nodes beyond {@code min_required})
     * into a single object (NPS-5 §3.3.2, NOP v0.6).
     */
    public static final String MERGE_ALL = "merge_all";
}
