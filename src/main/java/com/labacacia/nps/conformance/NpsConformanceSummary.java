// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.conformance;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NpsConformanceSummary(
    @JsonProperty("pass") int pass,
    @JsonProperty("fail") int fail,
    @JsonProperty("skip") int skip,
    @JsonProperty("na") int notApplicable
) {}
