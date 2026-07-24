// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.telemetry;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory and registry for {@link Counter} and {@link Histogram} instruments,
 * the metrics half of the SDK's dependency-free telemetry abstraction. Analogue
 * of the .NET {@code System.Diagnostics.Metrics.Meter}: instruments are created
 * once and cached by name, and the meter itself is the in-memory reader used by
 * tests (iterate {@link #counters()} / {@link #histograms()}).
 */
public final class Meter {

    private final String name;
    private final String version;
    private final ConcurrentHashMap<String, Counter>   counters   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Histogram> histograms = new ConcurrentHashMap<>();

    public Meter(String name, String version) {
        this.name = name;
        this.version = version;
    }

    /** Returns (creating on first use) the named counter. */
    public Counter counter(String name, String unit, String description) {
        return counters.computeIfAbsent(name, n -> new Counter(n, unit, description));
    }

    /** Returns (creating on first use) the named histogram. */
    public Histogram histogram(String name, String unit, String description) {
        return histograms.computeIfAbsent(name, n -> new Histogram(n, unit, description));
    }

    /** Existing counter by name, or null. */
    public Counter getCounter(String name) { return counters.get(name); }

    /** Existing histogram by name, or null. */
    public Histogram getHistogram(String name) { return histograms.get(name); }

    /** All registered counters. */
    public Collection<Counter> counters() { return counters.values(); }

    /** All registered histograms. */
    public Collection<Histogram> histograms() { return histograms.values(); }

    public String name()    { return name; }
    public String version() { return version; }
}
