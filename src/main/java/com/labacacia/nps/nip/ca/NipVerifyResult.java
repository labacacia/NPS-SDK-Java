// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

/** Result of a NIP certificate verification check (NPS-3 §7). */
public final class NipVerifyResult {

    private final boolean       valid;
    private final String        errorCode;
    private final String        message;
    private final NipCertRecord record;

    private NipVerifyResult(boolean valid, String errorCode, String message, NipCertRecord record) {
        this.valid = valid;
        this.errorCode = errorCode;
        this.message = message;
        this.record = record;
    }

    public boolean       valid()     { return valid; }
    public String        errorCode() { return errorCode; }
    public String        message()   { return message; }
    public NipCertRecord record()    { return record; }

    public static NipVerifyResult ok(NipCertRecord record) {
        return new NipVerifyResult(true, null, null, record);
    }

    public static NipVerifyResult fail(String errorCode, String message) {
        return new NipVerifyResult(false, errorCode, message, null);
    }
}
