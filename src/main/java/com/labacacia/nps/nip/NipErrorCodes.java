// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

/**
 * NIP error code string constants. Mirror of {@code spec/error-codes.md} NIP section.
 * Values are the canonical wire strings; consumers MUST compare by string equality.
 */
public final class NipErrorCodes {

    private NipErrorCodes() {}

    // ── Cert verification (v1 + v2) ──────────────────────────────────────────
    public static final String CERT_EXPIRED            = "NIP-CERT-EXPIRED";
    public static final String CERT_REVOKED            = "NIP-CERT-REVOKED";
    public static final String CERT_SIGNATURE_INVALID  = "NIP-CERT-SIGNATURE-INVALID";
    public static final String CERT_UNTRUSTED_ISSUER   = "NIP-CERT-UNTRUSTED-ISSUER";
    public static final String CERT_CAPABILITY_MISSING = "NIP-CERT-CAPABILITY-MISSING";
    public static final String CERT_SCOPE_VIOLATION    = "NIP-CERT-SCOPE-VIOLATION";

    // ── CA service ───────────────────────────────────────────────────────────
    public static final String CA_NID_NOT_FOUND          = "NIP-CA-NID-NOT-FOUND";
    public static final String CA_NID_ALREADY_EXISTS     = "NIP-CA-NID-ALREADY-EXISTS";
    public static final String CA_SERIAL_DUPLICATE       = "NIP-CA-SERIAL-DUPLICATE";
    public static final String CA_RENEWAL_TOO_EARLY      = "NIP-CA-RENEWAL-TOO-EARLY";
    public static final String CA_SCOPE_EXPANSION_DENIED = "NIP-CA-SCOPE-EXPANSION-DENIED";

    public static final String OCSP_UNAVAILABLE     = "NIP-OCSP-UNAVAILABLE";
    public static final String TRUST_FRAME_INVALID  = "NIP-TRUST-FRAME-INVALID";

    // ── RFC-0003 (assurance level) ───────────────────────────────────────────
    public static final String ASSURANCE_MISMATCH = "NIP-ASSURANCE-MISMATCH";
    public static final String ASSURANCE_UNKNOWN  = "NIP-ASSURANCE-UNKNOWN";

    // ── RFC-0004 (reputation log) ────────────────────────────────────────────
    public static final String REPUTATION_ENTRY_INVALID      = "NIP-REPUTATION-ENTRY-INVALID";
    public static final String REPUTATION_LOG_UNREACHABLE    = "NIP-REPUTATION-LOG-UNREACHABLE";
    public static final String REPUTATION_GOSSIP_FORK        = "NIP-REPUTATION-GOSSIP-FORK";
    public static final String REPUTATION_GOSSIP_SIG_INVALID = "NIP-REPUTATION-GOSSIP-SIG-INVALID";

    // ── RFC-0002 (X.509 + ACME) ──────────────────────────────────────────────
    public static final String CERT_FORMAT_INVALID       = "NIP-CERT-FORMAT-INVALID";
    public static final String CERT_EKU_MISSING          = "NIP-CERT-EKU-MISSING";
    public static final String CERT_SUBJECT_NID_MISMATCH = "NIP-CERT-SUBJECT-NID-MISMATCH";
    public static final String ACME_CHALLENGE_FAILED     = "NIP-ACME-CHALLENGE-FAILED";
}
