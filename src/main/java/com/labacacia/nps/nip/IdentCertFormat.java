// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

/** Wire-form constants for {@link IdentFrame#certFormat()}. */
public final class IdentCertFormat {

    private IdentCertFormat() {}

    /** Legacy proprietary IdentFrame signature (Phase 0/1 default). */
    public static final String V1_PROPRIETARY = "v1-proprietary";

    /** RFC-0002 X.509 + ACME chain (Phase 1 dual-trust, optional). */
    public static final String V2_X509 = "v2-x509";
}
