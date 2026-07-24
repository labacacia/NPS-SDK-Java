// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.ca.INipCaStore;
import com.labacacia.nps.nip.ca.NipCertRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC-backed {@link INipCaStore} (NPS-3 §8). Faithful port of the .NET
 * {@code SqliteNipCaStore}: identical table schema, SQL, serial format
 * ({@code 0x<UPPERHEX>}) and ISO-8601 timestamp round-tripping.
 *
 * <p>Execution is delegated to an injectable {@link CaStoreExecutor}, so the
 * store is fully testable without a live database. A concrete SQLite/JDBC driver
 * binding is deferred (no {@code java.sql} driver on the SDK classpath); hosts
 * supply a driver-backed executor. Migration SQL lives in {@link CaStoreSql}.</p>
 */
public final class JdbcNipCaStore implements INipCaStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final CaStoreExecutor exec;

    private JdbcNipCaStore(CaStoreExecutor exec) {
        this.exec = exec;
    }

    /** Opens the store and applies the NIP CA schema migrations via {@code exec}. */
    public static JdbcNipCaStore open(CaStoreExecutor exec) {
        JdbcNipCaStore store = new JdbcNipCaStore(exec);
        store.migrate();
        return store;
    }

    private void migrate() {
        try {
            for (String ddl : CaStoreSql.MIGRATIONS) exec.update(ddl, Map.of());
        } catch (Exception e) {
            throw new RuntimeException("NIP CA migration failed", e);
        }
    }

    // ── INipCaStore ───────────────────────────────────────────────────────────

    @Override
    public void save(NipCertRecord r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("Nid",         r.nid());
        p.put("EntityType",  r.entityType());
        p.put("Serial",      r.serial());
        p.put("PubKey",      r.pubKey());
        p.put("CapJson",     writeCaps(r.capabilities()));
        p.put("ScopeJson",   r.scopeJson());
        p.put("IssuedBy",    r.issuedBy());
        p.put("IssuedAt",    r.issuedAt().toString());
        p.put("ExpiresAt",   r.expiresAt().toString());
        p.put("MetaJson",    r.metadataJson());
        p.put("NidRole",     r.nidRole());
        p.put("ParentNid",   r.parentNid());
        p.put("LineageJson", r.lineageJson());
        run(() -> exec.update(CaStoreSql.INSERT, p));
    }

    @Override
    public NipCertRecord getByNid(String nid) {
        return first(CaStoreSql.SELECT_BY_NID, Map.of("Nid", nid));
    }

    @Override
    public NipCertRecord getBySerial(String serial) {
        return first(CaStoreSql.SELECT_BY_SERIAL, Map.of("Serial", serial));
    }

    @Override
    public boolean revoke(String nid, String reason, Instant revokedAt) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("Nid",       nid);
        p.put("Reason",    reason);
        p.put("RevokedAt", revokedAt.toString());
        return call(() -> exec.update(CaStoreSql.REVOKE, p)) > 0;
    }

    @Override
    public String nextSerial() {
        long next = call(() -> exec.updateThenScalar(
            CaStoreSql.SERIAL_UPDATE, Map.of(),
            CaStoreSql.SERIAL_SELECT, Map.of()));
        return "0x" + Long.toHexString(next).toUpperCase();
    }

    @Override
    public List<NipCertRecord> list() {
        return all(CaStoreSql.LIST, Map.of());
    }

    @Override
    public List<NipCertRecord> getRevoked() {
        return all(CaStoreSql.GET_REVOKED, Map.of());
    }

    @Override
    public List<NipCertRecord> getByParentNid(String parentNid) {
        return all(CaStoreSql.GET_BY_PARENT_NID, Map.of("ParentNid", parentNid));
    }

    // ── Row mapping ─────────────────────────────────────────────────────────────

    private NipCertRecord first(String sql, Map<String, Object> p) {
        List<NipCertRecord> rows = all(sql, p);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<NipCertRecord> all(String sql, Map<String, Object> p) {
        List<Map<String, Object>> rows = call(() -> exec.query(sql, p));
        List<NipCertRecord> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) out.add(readRecord(row));
        return out;
    }

    static NipCertRecord readRecord(Map<String, Object> r) {
        return NipCertRecord.builder()
            .nid(str(r, "nid"))
            .entityType(str(r, "entity_type"))
            .serial(str(r, "serial"))
            .pubKey(str(r, "pub_key"))
            .capabilities(readCaps(str(r, "capabilities_json")))
            .scopeJson(str(r, "scope_json"))
            .issuedBy(str(r, "issued_by"))
            .issuedAt(instant(r, "issued_at"))
            .expiresAt(instant(r, "expires_at"))
            .revokedAt(instant(r, "revoked_at"))
            .revokeReason(str(r, "revoke_reason"))
            .metadataJson(str(r, "metadata_json"))
            .nidRole(str(r, "nid_role"))
            .parentNid(str(r, "parent_nid"))
            .lineageJson(str(r, "lineage_json"))
            .build();
    }

    private static String str(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? null : String.valueOf(v);
    }

    private static Instant instant(Map<String, Object> r, String col) {
        Object v = r.get(col);
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        return Instant.parse(String.valueOf(v));
    }

    private static String writeCaps(List<String> caps) {
        try {
            return MAPPER.writeValueAsString(caps == null ? List.of() : caps);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> readCaps(String json) {
        if (json == null || json.isEmpty()) return List.of();
        try {
            return MAPPER.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Checked-exception plumbing ───────────────────────────────────────────────

    @FunctionalInterface private interface SqlCall<T> { T get() throws Exception; }
    @FunctionalInterface private interface SqlRun { void run() throws Exception; }

    private static <T> T call(SqlCall<T> c) {
        try { return c.get(); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void run(SqlRun r) {
        try { r.run(); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
