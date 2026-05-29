// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Node-side reputation enforcement policy (NPS-RFC-0005 §4.1). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ReputationPolicy {
    @JsonProperty("enabled")             public final boolean enabled;
    @JsonProperty("log_sources")         public final List<String> logSources;
    @JsonProperty("min_assurance_level") public final String minAssuranceLevel;
    @JsonProperty("cache_ttl_seconds")   public final int cacheTtlSeconds;
    @JsonProperty("ban_ttl_seconds")     public final int banTtlSeconds;
    @JsonProperty("on_log_unavailable")  public final String onLogUnavailable;
    @JsonProperty("throttle_on")         public final List<ReputationPolicyRule> throttleOn;
    @JsonProperty("reject_on")           public final List<ReputationPolicyRule> rejectOn;
    @JsonProperty("ban_on")              public final List<ReputationPolicyRule> banOn;

    public ReputationPolicy() {
        this(true, List.of(), "anonymous", 300, 3600, "allow", List.of(), List.of(), List.of());
    }
    @com.fasterxml.jackson.annotation.JsonCreator
    public ReputationPolicy(
                            @JsonProperty("enabled")             boolean enabled,
                            @JsonProperty("log_sources")         List<String> logSources,
                            @JsonProperty("min_assurance_level") String minAssuranceLevel,
                            @JsonProperty("cache_ttl_seconds")   int cacheTtlSeconds,
                            @JsonProperty("ban_ttl_seconds")     int banTtlSeconds,
                            @JsonProperty("on_log_unavailable")  String onLogUnavailable,
                            @JsonProperty("throttle_on")         List<ReputationPolicyRule> throttleOn,
                            @JsonProperty("reject_on")           List<ReputationPolicyRule> rejectOn,
                            @JsonProperty("ban_on")              List<ReputationPolicyRule> banOn) {
        this.enabled = enabled;
        this.logSources = logSources != null ? logSources : List.of();
        this.minAssuranceLevel = minAssuranceLevel != null ? minAssuranceLevel : "anonymous";
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.banTtlSeconds = banTtlSeconds;
        this.onLogUnavailable = onLogUnavailable != null ? onLogUnavailable : "allow";
        this.throttleOn = throttleOn != null ? throttleOn : List.of();
        this.rejectOn = rejectOn != null ? rejectOn : List.of();
        this.banOn = banOn != null ? banOn : List.of();
    }
}
