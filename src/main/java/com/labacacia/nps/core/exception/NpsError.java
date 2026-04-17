// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.exception;

/** Base unchecked exception for all NPS errors. */
public class NpsError extends RuntimeException {
    public NpsError(String message) { super(message); }
    public NpsError(String message, Throwable cause) { super(message, cause); }
}
