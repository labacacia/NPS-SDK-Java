// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/**
 * Exception raised when a Bridge Node cannot parse, route, or execute a bridge
 * invocation. {@link #errorCode()} carries the NWP-compatible failure code.
 */
public final class BridgeDispatchException extends RuntimeException {

    private final String errorCode;

    /** Create a Bridge dispatch exception. */
    public BridgeDispatchException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** Create a Bridge dispatch exception with an inner cause. */
    public BridgeDispatchException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** NWP-compatible error code for the failed dispatch. */
    public String errorCode() {
        return errorCode;
    }
}
