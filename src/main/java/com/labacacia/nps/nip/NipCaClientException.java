// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

public final class NipCaClientException extends RuntimeException {
    private final String errorCode;
    private final int statusCode;

    public NipCaClientException(String errorCode, String message, int statusCode) {
        super(message);
        this.errorCode = errorCode;
        this.statusCode = statusCode;
    }

    public String errorCode() {
        return errorCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
