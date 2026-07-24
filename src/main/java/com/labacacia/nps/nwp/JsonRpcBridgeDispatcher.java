// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.UUID;

/** Base dispatcher for JSON-RPC 2.0 protocols transported over HTTP POST. */
public abstract class JsonRpcBridgeDispatcher implements BridgeDispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final String defaultMethod;
    private final String responseAnchorRef;

    /** Create a JSON-RPC bridge dispatcher. */
    protected JsonRpcBridgeDispatcher(HttpClient client, String defaultMethod, String responseAnchorRef) {
        if (client == null) {
            throw new NullPointerException("client");
        }
        if (defaultMethod == null || defaultMethod.isBlank()) {
            throw new IllegalArgumentException("Default JSON-RPC method must not be empty.");
        }
        if (responseAnchorRef == null || responseAnchorRef.isBlank()) {
            throw new IllegalArgumentException("Response anchor reference must not be empty.");
        }
        this.client = client;
        this.defaultMethod = defaultMethod;
        this.responseAnchorRef = responseAnchorRef;
    }

    @Override
    public abstract String protocol();

    @Override
    public CapsFrame dispatch(ActionFrame frame, BridgeTarget target) {
        if (frame == null) throw new NullPointerException("frame");
        if (target == null) throw new NullPointerException("target");

        URI uri = BridgeEndpointValidator.parseHttpEndpoint(target);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(frame, target), StandardCharsets.UTF_8))
            .header("Content-Type", "application/json");
        applyHeaders(builder, target);
        if (frame.timeoutMs() != null && frame.timeoutMs() > 0) {
            builder.timeout(Duration.ofMillis(frame.timeoutMs()));
        }

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.UPSTREAM_FAILED, protocol() + " JSON-RPC bridge request timed out.");
        } catch (Exception ex) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.UPSTREAM_FAILED, protocol() + " JSON-RPC bridge request failed.", ex);
        }

        String bodyText = response.body() == null ? "" : response.body();
        Map<String, Object> record = buildResponseRecord(response, bodyText);
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(record);
        return new CapsFrame(responseAnchorRef, 1, data, null,
            estimateTokenCost(bodyText), null, null);
    }

    private String buildRequestBody(ActionFrame frame, BridgeTarget target) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", readRequestId(frame, target));
        body.put("method", readRpcMethod(frame, target));
        body.put("params", readRpcParams(frame, target));
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception ex) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.TARGET_INVALID, "JSON-RPC request body could not be serialized.", ex);
        }
    }

    private String readRpcMethod(ActionFrame frame, BridgeTarget target) {
        String method = BridgeTargetParser.getString(target, "rpc_method");
        if (method == null) {
            method = BridgeTargetParser.getString(target, "method");
        }
        if (method != null && !method.isBlank()) {
            return method;
        }

        Map<String, Object> parameters = frame.params();
        if (parameters != null && parameters.get("rpc_method") instanceof String frameMethod
            && !frameMethod.isBlank()) {
            return frameMethod;
        }

        return defaultMethod;
    }

    private static Object readRequestId(ActionFrame frame, BridgeTarget target) {
        Object targetId = BridgeTargetParser.getJson(target, "id");
        if (targetId != null) {
            return targetId;
        }

        Map<String, Object> parameters = frame.params();
        if (parameters != null && parameters.containsKey("id")) {
            return parameters.get("id");
        }

        if (frame.requestId() != null) {
            return frame.requestId();
        }
        if (frame.idempotencyKey() != null) {
            return frame.idempotencyKey();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Object readRpcParams(ActionFrame frame, BridgeTarget target) {
        Object targetRpcParams = BridgeTargetParser.getJson(target, "rpc_params");
        if (targetRpcParams == null) {
            targetRpcParams = BridgeTargetParser.getJson(target, "params");
        }
        if (targetRpcParams != null) {
            return targetRpcParams;
        }

        Map<String, Object> parameters = frame.params();
        if (parameters == null) {
            return new LinkedHashMap<>();
        }

        for (String name : new String[] { "rpc_params", "params", "body" }) {
            if (parameters.containsKey(name)) {
                return parameters.get(name);
            }
        }

        Map<String, Object> selected = new LinkedHashMap<>();
        for (Map.Entry<String, Object> property : parameters.entrySet()) {
            String name = property.getKey();
            if ("bridge_target".equals(name) || "rpc_method".equals(name)
                || "method".equals(name) || "id".equals(name)) {
                continue;
            }
            selected.put(name, property.getValue());
        }
        return selected;
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

    private static Map<String, Object> buildResponseRecord(HttpResponse<String> response, String bodyText) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("status_code", response.statusCode());
        record.put("success", response.statusCode() >= 200 && response.statusCode() < 300);
        String contentType = response.headers().firstValue("content-type").orElse(null);
        record.put("content_type", contentType);

        Map<String, Object> headers = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> headers.put(k, String.join(",", v)));
        record.put("headers", headers);

        writeJsonRpcBody(record, bodyText, contentType);
        return record;
    }

    @SuppressWarnings("unchecked")
    private static void writeJsonRpcBody(Map<String, Object> record, String bodyText, String contentType) {
        if (bodyText != null && !bodyText.isBlank() &&
            contentType != null && contentType.toLowerCase().contains("json")) {
            try {
                JsonNode body = MAPPER.readTree(bodyText);
                record.put("jsonrpc_response", MAPPER.convertValue(body, Object.class));

                if (body.isObject()) {
                    if (body.has("result")) {
                        record.put("result", MAPPER.convertValue(body.get("result"), Object.class));
                    }
                    if (body.has("error")) {
                        record.put("error", MAPPER.convertValue(body.get("error"), Object.class));
                    }
                }
                return;
            } catch (Exception ignore) {
                // Fall through to body_text for mislabeled upstream payloads.
            }
        }
        record.put("body_text", bodyText);
    }

    private static Integer estimateTokenCost(String bodyText) {
        if (bodyText == null || bodyText.isEmpty()) {
            return 0;
        }
        return Math.max(1, bodyText.length() / 4);
    }
}
