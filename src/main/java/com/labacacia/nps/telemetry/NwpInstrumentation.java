// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.telemetry;

/**
 * NWP-layer telemetry names and instruments. Port of the .NET
 * {@code NwpInstrumentation} / {@code NwpTelemetry} pair: the public name
 * constants are unchanged so metric/trace pipelines line up across SDKs, and
 * the instruments (previously B2/B6 no-op placeholders) are now backed by the
 * dependency-free {@link Meter} / {@link Tracer} abstraction.
 */
public final class NwpInstrumentation {

    private NwpInstrumentation() {}

    /** Tracer (ActivitySource) name for NWP frame-processing spans. */
    public static final String TRACER_NAME = "nps.nwp";

    /** Meter name for NWP frame metrics. */
    public static final String METER_NAME = "nps.nwp";

    /** Instrumentation library version. */
    public static final String VERSION = "1.0.0";

    /** Shared meter for all NWP metrics. */
    public static final Meter METER = new Meter(METER_NAME, VERSION);

    /** Shared tracer for all NWP spans. */
    public static final Tracer TRACER = new Tracer(TRACER_NAME, VERSION);

    /** Total NWP frames processed. */
    public static final Counter FRAMES_PROCESSED = METER.counter(
        "nps.frames.processed", "{frames}", "Total NWP frames processed");

    /** NWP frame processing duration (ms). */
    public static final Histogram FRAME_DURATION_MS = METER.histogram(
        "nps.frames.processing_ms", "ms", "NWP frame processing duration");

    /** CGN units consumed in NWP responses. */
    public static final Counter CGN_CONSUMED = METER.counter(
        "nps.cgn.consumed", "{cgn}", "CGN units consumed in NWP responses");

    /** NWP frames that returned an error response. */
    public static final Counter FRAME_ERRORS = METER.counter(
        "nps.frames.errors", "{frames}", "NWP frames that returned an error response");
}
