// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.util.List;

public record LlmToolDefinitionDto(String name, String description, List<ToolParameterDto> parameters) {}
