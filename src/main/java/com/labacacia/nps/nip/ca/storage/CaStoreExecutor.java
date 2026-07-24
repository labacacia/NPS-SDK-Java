// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.storage;

import java.util.List;
import java.util.Map;

/**
 * Injectable SQL executor seam for {@link JdbcNipCaStore}.
 *
 * <p>Separating execution from SQL generation lets the JDBC-backed CA store be
 * unit-tested without a live database (assert generated SQL + bound parameters)
 * and lets callers bind any driver. A concrete JDBC binding is deferred: the SDK
 * has no {@code java.sql} driver on its Gradle classpath (verified against
 * {@code build.gradle.kts}), and the task forbids adding a heavy dependency.
 * Hosts implement this over their own {@code java.sql.DataSource}; parameters
 * are supplied as an insertion-ordered {@code name → value} map, matching the
 * {@code @name} placeholders in the SQL emitted by {@link CaStoreSql}.</p>
 */
public interface CaStoreExecutor {

    /** Executes a non-SELECT statement. Returns rows affected. */
    int update(String sql, Map<String, Object> params) throws Exception;

    /** Executes a SELECT and returns rows as ordered field-name → value maps. */
    List<Map<String, Object>> query(String sql, Map<String, Object> params) throws Exception;

    /**
     * Executes {@code update} then {@code select} atomically (single transaction)
     * and returns the scalar from the first column of {@code select}'s first row.
     * Used by {@code nextSerial}'s reserve-and-read step.
     */
    long updateThenScalar(String update, Map<String, Object> updateParams,
                          String select, Map<String, Object> selectParams) throws Exception;
}
