// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.ra;

import com.labacacia.nps.nip.NipErrorCodes;
import com.labacacia.nps.nip.ca.NipCaException;

import java.util.List;

/**
 * Enrollment Tier 2: caller must present a valid single-use bootstrap token
 * obtained via {@code POST /v1/enrollment/tokens} (NPS-CR-0005 §3.3). The token
 * is consumed atomically on success.
 */
public final class BootstrapTokenPolicy implements IEnrollmentPolicy {

    private final IBootstrapTokenStore store;

    public BootstrapTokenPolicy(IBootstrapTokenStore store) { this.store = store; }

    @Override
    public void check(String entityType, String identifier, String pubKey,
                      List<String> capabilities, String scopeJson, String metadataJson,
                      String enrollmentToken) {
        if (enrollmentToken == null || enrollmentToken.isEmpty()
                || !enrollmentToken.startsWith("nps-bootstrap-")) {
            throw new NipCaException(
                "A bootstrap token (prefix 'nps-bootstrap-') is required for enrollment.",
                NipErrorCodes.RA_TOKEN_INVALID);
        }
        if (!store.validateAndConsume(enrollmentToken)) {
            throw new NipCaException(
                "Bootstrap token is invalid, expired, or already consumed.",
                NipErrorCodes.RA_TOKEN_EXPIRED);
        }
    }
}
