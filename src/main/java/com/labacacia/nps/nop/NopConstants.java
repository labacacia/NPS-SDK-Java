// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

/**
 * Protocol-level limits defined by NPS-5 §8.2.
 */
public final class NopConstants {
    private NopConstants() {}

    /** Maximum number of nodes in a single DAG. */
    public static final int MAX_DAG_NODES = 32;

    /** Maximum delegation chain depth (Orchestrator -&gt; Worker -&gt; Sub-Worker). */
    public static final int MAX_DELEGATE_CHAIN_DEPTH = 3;

    /** Maximum length of a CEL condition expression in characters. */
    public static final int MAX_CONDITION_LENGTH = 512;

    /** Maximum JSONPath nesting depth in input_mapping values. */
    public static final int MAX_INPUT_MAPPING_DEPTH = 8;

    /** Default task timeout in milliseconds. */
    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** Maximum task timeout in milliseconds (1 hour). */
    public static final long MAX_TIMEOUT_MS = 3_600_000;

    /** Default AnchorFrame TTL in seconds. */
    public static final long DEFAULT_ANCHOR_TTL = 3_600;

    /**
     * Maximum number of callback POST attempts with exponential backoff (NPS-5 §8.4).
     * Attempts use delays: 0 s, 1 s, 2 s (first attempt is immediate).
     */
    public static final int CALLBACK_MAX_RETRIES = 3;
}
