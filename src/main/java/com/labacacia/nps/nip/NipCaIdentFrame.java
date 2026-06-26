// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class NipCaIdentFrame {
    @JsonProperty("frame") public String frame;
    @JsonProperty("nid") public String nid;
    @JsonProperty("pub_key") public String pubKey;
    @JsonProperty("capabilities") public List<String> capabilities;
    @JsonProperty("scope") public Object scope;
    @JsonProperty("issued_by") public String issuedBy;
    @JsonProperty("issued_at") public String issuedAt;
    @JsonProperty("expires_at") public String expiresAt;
    @JsonProperty("serial") public String serial;
    @JsonProperty("signature") public String signature;
    @JsonProperty("cert_format") public String certFormat;
    @JsonProperty("cert_chain") public List<String> certChain;
    @JsonProperty("ocsp_staple") public String ocspStaple;

    @JsonIgnore
    private final Map<String, Object> extras = new LinkedHashMap<>();

    @JsonAnySetter
    public void putExtra(String key, Object value) {
        extras.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> extras() {
        return extras;
    }
}
