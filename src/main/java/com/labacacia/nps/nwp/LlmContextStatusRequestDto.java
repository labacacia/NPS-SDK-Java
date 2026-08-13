// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmContextStatusRequestDto(
    @JsonProperty("context_id") String contextId,
    @JsonProperty("idempotency_key") String idempotencyKey) {}
