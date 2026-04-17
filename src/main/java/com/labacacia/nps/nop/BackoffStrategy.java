// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

public enum BackoffStrategy {
    FIXED("fixed"), LINEAR("linear"), EXPONENTIAL("exponential");

    public final String value;
    BackoffStrategy(String value) { this.value = value; }

    public static long computeDelayMs(BackoffStrategy strategy, long baseMs, long maxMs, int attempt) {
        long delay = switch (strategy) {
            case FIXED       -> baseMs;
            case LINEAR      -> baseMs * (attempt + 1L);
            case EXPONENTIAL -> (long) (baseMs * Math.pow(2, attempt));
        };
        return Math.min(delay, maxMs);
    }
}
