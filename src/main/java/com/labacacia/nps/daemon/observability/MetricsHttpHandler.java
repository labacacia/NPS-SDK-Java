// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.daemon.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code com.sun.net.httpserver} handler serving {@code GET /metrics} in the
 * Prometheus/OpenMetrics text exposition format. Port of the .NET
 * {@code MetricsEndpoint}, including the optional constant-time bearer-token
 * gate and its response codes:
 *
 * <ul>
 *   <li>no token configured, not required → open (200)</li>
 *   <li>no token configured but required → 404 (hide the endpoint)</li>
 *   <li>token configured, wrong/absent {@code Authorization: Bearer …} → 401</li>
 * </ul>
 */
public final class MetricsHttpHandler implements HttpHandler {

    /** Prometheus text exposition content type (matches the .NET constant). */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final MetricsRegistry registry;
    private final String bearerToken;
    private final boolean requireBearerToken;

    public MetricsHttpHandler(MetricsRegistry registry) {
        this(registry, null, false);
    }

    public MetricsHttpHandler(MetricsRegistry registry, String bearerToken, boolean requireBearerToken) {
        this.registry = registry;
        this.bearerToken = bearerToken;
        this.requireBearerToken = requireBearerToken;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        int authStatus = authorize(ex);
        if (authStatus != 200) {
            ex.sendResponseHeaders(authStatus, -1);
            ex.close();
            return;
        }

        StringBuilder sb = new StringBuilder(1024);
        if (registry != null) registry.writeTo(sb);
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /** Returns the HTTP status the request should get: 200 = allowed. */
    int authorize(HttpExchange ex) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return requireBearerToken ? 404 : 200;
        }
        String header = ex.getRequestHeaders().getFirst("Authorization");
        String prefix = "Bearer ";
        if (header == null || !header.startsWith(prefix)) return 401;
        String supplied = header.substring(prefix.length());
        return fixedTimeEquals(supplied, bearerToken) ? 200 : 401;
    }

    private static boolean fixedTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        return a.length == b.length && MessageDigest.isEqual(a, b);
    }
}
