// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.storage;

import com.labacacia.nps.nip.ca.NipCertRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JDBC NIP CA store: SQL-generation assertions plus a full round-trip against a
 * fake in-memory {@link CaStoreExecutor} that interprets the store's exact SQL.
 */
class JdbcNipCaStoreTest {

    /** Minimal fake DB: a list of row maps + a serial counter, driven by the exact SQL strings. */
    static final class FakeExecutor implements CaStoreExecutor {
        final List<Map<String, Object>> rows = new ArrayList<>();
        long serialSeq = 0;
        final List<String> executed = new ArrayList<>();

        @Override public int update(String sql, Map<String, Object> params) {
            executed.add(sql);
            if (sql.equals(CaStoreSql.INSERT)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nid", params.get("Nid"));
                row.put("entity_type", params.get("EntityType"));
                row.put("serial", params.get("Serial"));
                row.put("pub_key", params.get("PubKey"));
                row.put("capabilities_json", params.get("CapJson"));
                row.put("scope_json", params.get("ScopeJson"));
                row.put("issued_by", params.get("IssuedBy"));
                row.put("issued_at", params.get("IssuedAt"));
                row.put("expires_at", params.get("ExpiresAt"));
                row.put("metadata_json", params.get("MetaJson"));
                row.put("nid_role", params.get("NidRole"));
                row.put("parent_nid", params.get("ParentNid"));
                row.put("lineage_json", params.get("LineageJson"));
                row.put("revoked_at", null);
                row.put("revoke_reason", null);
                rows.add(row);
                return 1;
            }
            if (sql.equals(CaStoreSql.REVOKE)) {
                int n = 0;
                for (Map<String, Object> r : rows) {
                    if (r.get("nid").equals(params.get("Nid")) && r.get("revoked_at") == null) {
                        r.put("revoked_at", params.get("RevokedAt"));
                        r.put("revoke_reason", params.get("Reason"));
                        n++;
                    }
                }
                return n;
            }
            return 0; // DDL / serial update handled elsewhere
        }

        @Override public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
            executed.add(sql);
            List<Map<String, Object>> out = new ArrayList<>();
            if (sql.equals(CaStoreSql.SELECT_BY_NID)) {
                for (Map<String, Object> r : rows) if (r.get("nid").equals(params.get("Nid"))) out.add(r);
                if (out.size() > 1) out.subList(0, out.size() - 1).clear(); // last (DESC + LIMIT 1)
            } else if (sql.equals(CaStoreSql.SELECT_BY_SERIAL)) {
                for (Map<String, Object> r : rows) if (r.get("serial").equals(params.get("Serial"))) { out.add(r); break; }
            } else if (sql.equals(CaStoreSql.LIST)) {
                out.addAll(rows);
            } else if (sql.equals(CaStoreSql.GET_REVOKED)) {
                for (Map<String, Object> r : rows) if (r.get("revoked_at") != null) out.add(r);
            } else if (sql.equals(CaStoreSql.GET_BY_PARENT_NID)) {
                for (Map<String, Object> r : rows)
                    if (params.get("ParentNid").equals(r.get("parent_nid"))) out.add(r);
            }
            return out;
        }

        @Override public long updateThenScalar(String update, Map<String, Object> up,
                                               String select, Map<String, Object> sp) {
            executed.add(update);
            assertEquals(CaStoreSql.SERIAL_UPDATE, update);
            assertEquals(CaStoreSql.SERIAL_SELECT, select);
            return ++serialSeq;
        }
    }

    private static NipCertRecord record(String nid, String serial) {
        return NipCertRecord.builder()
            .nid(nid).entityType("agent").serial(serial).pubKey("pk")
            .capabilities(List.of("read", "write")).scopeJson("{}")
            .issuedBy("ca").issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .expiresAt(Instant.parse("2027-01-01T00:00:00Z"))
            .build();
    }

    @Test void migrationRunsAllDdlOnOpen() {
        FakeExecutor exec = new FakeExecutor();
        JdbcNipCaStore.open(exec);
        assertTrue(exec.executed.containsAll(CaStoreSql.MIGRATIONS));
    }

    @Test void saveThenGetRoundTrip() {
        var store = JdbcNipCaStore.open(new FakeExecutor());
        store.save(record("urn:a", "0x1"));
        NipCertRecord got = store.getByNid("urn:a");
        assertNotNull(got);
        assertEquals("agent", got.entityType());
        assertEquals(List.of("read", "write"), got.capabilities());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), got.issuedAt());
        assertEquals(store.getBySerial("0x1").nid(), "urn:a");
    }

    @Test void revokeMarksRecordAndSurfacesInCrl() {
        var store = JdbcNipCaStore.open(new FakeExecutor());
        store.save(record("urn:a", "0x1"));
        assertTrue(store.revoke("urn:a", "compromised", Instant.parse("2026-06-01T00:00:00Z")));
        assertFalse(store.revoke("urn:missing", "x", Instant.now()));
        List<NipCertRecord> revoked = store.getRevoked();
        assertEquals(1, revoked.size());
        assertEquals("compromised", revoked.get(0).revokeReason());
        assertTrue(revoked.get(0).isRevoked());
    }

    @Test void nextSerialFormatMatchesDotNet() {
        var store = JdbcNipCaStore.open(new FakeExecutor());
        assertEquals("0x1", store.nextSerial());
        assertEquals("0x2", store.nextSerial());
    }

    @Test void getByParentNid() {
        var store = JdbcNipCaStore.open(new FakeExecutor());
        store.save(NipCertRecord.builder()
            .nid("urn:session").entityType("agent").serial("0x9").pubKey("pk")
            .capabilities(List.of()).scopeJson("{}").issuedBy("ca")
            .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .expiresAt(Instant.parse("2027-01-01T00:00:00Z"))
            .nidRole("session").parentNid("urn:group")
            .build());
        List<NipCertRecord> kids = store.getByParentNid("urn:group");
        assertEquals(1, kids.size());
        assertEquals("urn:session", kids.get(0).nid());
    }

    @Test void insertSqlShapeMatchesReference() {
        assertTrue(CaStoreSql.INSERT.contains("INSERT INTO nip_certs"));
        assertTrue(CaStoreSql.INSERT.contains("nid_role, parent_nid, lineage_json"));
        assertTrue(CaStoreSql.MIGRATIONS.get(0).contains("serial            TEXT NOT NULL UNIQUE"));
    }
}
