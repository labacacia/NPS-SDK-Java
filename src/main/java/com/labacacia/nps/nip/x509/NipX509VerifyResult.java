// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.x509;

import java.security.cert.X509Certificate;

/** Result of {@link NipX509Verifier#verify}. */
public final class NipX509VerifyResult {

    private final boolean          valid;
    private final String           errorCode;
    private final String           message;
    private final X509Certificate  leaf;

    private NipX509VerifyResult(boolean valid, String errorCode, String message, X509Certificate leaf) {
        this.valid     = valid;
        this.errorCode = errorCode;
        this.message   = message;
        this.leaf      = leaf;
    }

    public boolean         valid()     { return valid; }
    public String          errorCode() { return errorCode; }
    public String          message()   { return message; }
    public X509Certificate leaf()      { return leaf; }

    public static NipX509VerifyResult ok(X509Certificate leaf) {
        return new NipX509VerifyResult(true, null, null, leaf);
    }

    public static NipX509VerifyResult fail(String errorCode, String message) {
        return new NipX509VerifyResult(false, errorCode, message, null);
    }
}
