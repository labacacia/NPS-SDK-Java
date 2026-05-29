// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A single ban_on / reject_on / throttle_on rule (NPS-RFC-0005 §4.1.3). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ReputationPolicyRule {
    @JsonProperty("incident")    public final String  incident;
    @JsonProperty("severity")    public final String  severity;
    @JsonProperty("within_days") public final Integer withinDays;
    @JsonProperty("count")       public final int     count;

    public ReputationPolicyRule(String incident, String severity) {
        this(incident, severity, null, 1);
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public ReputationPolicyRule(
            @JsonProperty("incident")    String  incident,
            @JsonProperty("severity")    String  severity,
            @JsonProperty("within_days") Integer withinDays,
            @JsonProperty("count")       int     count) {
        this.incident   = incident   != null ? incident : "*";
        this.severity   = severity   != null ? severity : ">=minor";
        this.withinDays = withinDays;
        this.count      = count > 0 ? count : 1;
    }
}
