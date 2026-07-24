// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.ra;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link IPendingStore} for single-node deployments and integration
 * tests. Records older than {@code maxAge} in a terminal (approved/rejected)
 * state are swept lazily on write to bound memory growth (NPS-CR-0005 §3.4).
 */
public final class InMemoryPendingStore implements IPendingStore {

    private final Object gate = new Object();
    private final Map<String, PendingRegistration> records = new LinkedHashMap<>();
    private final Duration maxAge;

    public InMemoryPendingStore(Duration maxAge) { this.maxAge = maxAge; }

    @Override
    public int pendingCount() {
        synchronized (gate) {
            int n = 0;
            for (PendingRegistration r : records.values())
                if (r.status() == PendingStatus.PENDING) n++;
            return n;
        }
    }

    @Override
    public String enqueue(PendingRegistration request) {
        synchronized (gate) {
            sweep();
            records.put(request.id(), request);
        }
        return request.id();
    }

    @Override
    public List<PendingRegistration> list() {
        synchronized (gate) { return new ArrayList<>(records.values()); }
    }

    @Override
    public PendingRegistration get(String id) {
        synchronized (gate) { return records.get(id); }
    }

    @Override
    public boolean approve(String id) {
        synchronized (gate) {
            PendingRegistration r = records.get(id);
            if (r == null || r.status() != PendingStatus.PENDING) return false;
            records.put(id, r.withStatus(PendingStatus.APPROVED));
            return true;
        }
    }

    @Override
    public boolean reject(String id, String reason) {
        synchronized (gate) {
            PendingRegistration r = records.get(id);
            if (r == null || r.status() != PendingStatus.PENDING) return false;
            records.put(id, r.withReject(reason));
            return true;
        }
    }

    /** Removes terminal records older than {@code maxAge}. Caller holds {@code gate}. */
    private void sweep() {
        Instant cutoff = Instant.now().minus(maxAge);
        records.values().removeIf(r ->
            r.status() != PendingStatus.PENDING && r.requestedAt().isBefore(cutoff));
    }
}
