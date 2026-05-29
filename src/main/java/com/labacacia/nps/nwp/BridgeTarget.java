// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Inbound parameter object for a bridge invocation (NPS-2 §2A, NPS-CR-0001). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class BridgeTarget {
    @JsonProperty("protocol") public final String protocol;
    @JsonProperty("endpoint") public final String endpoint;
    @JsonProperty("extras")   public final Map<String, Object> extras;
    public BridgeTarget(String protocol, String endpoint) {
        this(protocol, endpoint, null);
    }
    public BridgeTarget(String protocol, String endpoint, Map<String, Object> extras) {
        this.protocol = protocol;
        this.endpoint = endpoint;
        this.extras = extras;
    }
}
