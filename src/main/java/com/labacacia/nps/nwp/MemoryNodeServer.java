// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory Node server using the JDK built-in {@link HttpHandler}
 * (com.sun.net.httpserver, zero third-party deps). Port of the .NET
 * {@code MemoryNodeMiddleware} (NPS-2 §2.1, §5). Sub-paths: {@code /.nwm},
 * {@code /.schema}, {@code /query}.
 *
 * <p>SQL providers and the NWP filter→SQL translation are deferred; this server
 * keeps the {@link Provider} interface clean and ships an
 * {@link InMemoryProvider} that filters/paginates in memory for tests.</p>
 */
public final class MemoryNodeServer implements HttpHandler {

    private static final ObjectMapper MAPPER =
        new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    // ── Schema ─────────────────────────────────────────────────────────────────

    /** Describes a single queryable field in a Memory Node schema (NPS-2 §4.1). */
    public static final class Field {
        public String name;
        public String type;         // string|number|boolean|datetime|object
        public String description;
        public boolean nullable = true;
        public String columnName;

        public Field() {}
        public Field(String name, String type) { this.name = name; this.type = type; }
        public Field(String name, String type, boolean nullable) { this.name = name; this.type = type; this.nullable = nullable; }
    }

    /** Schema definition for a Memory Node. */
    public static final class Schema {
        public String tableName;
        public String primaryKey;
        public List<Field> fields = new ArrayList<>();

        public Schema() {}
        public Schema(String tableName, String primaryKey, List<Field> fields) {
            this.tableName = tableName; this.primaryKey = primaryKey; this.fields = fields;
        }

        public Field getField(String name) {
            for (Field f : fields) if (f.name.equalsIgnoreCase(name)) return f;
            return null;
        }
        public boolean hasField(String name) { return getField(name) != null; }
    }

    /** Configuration for a single Memory Node (NPS-2 §2.1). */
    public static final class Options {
        public String nodeId;
        public String displayName;
        public Schema schema;
        public int defaultLimit = 20;
        public int maxLimit = 1000;
        public boolean requireAuth = false;
        public int defaultTokenBudget = 0;
        public String pathPrefix;
    }

    /** Result of a Memory Node query (NPS-2 §5). */
    public static final class QueryResult {
        public List<Map<String, Object>> rows;
        public String nextCursor;

        public QueryResult(List<Map<String, Object>> rows) { this.rows = rows; }
        public QueryResult(List<Map<String, Object>> rows, String nextCursor) { this.rows = rows; this.nextCursor = nextCursor; }
    }

    /** Raised by a provider to surface a filter validation failure. */
    public static final class FilterException extends RuntimeException {
        public final String nwpErrorCode;
        public FilterException(String nwpErrorCode, String message) { super(message); this.nwpErrorCode = nwpErrorCode; }
    }

    /** Provider seam over a data store backing a Memory Node. */
    public interface Provider {
        QueryResult query(QueryFrame frame, Schema schema, Options options) throws Exception;
    }

    // ── State ────────────────────────────────────────────────────────────────────

    private final Options opt;
    private final String prefix;
    private final Provider provider;
    private final byte[] nwmJson;
    private final byte[] schemaJson;
    private final String anchorId;

