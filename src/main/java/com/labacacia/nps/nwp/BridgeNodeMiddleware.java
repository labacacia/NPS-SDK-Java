// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.ncp.CapsFrame;
import com.labacacia.nps.ncp.ErrorFrame;
import com.labacacia.nps.core.NpsFrame;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK {@link HttpHandler} (com.sun.net.httpserver, zero third-party deps)
 * exposing a Bridge Node at {@code /.nwm}, {@code /actions}, and {@code /invoke}.
 * Server-side counterpart of the .NET BridgeNodeMiddleware.
 */
public final class BridgeNodeMiddleware implements HttpHandler {

    static final ObjectMapper JSON =
        new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final BridgeNode bridge;
    private final BridgeDispatcherRegistry registry;
    private final BridgeNodeOptions options;

    /** Create Bridge Node middleware. */
    public BridgeNodeMiddleware(BridgeNode bridge, BridgeDispatcherRegistry registry, BridgeNodeOptions options) {
        this.bridge = bridge;
        this.registry = registry;
        this.options = options;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String prefix = trimTrailingSlash(options.pathPrefix);

            if (!path.regionMatches(true, 0, prefix, 0, prefix.length())) {
                ex.sendResponseHeaders(404, -1);
                return;
            }

            String sub = path.substring(prefix.length());
            switch (sub) {
                case "/.nwm", "/.nwm/" ->
                    writeJson(ex, 200, buildManifest(), NwpHttpHeaders.MIME_MANIFEST);
                case "/actions", "/actions/" ->
                    writeJson(ex, 200, buildActions(), "application/json");
                case "/invoke", "/invoke/" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        ex.sendResponseHeaders(405, -1);
                        return;
                    }
                    handleInvoke(ex);
                }
                default -> ex.sendResponseHeaders(404, -1);
            }
        } finally {
            ex.close();
        }
    }

    private void handleInvoke(HttpExchange ex) throws IOException {
        if (options.requireAuth
            && ex.getRequestHeaders().getFirst(NwpHttpHeaders.AGENT) == null) {
            writeError(ex, 401, "NPS-CLIENT-UNAUTHORIZED",
                "NWP-BRIDGE-AUTH-REQUIRED", "X-NWP-Agent header is required.");
            return;
        }

        ActionFrame frame;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(ex.getRequestBody(), Map.class);
            if (body == null) {
                writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST",
                    BridgeErrorCodes.TARGET_INVALID, "ActionFrame body is required.");
                return;
            }
            frame = ActionFrame.fromDict(body);
        } catch (Exception exc) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST",
                BridgeErrorCodes.TARGET_INVALID, exc.getMessage());
            return;
        }

        if (!options.actionId.equals(frame.actionId())) {
            writeError(ex, 404, "NPS-CLIENT-NOT-FOUND",
                "NWP-BRIDGE-ACTION-NOT-FOUND", "Unknown bridge action '" + frame.actionId() + "'.");
            return;
        }

        try {
            CapsFrame caps = bridge.dispatch(frame);
            writeFrame(ex, 200, caps);
        } catch (BridgeDispatchException exc) {
            int status = BridgeErrorCodes.UPSTREAM_FAILED.equals(exc.errorCode()) ? 502 : 400;
            String npsStatus = status == 502 ? "NPS-SERVER-UPSTREAM-FAILED" : "NPS-CLIENT-BAD-REQUEST";
            writeError(ex, status, npsStatus, exc.errorCode(), exc.getMessage());
        } catch (Exception exc) {
            writeError(ex, 500, "NPS-SERVER-ERROR",
                BridgeErrorCodes.UPSTREAM_FAILED, exc.getMessage());
        }
    }

    private Map<String, Object> buildManifest() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node_type", BridgeNodeMetadata.NODE_TYPE);
        m.put("node_id", options.nodeId);
        m.put("bridge_protocols", sortedProtocols());
        m.put("actions", List.of(options.actionId));
        return m;
    }

    private List<Object> buildActions() {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("action_id", options.actionId);
        action.put("description", "Dispatch an NWP ActionFrame to an external Bridge target.");
        action.put("bridge_protocols", sortedProtocols());
        List<Object> list = new ArrayList<>();
        list.add(action);
        return list;
    }

    private List<String> sortedProtocols() {
        List<String> protocols = new ArrayList<>(registry.protocols());
        protocols.sort(String.CASE_INSENSITIVE_ORDER);
        return protocols;
    }

    private static void writeFrame(HttpExchange ex, int status, NpsFrame frame) throws IOException {
        Map<String, Object> dict = frame.toDict();
        dict.values().removeIf(v -> v == null);
        writeJson(ex, status, dict, "application/json");
    }

    private static void writeJson(HttpExchange ex, int status, Object body, String contentType)
            throws IOException {
        byte[] raw = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, raw.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(raw);
        }
    }

    private static void writeError(HttpExchange ex, int httpStatus, String status,
                                   String error, String message) throws IOException {
        ErrorFrame frame = new ErrorFrame(status, error, message, null);
        writeFrame(ex, httpStatus, frame);
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }
}
