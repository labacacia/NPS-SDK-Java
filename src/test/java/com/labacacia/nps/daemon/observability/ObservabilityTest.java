// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.daemon.observability;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Health/metrics endpoints, JSON logging, and graceful shutdown. */
class ObservabilityTest {

    // ── Health rendering ────────────────────────────────────────────────────────

    @Test void healthzIsOk() {
        HealthProbeResponse r = HealthProbeRenderer.renderHealthz();
        assertEquals(200, r.statusCode());
        assertEquals("{\"status\":\"ok\"}", r.body());
    }

    @Test void readyzOkWithNoProbes() {
        assertEquals(200, HealthProbeRenderer.renderReadyz(List.of()).statusCode());
    }

    @Test void readyzFailsOnFirstFailingProbe() {
        HealthProbeResponse r = HealthProbeRenderer.renderReadyz(List.of(
            new DelegateReadinessProbe("storage", () -> null),
            new DelegateReadinessProbe("keys", () -> "key material missing")));
        assertEquals(503, r.statusCode());
        assertEquals("{\"status\":\"error\",\"reason\":\"key material missing\"}", r.body());
    }

    @Test void readyzProbeExceptionBecomesReason() {
        HealthProbeResponse r = HealthProbeRenderer.renderReadyz(List.of(
            new DelegateReadinessProbe("boom", () -> { throw new RuntimeException("nope"); })));
        assertEquals(503, r.statusCode());
        assertTrue(r.reason().contains("boom"));
    }

    // ── Metrics registry ────────────────────────────────────────────────────────

    @Test void metricsPrometheusFormat() {
        MetricsRegistry reg = new MetricsRegistry();
        MetricsRegistry.Counter c = reg.registerCounter("nps_requests_total", "total requests", "method");
        c.inc("GET");
        c.inc(2, "GET");
        c.inc("POST");
        MetricsRegistry.Gauge g = reg.registerGauge("nps_inflight", "in flight");
        g.set(3);
        String out = reg.render();
        assertTrue(out.contains("# HELP nps_requests_total total requests"), out);
        assertTrue(out.contains("# TYPE nps_requests_total counter"), out);
        assertTrue(out.contains("nps_requests_total{method=\"GET\"} 3"), out);
        assertTrue(out.contains("nps_requests_total{method=\"POST\"} 1"), out);
        assertTrue(out.contains("# TYPE nps_inflight gauge"), out);
        assertTrue(out.contains("nps_inflight 3"), out);
    }

    @Test void gaugeIncDec() {
        MetricsRegistry.Gauge g = new MetricsRegistry().registerGauge("g", "h");
        g.inc(); g.inc(); g.dec();
        assertEquals(1.0, g.value());
    }

    // ── HTTP round-trips (com.sun.net.httpserver) ────────────────────────────────

    @Test void metricsEndpointOverHttp() throws Exception {
        MetricsRegistry reg = new MetricsRegistry();
        reg.registerCounter("nps_test_total", "t").inc();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metrics", new MetricsHttpHandler(reg));
        server.createContext("/healthz", HealthHttpHandler.healthz(null));
        server.start();
        try {
            HttpResponse<String> m = get(server, "/metrics");
            assertEquals(200, m.statusCode());
            assertEquals(MetricsHttpHandler.CONTENT_TYPE,
                m.headers().firstValue("Content-Type").orElse(""));
            assertTrue(m.body().contains("nps_test_total 1"), m.body());

            HttpResponse<String> h = get(server, "/healthz");
            assertEquals(200, h.statusCode());
            assertEquals("{\"status\":\"ok\"}", h.body());
        } finally {
            server.stop(0);
        }
    }

    @Test void metricsBearerTokenGateHidesWhenRequiredButUnset() {
        MetricsHttpHandler required = new MetricsHttpHandler(new MetricsRegistry(), null, true);
        // authorize() returns 404 before reading any header in this branch.
        assertEquals(404, required.authorize(null));
    }

