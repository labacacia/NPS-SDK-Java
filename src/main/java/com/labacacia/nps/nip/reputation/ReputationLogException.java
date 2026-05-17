// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.reputation;

/**
 * Thrown when a {@link ReputationLogClient} operation fails with a NIP error.
 * Carries the NIP wire error code and the raw NPS HTTP status string.
 */
public final class ReputationLogException extends Exception {

    private final String nipErrorCode;
    private final String npsStatus;

    public ReputationLogException(String nipErrorCode, String npsStatus, String message) {
        super(message);
        this.nipErrorCode = nipErrorCode;
        this.npsStatus    = npsStatus;
    }

    public ReputationLogException(String nipErrorCode, String npsStatus, String message, Throwable cause) {
        super(message, cause);
        this.nipErrorCode = nipErrorCode;
        this.npsStatus    = npsStatus;
    }

    /** The NIP wire error code (e.g. {@code "NIP-REPUTATION-ENTRY-INVALID"}). */
    public String getNipErrorCode() { return nipErrorCode; }

    /** The NPS HTTP status string returned by the server, or {@code null} if not available. */
    public String getNpsStatus() { return npsStatus; }
}
