// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0

package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReputationPolicyTest {

    @Test
    void defaultPolicyHasExpectedDefaults() {
        ReputationPolicy p = new ReputationPolicy();
        assertTrue(p.enabled);
        assertEquals("anonymous", p.minAssuranceLevel);
        assertEquals(300, p.cacheTtlSeconds);
        assertEquals(3600, p.banTtlSeconds);
        assertEquals("allow", p.onLogUnavailable);
        assertTrue(p.logSources.isEmpty());
        assertTrue(p.banOn.isEmpty());
        assertTrue(p.rejectOn.isEmpty());
        assertTrue(p.throttleOn.isEmpty());
    }

    @Test
    void reputationPolicyRuleDefaults() {
        ReputationPolicyRule rule = new ReputationPolicyRule("abuse", ">=minor");
        assertEquals("abuse", rule.incident);
        assertEquals(">=minor", rule.severity);
        assertNull(rule.withinDays);
        assertEquals(1, rule.count);
    }

    @Test
    void reputationPolicyRuleNullIncidentDefaultsStar() {
        ReputationPolicyRule rule = new ReputationPolicyRule(null, null);
        assertEquals("*", rule.incident);
        assertEquals(">=minor", rule.severity);
        assertEquals(1, rule.count);
    }

    @Test
    void reputationPolicyRuleCountClampedToOne() {
        ReputationPolicyRule rule = new ReputationPolicyRule("flood", "critical", null, 0);
        assertEquals(1, rule.count);
    }

    @Test
    void repOutcomeVariantsAreDistinct() {
        assertNotEquals(RepOutcome.ACCEPT,   RepOutcome.THROTTLE);
        assertNotEquals(RepOutcome.ACCEPT,   RepOutcome.REJECT);
        assertNotEquals(RepOutcome.ACCEPT,   RepOutcome.BAN);
        assertNotEquals(RepOutcome.THROTTLE, RepOutcome.REJECT);
        assertNotEquals(RepOutcome.THROTTLE, RepOutcome.BAN);
        assertNotEquals(RepOutcome.REJECT,   RepOutcome.BAN);
    }

    @Test
    void reputationDecisionIsAcceptedTrueForAccept() {
        ReputationDecision d = new ReputationDecision(RepOutcome.ACCEPT);
        assertTrue(d.isAccepted());
    }

    @Test
    void reputationDecisionIsAcceptedFalseForReject() {
        assertFalse(new ReputationDecision(RepOutcome.REJECT).isAccepted());
    }

    @Test
    void reputationDecisionIsAcceptedFalseForBan() {
        assertFalse(new ReputationDecision(RepOutcome.BAN).isAccepted());
    }

    @Test
    void reputationDecisionIsAcceptedFalseForThrottle() {
        assertFalse(new ReputationDecision(RepOutcome.THROTTLE).isAccepted());
    }

    @Test
    void reputationDecisionCarriesMatchedRuleAndErrorCode() {
        ReputationPolicyRule rule = new ReputationPolicyRule("spam", ">=major");
        ReputationDecision d = new ReputationDecision(RepOutcome.BAN, rule, "NWP-AUTH-REPUTATION-BLOCKED");
        assertEquals(RepOutcome.BAN, d.outcome);
        assertSame(rule, d.matchedRule);
        assertEquals("NWP-AUTH-REPUTATION-BLOCKED", d.errorCode);
    }

    @Test
    void jacksonRoundTripReputationPolicy() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        ReputationPolicyRule rule = new ReputationPolicyRule("abuse", ">=minor", 30, 2);
        ReputationPolicy policy = new ReputationPolicy(
            true,
            List.of("https://log.example.com"),
            "attested",
            600,
            7200,
            "deny",
            List.of(),
            List.of(rule),
            List.of()
        );

        String json = mapper.writeValueAsString(policy);
        ReputationPolicy parsed = mapper.readValue(json, ReputationPolicy.class);

        assertTrue(parsed.enabled);
        assertEquals("attested", parsed.minAssuranceLevel);
        assertEquals(600, parsed.cacheTtlSeconds);
        assertEquals(7200, parsed.banTtlSeconds);
        assertEquals("deny", parsed.onLogUnavailable);
        assertEquals(1, parsed.rejectOn.size());
        assertEquals("abuse", parsed.rejectOn.get(0).incident);
        assertEquals(">=minor", parsed.rejectOn.get(0).severity);
        assertEquals(30, parsed.rejectOn.get(0).withinDays);
        assertEquals(2, parsed.rejectOn.get(0).count);
    }
}
