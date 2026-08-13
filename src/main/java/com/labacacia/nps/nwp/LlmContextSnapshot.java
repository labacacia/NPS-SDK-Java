// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.time.Instant;
import java.util.List;

/** Defensive copy of committed context state for provider execution. */
public record LlmContextSnapshot(
    String contextId,
    long version,
    LlmContextState state,
    List<LlmMessageDto> transcript,
    LlmContextBinding binding,
    Instant expiresAt) {}
