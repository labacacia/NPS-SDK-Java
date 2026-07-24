// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp.query;

import com.labacacia.nps.nwp.MemoryNodeServer.Field;
import com.labacacia.nps.nwp.MemoryNodeServer.Schema;
import com.labacacia.nps.nwp.NwpErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Filter DSL → parameterized SQL translation table (NPS-2 §5.2). */
class NwpFilterTranslatorTest {

    private static Schema schema() {
        Schema s = new Schema();
        s.tableName = "products";
        s.primaryKey = "id";
        s.fields = List.of(
            new Field("id", "number"),
            new Field("name", "string"),
            new Field("price", "number"),
            withColumn("sku", "string", "product_sku"));
        return s;
    }

    private static Field withColumn(String name, String type, String col) {
        Field f = new Field(name, type);
        f.columnName = col;
        return f;
    }

    private static String pg(Map<String, Object> filter, SqlParameters p) {
        return new NwpFilterTranslator(schema(), DatabaseDialect.POSTGRE_SQL).translate(filter, p);
    }

    @Test void nullFilterIsEmpty() {
        assertEquals("", pg(null, new SqlParameters()));
    }

    @Test void eq() {
        SqlParameters p = new SqlParameters();
        assertEquals("\"name\" = @p0", pg(Map.of("name", Map.of("$eq", "widget")), p));
        assertEquals("widget", p.get("p0"));
    }

    @Test void allComparisonOps() {
        assertEquals("\"price\" <> @p0", pg(Map.of("price", Map.of("$ne", 5)), new SqlParameters()));
        assertEquals("\"price\" < @p0",  pg(Map.of("price", Map.of("$lt", 5)), new SqlParameters()));
        assertEquals("\"price\" <= @p0", pg(Map.of("price", Map.of("$lte", 5)), new SqlParameters()));
        assertEquals("\"price\" > @p0",  pg(Map.of("price", Map.of("$gt", 5)), new SqlParameters()));
        assertEquals("\"price\" >= @p0", pg(Map.of("price", Map.of("$gte", 5)), new SqlParameters()));
    }

    @Test void containsWrapsWithPercents() {
        SqlParameters p = new SqlParameters();
        assertEquals("\"name\" LIKE @p0", pg(Map.of("name", Map.of("$contains", "wid")), p));
        assertEquals("%wid%", p.get("p0"));
    }

    @Test void inAndNin() {
        SqlParameters p = new SqlParameters();
        assertEquals("\"id\" IN @p0", pg(Map.of("id", Map.of("$in", List.of(1, 2, 3))), p));
        assertEquals(List.of(1L, 2L, 3L), p.get("p0"));
        assertEquals("\"id\" NOT IN @p0", pg(Map.of("id", Map.of("$nin", List.of(1))), new SqlParameters()));
    }

    @Test void emptyInIsAlwaysFalse_emptyNinAlwaysTrue() {
        assertEquals("1=0", pg(Map.of("id", Map.of("$in", List.of())), new SqlParameters()));
        assertEquals("1=1", pg(Map.of("id", Map.of("$nin", List.of())), new SqlParameters()));
    }

    @Test void between() {
        SqlParameters p = new SqlParameters();
        assertEquals("\"price\" BETWEEN @p0 AND @p1",
            pg(Map.of("price", Map.of("$between", List.of(10, 20))), p));
        assertEquals(10L, p.get("p0"));
        assertEquals(20L, p.get("p1"));
    }

    @Test void columnAliasResolution() {
        assertEquals("\"product_sku\" = @p0",
            pg(Map.of("sku", Map.of("$eq", "ABC")), new SqlParameters()));
    }

    @Test void multipleFieldsAndedTogether() {
        String sql = pg(new java.util.LinkedHashMap<>() {{
            put("name", Map.of("$eq", "x"));
            put("price", Map.of("$gt", 1));
        }}, new SqlParameters());
        assertTrue(sql.startsWith("(") && sql.endsWith(")"), sql);
        assertTrue(sql.contains(" AND "), sql);
    }

    @Test void orCombinator() {
        String sql = pg(Map.of("$or", List.of(
            Map.of("name", Map.of("$eq", "a")),
            Map.of("name", Map.of("$eq", "b")))), new SqlParameters());
        assertEquals("(\"name\" = @p0 OR \"name\" = @p1)", sql);
    }

    @Test void sqlServerQuoting() {
        SqlParameters p = new SqlParameters();
        String sql = new NwpFilterTranslator(schema(), DatabaseDialect.SQL_SERVER)
            .translate(Map.of("name", Map.of("$eq", "x")), p);
        assertEquals("[name] = @p0", sql);
    }

    @Test void unknownFieldThrowsWithCode() {
        NwpFilterException ex = assertThrows(NwpFilterException.class,
            () -> pg(Map.of("nope", Map.of("$eq", 1)), new SqlParameters()));
        assertEquals(NwpErrorCodes.NWP_QUERY_FIELD_UNKNOWN, ex.nwpErrorCode());
    }

    @Test void unknownOperatorThrows() {
        assertThrows(NwpFilterException.class,
            () -> pg(Map.of("name", Map.of("$regex", "x")), new SqlParameters()));
    }

    @Test void betweenWrongArityThrows() {
        assertThrows(NwpFilterException.class,
            () -> pg(Map.of("price", Map.of("$between", List.of(1))), new SqlParameters()));
    }

    @Test void logicalOperatorRequiresArray() {
        assertThrows(NwpFilterException.class,
            () -> pg(Map.of("$and", Map.of("name", Map.of("$eq", "x"))), new SqlParameters()));
    }
}
