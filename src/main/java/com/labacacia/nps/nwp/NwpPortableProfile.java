// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.NpsStatusCodes;

import java.util.List;
import java.util.Locale;

/** Pure NWP v0.20 portable Node admission and outbound Bridge lifecycle policy. */
public final class NwpPortableProfile {
    private NwpPortableProfile() {}

    public enum Transport { HTTP, NATIVE }
    public enum NodeRole { MEMORY, ACTION, COMPLEX }

    public record NodeRequest(
        Transport transport,
        NodeRole nodeRole,
        String method,
        String path,
        String contentType,
        String accept,
        long bodyBytes,
        long maxBodyBytes,
        String frameKind,
        boolean bodyValid,
        boolean cancelled,
        String correlationId
    ) {}

    public record NodeDecision(
        String decision,
        Integer httpStatus,
        String contentType,
        String status,
        String error,
        String allow,
        String responseFrame,
        String correlationId,
        String telemetryOutcome,
        Boolean legacyMediaTypeAccepted
    ) {}

    /** Evaluate one Node request without reading a stream or invoking a provider. */
    public static NodeDecision evaluateNode(NodeRequest request) {
        if (request.cancelled()) {
            return nodeResult(request, "abort", null, null, null, null,
                null, null, "cancelled", null);
        }
        return request.transport() == Transport.NATIVE
            ? evaluateNativeNode(request) : evaluateHttpNode(request);
    }

