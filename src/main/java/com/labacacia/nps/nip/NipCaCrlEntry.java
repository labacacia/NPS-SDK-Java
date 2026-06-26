// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NipCaCrlEntry(
    @JsonProperty("nid") String nid,
    @JsonProperty("serial") String serial,
    @JsonProperty("revoked_at") String revokedAt,
    @JsonProperty("reason") String reason
) {}
