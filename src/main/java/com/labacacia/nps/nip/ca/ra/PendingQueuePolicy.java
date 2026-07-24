// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.ra;

import com.labacacia.nps.nip.NipErrorCodes;
import com.labacacia.nps.nip.ca.NipCaException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Enrollment Tier 3: every inbound registration request is queued as a
 * {@link IPendingStore.PendingRegistration}. The CA replies 202 Accepted with a
 * {@code pending_id}; an operator must approve or reject via the management
 * endpoints (NPS-CR-0005 §3.4).
 */
public final class PendingQueuePolicy implements IEnrollmentPolicy {

    private final IPendingStore store;
    private final int maxSize;

    public PendingQueuePolicy(IPendingStore store, int maxSize) {
        this.store = store;
        this.maxSize = maxSize;
    }

    @Override
    public void check(String entityType, String identifier, String pubKey,
                      List<String> capabilities, String scopeJson, String metadataJson,
                      String enrollmentToken) {
        if (store.pendingCount() >= maxSize) {
            throw new NipCaException(
                "Pending enrollment queue is full (max " + maxSize + "). Retry later.",
                NipErrorCodes.RA_TOKEN_INVALID);
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        IPendingStore.PendingRegistration req = new IPendingStore.PendingRegistration(
            id, entityType, identifier, pubKey,
            capabilities, scopeJson, metadataJson,
            Instant.now(), IPendingStore.PendingStatus.PENDING, null);
        store.enqueue(req);
        throw new NipRaPendingException(id);
    }
}
