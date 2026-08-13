// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/** Canonical NWP context error with optional current CAS version. */
public final class LlmContextStoreException extends RuntimeException {
    private final String errorCode;
    private final Long currentVersion;

    public LlmContextStoreException(String errorCode, String message, Long currentVersion) {
        super(message);
        this.errorCode = errorCode;
        this.currentVersion = currentVersion;
    }

    public String errorCode() { return errorCode; }
    public Long currentVersion() { return currentVersion; }
}
