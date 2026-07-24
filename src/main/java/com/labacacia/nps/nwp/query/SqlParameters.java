// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered, named parameter bag produced by {@link NwpFilterTranslator} and
 * {@link SqlQueryBuilder}. The Java analogue of Dapper's {@code DynamicParameters}.
 *
 * <p>Parameter names match the .NET translator exactly ({@code p0}, {@code p1},
 * … for filter values, plus {@code _limit} / {@code _offset} for pagination).
 * In the emitted SQL they are referenced with a leading {@code @}
 * (e.g. {@code @p0}) to match the .NET wire output byte-for-byte. When binding
 * to a JDBC {@link java.sql.PreparedStatement} the {@code @name} placeholders
 * are rewritten to positional {@code ?} in insertion order — see
 * {@code com.labacacia.nps.nwp.providers.JdbcMemoryNodeProvider}.</p>
 */
public final class SqlParameters {

    private final Map<String, Object> values = new LinkedHashMap<>();

    /** Adds (or replaces) a named parameter. Value may be null or a {@link List} (for IN). */
    public void add(String name, Object value) {
        values.put(name, value);
    }

    /** Returns the value bound to {@code name}, or null. */
    public Object get(String name) {
        return values.get(name);
    }

    /** Returns true if {@code name} is bound. */
    public boolean contains(String name) {
        return values.containsKey(name);
    }

    /** Number of bound parameters. */
    public int size() {
        return values.size();
    }

    /** Bound parameter names in insertion order. */
    public List<String> names() {
        return List.copyOf(values.keySet());
    }

    /** Live, insertion-ordered view of the parameter map. */
    public Map<String, Object> asMap() {
        return values;
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
