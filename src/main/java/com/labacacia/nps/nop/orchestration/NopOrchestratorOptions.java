// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.labacacia.nps.nop.AggregateStrategy;

/**
 * Configuration options for {@link NopOrchestrator}. Mutable POJO with sane defaults.
 */
public final class NopOrchestratorOptions {

    /**
     * Maximum number of DAG nodes that may execute concurrently per task.
     * Defaults to {@code availableProcessors * 2}.
     */
    private int maxConcurrentNodes = Runtime.getRuntime().availableProcessors() * 2;

    /**
     * When {@code true}, the orchestrator validates {@code AlignStreamFrame.senderNid}
     * against the {@code DagNode.agent} NID for every received frame. Default {@code true}.
     */
    private boolean validateSenderNid = true;

    /**
     * When {@code true}, the orchestrator POSTs the {@link NopTaskResult} to
     * {@code callback_url} on completion (fire-and-forget). Default {@code true}.
     */
    private boolean enableCallback = true;

    /** HTTP client timeout for callback POST requests (ms). Default 10000. */
    private int callbackTimeoutMs = 10_000;

    /**
     * Base delay in ms for exponential backoff between callback retry attempts.
     * Delay for attempt n (1-based) = {@code callbackRetryBaseDelayMs * 2^(n-1)}.
     * Set to 0 in tests to avoid real delays. Default 1000.
     */
    private int callbackRetryBaseDelayMs = 1000;

    /**
     * Default aggregate strategy applied to terminal (end) nodes when no SyncFrame is
     * present. Default {@code "merge"}.
     */
    private String defaultAggregateStrategy = AggregateStrategy.MERGE;

    public int maxConcurrentNodes()             { return maxConcurrentNodes; }
    public NopOrchestratorOptions maxConcurrentNodes(int v) { this.maxConcurrentNodes = v; return this; }

    public boolean validateSenderNid()          { return validateSenderNid; }
    public NopOrchestratorOptions validateSenderNid(boolean v) { this.validateSenderNid = v; return this; }

    public boolean enableCallback()             { return enableCallback; }
    public NopOrchestratorOptions enableCallback(boolean v) { this.enableCallback = v; return this; }

    public int callbackTimeoutMs()              { return callbackTimeoutMs; }
    public NopOrchestratorOptions callbackTimeoutMs(int v) { this.callbackTimeoutMs = v; return this; }

    public int callbackRetryBaseDelayMs()       { return callbackRetryBaseDelayMs; }
    public NopOrchestratorOptions callbackRetryBaseDelayMs(int v) { this.callbackRetryBaseDelayMs = v; return this; }

    public String defaultAggregateStrategy()    { return defaultAggregateStrategy; }
    public NopOrchestratorOptions defaultAggregateStrategy(String v) { this.defaultAggregateStrategy = v; return this; }
}
