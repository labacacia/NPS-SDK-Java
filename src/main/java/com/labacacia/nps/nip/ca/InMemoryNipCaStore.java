// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link INipCaStore} for tests, demos, and single-process stacks.
 * Not durable. Mirrors the .NET {@code InMemoryNipCaStore} semantics: last
 * record wins on {@code getByNid} (renewals append), revoke targets the last
 * un-revoked record.
 */
public final class InMemoryNipCaStore implements INipCaStore {

    private final Object gate = new Object();
    private final List<NipCertRecord> records = new ArrayList<>();
    private final AtomicLong serial = new AtomicLong();

    @Override
    public void save(NipCertRecord record) {
        // NID uniqueness is enforced at issuance time by the service (getByNid pre-check);
        // renewal deliberately appends a fresh record under the same NID (old one kept for
        // audit, last-wins on getByNid — NPS-3 §6). Only the serial must be globally unique.
        synchronized (gate) {
            for (NipCertRecord r : records) {
                if (r.serial().equals(record.serial()))
                    throw new IllegalStateException("Serial already exists: " + record.serial());
            }
            records.add(record);
        }
    }

    @Override
    public NipCertRecord getByNid(String nid) {
        synchronized (gate) {
            NipCertRecord found = null;
            for (NipCertRecord r : records) {
                if (r.nid().equals(nid)) found = r; // last wins
            }
            return found;
        }
    }

    @Override
    public NipCertRecord getBySerial(String serial) {
        synchronized (gate) {
            for (NipCertRecord r : records) {
                if (r.serial().equals(serial)) return r; // first wins
            }
            return null;
        }
    }

    @Override
    public boolean revoke(String nid, String reason, Instant revokedAt) {
        synchronized (gate) {
            int idx = -1;
            for (int i = 0; i < records.size(); i++) {
                NipCertRecord r = records.get(i);
                if (r.nid().equals(nid) && !r.isRevoked()) idx = i; // last un-revoked
            }
            if (idx < 0) return false;
            records.set(idx, records.get(idx).revoked(revokedAt, reason));
            return true;
        }
    }

    @Override
    public String nextSerial() {
        return "0x" + Long.toHexString(serial.incrementAndGet()).toUpperCase();
    }

    @Override
    public List<NipCertRecord> list() {
        synchronized (gate) { return new ArrayList<>(records); }
    }

    @Override
    public List<NipCertRecord> getRevoked() {
        synchronized (gate) {
            List<NipCertRecord> out = new ArrayList<>();
            for (NipCertRecord r : records) if (r.isRevoked()) out.add(r);
            return out;
        }
    }

    @Override
    public List<NipCertRecord> getByParentNid(String parentNid) {
        synchronized (gate) {
            List<NipCertRecord> out = new ArrayList<>();
            for (NipCertRecord r : records) if (Objects.equals(r.parentNid(), parentNid)) out.add(r);
            return out;
        }
    }
}
