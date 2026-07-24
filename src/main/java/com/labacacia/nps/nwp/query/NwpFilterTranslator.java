// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp.query;

import com.labacacia.nps.nwp.MemoryNodeServer.Field;
import com.labacacia.nps.nwp.MemoryNodeServer.Schema;
import com.labacacia.nps.nwp.NwpErrorCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates a NWP filter predicate (NPS-2 §5.2) into a parameterized SQL WHERE
 * clause. Faithful port of the .NET {@code NwpFilterTranslator}: field names are
 * validated against the schema to prevent SQL injection, and every literal is
 * bound as a parameter ({@code @p0}, {@code @p1}, …).
 *
 * <p>Supported operators: {@code $eq $ne $lt $lte $gt $gte $in $nin $contains
 * $between} on fields, and {@code $and $or} logical combinators. (The .NET
 * source declares {@code $not} in the DSL surface but implements the same
 * operator set below; this port matches it operator-for-operator.)</p>
 */
public final class NwpFilterTranslator {

    private final Schema schema;
    private final String quote;   // "[" for SQL Server, "\"" for PostgreSQL
    private int paramIndex;

    public NwpFilterTranslator(Schema schema, DatabaseDialect dialect) {
        this.schema = schema;
        this.quote  = dialect == DatabaseDialect.SQL_SERVER ? "[" : "\"";
    }

    /**
     * Translates {@code filter} into a WHERE-clause fragment and appends its
     * parameters to {@code p}. Returns an empty string when {@code filter} is null.
     */
    public String translate(Map<String, Object> filter, SqlParameters p) {
        paramIndex = 0;
        if (filter == null) return "";
        return buildObject(filter, p);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String buildObject(Object node, SqlParameters p) {
        if (!(node instanceof Map<?, ?> obj))
            throw new NwpFilterException("Filter node must be an object.");

        List<String> clauses = new ArrayList<>();
        for (Map.Entry<?, ?> e : obj.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (name.startsWith("$")) {
                clauses.add(buildLogical(name, e.getValue(), p));
            } else {
                Field field = validateField(name);
                clauses.add(buildFieldCondition(field, e.getValue(), p));
            }
        }

        return switch (clauses.size()) {
            case 0  -> "";
            case 1  -> clauses.get(0);
            default -> "(" + String.join(" AND ", clauses) + ")";
        };
    }

    private String buildLogical(String op, Object value, SqlParameters p) {
        if (!(value instanceof List<?> arr))
            throw new NwpFilterException("Logical operator '" + op + "' requires an array value.");

        String separator = switch (op) {
            case "$and" -> " AND ";
            case "$or"  -> " OR ";
            default     -> throw new NwpFilterException("Unknown logical operator '" + op + "'.");
        };

        List<String> parts = new ArrayList<>();
        for (Object el : arr) {
            String s = buildObject(el, p);
            if (!s.isEmpty()) parts.add(s);
        }

        return switch (parts.size()) {
            case 0  -> "";
            case 1  -> parts.get(0);
            default -> "(" + String.join(separator, parts) + ")";
        };
    }

    private String buildFieldCondition(Field field, Object condition, SqlParameters p) {
        if (!(condition instanceof Map<?, ?> cond))
            throw new NwpFilterException(
                "Field '" + field.name + "' condition must be an object (e.g. {\"$eq\": value}).");

        String col = quoteColumn(resolvedColumn(field));
        List<String> parts = new ArrayList<>();

        for (Map.Entry<?, ?> e : cond.entrySet()) {
            String op = String.valueOf(e.getKey());
            Object v  = e.getValue();
            parts.add(switch (op) {
                case "$in"      -> buildIn(col, v, p, false);
                case "$nin"     -> buildIn(col, v, p, true);
                case "$between" -> buildBetween(col, v, p);
                default         -> buildSimple(col, op, field.name, v, p);
            });
        }

        return parts.size() == 1 ? parts.get(0) : "(" + String.join(" AND ", parts) + ")";
    }

    private String buildSimple(String col, String op, String fieldName, Object value, SqlParameters p) {
        String paramName = "p" + (paramIndex++);
        return switch (op) {
            case "$eq"       -> { p.add(paramName, extractValue(value));            yield col + " = @" + paramName; }
            case "$ne"       -> { p.add(paramName, extractValue(value));            yield col + " <> @" + paramName; }
            case "$lt"       -> { p.add(paramName, extractValue(value));            yield col + " < @" + paramName; }
            case "$lte"      -> { p.add(paramName, extractValue(value));            yield col + " <= @" + paramName; }
            case "$gt"       -> { p.add(paramName, extractValue(value));            yield col + " > @" + paramName; }
            case "$gte"      -> { p.add(paramName, extractValue(value));            yield col + " >= @" + paramName; }
            case "$contains" -> { p.add(paramName, "%" + extractValue(value) + "%"); yield col + " LIKE @" + paramName; }
            default -> throw new NwpFilterException(
                "Unknown filter operator '" + op + "' on field '" + fieldName + "'.");
        };
    }

    private String buildIn(String col, Object arr, SqlParameters p, boolean negate) {
        if (!(arr instanceof List<?> list))
            throw new NwpFilterException("$in/$nin requires an array value.");

        List<Object> values = new ArrayList<>();
        for (Object o : list) values.add(extractValue(o));
        if (values.isEmpty())
            return negate ? "1=1" : "1=0";   // empty IN → always false; empty NIN → always true

        String paramName = "p" + (paramIndex++);
        p.add(paramName, values);
        return negate ? col + " NOT IN @" + paramName : col + " IN @" + paramName;
    }

    private String buildBetween(String col, Object arr, SqlParameters p) {
        if (!(arr instanceof List<?> list) || list.size() != 2)
            throw new NwpFilterException("$between requires an array of exactly two values [low, high].");

        String pLow  = "p" + (paramIndex++);
        String pHigh = "p" + (paramIndex++);
        p.add(pLow,  extractValue(list.get(0)));
        p.add(pHigh, extractValue(list.get(1)));
        return col + " BETWEEN @" + pLow + " AND @" + pHigh;
    }

    private Field validateField(String name) {
        Field field = schema.getField(name);
        if (field == null)
            throw new NwpFilterException("Unknown field '" + name + "'.", NwpErrorCodes.NWP_QUERY_FIELD_UNKNOWN);
        return field;
    }

    private static String resolvedColumn(Field f) {
        return f.columnName != null ? f.columnName : f.name;
    }

    private String quoteColumn(String col) {
        return "[".equals(quote) ? "[" + col + "]" : "\"" + col + "\"";
    }

    /** Normalises a decoded JSON literal to its SQL-bound value. */
    private static Object extractValue(Object el) {
        if (el instanceof Number n) {
            // Prefer an integral long when the value is whole, matching .NET's
            // TryGetInt64-then-double ordering.
            double d = n.doubleValue();
            if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte)
                return n.longValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)
                && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE)
                return (long) d;
            return d;
        }
        return el; // String, Boolean, null, or nested → verbatim
    }
}
