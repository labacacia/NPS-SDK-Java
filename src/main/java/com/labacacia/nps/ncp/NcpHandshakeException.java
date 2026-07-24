// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.exception.NpsError;

/**
 * Thrown by {@link NcpNativeClient} when the server rejects the native-mode
 * handshake (sends an {@link ErrorFrame}) or answers with an unexpected frame.
 *
 * <p>{@link #errorCode} carries the canonical NCP error string — for a server
 * rejection it is the {@code error} field of the received {@link ErrorFrame};
 * for a protocol violation it is {@code NCP-HANDSHAKE-UNEXPECTED-FRAME}.
 */
public final class NcpHandshakeException extends NpsError {

    /** Canonical NCP error code for an unexpected handshake frame. */
    public static final String UNEXPECTED_FRAME = "NCP-HANDSHAKE-UNEXPECTED-FRAME";

    private final String errorCode;

    public NcpHandshakeException(String errorCode, String message) {
        super(message != null ? message : errorCode);
        this.errorCode = errorCode;
    }

    /** The canonical NCP error code that caused the handshake to fail. */
    public String errorCode() {
        return errorCode;
    }
}
