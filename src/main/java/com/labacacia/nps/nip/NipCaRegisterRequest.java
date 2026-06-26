// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NipCaRegisterRequest(
    @JsonProperty("identifier") String identifier,
    @JsonProperty("pub_key") String pubKey,
    @JsonProperty("capabilities") List<String> capabilities,
    @JsonProperty("scope_json") String scopeJson,
    @JsonProperty("metadata_json") String metadataJson
) {}
