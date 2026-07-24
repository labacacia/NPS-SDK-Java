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

/** Built-in Bridge dispatcher for HTTP and HTTPS endpoints. */
public final class HttpBridgeDispatcher implements BridgeDispatcher {

    /** Anchor reference used for HTTP bridge response records. */
    public static final String RESPONSE_ANCHOR_REF = "nps://bridge/http-response/v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;

    /** Create an HTTP bridge dispatcher over an existing client. */
    public HttpBridgeDispatcher(HttpClient client) {
        if (client == null) {
            throw new NullPointerException("client");
        }
        this.client = client;
    }

    @Override
    public String protocol() {
        return BridgeProtocols.HTTP;
    }

    @Override
    public CapsFrame dispatch(ActionFrame frame, BridgeTarget target) {
        if (frame == null) throw new NullPointerException("frame");
        if (target == null) throw new NullPointerException("target");

        URI uri = BridgeEndpointValidator.parseHttpEndpoint(target);
        String method = parseMethod(BridgeTargetParser.getString(target, "method", "POST"));

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        applyBody(builder, frame, target, method);
        applyHeaders(builder, target);
        if (frame.timeoutMs() != null && frame.timeoutMs() > 0) {
            builder.timeout(Duration.ofMillis(frame.timeoutMs()));
        }

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.UPSTREAM_FAILED, "HTTP bridge request timed out.");
        } catch (Exception ex) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.UPSTREAM_FAILED, "HTTP bridge request failed.", ex);
        }

        String bodyText = response.body() == null ? "" : response.body();
        Map<String, Object> record = buildResponseRecord(response, bodyText);
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(record);
        return new CapsFrame(RESPONSE_ANCHOR_REF, 1, data, null,
            estimateTokenCost(bodyText), null, null);
    }

    private static String parseMethod(String method) {
        return (method == null || method.isBlank()) ? "POST" : method.trim().toUpperCase();
    }

    private static void applyBody(HttpRequest.Builder builder, ActionFrame frame,
                                  BridgeTarget target, String method) {
        if ("GET".equals(method) || "HEAD".equals(method)) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
            return;
        }

        Object body = null;
        Map<String, Object> parameters = frame.params();
        if (parameters != null && parameters.containsKey("body")) {
            body = parameters.get("body");
        } else {
            Object targetBody = BridgeTargetParser.getJson(target, "body");
            if (targetBody != null) {
                body = targetBody;
            }
        }

        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
            return;
        }

        String mediaType = BridgeTargetParser.getString(target, "content_type", "application/json");
        String rawBody = writeRaw(body);
        builder.method(method, HttpRequest.BodyPublishers.ofString(rawBody, StandardCharsets.UTF_8));
        builder.header("Content-Type", mediaType);
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
                    // Restricted / invalid header names are dropped, matching best-effort semantics.
                }
            }
        }
    }

    private static Map<String, Object> buildResponseRecord(HttpResponse<String> response, String bodyText) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("status_code", response.statusCode());
        record.put("reason_phrase", null);
        record.put("success", response.statusCode() >= 200 && response.statusCode() < 300);
        String contentType = response.headers().firstValue("content-type").orElse(null);
        record.put("content_type", contentType);

        Map<String, Object> headers = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> headers.put(k, String.join(",", v)));
        record.put("headers", headers);

        writeBody(record, bodyText, contentType);
        return record;
    }

    private static void writeBody(Map<String, Object> record, String bodyText, String contentType) {
        if (bodyText != null && !bodyText.isBlank() &&
            contentType != null && contentType.toLowerCase().contains("json")) {
            try {
                record.put("body", MAPPER.readValue(bodyText, Object.class));
                return;
            } catch (Exception ignore) {
                // Fall through to body_text for mislabeled upstream payloads.
            }
        }
        record.put("body_text", bodyText);
    }

    private static String writeRaw(Object body) {
        if (body instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception ex) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.TARGET_INVALID, "bridge_target body could not be serialized.", ex);
        }
    }

    private static Integer estimateTokenCost(String bodyText) {
        if (bodyText == null || bodyText.isEmpty()) {
            return 0;
        }
        return Math.max(1, bodyText.length() / 4);
    }
}
