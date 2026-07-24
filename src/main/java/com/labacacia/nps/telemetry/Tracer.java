// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.telemetry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Span factory and in-memory span sink, the tracing analogue of a .NET
 * {@code ActivitySource}. Ended spans are retained in {@link #endedSpans()} so
 * tests can assert on span names, statuses, and attributes without wiring an
 * external exporter.
 */
public final class Tracer {

    private final String name;
    private final String version;
    private final CopyOnWriteArrayList<Span> ended = new CopyOnWriteArrayList<>();

    public Tracer(String name, String version) {
        this.name = name;
        this.version = version;
    }

    /** Starts a new span. Close it (try-with-resources) to record its end. */
    public Span startSpan(String spanName) {
        return new Span(this, spanName);
    }

    void onSpanEnded(Span span) {
        ended.add(span);
    }

    /** All spans that have ended, in completion order. */
    public List<Span> endedSpans() {
        return List.copyOf(ended);
    }

    /** Clears the retained span buffer (test isolation). */
    public void reset() {
        ended.clear();
    }

    public String name()    { return name; }
    public String version() { return version; }
}
