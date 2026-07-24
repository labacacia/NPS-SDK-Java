// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.storage;

import java.util.List;

/**
 * SQL text for the JDBC-backed NIP CA store (NPS-3 §8, NPS-CR-0003 §5.1.3).
 * Statements mirror the .NET {@code SqliteNipCaStore} verbatim so schema and
 * query output match at the storage boundary. Kept in one place so SQL-generation
 * tests can assert on them independently of the executor binding.
 */
public final class CaStoreSql {

    private CaStoreSql() {}

    /** Certificate table + serial-sequence table DDL (run in order, one statement each). */
    public static final List<String> MIGRATIONS = List.of(
        """
        CREATE TABLE IF NOT EXISTS nip_certs (
            nid               TEXT NOT NULL,
            entity_type       TEXT NOT NULL,
            serial            TEXT NOT NULL UNIQUE,
            pub_key           TEXT NOT NULL,
            capabilities_json TEXT NOT NULL DEFAULT '[]',
            scope_json        TEXT NOT NULL DEFAULT '{}',
            issued_by         TEXT NOT NULL,
            issued_at         TEXT NOT NULL,
            expires_at        TEXT NOT NULL,
            revoked_at        TEXT,
            revoke_reason     TEXT,
            metadata_json     TEXT,
            nid_role          TEXT,
            parent_nid        TEXT,
            lineage_json      TEXT
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_nip_certs_nid        ON nip_certs (nid)",
        "CREATE INDEX IF NOT EXISTS idx_nip_certs_serial     ON nip_certs (serial)",
        "CREATE INDEX IF NOT EXISTS idx_nip_certs_parent_nid ON nip_certs (parent_nid)",
        """
        CREATE TABLE IF NOT EXISTS nip_serial (
            id   INTEGER PRIMARY KEY,
            seq  INTEGER NOT NULL DEFAULT 0
        )
        """,
        "INSERT OR IGNORE INTO nip_serial (id, seq) VALUES (1, 0)"
    );

    public static final String INSERT = """
        INSERT INTO nip_certs
            (nid, entity_type, serial, pub_key, capabilities_json, scope_json,
             issued_by, issued_at, expires_at, metadata_json,
             nid_role, parent_nid, lineage_json)
        VALUES
            (@Nid, @EntityType, @Serial, @PubKey, @CapJson, @ScopeJson,
             @IssuedBy, @IssuedAt, @ExpiresAt, @MetaJson,
             @NidRole, @ParentNid, @LineageJson)
        """;

    public static final String SELECT_BY_NID = """
        SELECT * FROM nip_certs
        WHERE nid = @Nid
        ORDER BY issued_at DESC
        LIMIT 1
        """;

    public static final String SELECT_BY_SERIAL =
        "SELECT * FROM nip_certs WHERE serial = @Serial LIMIT 1";

    public static final String REVOKE = """
        UPDATE nip_certs
        SET revoked_at = @RevokedAt, revoke_reason = @Reason
        WHERE nid = @Nid AND revoked_at IS NULL
        """;

    public static final String SERIAL_UPDATE =
        "UPDATE nip_serial SET seq = seq + 1 WHERE id = 1";

    public static final String SERIAL_SELECT =
        "SELECT seq FROM nip_serial WHERE id = 1";

    public static final String LIST =
        "SELECT * FROM nip_certs ORDER BY issued_at DESC";

    public static final String GET_REVOKED =
        "SELECT * FROM nip_certs WHERE revoked_at IS NOT NULL ORDER BY revoked_at DESC";

    public static final String GET_BY_PARENT_NID =
        "SELECT * FROM nip_certs WHERE parent_nid = @ParentNid ORDER BY issued_at DESC";
}
