// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.ConformanceFixtures;
import com.labacacia.nps.nop.orchestration.NopPortableProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NopPortableProfileTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sharedOrchestratorTranscriptsPass() throws Exception {
        List<Map<String, Object>> vectors = vectors("orchestrator_transcripts.json");
        assertEquals(10, vectors.size());
        for (Map<String, Object> vector : vectors) {
            assertEquals(
                vector.get("expected"),
                NopPortableProfile.evaluateOrchestration(map(vector.get("input"))),
                String.valueOf(vector.get("id")));
        }
    }

    @Test
    void sharedRuntimeSecurityVectorsPass() throws Exception {
        List<Map<String, Object>> vectors = vectors("runtime_security_vectors.json");
        assertEquals(22, vectors.size());
        for (Map<String, Object> vector : vectors) {
            assertEquals(
                vector.get("expected"),
                NopPortableProfile.evaluateRuntime(
                    String.valueOf(vector.get("category")),
                    map(vector.get("input"))),
                String.valueOf(vector.get("id")));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vectors(String name) throws Exception {
        Map<String, Object> fixture = JSON.readValue(
            ConformanceFixtures.resolve("nop/" + name).toFile(),
            new TypeReference<>() {});
        return (List<Map<String, Object>>) fixture.get("vectors");
    }
}
