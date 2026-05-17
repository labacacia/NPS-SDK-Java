// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.reputation;

/**
 * Incident type enum for NPS-RFC-0004 reputation log entries.
 * Unknown wire strings are mapped to {@link #OTHER} for forward compatibility.
 */
public enum IncidentType {
    OTHER("other"),
    CERT_REVOKED("cert-revoked"),
    RATE_LIMIT_VIOLATION("rate-limit-violation"),
    TOS_VIOLATION("tos-violation"),
    SCRAPING_PATTERN("scraping-pattern"),
    PAYMENT_DEFAULT("payment-default"),
    CONTRACT_DISPUTE("contract-dispute"),
    IMPERSONATION_CLAIM("impersonation-claim"),
    POSITIVE_ATTESTATION("positive-attestation");

    public final String wire;

    IncidentType(String wire) {
        this.wire = wire;
    }

    /**
     * Resolve a wire string to an {@link IncidentType}.
     * Unknown values return {@link #OTHER} for forward compatibility.
     */
    public static IncidentType fromWire(String s) {
        for (IncidentType t : values()) {
            if (t.wire.equals(s)) return t;
        }
        return OTHER; // forward-compat: unknown → OTHER
    }
}
