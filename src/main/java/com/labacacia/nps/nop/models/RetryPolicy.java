// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.models;

import com.labacacia.nps.nop.BackoffStrategy;

import java.util.List;

/**
 * Per-node retry policy (NPS-5 §3.1.4).
 * Delay formula: {@code min(initialDelayMs * factor^attempt, maxDelayMs)}.
 *
 * @param maxRetries     Maximum retry attempts. Overrides {@code TaskFrame.maxRetries} for this node.
 * @param backoff        Backoff strategy: {@code "fixed"}, {@code "linear"}, or {@code "exponential"} (default).
 * @param initialDelayMs Initial retry delay in milliseconds. Default 1000.
 * @param maxDelayMs     Maximum delay cap in milliseconds. Default 30000.
 * @param retryOn        Error codes that trigger retry. Null means retry on all failures.
 */
public record RetryPolicy(
    int maxRetries,
    String backoff,
    long initialDelayMs,
    long maxDelayMs,
    List<String> retryOn) {

    public RetryPolicy {
        if (backoff == null) backoff = BackoffStrategy.EXPONENTIAL.value;
    }

    /** Creates a policy with default backoff, delays and no retry-on filter. */
    public static RetryPolicy of(int maxRetries) {
        return new RetryPolicy(maxRetries, BackoffStrategy.EXPONENTIAL.value, 1000, 30000, null);
    }

    /**
     * Computes the delay for a given attempt number (0-based).
     */
    public long computeDelayMs(int attempt) {
        double factor = switch (backoff) {
            case "fixed"  -> 1;
            case "linear" -> attempt + 1;
            default        -> Math.pow(2, attempt);
        };
        return (long) Math.min((double) initialDelayMs * factor, (double) maxDelayMs);
    }
}
