// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single tracing span, the tracing analogue of a .NET {@code Activity}.
 * {@link AutoCloseable} so callers can bracket work with try-with-resources;
 * {@link #close()} stamps the end time and notifies the owning {@link Tracer}.
 */
public final class Span implements AutoCloseable {

    /** Terminal status of a span. */
    public enum Status { UNSET, OK, ERROR }

    private final Tracer tracer;
    private final String name;
    private final long startNanos;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private Status status = Status.UNSET;
    private long endNanos = -1;

    Span(Tracer tracer, String name) {
        this.tracer = tracer;
        this.name = name;
        this.startNanos = System.nanoTime();
    }

    /** Sets an attribute (last write wins). Returns this for chaining. */
    public Span setAttribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    /** Marks the span successful. */
    public Span setOk() { this.status = Status.OK; return this; }

    /** Marks the span failed and records the error message as an attribute. */
    public Span setError(String message) {
        this.status = Status.ERROR;
        attributes.put("error.message", message);
        return this;
    }

    public String name()                    { return name; }
    public Status status()                  { return status; }
    public Map<String, Object> attributes() { return attributes; }

    /** Elapsed duration in nanoseconds; valid once the span has ended. */
    public long durationNanos() {
        long end = endNanos < 0 ? System.nanoTime() : endNanos;
        return end - startNanos;
    }

    /** Elapsed duration in milliseconds. */
    public double durationMillis() { return durationNanos() / 1_000_000.0; }

    /** Whether {@link #close()} has run. */
    public boolean ended() { return endNanos >= 0; }

    @Override
    public void close() {
        if (endNanos >= 0) return;   // idempotent
        endNanos = System.nanoTime();
        if (status == Status.UNSET) status = Status.OK;
        tracer.onSpanEnded(this);
    }
}
