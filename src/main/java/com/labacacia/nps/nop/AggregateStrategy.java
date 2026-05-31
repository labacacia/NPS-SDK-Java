// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

/** Well-known aggregate strategy values for {@link SyncFrame#aggregate()}. */
public final class AggregateStrategy {
    private AggregateStrategy() {}

    public static final String MERGE           = "merge";
    public static final String FIRST           = "first";
    public static final String WEIGHTED_FIRST_K = "weighted_first_k";
    public static final String MERGE_ALL        = "merge_all";
}
