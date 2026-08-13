// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.util.List;

/** Stateful completion mutation admitted before provider dispatch. */
public record LlmContextMutationRequest(
    LlmContextOperation operation,
    LlmContextOwner owner,
    String contextId,
    Long baseVersion,
    LlmContextBinding binding,
    List<LlmMessageDto> messages,
    Integer ttlSeconds,
    String idempotencyKey,
    String requestId) {}
