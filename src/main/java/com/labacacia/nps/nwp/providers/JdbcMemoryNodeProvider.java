// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp.providers;

import com.labacacia.nps.nwp.MemoryNodeServer.Options;
import com.labacacia.nps.nwp.MemoryNodeServer.Provider;
import com.labacacia.nps.nwp.MemoryNodeServer.QueryResult;
import com.labacacia.nps.nwp.MemoryNodeServer.Schema;
import com.labacacia.nps.nwp.QueryFrame;
import com.labacacia.nps.nwp.query.DatabaseDialect;
import com.labacacia.nps.nwp.query.SqlQueryBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL-backed {@link Provider} for a Memory Node (NPS-2 §2.1). Faithful port of
 * the .NET {@code PostgreSqlMemoryNodeProvider} / {@code SqlServerMemoryNodeProvider}:
 * the two .NET types differed only by dialect and DBNull handling, so this port
 * collapses them behind a {@link DatabaseDialect} constructor argument.
 *
 * <p>Execution is delegated to an injectable {@link SqlExecutor}, so the
 * provider is fully testable without a live database. Concrete JDBC driver
 * binding (PostgreSQL / SQL Server / SQLite) is deferred because no
 * {@code java.sql} driver is on the SDK Gradle classpath.</p>
 */
public final class JdbcMemoryNodeProvider implements Provider {

    private final SqlExecutor executor;
    private final DatabaseDialect dialect;

    public JdbcMemoryNodeProvider(SqlExecutor executor, DatabaseDialect dialect) {
        this.executor = executor;
        this.dialect  = dialect;
    }

    @Override
    public QueryResult query(QueryFrame frame, Schema schema, Options options) throws Exception {
        SqlQueryBuilder builder = new SqlQueryBuilder(schema, dialect);
        SqlQueryBuilder.Built built = builder.build(frame, options);

        List<Map<String, Object>> rows = executor.query(built.sql(), built.params().asMap());

        int requested = frame.limit() == null ? 0 : frame.limit();
        long limit = Math.min(requested == 0 ? options.defaultLimit : requested, options.maxLimit);
        String nextCursor = rows.size() == limit
            ? SqlQueryBuilder.encodeCursor(SqlQueryBuilder.decodeCursor(frame.cursor()) + limit)
            : null;

        return new QueryResult(rows, nextCursor);
    }

    /** Returns the total row count matching the frame's filter. */
    public long count(QueryFrame frame, Schema schema) throws Exception {
        SqlQueryBuilder builder = new SqlQueryBuilder(schema, dialect);
        SqlQueryBuilder.Built built = builder.buildCount(frame);
        return executor.scalarLong(built.sql(), built.params().asMap());
    }

    // ── JDBC binding helper (for host-supplied executors) ──────────────────────

    private static final Pattern PARAM = Pattern.compile("@([A-Za-z_][A-Za-z0-9_]*)");

    /** Positional JDBC SQL plus the value list, in {@code ?} order. */
    public record PositionalSql(String sql, List<Object> values) {}

    /**
     * Rewrites {@code @name} placeholders in {@code sql} to positional
     * {@code ?} markers and returns the values in binding order, expanding any
     * {@link List} parameter (an {@code IN (...)} clause) to one {@code ?} per
     * element. This is the exact transform a JDBC-backed {@link SqlExecutor}
     * applies before calling {@link java.sql.Connection#prepareStatement}.
     */
    public static PositionalSql toPositional(String sql, Map<String, Object> parameters) {
        StringBuilder out = new StringBuilder(sql.length());
        List<Object> values = new ArrayList<>();
        Matcher m = PARAM.matcher(sql);
        while (m.find()) {
            String name = m.group(1);
            Object v = parameters.get(name);
            String replacement;
            if (v instanceof List<?> list) {
                StringBuilder marks = new StringBuilder("(");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) marks.append(", ");
                    marks.append("?");
                    values.add(list.get(i));
                }
                marks.append(")");
                replacement = marks.toString();
            } else {
                replacement = "?";
                values.add(v);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return new PositionalSql(out.toString(), values);
    }
}
