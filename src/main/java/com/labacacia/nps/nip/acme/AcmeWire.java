// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.acme;

/** ACME wire constants (RFC 8555 + NPS-RFC-0002 §4.4). */
public final class AcmeWire {

    private AcmeWire() {}

    // ── RFC 8555 ─────────────────────────────────────────────────────────────
    public static final String CONTENT_TYPE_JOSE_JSON = "application/jose+json";
    public static final String CONTENT_TYPE_PROBLEM   = "application/problem+json";
    public static final String CONTENT_TYPE_PEM_CERT  = "application/pem-certificate-chain";

    // ── NPS-RFC-0002 §4.4 ────────────────────────────────────────────────────
    public static final String CHALLENGE_AGENT_01 = "agent-01";
    public static final String IDENTIFIER_TYPE_NID = "nid";
}
