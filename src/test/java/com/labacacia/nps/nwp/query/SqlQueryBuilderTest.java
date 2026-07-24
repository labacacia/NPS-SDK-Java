// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp.query;

import com.labacacia.nps.nwp.MemoryNodeServer.Field;
import com.labacacia.nps.nwp.MemoryNodeServer.Options;
import com.labacacia.nps.nwp.MemoryNodeServer.Schema;
import com.labacacia.nps.nwp.QueryFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Full SELECT generation: projection, order, pagination, cursor round-trip (NPS-2 §5). */
class SqlQueryBuilderTest {

    private static Schema schema() {
        Schema s = new Schema();
        s.tableName = "products";
        s.primaryKey = "id";
        s.fields = List.of(new Field("id", "number"), new Field("name", "string"), new Field("price", "number"));
        return s;
    }

    private static Options options() {
        Options o = new Options();
        o.defaultLimit = 20;
        o.maxLimit = 1000;
        return o;
    }

    private static QueryFrame frame(Map<String, Object> filter, List<String> fields,
                                    List<Map<String, Object>> order, Integer limit, String cursor) {
        return new QueryFrame(null, filter, limit, null, order, fields, null, null,
            cursor, null, null, null, null, null, null);
    }

    @Test void selectAllFieldsDefaultsWithPkOrderAndPgPagination() {
        var b = new SqlQueryBuilder(schema(), DatabaseDialect.POSTGRE_SQL);
        var built = b.build(frame(null, null, null, null, null), options());
        assertEquals(
            "SELECT \"id\", \"name\", \"price\" FROM \"products\" ORDER BY \"id\" LIMIT @_limit OFFSET @_offset",
            built.sql());
        assertEquals(20, built.params().get("_limit"));
        assertEquals(0, built.params().get("_offset"));
    }

    @Test void projectionAndWhereAndOrder() {
        var b = new SqlQueryBuilder(schema(), DatabaseDialect.POSTGRE_SQL);
        var built = b.build(frame(
            Map.of("price", Map.of("$gt", 10)),
            List.of("id", "name"),
            List.of(Map.of("field", "name", "dir", "desc")),
            5, null), options());
        assertEquals(
            "SELECT \"id\", \"name\" FROM \"products\" WHERE \"price\" > @p0 "
                + "ORDER BY \"name\" DESC LIMIT @_limit OFFSET @_offset",
            built.sql());
        assertEquals(10L, built.params().get("p0"));
        assertEquals(5, built.params().get("_limit"));
    }

    @Test void sqlServerUsesOffsetFetch() {
        var b = new SqlQueryBuilder(schema(), DatabaseDialect.SQL_SERVER);
        var built = b.build(frame(null, null, null, null, null), options());
        assertTrue(built.sql().endsWith("OFFSET @_offset ROWS FETCH NEXT @_limit ROWS ONLY"), built.sql());
        assertTrue(built.sql().startsWith("SELECT [id], [name], [price] FROM [products]"), built.sql());
    }

    @Test void limitClampedToMax() {
        Options o = options();
        o.maxLimit = 50;
        var built = new SqlQueryBuilder(schema(), DatabaseDialect.POSTGRE_SQL)
            .build(frame(null, null, null, 999, null), o);
        assertEquals(50, built.params().get("_limit"));
    }

    @Test void cursorDecodesToOffset() {
        String cursor = SqlQueryBuilder.encodeCursor(40);
        var built = new SqlQueryBuilder(schema(), DatabaseDialect.POSTGRE_SQL)
            .build(frame(null, null, null, 20, cursor), options());
        assertEquals(40, built.params().get("_offset"));
    }

    @Test void cursorRoundTrip() {
        assertNull(SqlQueryBuilder.encodeCursor(0));
        assertNull(SqlQueryBuilder.encodeCursor(-1));
        String c = SqlQueryBuilder.encodeCursor(123);
        assertNotNull(c);
        assertFalse(c.contains("="), "cursor must be padding-stripped Base64-URL");
        assertEquals(123, SqlQueryBuilder.decodeCursor(c));
        assertEquals(0, SqlQueryBuilder.decodeCursor(null));
        assertEquals(0, SqlQueryBuilder.decodeCursor("!!!not-base64"));
    }

    @Test void buildCount() {
        var built = new SqlQueryBuilder(schema(), DatabaseDialect.POSTGRE_SQL)
            .buildCount(frame(Map.of("name", Map.of("$eq", "x")), null, null, null, null));
        assertEquals("SELECT COUNT(*) FROM \"products\" WHERE \"name\" = @p0", built.sql());
        assertEquals("x", built.params().get("p0"));
    }
}
