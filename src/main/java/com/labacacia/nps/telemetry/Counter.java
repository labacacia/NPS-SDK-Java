// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.telemetry;

import java.util.concurrent.atomic.LongAdder;

/**
 * Monotonic counter instrument. Thread-safe. Part of the SDK's lightweight,
 * dependency-free telemetry abstraction (no OpenTelemetry on the classpath).
 * Created via {@link Meter#counter}.
 */
public final class Counter {

    private final String name;
    private final String unit;
    private final String description;
    private final LongAdder value = new LongAdder();

    Counter(String name, String unit, String description) {
        this.name = name;
        this.unit = unit;
        this.description = description;
    }

    /** Increment by 1. */
    public void add() { value.add(1); }

    /** Increment by {@code delta} (must be ≥ 0 for a well-formed counter). */
    public void add(long delta) { value.add(delta); }

    /** Current accumulated value. */
    public long value() { return value.sum(); }

    public String name()        { return name; }
    public String unit()        { return unit; }
    public String description() { return description; }
}
