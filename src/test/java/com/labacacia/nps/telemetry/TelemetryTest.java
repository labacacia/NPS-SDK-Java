// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Lightweight metrics + tracing abstraction with in-memory readers. */
class TelemetryTest {

    @Test void counterAccumulates() {
        Meter m = new Meter("test", "1.0.0");
        Counter c = m.counter("nps.frames.processed", "{frames}", "frames");
        c.add();
        c.add(4);
        assertEquals(5, c.value());
        assertSame(c, m.getCounter("nps.frames.processed"), "counters are cached by name");
    }

    @Test void histogramStats() {
        Meter m = new Meter("test", "1.0.0");
        Histogram h = m.histogram("dur", "ms", "d");
        h.record(10);
        h.record(20);
        h.record(30);
        assertEquals(3, h.count());
        assertEquals(60.0, h.sum());
        assertEquals(20.0, h.mean());
        assertEquals(10.0, h.min());
        assertEquals(30.0, h.max());
    }

    @Test void histogramEmptyMeanIsNaN() {
        Histogram h = new Meter("t", "1").histogram("x", "ms", "");
        assertTrue(Double.isNaN(h.mean()));
    }

    @Test void spanRecordsIntoTracer() {
        Tracer t = new Tracer("test", "1.0.0");
        try (Span s = t.startSpan("op")) {
            s.setAttribute("k", "v").setOk();
            assertFalse(s.ended());
        }
        assertEquals(1, t.endedSpans().size());
        Span done = t.endedSpans().get(0);
        assertEquals("op", done.name());
        assertEquals(Span.Status.OK, done.status());
        assertEquals("v", done.attributes().get("k"));
        assertTrue(done.durationNanos() >= 0);
    }

    @Test void spanErrorStatus() {
        Tracer t = new Tracer("test", "1.0.0");
        Span s = t.startSpan("op");
        s.setError("boom");
        s.close();
        assertEquals(Span.Status.ERROR, t.endedSpans().get(0).status());
        assertEquals("boom", t.endedSpans().get(0).attributes().get("error.message"));
    }

    @Test void closeIsIdempotent() {
        Tracer t = new Tracer("test", "1.0.0");
        Span s = t.startSpan("op");
        s.close();
        s.close();
        assertEquals(1, t.endedSpans().size());
    }

    @Test void nwpInstrumentationNamesMatchDotNet() {
        assertEquals("nps.nwp", NwpInstrumentation.METER_NAME);
        assertEquals("nps.nwp", NwpInstrumentation.TRACER_NAME);
        assertEquals("nps.frames.processed", NwpInstrumentation.FRAMES_PROCESSED.name());
        assertEquals("nps.frames.processing_ms", NwpInstrumentation.FRAME_DURATION_MS.name());
        assertEquals("nps.cgn.consumed", NwpInstrumentation.CGN_CONSUMED.name());
        assertEquals("nps.frames.errors", NwpInstrumentation.FRAME_ERRORS.name());
    }

    @Test void nopInstrumentationNamesMatchDotNet() {
        assertEquals("nps.nop", NopInstrumentation.METER_NAME);
        assertEquals("nps.nop.task.duration_ms", NopInstrumentation.TASK_DURATION_MS.name());
        assertEquals("nps.nop.node.duration_ms", NopInstrumentation.NODE_DURATION_MS.name());
        assertEquals("nps.nop.node.retries", NopInstrumentation.NODE_RETRIES.name());
        assertEquals("nps.nop.tasks.completed", NopInstrumentation.TASKS_COMPLETED.name());
        assertEquals("nps.nop.tasks.failed", NopInstrumentation.TASKS_FAILED.name());
    }
}
