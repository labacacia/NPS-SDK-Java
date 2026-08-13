// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record LlmCompleteActionRequest(
    String kind,
    String model,
    @JsonProperty("max_tokens") Integer maxTokens,
    boolean stream,
    List<LlmMessageDto> messages,
    List<LlmToolDefinitionDto> tools,
    LlmContextRequestDto context) {
    public LlmCompleteActionRequest {
        if (kind == null) kind = LlmActionCodec.LLM_COMPLETE;
    }
}
