// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Subscriber-side filter (NPS-2 §12.2). All clauses are AND-combined;
 * a member matches if every populated clause matches.
 */
public final class TopologyFilter {

    /** Match-any: member's tag set MUST overlap with this list. */
    @JsonProperty("tags_any")
    public List<String> tagsAny;

    /** Match-all: member's tag set MUST be a superset of this list. */
    @JsonProperty("tags_all")
    public List<String> tagsAll;

    /** Member's node_roles array MUST contain at least one of these values. */
    @JsonProperty("node_roles")
    public List<String> nodeRoles;

    /** No-arg constructor for Jackson. */
    public TopologyFilter() {}

    public TopologyFilter(List<String> tagsAny, List<String> tagsAll, List<String> nodeRoles) {
        this.tagsAny   = tagsAny;
        this.tagsAll   = tagsAll;
        this.nodeRoles = nodeRoles;
    }
}
