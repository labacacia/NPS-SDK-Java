// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One member of an Anchor Node's cluster (NPS-2 §12.1 member object schema).
 */
public final class MemberInfo {

    @JsonProperty("nid")
    public String nid;

    /** NDP node_roles values (NPS-4 §3.1). Always at least one entry. */
    @JsonProperty("node_roles")
    public List<String> nodeRoles;

    /** NDP activation_mode: ephemeral / resident / hybrid. */
    @JsonProperty("activation_mode")
    public String activationMode;

    /** True if this member is itself an Anchor of a sub-cluster. */
    @JsonProperty("child_anchor")
    public Boolean childAnchor;

    /** Required when childAnchor is true; sub-Anchor's direct member count. */
    @JsonProperty("member_count")
    public Integer memberCount;

    /** NDP-declared tags (free-form labels). */
    @JsonProperty("tags")
    public List<String> tags;

    @JsonProperty("joined_at")
    public String joinedAt;

    @JsonProperty("last_seen")
    public String lastSeen;

    /** Returned only if requested via include=capabilities; schema implementation-defined. */
    @JsonProperty("capabilities")
    public JsonNode capabilities;

    /** Returned only if requested via include=metrics; schema implementation-defined. */
    @JsonProperty("metrics")
    public JsonNode metrics;

    /** No-arg constructor for Jackson. */
    public MemberInfo() {}

    public MemberInfo(String nid, List<String> nodeRoles, String activationMode,
                      Boolean childAnchor, Integer memberCount, List<String> tags,
                      String joinedAt, String lastSeen,
                      JsonNode capabilities, JsonNode metrics) {
        this.nid            = nid;
        this.nodeRoles      = nodeRoles;
        this.activationMode = activationMode;
        this.childAnchor    = childAnchor;
        this.memberCount    = memberCount;
        this.tags           = tags;
        this.joinedAt       = joinedAt;
        this.lastSeen       = lastSeen;
        this.capabilities   = capabilities;
        this.metrics        = metrics;
    }
}
