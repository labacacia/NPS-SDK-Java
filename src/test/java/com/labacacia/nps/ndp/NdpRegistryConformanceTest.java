// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.ConformanceFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class NdpRegistryConformanceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
        new TypeReference<>() {};

    @Test
    void sharedCanonicalizationVectorsPass() throws Exception {
        JsonNode vectors = vectors(
            "spec/conformance/ndp/announce_canonicalization_vectors.json");
        assertEquals(3, vectors.size());
        for (JsonNode vector : vectors) {
            JsonNode input = vector.get("input");
            Map<String, Object> frame = MAPPER.convertValue(
                input.get("frame"), MAP_TYPE);
            assertEquals(
                vector.at("/expected/canonical_json").asText(),
                NdpRegistryProfile.canonicalAnnounceJson(frame),
                vector.get("id").asText());
            assertEquals(
                vector.at("/expected/signature_valid").asBoolean(),
                NdpRegistryProfile.verifyAnnounceSignature(
                    frame,
                    input.get("public_key").asText(),
                    input.get("signature").asText()),
                vector.get("id").asText());
            frame.put("signature", input.get("signature").asText());
            AnnounceFrame model = AnnounceFrame.fromDict(frame);
            NdpAnnounceValidator validator = new NdpAnnounceValidator();
            validator.registerPublicKey(
                model.nid(), input.get("public_key").asText());
            assertEquals(
                vector.at("/expected/signature_valid").asBoolean(),
                validator.validate(model).isValid(),
                vector.get("id").asText());
        }
    }

    @Test
    void sharedRegistryConsistencyVectorsPass() throws Exception {
        JsonNode vectors = vectors(
            "spec/conformance/ndp/registry_consistency_vectors.json");
        assertEquals(16, vectors.size());
        for (JsonNode vector : vectors) assertRegistryVector(vector);
    }

    private static void assertRegistryVector(JsonNode vector) {
        JsonNode input = vector.get("input");
        JsonNode expected = vector.get("expected");
        Instant now = Instant.parse(input.get("now").asText());
        NdpRegistryProfile registry =
            new NdpRegistryProfile(input.get("profile").asText());
        List<String> decisions = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (JsonNode announce : input.get("announces")) {
            Map<String, Object> frame = MAPPER.convertValue(
                announce.get("frame"), MAP_TYPE);
            Instant receivedAt = announce.has("received_at")
                ? Instant.parse(announce.get("received_at").asText())
                : now;
            var result = registry.applyAnnounce(
                frame,
                announce.get("signature_valid").asBoolean(),
                receivedAt);
            decisions.add(result.decision().name().toLowerCase());
            errors.add(result.errorCode());
        }

        assertEquals(strings(expected.get("decisions")), decisions,
            vector.get("id").asText());
        assertEquals(nullableStrings(expected.get("errors")), errors,
            vector.get("id").asText());
        assertEquals(strings(expected.get("live_nids")), registry.liveNids(now),
            vector.get("id").asText());
        expected.get("highest_sequences").fields().forEachRemaining(item ->
            assertEquals(
                item.getValue().asLong(),
                registry.highestSequences().get(item.getKey()),
                vector.get("id").asText() + " " + item.getKey()));
        assertEquals(
            expected.get("highest_sequences").size(),
            registry.highestSequences().size());

        if (input.has("cluster_query")) {
            var selected = registry.resolveCluster(
                input.get("cluster_query").asText(), now);
            assertEquals(text(expected, "selected_nid"), selected.nid());
            assertEquals(longValue(expected, "selected_epoch"), selected.epoch());
            assertEquals(text(expected, "cluster_error"), selected.errorCode());
        }

        if (input.has("bridge_queries")) {
            List<List<String>> actual = new ArrayList<>();
            for (JsonNode query : input.get("bridge_queries")) {
                actual.add(registry.discoverBridges(
                    query.get("direction").asText(),
                    query.get("protocol").asText(),
                    now));
            }
            List<List<String>> expectedResults = new ArrayList<>();
            for (JsonNode result : expected.get("bridge_results")) {
                expectedResults.add(strings(result));
            }
            assertEquals(expectedResults, actual);
        }

        if (expected.has("resolve_error")) assertTrue(registry.hasStaleEntry(now));
    }

    private static JsonNode vectors(String relative) throws Exception {
        String fixture = relative.replaceFirst("^spec/conformance/", "");
        return MAPPER.readTree(
            Files.readString(ConformanceFixtures.resolve(fixture))).get("vectors");
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asText()));
        return result;
    }

    private static List<String> nullableStrings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.isNull() ? null : node.asText()));
        return result;
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    private static Long longValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asLong() : null;
    }
}
