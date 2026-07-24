// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.daemon.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight Prometheus-compatible counter / gauge registry backing the
 * {@code /metrics} endpoint. Port of the .NET {@code MetricsRegistry}: same
 * exposition format, same {@code # HELP} / {@code # TYPE} headers, same label
 * escaping. Designed for the small, known-up-front daemon metric set.
 */
public final class MetricsRegistry {

    private final List<MetricEntry> entries = new ArrayList<>();
    private final Object gate = new Object();

    /** Registers a counter (monotonic, accumulates per labelset). */
    public Counter registerCounter(String name, String help, String... labelNames) {
        Counter c = new Counter(name, help, labelNames);
        synchronized (gate) { entries.add(c); }
        return c;
    }

    /** Registers a gauge (sampled, may go up and down). */
    public Gauge registerGauge(String name, String help) {
        Gauge g = new Gauge(name, help);
        synchronized (gate) { entries.add(g); }
        return g;
    }

    /** Writes the registry in Prometheus exposition format to {@code sb}. */
    public void writeTo(StringBuilder sb) {
        MetricEntry[] snap;
        synchronized (gate) { snap = entries.toArray(new MetricEntry[0]); }
        for (MetricEntry e : snap) e.writeTo(sb);
    }

    /** Renders the full exposition text. */
    public String render() {
        StringBuilder sb = new StringBuilder(1024);
        writeTo(sb);
        return sb.toString();
    }

    /** Base type for a registered metric. */
    public abstract static class MetricEntry {
        public abstract void writeTo(StringBuilder sb);
    }

    /** Monotonic counter; one cell per label-value tuple. */
    public static final class Counter extends MetricEntry {

        // ASCII Unit Separator — safe in label values.
        private static final char CELL_SEPARATOR = '';

        private final String name;
        private final String help;
        private final String[] labels;
        private final ConcurrentHashMap<String, CounterValue> cells = new ConcurrentHashMap<>();

        Counter(String name, String help, String[] labels) {
            this.name = name;
            this.help = help;
            this.labels = labels;
            if (labels.length == 0) cells.put("", new CounterValue());
        }

        /** Increment by 1 with no labels. */
        public void inc() { inc(1.0, new String[0]); }

        /** Increment by {@code by} with no labels. */
        public void inc(double by) { inc(by, new String[0]); }

        /** Increment a labelled cell by 1. */
        public void inc(String... labelValues) { inc(1.0, labelValues); }

        public void inc(double by, String... labelValues) {
            String key = cellKey(labelValues);
            cells.computeIfAbsent(key, k -> new CounterValue()).add(by);
        }

        @Override
        public void writeTo(StringBuilder sb) {
            sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
            sb.append("# TYPE ").append(name).append(" counter").append('\n');
            for (Map.Entry<String, CounterValue> e : cells.entrySet()) {
                sb.append(name);
                if (labels.length > 0) {
                    sb.append('{');
                    String[] parts = e.getKey().split(String.valueOf(CELL_SEPARATOR), -1);
                    for (int i = 0; i < labels.length; i++) {
                        if (i > 0) sb.append(',');
                        String v = i < parts.length ? parts[i] : "";
                        sb.append(labels[i]).append("=\"").append(escapeLabel(v)).append('"');
                    }
                    sb.append('}');
                }
                sb.append(' ').append(fmt(e.getValue().value())).append('\n');
            }
        }

        private String cellKey(String[] labelValues) {
            if (labels.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < labels.length; i++) {
                if (i > 0) sb.append(CELL_SEPARATOR);
                sb.append(i < labelValues.length ? labelValues[i] : "");
            }
            return sb.toString();
        }

        private static final class CounterValue {
            private double v;
            private final Object gate = new Object();
            double value() { synchronized (gate) { return v; } }
            void add(double by) { synchronized (gate) { v += by; } }
        }
    }

    /** Single-valued gauge; thread-safe. */
    public static final class Gauge extends MetricEntry {

        private final String name;
        private final String help;
        private final AtomicLong bits = new AtomicLong(Double.doubleToLongBits(0.0));

        Gauge(String name, String help) {
            this.name = name;
            this.help = help;
        }

        public void set(double value) { bits.set(Double.doubleToLongBits(value)); }
        public void inc() { add(1); }
        public void dec() { add(-1); }

        public void add(double by) {
            while (true) {
                long current = bits.get();
                double curD = Double.longBitsToDouble(current);
                long newBits = Double.doubleToLongBits(curD + by);
                if (bits.compareAndSet(current, newBits)) return;
            }
        }

        public double value() { return Double.longBitsToDouble(bits.get()); }

        @Override
        public void writeTo(StringBuilder sb) {
            sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
            sb.append("# TYPE ").append(name).append(" gauge").append('\n');
            sb.append(name).append(' ').append(fmt(value())).append('\n');
        }
    }

    private static String escapeLabel(String v) {
        if (v.indexOf('\\') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0) return v;
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Formats a double like .NET's {@code "0.################"} — no trailing zeros. */
    private static String fmt(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        String s = String.format(Locale.ROOT, "%.16f", d);
        // Trim trailing zeros (and a trailing dot) to mimic the .NET pattern.
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }
}
