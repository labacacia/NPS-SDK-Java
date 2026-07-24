// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp.providers;

import com.labacacia.nps.nwp.MemoryNodeServer.Field;
import com.labacacia.nps.nwp.MemoryNodeServer.Options;
import com.labacacia.nps.nwp.MemoryNodeServer.QueryResult;
import com.labacacia.nps.nwp.MemoryNodeServer.Schema;
import com.labacacia.nps.nwp.QueryFrame;
import com.labacacia.nps.nwp.query.DatabaseDialect;
import com.labacacia.nps.nwp.query.SqlQueryBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** SQL Memory Node provider with an injectable (no-DB) executor. */
class JdbcMemoryNodeProviderTest {

    private static Schema schema() {
        Schema s = new Schema();
        s.tableName = "products";
        s.primaryKey = "id";
        s.fields = List.of(new Field("id", "number"), new Field("name", "string"));
        return s;
    }

    private static Options options() {
        Options o = new Options();
        o.defaultLimit = 2;
        o.maxLimit = 1000;
        return o;
    }

    /** Records the SQL/params it was called with and returns a canned page. */
    static final class RecordingExecutor implements SqlExecutor {
        String lastSql;
        Map<String, Object> lastParams;
        List<Map<String, Object>> next;
        RecordingExecutor(List<Map<String, Object>> next) { this.next = next; }
        @Override public List<Map<String, Object>> query(String sql, Map<String, Object> parameters) {
            this.lastSql = sql;
            this.lastParams = parameters;
            return next;
        }
    }

    private static Map<String, Object> row(int id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    private static QueryFrame frame(Integer limit, String cursor) {
        return new QueryFrame(null, null, limit, null, null, null, null, null,
            cursor, null, null, null, null, null, null);
    }

    @Test void queryReturnsRowsAndGeneratesExpectedSql() throws Exception {
        var exec = new RecordingExecutor(List.of(row(1, "a"), row(2, "b")));
        var p = new JdbcMemoryNodeProvider(exec, DatabaseDialect.POSTGRE_SQL);
        QueryResult res = p.query(frame(2, null), schema(), options());
        assertEquals(2, res.rows.size());
        assertTrue(exec.lastSql.startsWith("SELECT \"id\", \"name\" FROM \"products\""), exec.lastSql);
        // full page ⇒ next cursor points to offset 2
        assertEquals(SqlQueryBuilder.encodeCursor(2), res.nextCursor);
    }

    @Test void partialPageHasNoNextCursor() throws Exception {
        var exec = new RecordingExecutor(List.of(row(1, "a")));
        var p = new JdbcMemoryNodeProvider(exec, DatabaseDialect.POSTGRE_SQL);
        QueryResult res = p.query(frame(2, null), schema(), options());
        assertNull(res.nextCursor);
    }

    @Test void countDelegatesToScalar() throws Exception {
        SqlExecutor exec = (sql, params) -> {
            assertTrue(sql.startsWith("SELECT COUNT(*)"), sql);
            return List.of(Map.of("c", 7L));
        };
        var p = new JdbcMemoryNodeProvider(exec, DatabaseDialect.POSTGRE_SQL);
        assertEquals(7L, p.count(frame(null, null), schema()));
    }

    @Test void positionalRewriteScalarParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("p0", "x");
        params.put("_limit", 20);
        params.put("_offset", 0);
        var pos = JdbcMemoryNodeProvider.toPositional(
            "SELECT * FROM t WHERE name = @p0 LIMIT @_limit OFFSET @_offset", params);
        assertEquals("SELECT * FROM t WHERE name = ? LIMIT ? OFFSET ?", pos.sql());
        assertEquals(List.of("x", 20, 0), pos.values());
    }

    @Test void positionalRewriteExpandsInList() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("p0", new ArrayList<>(List.of(1, 2, 3)));
        var pos = JdbcMemoryNodeProvider.toPositional("SELECT * FROM t WHERE id IN @p0", params);
        assertEquals("SELECT * FROM t WHERE id IN (?, ?, ?)", pos.sql());
        assertEquals(List.of(1, 2, 3), pos.values());
    }
}
