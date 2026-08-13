// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record LlmMessageDto(
    String role,
    String content,
    @JsonProperty("tool_call_id") String toolCallId,
    @JsonProperty("tool_name") String toolName,
    @JsonProperty("tool_calls") List<LlmToolCallDto> toolCalls) {}
