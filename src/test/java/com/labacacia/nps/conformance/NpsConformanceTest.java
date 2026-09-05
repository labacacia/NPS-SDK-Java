// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.conformance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NpsConformanceTest {
    @Test
    void catalogContainsExpectedCases() {
        assertEquals(20, NpsConformance.NODE_L1_CASES.size());
        assertEquals(38, NpsConformance.NODE_L2_CASES.size());
        assertEquals("TC-N1-NCP-01", NpsConformance.NODE_L1_CASES.getFirst().id());
    }

    @Test
    void validatorAcceptsCompleteL1Manifest() {
        List<NpsConformanceCaseResult> results = NpsConformance.NODE_L1_CASES.stream()
            .map(c -> new NpsConformanceCaseResult(c.id(), c.optional() ? "na" : "pass", null))
            .toList();
        NpsConformanceManifest manifest = NpsConformanceManifest.create(
            NpsConformance.NODE_L1,
            "node",
            "0.1.0",
            "urn:nps:node:example.test:node-1",
            "reference",
            "1.0.0-alpha.18",
            results,
            null);

        assertTrue(NpsConformance.validate(manifest).valid());
    }

    @Test
    void validatorRejectsMissingCase() {
        List<NpsConformanceCaseResult> results = NpsConformance.NODE_L1_CASES.subList(0, NpsConformance.NODE_L1_CASES.size() - 1).stream()
            .map(c -> new NpsConformanceCaseResult(c.id(), "pass", null))
            .toList();
        NpsConformanceManifest manifest = NpsConformanceManifest.create(
            NpsConformance.NODE_L1,
            "node",
            "0.1.0",
            "urn:nps:node:example.test:node-1",
            "reference",
            "1.0.0-alpha.18",
            results,
            null);

        assertFalse(NpsConformance.validate(manifest).valid());
    }

    @Test
    void validatorEnforcesL2AllOrNothingFamilies() {
        List<NpsConformanceCaseResult> results = NpsConformance.NODE_L2_CASES.stream()
            .map(c -> new NpsConformanceCaseResult(
                c.id(),
                c.id().startsWith("TC-N2-AaaS-") || c.id().startsWith("TC-N2-Anchor") || "TC-N2-HA-09".equals(c.id()) ? "pass" : "na",
                null))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        NpsConformanceManifest manifest = NpsConformanceManifest.create(
            NpsConformance.NODE_L2,
            "single-anchor",
            "0.1.0",
            "urn:nps:node:example.test:anchor-1",
            "reference",
            "1.0.0-alpha.18",
            results,
            null);

        assertEquals("0.7", manifest.profileVersion());
        assertTrue(NpsConformance.validate(manifest).valid());

        results.set(19, new NpsConformanceCaseResult("TC-N2-Tls-01", "pass", null));
        NpsConformanceManifest partial = NpsConformanceManifest.create(
            NpsConformance.NODE_L2,
            "single-anchor",
            "0.1.0",
            "urn:nps:node:example.test:anchor-1",
            "reference",
            "1.0.0-alpha.18",
            results,
            null);
        var validation = NpsConformance.validate(partial);
        assertFalse(validation.valid());
        assertTrue(validation.message().contains("must be all pass or all na"));

        results.set(19, new NpsConformanceCaseResult("TC-N2-Tls-01", "na", null));
        results.set(results.size() - 1, new NpsConformanceCaseResult("TC-N2-HA-09", "na", null));
        NpsConformanceManifest noAnchorMode = NpsConformanceManifest.create(
            NpsConformance.NODE_L2,
            "invalid-anchor",
            "0.1.0",
            "urn:nps:node:example.test:anchor-1",
            "reference",
            "1.0.0-alpha.18",
            results,
            null);
        var applicability = NpsConformance.validate(noAnchorMode);
        assertFalse(applicability.valid());
        assertTrue(applicability.message().contains("opposite applicability"));
    }

    @Test
    void validatorRequiresReasonForAaaSShouldException() {
        List<NpsConformanceCaseResult> results = NpsConformance.NODE_L2_CASES.stream()
            .map(c -> new NpsConformanceCaseResult(
                c.id(),
                c.id().startsWith("TC-N2-AaaS-") || c.id().startsWith("TC-N2-Anchor") || "TC-N2-HA-09".equals(c.id()) ? "pass" : "na",
                null))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        results.set(5, new NpsConformanceCaseResult("TC-N2-AaaS-06", "na", null));
        var missingReason = NpsConformanceManifest.create(
            NpsConformance.NODE_L2, "service", "0.1.0",
            "urn:nps:node:example.test:anchor-1", "reference", "1.0.0-alpha.18", results, null);
        assertTrue(NpsConformance.validate(missingReason).message().contains("requires a non-empty message"));

        results.set(5, new NpsConformanceCaseResult("TC-N2-AaaS-06", "na", "Synchronous-only deployment"));
        var reasoned = NpsConformanceManifest.create(
            NpsConformance.NODE_L2, "service", "0.1.0",
            "urn:nps:node:example.test:anchor-1", "reference", "1.0.0-alpha.18", results, null);
        assertTrue(NpsConformance.validate(reasoned).valid());
    }
}