    public MemoryNodeServer(Options opt, Provider provider) {
        this.opt = opt;
        this.prefix = opt.pathPrefix.replaceAll("/+$", "");
        this.provider = provider;
        try {
            this.schemaJson = MAPPER.writeValueAsBytes(buildSchema());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(schemaJson);
            this.anchorId = "sha256:" + HexFormat.of().formatHex(digest);
            this.nwmJson = MAPPER.writeValueAsBytes(buildManifest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── HttpHandler ─────────────────────────────────────────────────────────────

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!path.startsWith(prefix)) { ex.sendResponseHeaders(404, -1); return; }
            String sub = path.substring(prefix.length());
            String method = ex.getRequestMethod();

            if (opt.requireAuth && ex.getRequestHeaders().getFirst(NwpHttpHeaders.AGENT) == null) {
                writeError(ex, 401, "NPS-CLIENT-UNAUTHORIZED", NwpErrorCodes.NWP_AUTH_NID_SCOPE_VIOLATION,
                    "X-NWP-Agent header is required.");
                return;
            }

            switch (sub) {
                case "/.nwm", "/.nwm/" -> {
                    ex.getResponseHeaders().set("Content-Type", NwpHttpHeaders.MIME_MANIFEST);
                    ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "memory");
                    writeRaw(ex, 200, nwmJson);
                }
                case "/.schema", "/.schema/" -> {
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.getResponseHeaders().set(NwpHttpHeaders.SCHEMA, anchorId);
                    writeRaw(ex, 200, schemaJson);
                }
                case "/query", "/query/" -> {
                    if (!method.equals("POST")) { ex.sendResponseHeaders(405, -1); return; }
                    handleQuery(ex);
                }
                default -> ex.sendResponseHeaders(404, -1);
            }
        } finally {
            ex.close();
        }
    }

    // ── /query ─────────────────────────────────────────────────────────────────

    private void handleQuery(HttpExchange ex) throws IOException {
        QueryFrame frame;
        try {
            frame = QueryFrame.fromDict(readJson(ex));
        } catch (Exception e) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_QUERY_FILTER_INVALID, e.getMessage());
            return;
        }

        int budget = parseBudget(ex);

        QueryResult result;
        try {
            result = provider.query(frame, opt.schema, opt);
        } catch (FilterException fe) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", fe.nwpErrorCode, fe.getMessage());
            return;
        } catch (Exception e) {
            writeError(ex, 500, "NPS-SERVER-INTERNAL", NwpErrorCodes.NWP_NODE_UNAVAILABLE, "Internal server error.");
            return;
        }

        List<Map<String, Object>> rows = result.rows != null ? result.rows : List.of();
        int tokenEst;
        try {
            tokenEst = CgnHelper.estimateRows(rows);
        } catch (Exception e) {
            tokenEst = 0;
        }

        // Budget enforcement — trim rows to fit.
        List<Map<String, Object>> out = rows;
        if (budget > 0 && tokenEst > budget) {
            out = new ArrayList<>();
            int acc = 0;
            for (Map<String, Object> row : rows) {
                int tok;
                try { tok = CgnHelper.estimateJson(row); } catch (Exception e) { tok = 0; }
                if (acc + tok > budget) break;
                out.add(row);
                acc += tok;
            }
            tokenEst = acc;
        }

        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("anchor_ref", anchorId);
        caps.put("count", out.size());
        caps.put("data", out);
        if (result.nextCursor != null) caps.put("next_cursor", result.nextCursor);
        caps.put("token_est", tokenEst);

        ex.getResponseHeaders().set("Content-Type", NwpHttpHeaders.MIME_CAPSULE);
        ex.getResponseHeaders().set(NwpHttpHeaders.SCHEMA, anchorId);
        ex.getResponseHeaders().set(NwpHttpHeaders.TOKENS, String.valueOf(tokenEst));
        ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "memory");
        writeRaw(ex, 200, MAPPER.writeValueAsBytes(caps));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private int parseBudget(HttpExchange ex) {
        String raw = ex.getRequestHeaders().getFirst(NwpHttpHeaders.BUDGET);
        if (raw != null) {
            try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private void writeRaw(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void writeError(HttpExchange ex, int status, String npsStatus, String errorCode, String message) throws IOException {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("status", npsStatus);
        env.put("error", errorCode);
        if (message != null) env.put("message", message);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        writeRaw(ex, status, MAPPER.writeValueAsBytes(env));
    }

    private Map<String, Object> buildSchema() {
        Map<String, Object> s = new LinkedHashMap<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Field f : opt.schema.fields) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", f.name);
            fm.put("type", f.type);
            fm.put("nullable", f.nullable);
            fields.add(fm);
        }
        s.put("fields", fields);
        return s;
    }

    private Map<String, Object> buildManifest() {
        String baseUrl = prefix;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nwp", "0.4");
        m.put("node_id", opt.nodeId);
        m.put("node_type", "memory");
        if (opt.displayName != null) m.put("display_name", opt.displayName);
        m.put("wire_formats", List.of("ncp-capsule", "json"));
        m.put("preferred_format", "json");
        m.put("schema_anchors", Map.of("default", anchorId));
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("query", true);
        caps.put("stream", true);
        caps.put("token_budget_hint", true);
        m.put("capabilities", caps);
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("required", opt.requireAuth);
        auth.put("identity_type", opt.requireAuth ? "nip-cert" : "none");
        m.put("auth", auth);
        m.put("endpoints", Map.of(
            "query", baseUrl + "/query",
            "stream", baseUrl + "/stream",
            "schema", baseUrl + "/.schema"));
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length == 0) return new LinkedHashMap<>();
        return MAPPER.readValue(raw, Map.class);
    }

    // ── In-memory provider (tests / demos) ─────────────────────────────────────

    /**
     * In-memory provider with equality/comparison filtering and offset/limit paging.
     * Not a SQL substitute — the SQL providers + filter translation are deferred.
     */
    public static final class InMemoryProvider implements Provider {
        private final List<Map<String, Object>> rows;

        public InMemoryProvider(List<Map<String, Object>> rows) { this.rows = rows; }

        @Override
        public QueryResult query(QueryFrame frame, Schema schema, Options options) {
            List<Map<String, Object>> matched = new ArrayList<>();
            Map<String, Object> filter = frame.filter();
            for (Map<String, Object> row : rows) {
                if (matches(row, filter, schema)) matched.add(row);
            }

            int offset = frame.offset() != null ? Math.max(0, frame.offset()) : 0;
            int limit = frame.limit() != null ? frame.limit() : options.defaultLimit;
            if (limit > options.maxLimit) limit = options.maxLimit;
            if (limit < 0) limit = 0;

            int from = Math.min(offset, matched.size());
            int to = Math.min(from + limit, matched.size());
            List<Map<String, Object>> page = new ArrayList<>(matched.subList(from, to));
            String nextCursor = to < matched.size() ? String.valueOf(to) : null;
            return new QueryResult(page, nextCursor);
        }

        @SuppressWarnings("unchecked")
        private boolean matches(Map<String, Object> row, Map<String, Object> filter, Schema schema) {
            if (filter == null || filter.isEmpty()) return true;
            for (Map.Entry<String, Object> e : filter.entrySet()) {
                String field = e.getKey();
                if (schema != null && !schema.hasField(field)) {
                    throw new FilterException(NwpErrorCodes.NWP_QUERY_FIELD_UNKNOWN,
                        "unknown filter field '" + field + "'.");
                }
                Object cond = e.getValue();
                Object actual = row.get(field);
                if (cond instanceof Map<?, ?> ops) {
                    for (Map.Entry<?, ?> op : ops.entrySet()) {
                        if (!applyOp(String.valueOf(op.getKey()), actual, op.getValue())) return false;
                    }
                } else {
                    if (!java.util.Objects.equals(actual, cond)) return false;
                }
            }
            return true;
        }

        private boolean applyOp(String op, Object actual, Object expected) {
            switch (op) {
                case "$eq":  return java.util.Objects.equals(actual, expected);
                case "$ne":  return !java.util.Objects.equals(actual, expected);
                case "$gt":  return compare(actual, expected) > 0;
                case "$gte": return compare(actual, expected) >= 0;
                case "$lt":  return compare(actual, expected) < 0;
                case "$lte": return compare(actual, expected) <= 0;
                case "$in":
                    if (expected instanceof List<?> list) return list.contains(actual);
                    return false;
                case "$contains":
                    return actual != null && expected != null
                        && actual.toString().contains(expected.toString());
                default:
                    throw new FilterException(NwpErrorCodes.NWP_QUERY_FILTER_INVALID,
                        "unsupported filter operator '" + op + "'.");
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private int compare(Object a, Object b) {
            if (a == null || b == null) return a == b ? 0 : (a == null ? -1 : 1);
            if (a instanceof Number na && b instanceof Number nb) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            if (a instanceof Comparable ca && a.getClass().isInstance(b)) {
                return ca.compareTo(b);
            }
            return a.toString().compareTo(b.toString());
        }
    }
}