    @Test void metricsBearerTokenOverHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metrics", new MetricsHttpHandler(new MetricsRegistry(), "s3cret", false));
        server.start();
        try {
            assertEquals(401, get(server, "/metrics").statusCode());
            HttpResponse<String> ok = getWithAuth(server, "/metrics", "Bearer s3cret");
            assertEquals(200, ok.statusCode());
            assertEquals(401, getWithAuth(server, "/metrics", "Bearer wrong").statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test void healthzReportsDrainingWhenStopping() throws Exception {
        ShutdownState state = new ShutdownState();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/healthz", HealthHttpHandler.healthz(state));
        server.start();
        try {
            assertEquals(200, get(server, "/healthz").statusCode());
            state.markStopping();
            HttpResponse<String> draining = get(server, "/healthz");
            assertEquals(503, draining.statusCode());
            assertTrue(draining.body().contains("draining"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpResponse<String> get(HttpServer s, String path) throws Exception {
        return getWithAuth(s, path, null);
    }

    private static HttpResponse<String> getWithAuth(HttpServer s, String path, String auth) throws Exception {
        int port = s.getAddress().getPort();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (auth != null) b.header("Authorization", auth);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ── JSON structured logging ──────────────────────────────────────────────────

    @Test void jsonLogEmitsExpectedFields() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonStructuredLogger log = new JsonStructuredLogger(
            "nps.test", JsonStructuredLogger.Level.INFO, new PrintStream(buf, true, StandardCharsets.UTF_8));
        log.info("hello world");
        String line = buf.toString(StandardCharsets.UTF_8).strip();
        assertTrue(line.startsWith("{") && line.endsWith("}"), line);
        assertTrue(line.contains("\"level\":\"info\""), line);
        assertTrue(line.contains("\"msg\":\"hello world\""), line);
        assertTrue(line.contains("\"logger\":\"nps.test\""), line);
        assertTrue(line.contains("\"timestamp\":\""), line);
    }

    @Test void jsonLogRespectsMinLevelAndEscaping() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonStructuredLogger log = new JsonStructuredLogger(
            "nps.test", JsonStructuredLogger.Level.WARN, new PrintStream(buf, true, StandardCharsets.UTF_8));
        log.debug("suppressed");
        assertEquals("", buf.toString(StandardCharsets.UTF_8).strip());
        log.warn("has \"quotes\" and \\slash");
        String line = buf.toString(StandardCharsets.UTF_8).strip();
        assertTrue(line.contains("\\\"quotes\\\""), line);
    }

    @Test void jsonLogExceptionField() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JsonStructuredLogger log = new JsonStructuredLogger(
            "nps.test", JsonStructuredLogger.Level.ERROR, new PrintStream(buf, true, StandardCharsets.UTF_8));
        log.error("failed", new IllegalStateException("kaboom"));
        String line = buf.toString(StandardCharsets.UTF_8).strip();
        assertTrue(line.contains("\"exception\":\""), line);
        assertTrue(line.contains("kaboom"), line);
    }

    @Test void logLevelResolvesFromEnvFallback() {
        // env var not set in test → fallback returned
        assertEquals(JsonStructuredLogger.Level.INFO,
            JsonStructuredLogger.resolveLogLevel(JsonStructuredLogger.Level.INFO));
    }

    // ── Graceful shutdown ────────────────────────────────────────────────────────

    @Test void shutdownFlipsGateAndRunsDrainTasks() {
        AtomicBoolean drained = new AtomicBoolean(false);
        ShutdownState state = new ShutdownState();
        GracefulShutdown gs = new GracefulShutdown(state, Duration.ofSeconds(2), null)
            .onDrain(() -> drained.set(true));
        assertFalse(state.isStopping());
        gs.triggerShutdown();
        assertTrue(state.isStopping());
        assertTrue(gs.await());
        assertTrue(drained.get());
    }

    @Test void shutdownIsIdempotent() {
        AtomicBoolean once = new AtomicBoolean(false);
        GracefulShutdown gs = new GracefulShutdown(new ShutdownState(), Duration.ofSeconds(1), null)
            .onDrain(() -> {
                if (!once.compareAndSet(false, true)) fail("drain ran twice");
            });
        gs.triggerShutdown();
        gs.triggerShutdown();
        assertTrue(once.get());
    }

    @Test void defaultDrainTimeoutIs30s() {
        assertEquals(30, GracefulShutdown.DEFAULT_DRAIN_TIMEOUT.toSeconds());
    }
}
