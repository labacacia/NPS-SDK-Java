// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.conformance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class NpsConformance {
    public static final String NODE_L1 = "NPS-Node-L1";
    public static final String NODE_L2 = "NPS-Node-L2";

    public static final List<NpsConformanceCase> NODE_L1_CASES = List.of(
        c("TC-N1-NCP-01", NODE_L1, "N1-NCP-01", "Tier-1 JSON frame round-trip", false),
        c("TC-N1-NCP-02", NODE_L1, "N1-NCP-02", "Hello + Anchor handshake", false),
        c("TC-N1-NCP-03", NODE_L1, "N1-NCP-03", "Loopback listener default", false),
        c("TC-N1-NCP-04", NODE_L1, "N1-NCP-04", "Tier-2 negotiation hygiene", false),
        c("TC-N1-NIP-01", NODE_L1, "N1-NIP-01", "Root keypair generation and permission", false),
        c("TC-N1-NIP-02", NODE_L1, "N1-NIP-02", "IdentFrame sign and verify", false),
        c("TC-N1-NIP-03", NODE_L1, "N1-NIP-03", "NID format", false),
        c("TC-N1-NIP-04", NODE_L1, "N1-NIP-04", "Sub-NID issuance", true),
        c("TC-N1-NDP-01", NODE_L1, "N1-NDP-01", "AnnounceFrame carries activation_mode", false),
        c("TC-N1-NDP-02", NODE_L1, "N1-NDP-02", "AnnounceFrame signature", false),
        c("TC-N1-NDP-03", NODE_L1, "N1-NDP-03", "ResolveFrame response", false),
        c("TC-N1-NDP-04", NODE_L1, "N1-NDP-04", "GraphFrame topology snapshot", true),
        c("TC-N1-NWP-01", NODE_L1, "N1-NWP-01", "Inbox accepts ActionFrame", false),
        c("TC-N1-NWP-02", NODE_L1, "N1-NWP-02", "Inbox persists across restart", false),
        c("TC-N1-NWP-03", NODE_L1, "N1-NWP-03", "NWP pull serves inbox", false),
        c("TC-N1-NWP-04", NODE_L1, "N1-NWP-04", "100 QPS baseline", false),
        c("TC-N1-NWP-05", NODE_L1, "N1-NWP-05", "Push path", true),
        c("TC-N1-OBS-01", NODE_L1, "N1-OBS-01", "Frame log entry per direction", false),
        c("TC-N1-OBS-02", NODE_L1, "N1-OBS-02", "Log entry fields", false),
        c("TC-N1-OBS-03", NODE_L1, "N1-OBS-03", "Log destination flexibility", false)
    );

    public static final List<NpsConformanceCase> NODE_L2_CASES = List.of(
        c("TC-N2-AaaS-01", NODE_L2, "L2-01", "Internal work uses NOP TaskFrame", false),
        c("TC-N2-AaaS-02", NODE_L2, "L2-02", "OpenTelemetry TaskFrame context injection", false),
        c("TC-N2-AaaS-03", NODE_L2, "L2-03", "CGN-Estimate budget and token_est response", false),
        c("TC-N2-AaaS-04", NODE_L2, "L2-04", "NOP preflight gates worker dispatch", false),
        c("TC-N2-AaaS-05", NODE_L2, "L2-05", "NOP retry and timeout semantics", false),
        c("TC-N2-AaaS-06", NODE_L2, "L2-06", "Asynchronous Action lifecycle", true),
        c("TC-N2-AaaS-07", NODE_L2, "L2-07", "AlignStream CGN back-pressure", true),
        c("TC-N2-AnchorTopo-01", NODE_L2, "L2-08", "Snapshot of a 3-member cluster", false),
        c("TC-N2-AnchorTopo-02", NODE_L2, "L2-08", "Version monotonicity across joins", false),
        c("TC-N2-AnchorTopo-03", NODE_L2, "L2-08", "Sub-Anchor member surfaces", false),
        c("TC-N2-AnchorStream-01", NODE_L2, "L2-08", "member_joined on NDP Announce", false),
        c("TC-N2-AnchorStream-02", NODE_L2, "L2-08", "member_left on NDP TTL expiry", false),
        c("TC-N2-AnchorStream-03", NODE_L2, "L2-08", "Resume from topology.since_version", false),
        c("TC-N2-AnchorTopo-04", NODE_L2, "L2-08", "Unauthorized topology access", false),
        c("TC-N2-AnchorTopo-05", NODE_L2, "L2-08", "Depth cap exceeded", false),
        c("TC-N2-AnchorTopo-06", NODE_L2, "L2-08", "Unsupported topology scope", false),
        c("TC-N2-AnchorTopo-07", NODE_L2, "L2-08", "Unsupported topology filter", false),
        c("TC-N2-AnchorTopo-08", NODE_L2, "L2-08", "Unsupported reserved topology type", false),
        c("TC-N2-AnchorStream-04", NODE_L2, "L2-08", "resync_required when version is too old", false),
        c("TC-N2-Tls-01", NODE_L2, "NPS-RFC-0006", "ALPN nps/1.0 negotiated over TLS 1.3", true),
        c("TC-N2-Tls-02", NODE_L2, "NPS-RFC-0006", "Mutual TLS required", true),
        c("TC-N2-Tls-03", NODE_L2, "NPS-RFC-0006", "Client cert trust anchor and NID binding", true),
        c("TC-N2-Tls-04", NODE_L2, "NPS-RFC-0006", "IdentFrame/certificate NID mismatch", true),
        c("TC-N2-BridgeIn-01", NODE_L2, "NPS-CR-0010", "MCP inbound required method set", true),
        c("TC-N2-BridgeIn-02", NODE_L2, "NPS-CR-0010", "gRPC inbound round-trip", true),
        c("TC-N2-BridgeIn-03", NODE_L2, "NPS-CR-0010", "A2A inbound round-trip", true),
        c("TC-N2-BridgeIn-04", NODE_L2, "NPS-CR-0010", "Bare action resolution and ambiguity rejection", true),
        c("TC-N2-BridgeIn-05", NODE_L2, "NPS-CR-0010", "Foreign-protocol error mapping", true),
        c("TC-N2-BridgeIn-06", NODE_L2, "NPS-CR-0010", "Undeclared protocol or direction refusal", true),
        c("TC-N2-HA-01", NODE_L2, "NPS-CR-0009", "cluster_epoch on topology read surfaces", true),
        c("TC-N2-HA-02", NODE_L2, "NPS-CR-0009", "Planned anchor_failover wire shape", true),
        c("TC-N2-HA-03", NODE_L2, "NPS-CR-0009", "Active-loss failover is terminal", true),
        c("TC-N2-HA-04", NODE_L2, "NPS-CR-0009", "Quorum-loss wire shape and read-only mode", true),
        c("TC-N2-HA-05", NODE_L2, "NPS-CR-0009", "Standby rejects topology writes", true),
        c("TC-N2-HA-06", NODE_L2, "NPS-CR-0009", "Superseded leader is epoch fenced", true),
        c("TC-N2-HA-07", NODE_L2, "NPS-CR-0009", "Registry resolves highest cluster_epoch", true),
        c("TC-N2-HA-08", NODE_L2, "NPS-CR-0009", "Equal-epoch split-brain rejection", true),
        c("TC-N2-HA-09", NODE_L2, "NPS-CR-0009", "Single-Anchor epoch-one compatibility", true)
    );

    private NpsConformance() {}

    public static List<NpsConformanceCase> catalogForProfile(String profile) {
        if (NODE_L1.equals(profile)) return NODE_L1_CASES;
        if (NODE_L2.equals(profile)) return NODE_L2_CASES;
        throw new IllegalArgumentException("Unknown NPS conformance profile: " + profile);
    }

    public static NpsConformanceValidation validate(NpsConformanceManifest manifest) {
        List<NpsConformanceCase> catalog = catalogForProfile(manifest.profile());
        Map<String, NpsConformanceCase> known = catalog.stream()
            .collect(Collectors.toMap(NpsConformanceCase::id, Function.identity()));
        Set<String> validResults = Set.of("pass", "fail", "skip", "na");
        Set<String> seen = new java.util.HashSet<>();

        for (var result : manifest.cases()) {
            var knownCase = known.get(result.id());
            if (knownCase == null) return new NpsConformanceValidation(false, "Unknown conformance case id '" + result.id() + "'.");
            if (!seen.add(result.id())) return new NpsConformanceValidation(false, "Duplicate conformance case id '" + result.id() + "'.");
            if (!validResults.contains(result.result())) return new NpsConformanceValidation(false, "Case '" + result.id() + "' has invalid result '" + result.result() + "'.");
            if ("na".equals(result.result()) && !knownCase.optional()) return new NpsConformanceValidation(false, "Case '" + result.id() + "' is required and cannot be marked na.");
            if ("na".equals(result.result())
                    && ("TC-N2-AaaS-06".equals(result.id()) || "TC-N2-AaaS-07".equals(result.id()))
                    && (result.message() == null || result.message().isBlank())) {
                return new NpsConformanceValidation(false, "Case '" + result.id() + "' requires a non-empty message for a SHOULD exception.");
            }
        }

        List<String> missing = new ArrayList<>();
        for (var c : catalog) {
            if (!seen.contains(c.id())) missing.add(c.id());
        }
        if (!missing.isEmpty()) return new NpsConformanceValidation(false, "Missing conformance case results: " + String.join(", ", missing) + ".");
        if (manifest.cases().stream().anyMatch(c -> "fail".equals(c.result()) || "skip".equals(c.result()))) {
            return new NpsConformanceValidation(false, "Conformance manifest contains fail or skip results.");
        }
        String expectedVersion = NODE_L2.equals(manifest.profile()) ? "0.7" : "0.1";
        if (!expectedVersion.equals(manifest.profileVersion())) {
            return new NpsConformanceValidation(false, "Profile '" + manifest.profile() + "' requires manifest version '" + expectedVersion + "'.");
        }
        NpsConformanceSummary expectedSummary = new NpsConformanceSummary(
            (int) manifest.cases().stream().filter(c -> "pass".equals(c.result())).count(),
            (int) manifest.cases().stream().filter(c -> "fail".equals(c.result())).count(),
            (int) manifest.cases().stream().filter(c -> "skip".equals(c.result())).count(),
            (int) manifest.cases().stream().filter(c -> "na".equals(c.result())).count());
        if (!expectedSummary.equals(manifest.summary())) {
            return new NpsConformanceValidation(false, "Conformance manifest summary does not match case results.");
        }
        if (NODE_L2.equals(manifest.profile())) {
            Map<String, String> results = manifest.cases().stream()
                .collect(Collectors.toMap(NpsConformanceCaseResult::id, NpsConformanceCaseResult::result));
            List<List<String>> families = List.of(
                List.of("TC-N2-Tls-01", "TC-N2-Tls-02", "TC-N2-Tls-03", "TC-N2-Tls-04"),
                List.of("TC-N2-BridgeIn-01", "TC-N2-BridgeIn-02", "TC-N2-BridgeIn-03", "TC-N2-BridgeIn-04", "TC-N2-BridgeIn-05", "TC-N2-BridgeIn-06"),
                List.of("TC-N2-HA-01", "TC-N2-HA-02", "TC-N2-HA-03", "TC-N2-HA-04", "TC-N2-HA-05", "TC-N2-HA-06"),
                List.of("TC-N2-HA-07", "TC-N2-HA-08"));
            for (var family : families) {
                Set<String> familyResults = family.stream().map(results::get).collect(Collectors.toSet());
                if (familyResults.size() != 1 || !(familyResults.contains("pass") || familyResults.contains("na"))) {
                    return new NpsConformanceValidation(false, "L2 case family '" + family.getFirst() + "' must be all pass or all na.");
                }
            }
            if (("na".equals(results.get("TC-N2-HA-01"))) == ("na".equals(results.get("TC-N2-HA-09")))) {
                return new NpsConformanceValidation(false, "L2 multi-Anchor HA and single-Anchor compatibility cases must have opposite applicability.");
            }
        }
        return new NpsConformanceValidation(true, "Conformance manifest is valid.");
    }

    private static NpsConformanceCase c(String id, String profile, String requirement, String title, boolean optional) {
        return new NpsConformanceCase(id, profile, requirement, title, optional);
    }
}
