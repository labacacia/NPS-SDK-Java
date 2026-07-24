// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.daemon.observability;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

import java.io.PrintStream;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Single-line JSON structured logger for NPS daemons. Portable port of the .NET
 * {@code JsonStructuredLogging} / {@code NpsJsonConsoleFormatter}: each record
 * carries {@code timestamp} (ISO-8601 UTC), {@code level}, {@code msg},
 * {@code logger}, and optionally {@code trace_id} and {@code exception}. The
 * minimum level honours the {@code NPS_LOG_LEVEL} environment variable
 * (trace / debug / info / warn / error / critical / none, case-insensitive).
 */
public final class JsonStructuredLogger {

    /** Env var that overrides the default minimum log level. */
    public static final String LOG_LEVEL_ENV_VAR = "NPS_LOG_LEVEL";

    /** Log severity levels, ordered least → most severe (mirrors .NET LogLevel). */
    public enum Level {
        TRACE("trace"), DEBUG("debug"), INFO("info"),
        WARN("warn"), ERROR("error"), CRITICAL("critical"), NONE("none");

        private final String wire;
        Level(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    private static final JsonStringEncoder ENC = JsonStringEncoder.getInstance();

    private final String logger;
    private final Level minLevel;
    private final PrintStream out;

    public JsonStructuredLogger(String logger, Level minLevel, PrintStream out) {
        this.logger = logger;
        this.minLevel = minLevel;
        this.out = out;
    }

    /** Creates a logger writing to {@code System.out}, resolving the level from the env var. */
    public static JsonStructuredLogger forConsole(String logger, Level fallback) {
        return new JsonStructuredLogger(logger, resolveLogLevel(fallback), System.out);
    }

    /** Resolves {@code NPS_LOG_LEVEL}, falling back to {@code fallback}. */
    public static Level resolveLogLevel(Level fallback) {
        String raw = System.getenv(LOG_LEVEL_ENV_VAR);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Level.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // Accept the wire spellings too (e.g. "information" is not one; .NET
            // maps Information→info — we only match the enum names and wire tags).
            for (Level l : Level.values()) {
                if (l.wire.equalsIgnoreCase(raw.trim())) return l;
            }
            return fallback;
        }
    }

    public boolean isEnabled(Level level) {
        return level.ordinal() >= minLevel.ordinal() && level != Level.NONE;
    }

    public void log(Level level, String message) { log(level, message, null, null); }

    public void log(Level level, String message, Throwable ex) { log(level, message, ex, null); }

    /**
     * Emits one JSON record. {@code traceId} is written as {@code trace_id} when
     * non-null; {@code ex} is stringified into {@code exception} when present.
     */
    public void log(Level level, String message, Throwable ex, String traceId) {
        if (!isEnabled(level)) return;

        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "timestamp", Instant.now().toString()); sb.append(',');
        field(sb, "level", level.wire());                  sb.append(',');
        field(sb, "msg", message == null ? "" : message);  sb.append(',');
        field(sb, "logger", logger);
        if (traceId != null && !traceId.isEmpty()) {
            sb.append(',');
            field(sb, "trace_id", traceId);
        }
        if (ex != null) {
            sb.append(',');
            field(sb, "exception", stringify(ex));
        }
        sb.append('}');
        out.println(sb);
    }

    public void trace(String m)   { log(Level.TRACE, m); }
    public void debug(String m)   { log(Level.DEBUG, m); }
    public void info(String m)    { log(Level.INFO, m); }
    public void warn(String m)    { log(Level.WARN, m); }
    public void error(String m)   { log(Level.ERROR, m); }
    public void error(String m, Throwable ex) { log(Level.ERROR, m, ex); }

    private static void field(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"")
          .append(new String(ENC.quoteAsString(value)))
          .append('"');
    }

    private static String stringify(Throwable ex) {
        StringBuilder sb = new StringBuilder(ex.toString());
        for (StackTraceElement el : ex.getStackTrace()) {
            sb.append("\n\tat ").append(el);
        }
        return sb.toString();
    }

    /** Parsed record — helper for tests that assert on emitted JSON. */
    public record Record(Map<String, Object> fields) {}
}