    private static NodeDecision evaluateHttpNode(NodeRequest request) {
        String path = lower(request.path());
        String method = upper(request.method());
        if ("/.nwm".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(request, "GET");
            return nodeResult(request, "serve_manifest", 200,
                NwpHttpHeaders.MIME_MANIFEST, null, null, null, null,
                "success", null);
        }
        if (!"/query".equals(path) && !"/invoke".equals(path)) {
            return nodeReject(request, 404, NpsStatusCodes.NPS_CLIENT_NOT_FOUND,
                NwpErrorCodes.NWP_HTTP_FRAME_BODY_MALFORMED);
        }
        if (!"POST".equals(method)) return methodNotAllowed(request, "POST");

        String mediaType = baseMediaType(request.contentType());
        boolean legacy = NwpHttpHeaders.MIME_LEGACY_FRAME.equals(mediaType);
        if (!legacy && !NwpHttpHeaders.MIME_FRAME.equals(mediaType)) {
            return nodeReject(request, 400, NpsStatusCodes.NPS_CLIENT_BAD_FRAME,
                NwpErrorCodes.NWP_HTTP_CONTENT_TYPE_UNSUPPORTED);
        }
        if (!accepts(request.accept(), NwpHttpHeaders.MIME_CAPSULE)) {
            return nodeReject(request, 400, NpsStatusCodes.NPS_CLIENT_BAD_PARAM,
                NwpErrorCodes.NWP_HTTP_ACCEPT_UNSATISFIABLE);
        }
        if (request.maxBodyBytes() <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        if (request.bodyBytes() > request.maxBodyBytes()) {
            return nodeReject(request, 413, NpsStatusCodes.NPS_LIMIT_PAYLOAD,
                NwpErrorCodes.NWP_HTTP_BODY_TOO_LARGE);
        }
        if (!request.bodyValid()) {
            return nodeReject(request, 400, NpsStatusCodes.NPS_CLIENT_BAD_FRAME,
                NwpErrorCodes.NWP_HTTP_FRAME_BODY_MALFORMED);
        }

        boolean query = "/query".equals(path)
            && (request.nodeRole() == NodeRole.MEMORY || request.nodeRole() == NodeRole.COMPLEX)
            && "query".equals(lower(request.frameKind()));
        boolean action = "/invoke".equals(path)
            && (request.nodeRole() == NodeRole.ACTION || request.nodeRole() == NodeRole.COMPLEX)
            && "action".equals(lower(request.frameKind()));
        if (!query && !action) {
            return nodeReject(request, 400, NpsStatusCodes.NPS_CLIENT_BAD_FRAME,
                NwpErrorCodes.NWP_HTTP_FRAME_BODY_MALFORMED);
        }
        return nodeResult(request, query ? "dispatch_query" : "dispatch_action",
            200, NwpHttpHeaders.MIME_CAPSULE, null, null, null, null,
            "success", legacy);
    }

    private static NodeDecision evaluateNativeNode(NodeRequest request) {
        String frameKind = lower(request.frameKind());
        boolean query = "query".equals(frameKind)
            && (request.nodeRole() == NodeRole.MEMORY || request.nodeRole() == NodeRole.COMPLEX);
        boolean action = "action".equals(frameKind)
            && (request.nodeRole() == NodeRole.ACTION || request.nodeRole() == NodeRole.COMPLEX);
        if (request.bodyValid() && (query || action)) {
            return nodeResult(request, query ? "dispatch_query" : "dispatch_action",
                null, null, null, null, null, "caps", "success", null);
        }
        return nodeResult(request, "error_frame", null, null,
            NpsStatusCodes.NPS_CLIENT_BAD_FRAME, "NWP-NATIVE-FRAME-UNSUPPORTED",
            null, "error", "rejected", null);
    }

    private static NodeDecision methodNotAllowed(NodeRequest request, String allowedMethod) {
        return nodeResult(request, "reject", 405, null, null, null,
            allowedMethod, null, "rejected", null);
    }

    private static NodeDecision nodeReject(
            NodeRequest request, int httpStatus, String status, String error) {
        return nodeResult(request, "reject", httpStatus, NwpHttpHeaders.MIME_ERROR,
            status, error, null, null, "rejected", null);
    }

    private static NodeDecision nodeResult(
            NodeRequest request,
            String decision,
            Integer httpStatus,
            String contentType,
            String status,
            String error,
            String allow,
            String responseFrame,
            String telemetryOutcome,
            Boolean legacyMediaTypeAccepted) {
        return new NodeDecision(decision, httpStatus, contentType, status, error,
            allow, responseFrame, request.correlationId(), telemetryOutcome,
            legacyMediaTypeAccepted);
    }

    public record BridgeRequest(
        String protocol,
        String endpoint,
        List<String> registeredProtocols,
        boolean allowHttp,
        boolean rejectPrivate,
        List<String> allowedPrefixes,
        long timeoutMs,
        long elapsedMs,
        boolean cancelled,
        String correlationId,
        String taskMode
    ) {}

    public record BridgeDecision(
        String decision,
        Integer httpStatus,
        String status,
        String error,
        String correlationId,
        String taskMode,
        String telemetryOutcome
    ) {}

    /** Evaluate outbound Bridge preflight without making an upstream connection. */
    public static BridgeDecision evaluateBridge(BridgeRequest request) {
        if (request.cancelled()) {
            return bridgeResult(request, "abort", null, null, null, null, "cancelled");
        }
        if (blank(request.protocol()) || blank(request.endpoint())) {
            return bridgeResult(request, "reject", 422,
                NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE,
                BridgeErrorCodes.NWP_BRIDGE_TARGET_INVALID, null, "rejected");
        }
        if (request.registeredProtocols().stream()
                .noneMatch(value -> value.equalsIgnoreCase(request.protocol()))) {
            return bridgeResult(request, "reject", 501,
                NpsStatusCodes.NPS_SERVER_UNSUPPORTED,
                BridgeErrorCodes.NWP_BRIDGE_PROTOCOL_UNSUPPORTED, null, "rejected");
        }

        String endpointError = ComplexNodeServer.ComplexChildUrlValidator.validate(
            request.endpoint(),
            request.allowedPrefixes(),
            request.rejectPrivate(),
            request.allowHttp());
        if (endpointError != null) {
            return bridgeResult(request, "reject", 422,
                NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE,
                BridgeErrorCodes.NWP_BRIDGE_ENDPOINT_INVALID, null, "rejected");
        }

        if (request.timeoutMs() <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        if (request.elapsedMs() < 0) {
            throw new IllegalArgumentException("elapsedMs must not be negative");
        }
        if (request.elapsedMs() >= request.timeoutMs()) {
            return bridgeResult(request, "reject", 504,
                NpsStatusCodes.NPS_SERVER_TIMEOUT,
                BridgeErrorCodes.NWP_BRIDGE_UPSTREAM_FAILED, null, "timeout");
        }

        String taskMode = "async".equalsIgnoreCase(request.taskMode()) ? "async" : "sync";
        return bridgeResult(request, "dispatch", null,
            "async".equals(taskMode)
                ? NpsStatusCodes.NPS_OK_ACCEPTED : NpsStatusCodes.NPS_OK,
            null, taskMode, "success");
    }

    private static BridgeDecision bridgeResult(
            BridgeRequest request,
            String decision,
            Integer httpStatus,
            String status,
            String error,
            String taskMode,
            String telemetryOutcome) {
        return new BridgeDecision(decision, httpStatus, status, error,
            request.correlationId(), taskMode, telemetryOutcome);
    }

    private static String baseMediaType(String value) {
        if (value == null) return "";
        int separator = value.indexOf(';');
        return (separator < 0 ? value : value.substring(0, separator))
            .trim().toLowerCase(Locale.ROOT);
    }

    private static boolean accepts(String value, String responseType) {
        if (blank(value)) return true;
        for (String item : value.split(",")) {
            String mediaType = baseMediaType(item);
            if ("*/*".equals(mediaType)
                    || "application/*".equals(mediaType)
                    || responseType.equals(mediaType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
