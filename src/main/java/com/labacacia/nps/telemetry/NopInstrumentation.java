// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.telemetry;

/**
 * NOP-layer telemetry names and instruments. Port of the .NET
 * {@code NopInstrumentation} / {@code NopTelemetry} pair. Public name constants
 * are preserved for cross-SDK pipeline alignment; the instruments are backed by
 * the dependency-free {@link Meter} / {@link Tracer} abstraction.
 */
public final class NopInstrumentation {

    private NopInstrumentation() {}

    /** Tracer (ActivitySource) name for NOP orchestration spans. */
    public static final String TRACER_NAME = "nps.nop";

    /** Meter name for NOP orchestration metrics. */
    public static final String METER_NAME = "nps.nop";

    /** Instrumentation library version. */
    public static final String VERSION = "1.0.0";

    /** Shared meter for all NOP metrics. */
    public static final Meter METER = new Meter(METER_NAME, VERSION);

    /** Shared tracer for all NOP spans. */
    public static final Tracer TRACER = new Tracer(TRACER_NAME, VERSION);

    /** NOP task total execution duration (ms). */
    public static final Histogram TASK_DURATION_MS = METER.histogram(
        "nps.nop.task.duration_ms", "ms", "NOP task total execution duration");

    /** NOP DAG node execution duration (ms). */
    public static final Histogram NODE_DURATION_MS = METER.histogram(
        "nps.nop.node.duration_ms", "ms", "NOP DAG node execution duration");

    /** NOP DAG node retry attempts. */
    public static final Counter NODE_RETRIES = METER.counter(
        "nps.nop.node.retries", "{retries}", "NOP DAG node retry attempts");

    /** NOP tasks completed successfully. */
    public static final Counter TASKS_COMPLETED = METER.counter(
        "nps.nop.tasks.completed", "{tasks}", "NOP tasks completed successfully");

    /** NOP tasks that failed or timed out. */
    public static final Counter TASKS_FAILED = METER.counter(
        "nps.nop.tasks.failed", "{tasks}", "NOP tasks that failed or timed out");
}
