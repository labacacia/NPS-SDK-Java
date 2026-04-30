// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

/** Result of {@link NipIdentVerifier#verify}. */
public final class NipIdentVerifyResult {

    private final boolean valid;
    private final int     stepFailed;     // 0 = none, 1 = signature, 2 = assurance, 3 = X.509
    private final String  errorCode;
    private final String  message;

    private NipIdentVerifyResult(boolean valid, int stepFailed, String errorCode, String message) {
        this.valid      = valid;
        this.stepFailed = stepFailed;
        this.errorCode  = errorCode;
        this.message    = message;
    }

    public boolean valid()      { return valid; }
    public int     stepFailed() { return stepFailed; }
    public String  errorCode()  { return errorCode; }
    public String  message()    { return message; }

    public static NipIdentVerifyResult ok() {
        return new NipIdentVerifyResult(true, 0, null, null);
    }

    public static NipIdentVerifyResult fail(int stepFailed, String errorCode, String message) {
        return new NipIdentVerifyResult(false, stepFailed, errorCode, message);
    }
}
