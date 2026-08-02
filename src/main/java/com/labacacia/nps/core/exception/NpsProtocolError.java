// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.exception;

/**
 * An {@link NpsError} that carries a protocol error code from
 * {@code spec/error-codes.md} (e.g. {@code NCP-NID-MISMATCH}), so callers can branch on
 * the exact fault rather than on an exception type alone.
 */
public class NpsProtocolError extends NpsError {

    private final String protocolErrorCode;
    private final String npsStatus;   // nullable

    public NpsProtocolError(String protocolErrorCode, String message) {
        this(protocolErrorCode, null, message, null);
    }

    public NpsProtocolError(String protocolErrorCode, String npsStatus, String message) {
        this(protocolErrorCode, npsStatus, message, null);
    }

    public NpsProtocolError(String protocolErrorCode, String npsStatus, String message, Throwable cause) {
        super(message, cause);
        this.protocolErrorCode = protocolErrorCode;
        this.npsStatus         = npsStatus;
    }

    /** The wire error code, e.g. {@code "NCP-NID-MISMATCH"}. */
    public String protocolErrorCode() { return protocolErrorCode; }

    /** The mapped NPS status, when the raiser knew it. */
    public String npsStatus() { return npsStatus; }
}
