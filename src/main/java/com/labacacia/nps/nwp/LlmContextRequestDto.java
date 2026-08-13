// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmContextRequestDto(
    LlmContextOperation operation,
    @JsonProperty("context_id") String contextId,
    @JsonProperty("base_version") Long baseVersion,
    @JsonProperty("ttl_seconds") Integer ttlSeconds) {}
