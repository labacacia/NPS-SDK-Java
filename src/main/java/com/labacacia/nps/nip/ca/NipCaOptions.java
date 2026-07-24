// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the reusable NIP CA service library (NPS-3 §8,
 * NPS-CR-0003, NPS-CR-0005). Mutable POJO — set fields after construction.
 *
 * <p>Faithful port of the .NET {@code NipCaOptions}; only the fields the
 * in-process service library needs are carried (DB connection strings, ACME
 * toggles, and key-file paths are the standalone server's concern and are
 * intentionally omitted here — the library is handed a
 * {@link com.labacacia.nps.nip.NipIdentity} directly).
 */
public final class NipCaOptions {

    // ── Identity ─────────────────────────────────────────────────────────────

    /** CA NID, e.g. {@code urn:nps:org:ca.example.com}. Used as {@code issued_by}. */
    public String caNid;

    /** Human-readable CA name for {@code /.well-known/nps-ca}. */
    public String displayName;

    /** Base URL of this CA, e.g. {@code https://ca.example.com}. */
    public String baseUrl = "";

    /** HTTP route prefix. Default {@code ""} (root). */
    public String routePrefix = "";

    // ── Certificate lifetimes ─────────────────────────────────────────────────

    /** Agent certificate validity in days. Default 30 (NPS-3 §2.2). */
    public int agentCertValidityDays = 30;

    /** Node certificate validity in days. Default 90 (NPS-3 §2.2). */
    public int nodeCertValidityDays = 90;

    /** Renewal window in days before expiry. Default 7 (NPS-3 §6). */
    public int renewalWindowDays = 7;

    /** Orchestrator group NID validity in days. Default 365 (NPS-CR-0003 §5.1.3). */
    public int groupCertValidityDays = 365;

    /** Default session NID validity. Default 1 hour (NPS-CR-0003 §5.1.3). */
    public Duration sessionDefaultValidity = Duration.ofHours(1);

    /** Maximum session validity. Default 24 hours. */
    public Duration sessionMaxValidity = Duration.ofHours(24);

    /** Minimum session validity. Default 60 seconds. */
    public Duration sessionMinValidity = Duration.ofMinutes(1);

    /** Group-JWS {@code iat} clock-skew window. Default ±5 minutes. */
    public Duration sessionJwsClockSkew = Duration.ofMinutes(5);

    // ── Security ──────────────────────────────────────────────────────────────

    /** When true, OCSP responses are delayed to ≥200 ms (NPS-3 §10.2). Default true. */
    public boolean normalizeOcspResponseTime = true;

    /** Advertised algorithms. Default {@code ["ed25519"]}. */
    public List<String> algorithms = List.of("ed25519");

    /**
     * Bearer token required on operator endpoints. When null, operator auth is
     * skipped (development only).
     */
    public String operatorApiKey;

    /** When non-null, only these capabilities may be requested at registration. */
    public java.util.Set<String> allowedCapabilities;

    // ── Enrollment / RA (NPS-CR-0005) ────────────────────────────────────────

    /** Active enrollment tier. Default {@link EnrollmentTier#ALLOWLIST}. */
    public EnrollmentTier enrollmentTier = EnrollmentTier.ALLOWLIST;

    /** Tier-1 glob allowlist patterns. Default {@code ["*"]} (open CA). */
    public List<String> enrollmentAllowlistPatterns = List.of("*");

    /** Max TTL for bootstrap tokens. Default 24 hours. */
    public Duration bootstrapTokenMaxTtl = Duration.ofHours(24);

    /** Max pending-queue size. Default 1000. */
    public int pendingQueueMaxSize = 1000;

    /** Age after which non-pending records are swept. Default 7 days. */
    public Duration pendingQueueMaxAge = Duration.ofDays(7);
}
