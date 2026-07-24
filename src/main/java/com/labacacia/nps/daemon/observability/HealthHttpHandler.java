// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.daemon.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code com.sun.net.httpserver} handler for the daemon liveness/readiness
 * probes. Register on paths {@code /healthz} and {@code /readyz}. Port of the
 * .NET {@code HealthEndpoints}; delegates rendering to {@link HealthProbeRenderer}.
 *
 * <p>{@code /healthz} additionally returns 503 when a {@link ShutdownState} was
 * supplied and the daemon is draining, so a load balancer sees the pod leave
 * rotation before its listener closes.</p>
 */
public final class HealthHttpHandler implements HttpHandler {

    /** Probe mode selected at registration. */
    public enum Mode { HEALTHZ, READYZ }

    private final Mode mode;
    private final List<ReadinessProbe> probes;
    private final ShutdownState shutdownState;

    public HealthHttpHandler(Mode mode, List<ReadinessProbe> probes, ShutdownState shutdownState) {
        this.mode = mode;
        this.probes = probes == null ? List.of() : probes;
        this.shutdownState = shutdownState;
    }

    /** Liveness handler. */
    public static HealthHttpHandler healthz(ShutdownState shutdownState) {
        return new HealthHttpHandler(Mode.HEALTHZ, List.of(), shutdownState);
    }

    /** Readiness handler over {@code probes}. */
    public static HealthHttpHandler readyz(List<ReadinessProbe> probes) {
        return new HealthHttpHandler(Mode.READYZ, probes, null);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        HealthProbeResponse resp;
        if (mode == Mode.HEALTHZ) {
            resp = (shutdownState != null && shutdownState.isStopping())
                ? draining()
                : HealthProbeRenderer.renderHealthz();
        } else {
            resp = HealthProbeRenderer.renderReadyz(probes);
        }
        write(ex, resp);
    }

    private static HealthProbeResponse draining() {
        return new HealthProbeResponse(
            503, HealthProbeRenderer.JSON_CONTENT_TYPE,
            "{\"status\":\"error\",\"reason\":\"draining\"}", "error", "draining");
    }

    private static void write(HttpExchange ex, HealthProbeResponse resp) throws IOException {
        byte[] body = resp.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", resp.contentType());
        ex.sendResponseHeaders(resp.statusCode(), body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
