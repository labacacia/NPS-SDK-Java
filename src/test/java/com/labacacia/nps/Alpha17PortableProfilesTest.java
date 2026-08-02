// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameFlags;
import com.labacacia.nps.core.FrameHeader;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.ncp.HelloFrame;
import com.labacacia.nps.ncp.NcpHandshakePolicy;
import com.labacacia.nps.ncp.NcpHandshakeProfile;
import com.labacacia.nps.nip.NipCaClient;
import com.labacacia.nps.nip.NipCaCrl;
import com.labacacia.nps.nip.NipIdentVerifyResult;
import com.labacacia.nps.nip.NipRevocationPolicy;
import com.labacacia.nps.nwp.NwpPortableProfile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class Alpha17PortableProfilesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode vectors(String relative) throws Exception {
        String fixture = relative.replaceFirst("^spec/conformance/", "");
        return MAPPER.readTree(
            Files.readString(ConformanceFixtures.resolve(fixture))).get("vectors");
    }

    @Test
    void ncpNativeServerHandshakeVectors() throws Exception {
        for (JsonNode vector : vectors(
                "spec/conformance/ncp/native_server_handshake_vectors.json")) {
            JsonNode server = vector.at("/input/server");
            JsonNode transport = vector.at("/input/transport");
            JsonNode expected = vector.get("expected");
            byte[] preamble = java.util.HexFormat.of().parseHex(
                transport.get("preamble_hex").asText());
            NcpHandshakePolicy.Decision decision =
                NcpHandshakePolicy.evaluatePreamble(
                    preamble,
                    transport.get("preamble_elapsed_ms").asLong(),
                    server.get("preamble_timeout_ms").asLong());

            if (decision.action() == NcpHandshakePolicy.Action.CONTINUE
                    && transport.has("first_frame_type")) {
                int flags = switch (transport.get("first_frame_tier").asText()) {
                    case "msgpack" -> EncodingTier.MSGPACK.wireCode;
                    case "binary_vector" -> EncodingTier.BINARY_VECTOR.wireCode;
                    default -> EncodingTier.JSON.wireCode;
                };
                if (transport.get("first_frame_encrypted").asBoolean()) {
                    flags |= FrameFlags.ENCRYPTED;
                }
                if (transport.get("first_frame_extended").asBoolean()) {
                    flags |= FrameFlags.EXT;
                }
                decision = NcpHandshakePolicy.evaluateHelloHeader(
                    new FrameHeader(
                        FrameType.fromCode(Integer.decode(
                            transport.get("first_frame_type").asText())),
                        flags,
                        transport.get("hello_payload_length").asLong()),
                    transport.get("hello_elapsed_ms").asLong(),
                    server.get("hello_timeout_ms").asLong(),
                    server.get("max_hello_payload").asLong());
            }

            if (decision.action() == NcpHandshakePolicy.Action.CONTINUE
                    && vector.at("/input").has("hello")) {
                JsonNode hello = vector.at("/input/hello");
                decision = NcpHandshakePolicy.negotiate(
                    new NcpHandshakeProfile(
                        server.get("min_version").asText(),
                        server.get("nps_version").asText(),
                        strings(server.get("supported_encodings")),
                        strings(server.get("supported_protocols")),
                        server.get("max_frame_payload").asInt(),
                        server.get("ext_support").asBoolean(),
                        server.get("max_concurrent_streams").asInt()),
                    new HelloFrame(
                        hello.get("nps_version").asText(),
                        strings(hello.get("supported_encodings")),
                        strings(hello.get("supported_protocols")),
                        hello.get("min_version").asText(),
                        null,
                        hello.get("max_frame_payload").asInt(),
                        hello.get("ext_support").asBoolean(),
                        hello.get("max_concurrent_streams").asInt(),
                        null));
            }

            assertEquals(
                expected.get("action").asText().toUpperCase(),
                decision.action().name(),
                vector.get("id").asText());
            assertEquals(
                expected.get("emit_error").asBoolean(),
                decision.action() == NcpHandshakePolicy.Action.ERROR_CLOSE);
            assertExpected(expected, "diagnostic_error", decision.diagnosticError());
            assertExpected(expected, "status", decision.status());
            assertExpected(expected, "error", decision.error());
            assertExpected(expected, "session_version", decision.sessionVersion());
            assertExpected(expected, "negotiated_encoding", decision.negotiatedEncoding());
            assertExpected(expected, "max_frame_payload", decision.maxFramePayload());
            assertExpected(expected, "ext_support", decision.extSupport());
            assertExpected(
                expected, "max_concurrent_streams", decision.maxConcurrentStreams());
            if (expected.has("enabled_encodings")) {
                assertEquals(strings(expected.get("enabled_encodings")),
                    decision.enabledEncodings());
            }
            if (expected.has("supported_protocols")) {
                assertEquals(strings(expected.get("supported_protocols")),
                    decision.supportedProtocols());
            }
        }
    }

    @Test
    void nipRevocationPolicyVectors() throws Exception {
        for (JsonNode vector : vectors(
                "spec/conformance/nip/revocation_policy_vectors.json")) {
            JsonNode input = vector.get("input");
            JsonNode expected = vector.get("expected");
            NipRevocationPolicy evaluation = new NipRevocationPolicy(
                NipRevocationPolicy.Mode.valueOf(
                    input.get("revocation_mode").asText().toUpperCase()),
                input.get("ocsp_fail_open").asBoolean());
            NipIdentVerifyResult decision = null;
            for (JsonNode observation : input.get("sources")) {
                decision = evaluation.observe(
                    NipRevocationPolicy.Source.valueOf(
                        observation.get("source").asText().toUpperCase()),
                    NipRevocationPolicy.Outcome.valueOf(
                        observation.get("outcome").asText().toUpperCase()));
                if (decision != null) break;
            }
            if (decision == null) decision = evaluation.complete();
            assertEquals(expected.get("valid").asBoolean(), decision.valid());
            assertEquals(
                strings(expected.get("consulted_sources")),
                evaluation.consultedSources().stream()
                    .map(value -> value.name().toLowerCase()).toList());
            if (!decision.valid()) {
                assertEquals(expected.get("failed_step").asInt(), decision.stepFailed());
                assertEquals(expected.get("error").asText(), decision.errorCode());
            }
        }
    }

    @Test
    void nipSignedCrlVectors() throws Exception {
        for (JsonNode vector : vectors(
                "spec/conformance/nip/signed_crl_vectors.json")) {
            JsonNode input = vector.get("input");
            var merged = (com.fasterxml.jackson.databind.node.ObjectNode)
                input.get("body").deepCopy();
            merged.put("signature", input.get("signature").asText());
            NipCaCrl crl = MAPPER.treeToValue(merged, NipCaCrl.class);
            assertEquals(
                vector.at("/expected/signature_valid").asBoolean(),
                NipCaClient.verifyCrlSignature(
                    crl, input.get("public_key").asText()),
                vector.get("id").asText());
        }
    }

    @Test
    void nwpPortableNodeServerVectors() throws Exception {
        for (JsonNode vector : vectors(
                "spec/conformance/nwp/portable_node_server_vectors.json")) {
            JsonNode input = vector.get("input");
            JsonNode expected = vector.get("expected");
            var decision = NwpPortableProfile.evaluateNode(
                new NwpPortableProfile.NodeRequest(
                    NwpPortableProfile.Transport.valueOf(
                        input.get("transport").asText().toUpperCase()),
                    NwpPortableProfile.NodeRole.valueOf(
                        input.get("node_role").asText().toUpperCase()),
                    text(input, "method"),
                    text(input, "path"),
                    text(input, "content_type"),
                    text(input, "accept"),
                    number(input, "body_bytes", 0),
                    number(input, "max_body_bytes", 1024 * 1024),
                    text(input, "frame_kind"),
                    bool(input, "body_valid", true),
                    bool(input, "cancelled", false),
                    text(input, "correlation_id")));

            assertExpected(expected, "decision", decision.decision());
            assertExpected(expected, "http_status", decision.httpStatus());
            assertExpected(expected, "content_type", decision.contentType());
            assertExpected(expected, "status", decision.status());
            assertExpected(expected, "error", decision.error());
            assertExpected(expected, "allow", decision.allow());
            assertExpected(expected, "response_frame", decision.responseFrame());
            assertExpected(expected, "correlation_id", decision.correlationId());
            assertExpected(expected, "telemetry_outcome", decision.telemetryOutcome());
            assertExpected(expected, "legacy_media_type_accepted",
                decision.legacyMediaTypeAccepted());
        }
    }

    @Test
    void nwpBridgeLifecycleVectors() throws Exception {
        for (JsonNode vector : vectors(
                "spec/conformance/nwp/bridge_lifecycle_vectors.json")) {
            JsonNode input = vector.get("input");
            JsonNode expected = vector.get("expected");
            var decision = NwpPortableProfile.evaluateBridge(
                new NwpPortableProfile.BridgeRequest(
                    input.get("protocol").asText(),
                    input.get("endpoint").asText(),
                    strings(input.get("registered_protocols")),
                    bool(input, "allow_http", true),
                    bool(input, "reject_private", true),
                    input.has("allowed_prefixes")
                        ? strings(input.get("allowed_prefixes")) : List.of(),
                    number(input, "timeout_ms", 0),
                    number(input, "elapsed_ms", 0),
                    bool(input, "cancelled", false),
                    text(input, "correlation_id"),
                    text(input, "task_mode")));

            assertExpected(expected, "decision", decision.decision());
            assertExpected(expected, "http_status", decision.httpStatus());
            assertExpected(expected, "status", decision.status());
            assertExpected(expected, "error", decision.error());
            assertExpected(expected, "correlation_id", decision.correlationId());
            assertExpected(expected, "task_mode", decision.taskMode());
            assertExpected(expected, "telemetry_outcome", decision.telemetryOutcome());
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asText()));
        return result;
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    private static long number(JsonNode node, String field, long fallback) {
        return node.has(field) ? node.get(field).asLong() : fallback;
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        return node.has(field) ? node.get(field).asBoolean() : fallback;
    }

    private static void assertExpected(
            JsonNode expected, String field, Object actual) {
        if (!expected.has(field)) return;
        JsonNode value = expected.get(field);
        Object expectedValue = value.isBoolean()
            ? value.asBoolean()
            : value.isIntegralNumber() ? value.asInt() : value.asText();
        assertEquals(expectedValue, actual, field);
    }
}
