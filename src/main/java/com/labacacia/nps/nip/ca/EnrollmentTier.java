// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

/**
 * Enrollment-tier selector for the NIP CA Registration Authority model
 * (NPS-CR-0005 §3). Governs which gate an inbound registration request must
 * pass before the CA issues an IdentFrame.
 *
 * <p>The numeric {@link #tier()} value is stable wire-form (advertised in
 * {@code /.well-known/nps-ca} as {@code ra-tier-<n>}), matching the .NET
 * reference enum ordinals: Allowlist=1, BootstrapToken=2, PendingQueue=3.
 */
public enum EnrollmentTier {

    /** Tier 1: operator-configured glob allowlist. Default tier. */
    ALLOWLIST(1),

    /** Tier 2: single-use bootstrap token (prefix {@code nps-bootstrap-}). */
    BOOTSTRAP_TOKEN(2),

    /** Tier 3: all registrations queued for operator approval (202 Accepted). */
    PENDING_QUEUE(3);

    private final int tier;

    EnrollmentTier(int tier) { this.tier = tier; }

    /** Stable numeric tier value (1..3). */
    public int tier() { return tier; }
}
