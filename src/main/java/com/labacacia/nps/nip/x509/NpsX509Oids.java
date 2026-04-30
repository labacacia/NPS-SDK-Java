// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.x509;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;

/**
 * OID constants for NPS X.509 certificates per NPS-RFC-0002 §4.
 * <p>
 * The {@code 1.3.6.1.4.1.99999} arc is provisional pending IANA Private
 * Enterprise Number assignment (RFC-0002 §10 OQ-2). All implementations
 * MUST update these constants when the official PEN is granted.
 */
public final class NpsX509Oids {

    private NpsX509Oids() {}

    // ── Provisional LabAcacia PEN arc ────────────────────────────────────────
    public static final String LAB_ACACIA_PEN_ARC = "1.3.6.1.4.1.99999";
    public static final String EKU_ARC            = LAB_ACACIA_PEN_ARC + ".1";
    public static final String EXTENSION_ARC      = LAB_ACACIA_PEN_ARC + ".2";

    // ── EKUs ─────────────────────────────────────────────────────────────────
    public static final ASN1ObjectIdentifier EKU_AGENT_IDENTITY =
        new ASN1ObjectIdentifier(EKU_ARC + ".1");
    public static final ASN1ObjectIdentifier EKU_NODE_IDENTITY =
        new ASN1ObjectIdentifier(EKU_ARC + ".2");
    public static final ASN1ObjectIdentifier EKU_CA_INTERMEDIATE_AGENT =
        new ASN1ObjectIdentifier(EKU_ARC + ".3");

    // ── Custom extensions ────────────────────────────────────────────────────
    public static final ASN1ObjectIdentifier NID_ASSURANCE_LEVEL =
        new ASN1ObjectIdentifier(EXTENSION_ARC + ".1");

    // ── Ed25519 algorithm OID per RFC 8410 ───────────────────────────────────
    public static final ASN1ObjectIdentifier ED25519 =
        new ASN1ObjectIdentifier("1.3.101.112");
}
