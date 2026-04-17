// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.exception;

public class NpsCodecError extends NpsError {
    public NpsCodecError(String message) { super(message); }
    public NpsCodecError(String message, Throwable cause) { super(message, cause); }
}
