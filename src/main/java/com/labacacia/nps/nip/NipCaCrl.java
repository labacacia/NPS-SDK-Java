// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record NipCaCrl(
    @JsonProperty("issued_by") String issuedBy,
    @JsonProperty("issued_at") String issuedAt,
    @JsonProperty("entries") List<NipCaCrlEntry> entries,
    @JsonProperty("signature") String signature
) {}
