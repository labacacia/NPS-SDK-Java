// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Field-level diff per CR-0002 §10 OQ-2. Subset of {@link MemberInfo} fields
 * the Anchor reports as changed. All fields are nullable; only populated fields
 * contribute to the diff.
 */
public final class MemberChanges {

    @JsonProperty("node_roles")
    public List<String> nodeRoles;

    @JsonProperty("activation_mode")
    public String activationMode;

    @JsonProperty("tags")
    public List<String> tags;

    @JsonProperty("member_count")
    public Integer memberCount;

    @JsonProperty("last_seen")
    public String lastSeen;

    @JsonProperty("capabilities")
    public JsonNode capabilities;

    @JsonProperty("metrics")
    public JsonNode metrics;

    /** No-arg constructor for Jackson. */
    public MemberChanges() {}

    public MemberChanges(List<String> nodeRoles, String activationMode, List<String> tags,
                         Integer memberCount, String lastSeen,
                         JsonNode capabilities, JsonNode metrics) {
        this.nodeRoles      = nodeRoles;
        this.activationMode = activationMode;
        this.tags           = tags;
        this.memberCount    = memberCount;
        this.lastSeen       = lastSeen;
        this.capabilities   = capabilities;
        this.metrics        = metrics;
    }
}
