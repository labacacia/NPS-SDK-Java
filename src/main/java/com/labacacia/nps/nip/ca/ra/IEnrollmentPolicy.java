// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.ra;

import com.labacacia.nps.nip.ca.NipCaException;

import java.util.List;

/**
 * Gate that must pass before a NIP CA issues an IdentFrame (NPS-CR-0005 §3).
 * Implementations correspond to the three enrollment tiers: allowlist (T1),
 * bootstrap token (T2), pending queue (T3).
 */
public interface IEnrollmentPolicy {

    /**
     * Checks whether {@code identifier} is permitted to enroll.
     *
     * @throws NipCaException        enrollment denied (RA_TOKEN_INVALID,
     *                               RA_TOKEN_EXPIRED, RA_NID_NOT_ALLOWED).
     * @throws NipRaPendingException Tier 3 queued the request; caller returns 202.
     */
    void check(
        String       entityType,
        String       identifier,
        String       pubKey,
        List<String> capabilities,
        String       scopeJson,
        String       metadataJson,
        String       enrollmentToken);
}
