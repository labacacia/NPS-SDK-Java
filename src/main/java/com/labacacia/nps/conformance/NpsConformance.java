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
        c("TC-N1-NDP-04", NODE_L1, "N1-NDP-04", "GraphFrame subscription", true),
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
        c("TC-N2-Tls-01", NODE_L2, "NPS-RFC-0006", "ALPN nps/1.0 negotiated over TLS 1.3", false),
        c("TC-N2-Tls-02", NODE_L2, "NPS-RFC-0006", "Mutual TLS required", false),
        c("TC-N2-Tls-03", NODE_L2, "NPS-RFC-0006", "Client cert trust anchor and NID binding", false),
        c("TC-N2-Tls-04", NODE_L2, "NPS-RFC-0006", "IdentFrame/certificate NID mismatch", false)
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
        }

        List<String> missing = new ArrayList<>();
        for (var c : catalog) {
            if (!seen.contains(c.id())) missing.add(c.id());
        }
        if (!missing.isEmpty()) return new NpsConformanceValidation(false, "Missing conformance case results: " + String.join(", ", missing) + ".");
        if (manifest.cases().stream().anyMatch(c -> "fail".equals(c.result()) || "skip".equals(c.result()))) {
            return new NpsConformanceValidation(false, "Conformance manifest contains fail or skip results.");
        }
        return new NpsConformanceValidation(true, "Conformance manifest is valid.");
    }

    private static NpsConformanceCase c(String id, String profile, String requirement, String title, boolean optional) {
        return new NpsConformanceCase(id, profile, requirement, title, optional);
    }
}
