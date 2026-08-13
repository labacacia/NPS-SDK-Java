// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmContextStatusDto(
    LlmContextState state,
    @JsonProperty("context_id") String contextId,
    Long version,
    @JsonProperty("expires_at") String expiresAt,
    @JsonProperty("request_id") String requestId,
    @JsonProperty("error_code") String errorCode) {}
