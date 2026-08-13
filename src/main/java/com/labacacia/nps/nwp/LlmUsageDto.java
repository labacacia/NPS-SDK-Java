// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmUsageDto(
    @JsonProperty("input_tokens") Integer inputTokens,
    @JsonProperty("output_tokens") Integer outputTokens,
    @JsonProperty("cache_hit") Boolean cacheHit,
    @JsonProperty("reused_tokens") Integer reusedTokens,
    @JsonProperty("evaluated_tokens") Integer evaluatedTokens,
    @JsonProperty("wire_input_bytes") Long wireInputBytes) {}
