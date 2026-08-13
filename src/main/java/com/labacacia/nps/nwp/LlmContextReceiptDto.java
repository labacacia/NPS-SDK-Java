// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmContextReceiptDto(
    @JsonProperty("context_id") String contextId,
    long version,
    LlmContextOperation operation,
    LlmContextState state,
    @JsonProperty("expires_at") String expiresAt,
    @JsonProperty("parent_context_id") String parentContextId,
    @JsonProperty("parent_version") Long parentVersion) {}
