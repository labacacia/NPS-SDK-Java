// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.nip.reputation.IncidentType;
import com.labacacia.nps.nip.reputation.ReputationLogClient;
import com.labacacia.nps.nip.reputation.ReputationLogEntry;
import com.labacacia.nps.nip.reputation.Severity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process reference reputation policy evaluator (RFC-0005 §4.1.4):
 * min_assurance → ban cache → log query (cached) → ban_on → reject_on →
 * throttle_on → accept. Produces this SDK's canonical {@link ReputationDecision}
 * (the client-side reputation parity ships the types; this evaluator drives the
 * Anchor Node server admission gate).
 */
public final class DefaultReputationPolicyEvaluator implements AnchorNodeServer.ReputationEvaluator {

    private record CachedEntries(List<ReputationLogEntry> entries, Instant expiry) {}

    private static final List<String> ASS_ORDER = List.of("anonymous", "attested", "verified");

    private final Map<String, ReputationLogClient> clients = new ConcurrentHashMap<>();
    private final Map<String, CachedEntries> queryCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> banCache = new ConcurrentHashMap<>();

    /** Testing / warm-start helper: prime the per-NID query cache. */
    public void primeCache(String nid, List<ReputationLogEntry> entries, Duration ttl) {
        queryCache.put(nid, new CachedEntries(entries, Instant.now().plus(ttl)));
    }

    @Override
    public ReputationDecision evaluate(String requesterNid, String assuranceWire, ReputationPolicy policy) {
        if (!policy.enabled) return new ReputationDecision(RepOutcome.ACCEPT);
        Instant now = Instant.now();

        // Step 1: min_assurance_level (string-ordinal compare; unknown → -1).
        if (assIdx(policy.minAssuranceLevel) > assIdx(assuranceWire)) {
            return new ReputationDecision(RepOutcome.REJECT, null, NwpErrorCodes.NWP_AUTH_ASSURANCE_TOO_LOW);
        }

        // Step 2: ban cache.
        Instant banExpiry = banCache.get(requesterNid);
        if (banExpiry != null && banExpiry.isAfter(now)) {
            return new ReputationDecision(RepOutcome.BAN, null, NwpErrorCodes.NWP_REPUTATION_BANNED);
        }

        // Step 3: log query.
        boolean[] availOut = new boolean[1];
        List<ReputationLogEntry> entries = fetchEntries(requesterNid, policy, now, availOut);
        if (!availOut[0] && "deny".equalsIgnoreCase(policy.onLogUnavailable)) {
            return new ReputationDecision(RepOutcome.REJECT, null, NwpErrorCodes.NWP_NODE_UNAVAILABLE);
        }

        // Step 4-6: ban_on / reject_on / throttle_on
        ReputationPolicyRule ban = firstMatch(policy.banOn, entries);
        if (ban != null) {
            banCache.put(requesterNid, now.plusSeconds(policy.banTtlSeconds));
            return new ReputationDecision(RepOutcome.BAN, ban, NwpErrorCodes.NWP_REPUTATION_BANNED);
        }
        ReputationPolicyRule reject = firstMatch(policy.rejectOn, entries);
        if (reject != null) {
            return new ReputationDecision(RepOutcome.REJECT, reject, NwpErrorCodes.NWP_REPUTATION_REJECTED);
        }
        ReputationPolicyRule throttle = firstMatch(policy.throttleOn, entries);
        if (throttle != null) {
            return new ReputationDecision(RepOutcome.THROTTLE, throttle, NwpErrorCodes.NWP_REPUTATION_THROTTLED);
        }

        // Step 7: accept
        return new ReputationDecision(RepOutcome.ACCEPT);
    }

    private static int assIdx(String s) {
        return s == null ? -1 : ASS_ORDER.indexOf(s.toLowerCase());
    }

    private ReputationPolicyRule firstMatch(List<ReputationPolicyRule> rules, List<ReputationLogEntry> entries) {
        if (rules == null) return null;
        for (ReputationPolicyRule rule : rules) {
            int needed = Math.max(rule.count, 1);
            int matched = 0;
            for (ReputationLogEntry entry : entries) {
                if (!ruleMatches(rule, entry)) continue;
                if (++matched >= needed) return rule;
            }
        }
        return null;
    }

    private static boolean ruleMatches(ReputationPolicyRule rule, ReputationLogEntry entry) {
        String pattern = rule.incident == null ? "*" : rule.incident;
        if (!"*".equals(pattern)) {
            String wire = entry.getIncident() == IncidentType.OTHER && entry.getIncidentRaw() != null
                ? entry.getIncidentRaw() : entry.getIncident().wire;
            if (!pattern.equalsIgnoreCase(wire)) return false;
        }
        String sev = rule.severity == null ? ">=minor" : rule.severity.trim();
        boolean atLeast = sev.startsWith(">=");
        String levelStr = atLeast ? sev.substring(2).trim() : sev;
        int threshold;
        try {
            threshold = Severity.fromWire(levelStr).level;
        } catch (RuntimeException e) {
            return false;
        }
        int actual = entry.getSeverity().level;
        return atLeast ? actual >= threshold : actual == threshold;
    }

    private List<ReputationLogEntry> fetchEntries(String nid, ReputationPolicy policy, Instant now, boolean[] availOut) {
        if (policy.cacheTtlSeconds > 0) {
            CachedEntries cached = queryCache.get(nid);
            if (cached != null && cached.expiry().isAfter(now)) {
                availOut[0] = true;
                return cached.entries();
            }
        }
        for (String source : policy.logSources) {
            try {
                ReputationLogClient client = clients.computeIfAbsent(source, ReputationLogClient::new);
                List<ReputationLogEntry> results = client.query(nid, null);
                if (policy.cacheTtlSeconds > 0) {
                    queryCache.put(nid, new CachedEntries(results, now.plusSeconds(policy.cacheTtlSeconds)));
                }
                availOut[0] = true;
                return results;
            } catch (Exception e) {
                // source unavailable — try next
            }
        }
        CachedEntries stale = queryCache.get(nid);
        if (stale != null) { availOut[0] = false; return stale.entries(); }
        availOut[0] = false;
        return List.of();
    }
}
