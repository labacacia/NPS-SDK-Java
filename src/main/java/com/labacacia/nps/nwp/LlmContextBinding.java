// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.util.List;

/** Immutable inputs that determine whether a retained model prefix can be reused. */
public record LlmContextBinding(
    String model,
    List<LlmMessageDto> systemMessages,
    List<LlmToolDefinitionDto> tools,
    String runtimeRevision) {}
