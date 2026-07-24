// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.labacacia.nps.nop.CompensationPolicy;
import com.labacacia.nps.nop.NopConstants;
import com.labacacia.nps.nop.models.TaskContext;
import com.labacacia.nps.nop.models.TaskDag;
import com.labacacia.nps.nop.models.TaskPriority;

/**
 * Typed task definition consumed by {@link NopOrchestrator} (NPS-5 §3.1).
 *
 * <p>This is the engine-facing analog of the .NET {@code TaskFrame} record. It carries
 * a strongly-typed {@link TaskDag} rather than the untyped {@code Map} wire form used by
 * {@code com.labacacia.nps.nop.TaskFrame} (which remains the on-the-wire representation).
 *
 * @param taskId             Task unique identifier (UUID v4).
 * @param dag                DAG definition describing sub-tasks and their execution order.
 * @param timeoutMs          Overall task timeout in ms. Default 30000, max 3600000 (1 hour).
 * @param maxRetries         Global max retries per node before the task fails. Default 2.
 * @param priority           Task priority: {@code "low"}, {@code "normal"} (default), or {@code "high"}.
 * @param callbackUrl        HTTPS callback URL for completion/failure notification (NPS-5 §8.4).
 * @param callbackSecret     Shared secret for HMAC-SHA256 signing of callback POSTs (NPS-5 §8.4).
 * @param preflight          When true, run resource pre-flight checks before execution (NPS-5 §4).
 * @param context            Transparent context propagated to all sub-tasks (NPS-5 §3.1.2).
 * @param requestId          Request tracking ID (UUID v4).
 * @param delegateDepth      Current delegation chain depth (0 = root task).
 * @param compensationPolicy Saga compensation policy (NPS-5 §3.1.6). Default {@code "best_effort"}.
 */
public record NopTask(
    String taskId,
    TaskDag dag,
    long timeoutMs,
    int maxRetries,
    String priority,
    String callbackUrl,
    String callbackSecret,
    boolean preflight,
    TaskContext context,
    String requestId,
    int delegateDepth,
    String compensationPolicy) {

    public NopTask {
        if (priority == null) priority = TaskPriority.NORMAL;
        if (compensationPolicy == null) compensationPolicy = CompensationPolicy.BEST_EFFORT;
    }

    /** Minimal factory with NPS-5 defaults (30s timeout, 2 retries, best-effort). */
    public static NopTask of(String taskId, TaskDag dag) {
        return new Builder(taskId, dag).build();
    }

    /** Fluent builder for {@link NopTask}. */
    public static final class Builder {
        private final String taskId;
        private final TaskDag dag;
        private long timeoutMs = NopConstants.DEFAULT_TIMEOUT_MS;
        private int maxRetries = 2;
        private String priority = TaskPriority.NORMAL;
        private String callbackUrl;
        private String callbackSecret;
        private boolean preflight;
        private TaskContext context;
        private String requestId;
        private int delegateDepth;
        private String compensationPolicy = CompensationPolicy.BEST_EFFORT;

        public Builder(String taskId, TaskDag dag) {
            this.taskId = taskId;
            this.dag = dag;
        }

        public Builder timeoutMs(long v)          { this.timeoutMs = v; return this; }
        public Builder maxRetries(int v)          { this.maxRetries = v; return this; }
        public Builder priority(String v)         { this.priority = v; return this; }
        public Builder callbackUrl(String v)      { this.callbackUrl = v; return this; }
        public Builder callbackSecret(String v)   { this.callbackSecret = v; return this; }
        public Builder preflight(boolean v)       { this.preflight = v; return this; }
        public Builder context(TaskContext v)     { this.context = v; return this; }
        public Builder requestId(String v)        { this.requestId = v; return this; }
        public Builder delegateDepth(int v)       { this.delegateDepth = v; return this; }
        public Builder compensationPolicy(String v) { this.compensationPolicy = v; return this; }

        public NopTask build() {
            return new NopTask(taskId, dag, timeoutMs, maxRetries, priority, callbackUrl,
                callbackSecret, preflight, context, requestId, delegateDepth, compensationPolicy);
        }
    }
}
