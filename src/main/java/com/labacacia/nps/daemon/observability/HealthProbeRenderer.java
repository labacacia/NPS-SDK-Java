// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.daemon.observability;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders liveness and readiness probes without depending on any HTTP router.
 * Port of the .NET {@code HealthProbeRenderer}: {@code /healthz} always renders
 * {@code {"status":"ok"}} with 200; {@code /readyz} runs the supplied probes and
 * renders {@code {"status":"error","reason":...}} with 503 on the first failure.
 */
public final class HealthProbeRenderer {

    private HealthProbeRenderer() {}

    public static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Renders the liveness response used by {@code /healthz}. */
    public static HealthProbeResponse renderHealthz() {
        return ok();
    }

    /**
     * Runs {@code probes} and renders the readiness response used by
     * {@code /readyz}. With no probes, readiness is {@code ok}.
     */
    public static HealthProbeResponse renderReadyz(Iterable<ReadinessProbe> probes) {
        for (ReadinessProbe probe : probes) {
            String reason;
            try {
                reason = probe.check();
            } catch (Exception ex) {
                reason = probe.name() + ": " + ex.getMessage();
            }
            if (reason != null) return error(reason);
        }
        return ok();
    }

    /** Convenience overload for a {@link List} of probes. */
    public static HealthProbeResponse renderReadyz(List<ReadinessProbe> probes) {
        return renderReadyz((Iterable<ReadinessProbe>) probes);
    }

    private static HealthProbeResponse ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        return new HealthProbeResponse(200, JSON_CONTENT_TYPE, write(m), "ok", null);
    }

    private static HealthProbeResponse error(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("reason", reason);
        return new HealthProbeResponse(503, JSON_CONTENT_TYPE, write(m), "error", reason);
    }

    private static String write(Map<String, Object> m) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
