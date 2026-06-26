// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.conformance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NpsConformanceActor(
    @JsonProperty("name") String name,
    @JsonProperty("version") String version,
    @JsonProperty("nid") String nid
) {}
