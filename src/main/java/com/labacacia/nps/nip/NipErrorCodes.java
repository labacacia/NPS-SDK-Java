// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.core.NpsStatusCodes;

import java.util.Map;

/**
 * NIP error code string constants. Mirror of {@code spec/error-codes.md} NIP section.
 * Values are the canonical wire strings; consumers MUST compare by string equality.
 */
public interface NipErrorCodes {

    // ── Cert verification (v1 + v2) ──────────────────────────────────────────
    String CERT_EXPIRED            = "NIP-CERT-EXPIRED";
    String CERT_REVOKED            = "NIP-CERT-REVOKED";
    String CERT_SIGNATURE_INVALID  = "NIP-CERT-SIGNATURE-INVALID";
    String CERT_UNTRUSTED_ISSUER   = "NIP-CERT-UNTRUSTED-ISSUER";
    String CERT_CAPABILITY_MISSING = "NIP-CERT-CAPABILITY-MISSING";
    String CERT_SCOPE_VIOLATION    = "NIP-CERT-SCOPE-VIOLATION";

    // ── CA service ───────────────────────────────────────────────────────────
    String CA_NID_NOT_FOUND          = "NIP-CA-NID-NOT-FOUND";
    String CA_NID_ALREADY_EXISTS     = "NIP-CA-NID-ALREADY-EXISTS";
    String CA_SERIAL_DUPLICATE       = "NIP-CA-SERIAL-DUPLICATE";
    String CA_RENEWAL_TOO_EARLY      = "NIP-CA-RENEWAL-TOO-EARLY";
    String CA_SCOPE_EXPANSION_DENIED = "NIP-CA-SCOPE-EXPANSION-DENIED";

    String OCSP_UNAVAILABLE    = "NIP-OCSP-UNAVAILABLE";

    // ── Trust / Revoke frames (NPS-3 §5.2–5.3) ──────────────────────────────
    String TRUST_FRAME_INVALID                 = "NIP-TRUST-FRAME-INVALID";
    String TRUST_FRAME_EXPIRED                 = "NIP-TRUST-FRAME-EXPIRED";
    String TRUST_FRAME_GRANTOR_REVOKED         = "NIP-TRUST-FRAME-GRANTOR-REVOKED";
    String TRUST_FRAME_SCOPE_EXCEEDS_GRANTOR   = "NIP-TRUST-FRAME-SCOPE-EXCEEDS-GRANTOR";
    String TRUST_FRAME_NODES_PATTERN_INVALID   = "NIP-TRUST-FRAME-NODES-PATTERN-INVALID";
    String REVOKE_FRAME_INVALID                = "NIP-REVOKE-FRAME-INVALID";
    String REVOKE_FRAME_UNAUTHORIZED_ISSUER    = "NIP-REVOKE-FRAME-UNAUTHORIZED-ISSUER";
    String REVOKE_FRAME_SERIAL_MISMATCH        = "NIP-REVOKE-FRAME-SERIAL-MISMATCH";
    String REVOKE_FRAME_REASON_UNKNOWN         = "NIP-REVOKE-FRAME-REASON-UNKNOWN";

    // ── RFC-0003 (assurance level) ───────────────────────────────────────────
    String ASSURANCE_MISMATCH = "NIP-ASSURANCE-MISMATCH";
    String ASSURANCE_UNKNOWN  = "NIP-ASSURANCE-UNKNOWN";

    // ── RFC-0004 (reputation log) ────────────────────────────────────────────
    String REPUTATION_ENTRY_INVALID   = "NIP-REPUTATION-ENTRY-INVALID";
    String REPUTATION_LOG_UNREACHABLE = "NIP-REPUTATION-LOG-UNREACHABLE";
    String REPUTATION_GOSSIP_FORK     = "NIP-REPUTATION-GOSSIP-FORK";
    String REPUTATION_GOSSIP_SIG_INVALID = "NIP-REPUTATION-GOSSIP-SIG-INVALID";

    // ── RFC-0002 (X.509 + ACME) ──────────────────────────────────────────────
    String CERT_FORMAT_INVALID       = "NIP-CERT-FORMAT-INVALID";
    String CERT_EKU_MISSING          = "NIP-CERT-EKU-MISSING";
    String CERT_SUBJECT_NID_MISMATCH = "NIP-CERT-SUBJECT-NID-MISMATCH";
    String ACME_CHALLENGE_FAILED     = "NIP-ACME-CHALLENGE-FAILED";

    // ── NPS-CR-0003 (group / session NIDs) ──────────────────────────────────
    String CA_GROUP_REVOKED          = "NIP-CA-GROUP-REVOKED";
    String CA_PARENT_NOT_FOUND       = "NIP-CA-PARENT-NOT-FOUND";
    String CA_PARENT_NOT_GROUP       = "NIP-CA-PARENT-NOT-GROUP";
    String CA_SESSION_VALIDITY_INVALID = "NIP-CA-SESSION-VALIDITY-INVALID";
    String CA_JWS_INVALID            = "NIP-CA-JWS-INVALID";
    String CA_JWS_EXPIRED            = "NIP-CA-JWS-EXPIRED";
    String CERT_PARENT_REVOKED       = "NIP-CERT-PARENT-REVOKED";

