// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.labacacia.nps.telemetry.NopInstrumentation;

/**
 * NOP orchestrator instrumentation façade. Previously a no-op placeholder; now
 * delegates to the dependency-free {@link NopInstrumentation} instruments so the
 * orchestrator's existing call-sites emit real counters/histograms that tests
 * can read in-process. Metric names match the .NET {@code NopTelemetry} exactly.
 */
public final class NopTelemetry {
    private NopTelemetry() {}

    public static void taskCompleted() { NopInstrumentation.TASKS_COMPLETED.add(); }

    public static void taskFailed()    { NopInstrumentation.TASKS_FAILED.add(); }

    public static void recordTaskDuration(double ms, String outcome) {
        NopInstrumentation.TASK_DURATION_MS.record(ms);
    }

    public static void nodeRetry() { NopInstrumentation.NODE_RETRIES.add(); }

    public static void recordNodeDuration(double ms, String outcome) {
        NopInstrumentation.NODE_DURATION_MS.record(ms);
    }
}
