// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nwp.MemoryNodeServer.Field;
import com.labacacia.nps.nwp.MemoryNodeServer.Options;
import com.labacacia.nps.nwp.MemoryNodeServer.Schema;
import com.labacacia.nps.nwp.NwpErrorCodes;
import com.labacacia.nps.nwp.QueryFrame;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Builds a complete parameterized SELECT query from a {@link QueryFrame},
 * handling field projection, filter, ordering, and cursor-based pagination.
 * Faithful port of the .NET {@code SqlQueryBuilder}; dialect-specific quoting
 * and pagination syntax is injected via {@link DatabaseDialect}.
 */
public final class SqlQueryBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Schema schema;
    private final DatabaseDialect dialect;
    private final NwpFilterTranslator filter;

    public SqlQueryBuilder(Schema schema, DatabaseDialect dialect) {
        this.schema  = schema;
        this.dialect = dialect;
        this.filter  = new NwpFilterTranslator(schema, dialect);
    }

    /** SQL text and its bound parameters. */
    public record Built(String sql, SqlParameters params) {}

    /** Builds the full SELECT query and its parameters. */
    public Built build(QueryFrame frame, Options options) {
        SqlParameters p = new SqlParameters();
        StringBuilder sb = new StringBuilder();

        int requested = frame.limit() == null ? 0 : frame.limit();
        long limit  = Math.min(requested == 0 ? options.defaultLimit : requested, options.maxLimit);
        long offset = decodeCursor(frame.cursor());

        // SELECT
        sb.append("SELECT ").append(buildSelectList(frame.fields()));

        // FROM
        sb.append(" FROM ").append(quoteTable(schema.tableName));

        // WHERE
        String where = filter.translate(frame.filter(), p);
        if (!where.isEmpty())
            sb.append(" WHERE ").append(where);

        // ORDER BY (required for stable pagination)
        if (frame.orderBy() != null && !frame.orderBy().isEmpty()) {
            sb.append(" ORDER BY ").append(buildOrderBy(frame.orderBy()));
        } else {
            sb.append(" ORDER BY ").append(quoteColumn(schema.primaryKey));
        }

        // PAGINATION — dialect-specific syntax
        if (dialect == DatabaseDialect.SQL_SERVER) {
            sb.append(" OFFSET @_offset ROWS FETCH NEXT @_limit ROWS ONLY");
        } else {
            sb.append(" LIMIT @_limit OFFSET @_offset");
        }

        p.add("_limit",  (int) limit);
        p.add("_offset", (int) offset);

        return new Built(sb.toString(), p);
    }

    /** Builds a COUNT(*) query for the same filter (used for cursor validation). */
    public Built buildCount(QueryFrame frame) {
        SqlParameters p = new SqlParameters();
        StringBuilder sb = new StringBuilder();

        sb.append("SELECT COUNT(*) FROM ").append(quoteTable(schema.tableName));

        String where = filter.translate(frame.filter(), p);
        if (!where.isEmpty())
            sb.append(" WHERE ").append(where);

        return new Built(sb.toString(), p);
    }

    // ── Cursor ────────────────────────────────────────────────────────────────

    /** Encodes a row offset as an opaque Base64-URL cursor. Returns null for offsets ≤ 0. */
    public static String encodeCursor(long nextOffset) {
        if (nextOffset <= 0) return null;
        byte[] raw = ("{\"o\":" + nextOffset + "}").getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Decodes a Base64-URL cursor back to a row offset. Returns 0 for null/invalid. */
    public static long decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) return 0;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            Map<?, ?> doc = MAPPER.readValue(raw, Map.class);
            Object o = doc.get("o");
            return o instanceof Number n ? n.longValue() : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private String buildSelectList(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            // Return all declared schema fields (not SELECT *, to avoid schema drift)
            StringJoiner j = new StringJoiner(", ");
            for (Field f : schema.fields) j.add(quoteColumn(resolvedColumn(f)));
            return j.toString();
        }

        for (String name : fields) {
            if (!schema.hasField(name))
                throw new NwpFilterException("Unknown field '" + name + "'.", NwpErrorCodes.NWP_QUERY_FIELD_UNKNOWN);
        }

        StringJoiner j = new StringJoiner(", ");
        for (String name : fields) {
            Field f = schema.getField(name);
            String col = quoteColumn(resolvedColumn(f));
            // Alias back to the NWP name if column name differs
            j.add(f.columnName != null ? col + " AS " + quoteColumn(f.name) : col);
        }
        return j.toString();
    }

    private String buildOrderBy(List<Map<String, Object>> order) {
        StringJoiner j = new StringJoiner(", ");
        for (Map<String, Object> o : order) {
            String fieldName = String.valueOf(o.get("field"));
            Field field = schema.getField(fieldName);
            if (field == null)
                throw new NwpFilterException("Unknown order field '" + fieldName + "'.", NwpErrorCodes.NWP_QUERY_FIELD_UNKNOWN);
            String dirRaw = o.get("dir") == null ? "" : String.valueOf(o.get("dir"));
            String dir = "DESC".equalsIgnoreCase(dirRaw) ? "DESC" : "ASC";
            j.add(quoteColumn(resolvedColumn(field)) + " " + dir);
        }
        return j.toString();
    }

    private static String resolvedColumn(Field f) {
        return f.columnName != null ? f.columnName : f.name;
    }

    private String quoteColumn(String col) {
        return dialect == DatabaseDialect.SQL_SERVER ? "[" + col + "]" : "\"" + col + "\"";
    }

    private String quoteTable(String table) {
        return dialect == DatabaseDialect.SQL_SERVER ? "[" + table + "]" : "\"" + table + "\"";
    }
}
