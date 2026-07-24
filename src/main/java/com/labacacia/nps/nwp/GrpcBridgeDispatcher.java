// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.ncp.CapsFrame;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in Bridge dispatcher for unary gRPC calls using the JSON gRPC codec
 * ({@code application/grpc+json}). The endpoint path identifies the service and
 * method, for example {@code https://host/Package.Service/Method}.
 */
public final class GrpcBridgeDispatcher implements BridgeDispatcher {

    /** Anchor reference used for gRPC bridge response records. */
    public static final String RESPONSE_ANCHOR_REF = "nps://bridge/grpc-json-response/v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;

    /** Create a gRPC bridge dispatcher over an existing client. */
    public GrpcBridgeDispatcher(HttpClient client) {
        if (client == null) {
            throw new NullPointerException("client");
        }
        this.client = client;
    }

    @Override
    public String protocol() {
        return BridgeProtocols.GRPC;
    }

    @Override
    public CapsFrame dispatch(ActionFrame frame, BridgeTarget target) {
        if (frame == null) throw new NullPointerException("frame");
        if (target == null) throw new NullPointerException("target");

        URI uri = BridgeEndpointValidator.parseHttpEndpoint(target);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .version(HttpClient.Version.HTTP_2)
            .POST(HttpRequest.BodyPublishers.ofByteArray(buildGrpcMessage(frame, target)))
            .header("Content-Type", "application/grpc+json")
            .header("te", "trailers");
        applyHeaders(builder, target);
        if (frame.timeoutMs() != null && frame.timeoutMs() > 0) {
            builder.timeout(Duration.ofMillis(frame.timeoutMs()));
        }

        HttpResponse<byte[]> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.UPSTREAM_FAILED, "gRPC bridge request timed out.");
        } catch (Exception ex) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.UPSTREAM_FAILED, "gRPC bridge request failed.", ex);
        }

        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        Map<String, Object> record = buildResponseRecord(response, bytes);
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(record);
        return new CapsFrame(RESPONSE_ANCHOR_REF, 1, data, null,
            estimateTokenCost(bytes.length), null, null);
    }

    private static byte[] buildGrpcMessage(ActionFrame frame, BridgeTarget target) {
        Object payload;
        Object targetMessage = BridgeTargetParser.getJson(target, "grpc_message");
        if (targetMessage == null) targetMessage = BridgeTargetParser.getJson(target, "message");
        if (targetMessage == null) targetMessage = BridgeTargetParser.getJson(target, "body");

        Map<String, Object> parameters = frame.params();
        if (targetMessage != null) {
            payload = targetMessage;
        } else if (parameters != null && parameters.containsKey("grpc_message")) {
            payload = parameters.get("grpc_message");
        } else if (parameters != null) {
            payload = parameters;
        } else {
            payload = new LinkedHashMap<>();
        }

        byte[] json;
        try {
            json = MAPPER.writeValueAsBytes(payload);
        } catch (Exception ex) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.TARGET_INVALID, "gRPC message could not be serialized.", ex);
        }

        byte[] wire = new byte[json.length + 5];
        wire[0] = 0;
        int len = json.length;
        wire[1] = (byte) ((len >>> 24) & 0xFF);
        wire[2] = (byte) ((len >>> 16) & 0xFF);
        wire[3] = (byte) ((len >>> 8) & 0xFF);
        wire[4] = (byte) (len & 0xFF);
        System.arraycopy(json, 0, wire, 5, json.length);
        return wire;
    }

    private static void applyHeaders(HttpRequest.Builder builder, BridgeTarget target) {
        Object headers = BridgeTargetParser.getJson(target, "headers");
        if (!(headers instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> header : map.entrySet()) {
            if (header.getValue() instanceof String value && !value.isEmpty()) {
                try {
                    builder.header(String.valueOf(header.getKey()), value);
                } catch (IllegalArgumentException ignore) {
                    // Restricted / invalid header names are dropped.
                }
            }
        }
    }

    private static Map<String, Object> buildResponseRecord(HttpResponse<byte[]> response, byte[] body) {
        Map<String, Object> record = new LinkedHashMap<>();
        String grpcStatus = response.headers().firstValue("grpc-status").orElse(null);
        record.put("status_code", response.statusCode());
        boolean http2xx = response.statusCode() >= 200 && response.statusCode() < 300;
        record.put("success", http2xx && (grpcStatus == null || "0".equals(grpcStatus)));
        record.put("content_type", response.headers().firstValue("content-type").orElse(null));
        record.put("grpc_status", grpcStatus);
        record.put("grpc_message", response.headers().firstValue("grpc-message").orElse(null));

        Map<String, Object> headers = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> headers.put(k, String.join(",", v)));
        record.put("headers", headers);

        // The JDK HttpClient does not surface HTTP/2 trailers separately; they
        // are folded into headers above. Emit an empty trailers object for parity.
        record.put("trailers", new LinkedHashMap<>());

        List<Object> messages = new ArrayList<>();
        for (byte[] message : readGrpcMessages(body)) {
            messages.add(decodeMessage(message));
        }
        record.put("messages", messages);
        return record;
    }

    private static List<byte[]> readGrpcMessages(byte[] body) {
        List<byte[]> out = new ArrayList<>();
        int offset = 0;
        while (body.length - offset >= 5) {
            boolean compressed = body[offset] != 0;
            long length = ((long) (body[offset + 1] & 0xFF) << 24)
                | ((body[offset + 2] & 0xFF) << 16)
                | ((body[offset + 3] & 0xFF) << 8)
                | (body[offset + 4] & 0xFF);
            offset += 5;

            if (compressed || length > Integer.MAX_VALUE || body.length - offset < length) {
                break;
            }

            byte[] message = new byte[(int) length];
            System.arraycopy(body, offset, message, 0, (int) length);
            offset += (int) length;
            out.add(message);
        }
        return out;
    }

    private static Object decodeMessage(byte[] message) {
        try {
            return MAPPER.readValue(message, Object.class);
        } catch (Exception ex) {
            return java.util.Base64.getEncoder().encodeToString(message);
        }
    }

    private static Integer estimateTokenCost(int byteLength) {
        return byteLength == 0 ? 0 : Math.max(1, byteLength / 4);
    }
}
