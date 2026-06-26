// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record NipCaDiscoveryDocument(
    @JsonProperty("nps_ca") String npsCa,
    @JsonProperty("issuer") String issuer,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("public_key") String publicKey,
    @JsonProperty("algorithms") List<String> algorithms,
    @JsonProperty("endpoints") Map<String, Object> endpoints,
    @JsonProperty("capabilities") List<String> capabilities,
    @JsonProperty("max_cert_validity_days") Integer maxCertValidityDays
) {}
