// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.models;

/**
 * Error payload carried by an AlignStream final frame (NPS-5 §3.4).
 *
 * @param code      NOP error code (e.g. {@code NOP-TASK-TIMEOUT}).
 * @param message   Human-readable error description.
 * @param retryable Whether the caller may retry this operation.
 */
public record StreamError(String code, String message, boolean retryable) {
}
