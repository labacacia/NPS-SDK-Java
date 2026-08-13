// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record LlmCompleteStreamChunkDto(
    @JsonProperty("content_delta") String contentDelta,
    @JsonProperty("tool_calls") List<LlmToolCallDto> toolCalls,
    @JsonProperty("stop_reason") LlmStopReason stopReason,
    String error,
    LlmUsageDto usage,
    LlmContextReceiptDto context) {}
