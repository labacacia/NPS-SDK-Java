// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

/** Thrown when an input mapping path cannot be resolved. */
public final class NopMappingException extends RuntimeException {

    private final String errorCode;

    public NopMappingException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
