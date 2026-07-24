// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.ra;

/**
 * Thrown by a Tier-3 (pending queue) {@link IEnrollmentPolicy} when it enqueues
 * a registration request. The router translates this into 202 Accepted with a
 * {@code pending_id} (NPS-CR-0005 §3.4).
 */
public final class NipRaPendingException extends RuntimeException {

    private final String pendingId;

    public NipRaPendingException(String pendingId) {
        super("Registration queued with pending id: " + pendingId);
        this.pendingId = pendingId;
    }

    /** Opaque identifier of the queued record. */
    public String pendingId() { return pendingId; }
}
