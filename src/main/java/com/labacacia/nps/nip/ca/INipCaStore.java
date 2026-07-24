// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

import java.time.Instant;
import java.util.List;

/**
 * Persistence abstraction for NIP CA certificate storage (NPS-3 §8,
 * NPS-CR-0003 §5.1.3).
 */
public interface INipCaStore {

    /** Saves a newly issued record. Throws if NID or serial already exists. */
    void save(NipCertRecord record);

    /** Returns the active record for {@code nid}, or null. */
    NipCertRecord getByNid(String nid);

    /** Returns the record for {@code serial}, or null. */
    NipCertRecord getBySerial(String serial);

    /** Marks a certificate revoked. Returns false if the NID was not found. */
    boolean revoke(String nid, String reason, Instant revokedAt);

    /** Generates and reserves the next unique serial ({@code 0x<hex>}). Atomic. */
    String nextSerial();

    /** Returns all records. */
    List<NipCertRecord> list();

    /** Returns all revoked records (for CRL generation). */
    List<NipCertRecord> getRevoked();

    /** Returns every record whose {@code parentNid} equals {@code parentNid}. */
    List<NipCertRecord> getByParentNid(String parentNid);
}
