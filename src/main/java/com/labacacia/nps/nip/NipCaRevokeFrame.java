// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NipCaRevokeFrame {
    @JsonProperty("frame") public String frame;
    @JsonProperty("target_nid") public String targetNid;
    @JsonProperty("nid") public String nid;
    @JsonProperty("serial") public String serial;
    @JsonProperty("reason") public String reason;
    @JsonProperty("revoked_at") public String revokedAt;
    @JsonProperty("signature") public String signature;

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
