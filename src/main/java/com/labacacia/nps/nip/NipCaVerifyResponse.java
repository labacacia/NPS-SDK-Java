// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NipCaVerifyResponse(
    @JsonProperty("valid") boolean valid,
    @JsonProperty("nid") String nid,
    @JsonProperty("expires_at") String expiresAt,
    @JsonProperty("serial") String serial,
    @JsonProperty("error_code") String errorCode,
    @JsonProperty("message") String message
) {}
