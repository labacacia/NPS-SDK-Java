// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.conformance;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NpsConformanceRun(
    @JsonProperty("date") String date,
    @JsonProperty("environment") String environment
) {}
