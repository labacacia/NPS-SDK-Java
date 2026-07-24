// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.telemetry;

/**
 * Distribution instrument recording a stream of {@code double} measurements
 * (e.g. durations in ms). Thread-safe. Exposes count / sum / min / max for
 * in-memory test assertions. Created via {@link Meter#histogram}.
 */
public final class Histogram {

    private final String name;
    private final String unit;
    private final String description;

    private final Object gate = new Object();
    private long count;
    private double sum;
    private double min = Double.NaN;
    private double max = Double.NaN;

    Histogram(String name, String unit, String description) {
        this.name = name;
        this.unit = unit;
        this.description = description;
    }

    /** Records a single measurement. */
    public void record(double value) {
        synchronized (gate) {
            count++;
            sum += value;
            if (Double.isNaN(min) || value < min) min = value;
            if (Double.isNaN(max) || value > max) max = value;
        }
    }

    /** Number of recorded measurements. */
    public long count() { synchronized (gate) { return count; } }

    /** Sum of all recorded measurements. */
    public double sum() { synchronized (gate) { return sum; } }

    /** Arithmetic mean, or {@code NaN} when no measurement has been recorded. */
    public double mean() { synchronized (gate) { return count == 0 ? Double.NaN : sum / count; } }

    /** Smallest recorded measurement, or {@code NaN}. */
    public double min() { synchronized (gate) { return min; } }

    /** Largest recorded measurement, or {@code NaN}. */
    public double max() { synchronized (gate) { return max; } }

    public String name()        { return name; }
    public String unit()        { return unit; }
    public String description() { return description; }
}
