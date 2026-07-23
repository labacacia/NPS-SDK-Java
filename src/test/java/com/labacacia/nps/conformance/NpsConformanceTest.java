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
        assertEquals(16, NpsConformance.NODE_L2_CASES.size());
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
            "1.0.0-alpha.16",
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
            "1.0.0-alpha.16",
            results,
            null);

        assertFalse(NpsConformance.validate(manifest).valid());
    }
}