    // ── NPS-CR-0005 (RA enrollment tiers) ───────────────────────────────────
    String RA_TOKEN_INVALID          = "NIP-RA-TOKEN-INVALID";
    String RA_TOKEN_EXPIRED          = "NIP-RA-TOKEN-EXPIRED";
    String RA_NID_NOT_ALLOWED        = "NIP-RA-NID-NOT-ALLOWED";
    String RA_PENDING_REJECTED       = "NIP-RA-PENDING-REJECTED";

    // ── OCSP staple (roadmap) ─────────────────────────────────────────────────
    String OCSP_STAPLE_EXPIRED       = "NIP-OCSP-STAPLE-EXPIRED";

    // ── NIP v0.10 — node_roles ────────────────────────────────────────────────
    String CERT_NODE_ROLES_MISMATCH  = "NIP-CERT-NODE-ROLES-MISMATCH";

    // ── NIP v0.12 — §7.5 Phase-3 enforcement ─────────────────────────────────
    /**
     * The IdentFrame claims capabilities the CA did not attest in the leaf's
     * {@code id-nps-capabilities} extension. Note the deliberate asymmetry with its
     * sibling {@link #CERT_NODE_ROLES_MISMATCH}: this maps to {@code NPS-AUTH-FORBIDDEN},
     * that one to {@code NPS-CLIENT-BAD-FRAME}.
     */
    String CERT_CAPABILITIES_EXCEEDED = "NIP-CERT-CAPABILITIES-EXCEEDED";

    // ── NIP error → NPS status mapping ───────────────────────────────────────

    /**
     * Maps each NIP error code to its corresponding NPS status code.
     */
    Map<String, String> NIP_TO_NPS_STATUS = Map.ofEntries(
        Map.entry(CERT_EXPIRED,                     NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(CERT_REVOKED,                     NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(CERT_SIGNATURE_INVALID,           NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(CERT_UNTRUSTED_ISSUER,            NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(CERT_CAPABILITY_MISSING,          NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(CERT_SCOPE_VIOLATION,             NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(CA_NID_NOT_FOUND,                 NpsStatusCodes.NPS_CLIENT_NOT_FOUND),
        Map.entry(CA_NID_ALREADY_EXISTS,            NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(CA_SERIAL_DUPLICATE,              NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(CA_RENEWAL_TOO_EARLY,             NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(CA_SCOPE_EXPANSION_DENIED,        NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(OCSP_UNAVAILABLE,                 NpsStatusCodes.NPS_SERVER_UNAVAILABLE),
        Map.entry(TRUST_FRAME_INVALID,              NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(TRUST_FRAME_EXPIRED,              NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(TRUST_FRAME_GRANTOR_REVOKED,      NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(TRUST_FRAME_SCOPE_EXCEEDS_GRANTOR,NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(TRUST_FRAME_NODES_PATTERN_INVALID,NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(REVOKE_FRAME_INVALID,             NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(REVOKE_FRAME_UNAUTHORIZED_ISSUER, NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(REVOKE_FRAME_SERIAL_MISMATCH,     NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(REVOKE_FRAME_REASON_UNKNOWN,      NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(ASSURANCE_MISMATCH,               NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(ASSURANCE_UNKNOWN,                NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(REPUTATION_ENTRY_INVALID,         NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(REPUTATION_LOG_UNREACHABLE,       NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE),
        Map.entry(REPUTATION_GOSSIP_FORK,           NpsStatusCodes.NPS_SERVER_INTERNAL),
        Map.entry(REPUTATION_GOSSIP_SIG_INVALID,    NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(CERT_FORMAT_INVALID,              NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(CERT_EKU_MISSING,                 NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(CERT_SUBJECT_NID_MISMATCH,        NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(ACME_CHALLENGE_FAILED,            NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(CA_GROUP_REVOKED,                 NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(CA_PARENT_NOT_FOUND,              NpsStatusCodes.NPS_CLIENT_NOT_FOUND),
        Map.entry(CA_PARENT_NOT_GROUP,              NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(CA_SESSION_VALIDITY_INVALID,      NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(CA_JWS_INVALID,                   NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(CA_JWS_EXPIRED,                   NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(CERT_PARENT_REVOKED,              NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(OCSP_STAPLE_EXPIRED,              NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(CERT_NODE_ROLES_MISMATCH,         NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(CERT_CAPABILITIES_EXCEEDED,       NpsStatusCodes.NPS_AUTH_FORBIDDEN)
    );
}
