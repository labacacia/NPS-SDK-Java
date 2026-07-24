// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

/**
 * Thrown when a NIP CA operation cannot be completed (NPS-3 §9). Carries a
 * machine-readable {@link #errorCode()} from
 * {@link com.labacacia.nps.nip.NipErrorCodes}.
 */
public final class NipCaException extends RuntimeException {

    private final String errorCode;

    public NipCaException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    /** Canonical NIP wire error code (e.g. {@code NIP-CA-NID-NOT-FOUND}). */
    public String errorCode() { return errorCode; }
}
