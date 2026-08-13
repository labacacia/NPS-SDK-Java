// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmToolCallDto(
    @JsonProperty("call_id") String callId,
    @JsonProperty("tool_name") String toolName,
    @JsonProperty("arguments_json") String argumentsJson) {}
