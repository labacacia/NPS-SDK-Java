// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import java.util.concurrent.CompletableFuture;

/**
 * Core NOP orchestrator contract (NPS-5 §3, §5).
 *
 * <p>Accepts a {@link NopTask}, executes its DAG by dispatching delegations to Worker
 * Agents via {@link INopWorkerClient}, and completes with a {@link NopTaskResult} when
 * the task reaches a terminal state.
 */
public interface INopOrchestrator {

    /**
     * Executes the full task lifecycle:
     * validate -&gt; (preflight) -&gt; run DAG -&gt; aggregate -&gt; (callback).
     * The returned future completes when the task reaches a terminal state.
     */
    CompletableFuture<NopTaskResult> execute(NopTask task);

    /**
     * Requests cancellation of a running task. In-flight subtasks receive a cancel
     * signal; the task transitions to {@code CANCELLED}.
     */
    void cancel(String taskId);

    /** Returns the current status of a task, or {@code null} if not found. */
    NopTaskRecord getStatus(String taskId);
}
