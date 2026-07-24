// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.ra;

import java.time.Instant;
import java.util.List;

/**
 * Persistent store for single-use enrollment bootstrap tokens (NPS-CR-0005
 * §3.3). Tokens are stored as SHA-256 hashes; the raw value is returned only
 * once at creation and never persisted.
 */
public interface IBootstrapTokenStore {

    /** Creates a token, stores its hash, and returns the raw {@code nps-bootstrap-} value. */
    String create(String label, Instant expiresAt);

    /** Validates and atomically consumes {@code token}. False if unknown/consumed/expired. */
    boolean validateAndConsume(String token);

    /** Returns all tokens (value excluded) for operator inspection. */
    List<BootstrapTokenInfo> list();

    /** Administratively revokes a token before consumption. False if unknown/already consumed. */
    boolean revoke(String tokenId);

    /** Public metadata for a bootstrap token (token value excluded). */
    record BootstrapTokenInfo(
        String  id,
        String  label,
        Instant createdAt,
        Instant expiresAt,
        boolean consumed,
        boolean revoked) {}
}
