// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp.providers;

import java.util.List;
import java.util.Map;

/**
 * Injectable query executor seam for the SQL Memory Node providers.
 *
 * <p>Decoupling execution from SQL generation lets the providers be unit-tested
 * without a live database (assert the generated SQL and bound parameters), and
 * lets callers bind any driver — JDBC, a connection pool, or a mock. A concrete
 * JDBC binding is deferred: no {@code java.sql} driver is on the SDK's Gradle
 * classpath (checked against {@code build.gradle.kts}), so shipping a real
 * driver-backed executor would add a heavy dependency the task forbids. Callers
 * supply their own {@code SqlExecutor} over {@code java.sql.DataSource} at the
 * host boundary; see {@link JdbcMemoryNodeProvider} for the placeholder→{@code ?}
 * rewrite helper they can reuse.</p>
 */
@FunctionalInterface
public interface SqlExecutor {

    /**
     * Executes {@code sql} with the ordered {@code parameters} and returns each
     * row as an ordered field-name → value map.
     *
     * @param sql        parameterized SQL with {@code @name} placeholders
     * @param parameters insertion-ordered parameter bindings ({@code name → value})
     */
    List<Map<String, Object>> query(String sql, Map<String, Object> parameters) throws Exception;

    /** Executes {@code sql} and returns the first column of the first row as a long. */
    default long scalarLong(String sql, Map<String, Object> parameters) throws Exception {
        List<Map<String, Object>> rows = query(sql, parameters);
        if (rows.isEmpty()) return 0L;
        Object v = rows.get(0).values().iterator().next();
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
