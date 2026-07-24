// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.ra;

import java.time.Instant;
import java.util.List;

/**
 * Store for pending registration requests awaiting operator approval
 * (NPS-CR-0005 §3.4).
 */
public interface IPendingStore {

    /** Enqueues a new pending registration and returns its ID. */
    String enqueue(PendingRegistration request);

    /** Lists all pending registrations (all statuses). */
    List<PendingRegistration> list();

    /** Returns a single pending registration by ID, or null. */
    PendingRegistration get(String id);

    /** Transitions a Pending record to Approved. False if not found or not Pending. */
    boolean approve(String id);

    /** Transitions a Pending record to Rejected. False if not found or not Pending. */
    boolean reject(String id, String reason);

    /** Count of records currently in Pending status. */
    int pendingCount();

    /** Lifecycle state of a pending registration record. */
    enum PendingStatus { PENDING, APPROVED, REJECTED }

    /** A registration request waiting for operator approval. */
    record PendingRegistration(
        String        id,
        String        entityType,
        String        identifier,
        String        pubKey,
        List<String>  capabilities,
        String        scopeJson,
        String        metadataJson,
        Instant       requestedAt,
        PendingStatus status,
        String        rejectReason) {

        public PendingRegistration withStatus(PendingStatus s) {
            return new PendingRegistration(id, entityType, identifier, pubKey,
                capabilities, scopeJson, metadataJson, requestedAt, s, rejectReason);
        }

        public PendingRegistration withReject(String reason) {
            return new PendingRegistration(id, entityType, identifier, pubKey,
                capabilities, scopeJson, metadataJson, requestedAt, PendingStatus.REJECTED, reason);
        }
    }
}
