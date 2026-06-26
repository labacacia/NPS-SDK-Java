// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.conformance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

public record NpsConformanceManifest(
    @JsonProperty("profile") String profile,
    @JsonProperty("profile_version") String profileVersion,
    @JsonProperty("iut") NpsConformanceActor iut,
    @JsonProperty("peer") NpsConformanceActor peer,
    @JsonProperty("run") NpsConformanceRun run,
    @JsonProperty("cases") List<NpsConformanceCaseResult> cases,
    @JsonProperty("summary") NpsConformanceSummary summary
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static NpsConformanceManifest create(
            String profile,
            String iutName,
            String iutVersion,
            String iutNid,
            String peerName,
            String peerVersion,
            List<NpsConformanceCaseResult> results,
            String environment) {
        int pass = 0, fail = 0, skip = 0, na = 0;
        for (var result : results) {
            switch (result.result()) {
                case "pass" -> pass++;
                case "fail" -> fail++;
                case "skip" -> skip++;
                case "na" -> na++;
                default -> { }
            }
        }
        return new NpsConformanceManifest(
            profile,
            NpsConformance.NODE_L2.equals(profile) ? "0.3" : "0.1",
            new NpsConformanceActor(iutName, iutVersion, iutNid),
            new NpsConformanceActor(peerName, peerVersion, null),
            new NpsConformanceRun(Instant.now().toString(), environment == null || environment.isBlank() ? "unspecified" : environment),
            List.copyOf(results),
            new NpsConformanceSummary(pass, fail, skip, na)
        );
    }

    public String toJson() throws JsonProcessingException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
    }
}
