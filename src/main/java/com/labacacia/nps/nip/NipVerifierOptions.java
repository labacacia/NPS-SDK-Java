// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import java.net.http.HttpClient;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for {@link NipIdentVerifier}.
 *
 * <p>Phase 1 dual-trust per NPS-RFC-0002 §8.1:
 * <ul>
 *   <li>If {@link #trustedX509Roots()} is null/empty, the verifier rejects v2 frames
 *       (no trust possible) but continues to accept v1 frames per the legacy path.</li>
 *   <li>If populated, the verifier runs both v1 Ed25519 check AND v2 X.509 chain check.</li>
 * </ul>
 *
 * <p>Trusted CA public keys (legacy v1 path) are looked up by issuer NID via
 * {@link #trustedCaPublicKeys()}.
 *
 * <p>Revocation (NPS-3 §7 step 4) is layered in the same order as the .NET
 * reference: local CRL → {@link #revocationCheck()} callback →
 * {@link #revocationStore()} → OCSP GET {@code {ocspUrl}/{nid}}. When none of
 * these are configured the revocation step is a pass-through.
 */
public final class NipVerifierOptions {

    private final Map<String, PublicKey>   trustedCaPublicKeys;
    private final List<X509Certificate>    trustedX509Roots;
    private final AssuranceLevel           minAssuranceLevel;

    // ── Revocation (Step 4) ──────────────────────────────────────────────────
    private final Set<String>              localRevokedSerials;
    private final NipRevocationCheck       revocationCheck;
    private final NipRevocationStore       revocationStore;
    private final String                   ocspUrl;
    private final boolean                  ocspFailOpen;
    private final HttpClient               httpClient;
    private final NipRevocationPolicy.Mode revocationMode;
    private final boolean                  phase3Enforcement;

    private NipVerifierOptions(Builder b) {
        this.trustedCaPublicKeys = b.trustedCaPublicKeys;
        this.trustedX509Roots    = b.trustedX509Roots;
        this.minAssuranceLevel   = b.minAssuranceLevel;
        this.localRevokedSerials = b.localRevokedSerials;
        this.revocationCheck     = b.revocationCheck;
        this.revocationStore     = b.revocationStore;
        this.ocspUrl             = b.ocspUrl;
        this.ocspFailOpen        = b.ocspFailOpen;
        this.httpClient          = b.httpClient;
        this.revocationMode      = b.revocationMode;
        this.phase3Enforcement   = b.phase3Enforcement;
    }

    public Map<String, PublicKey>  trustedCaPublicKeys() { return trustedCaPublicKeys; }
    public List<X509Certificate>   trustedX509Roots()    { return trustedX509Roots; }
    public AssuranceLevel          minAssuranceLevel()   { return minAssuranceLevel; }

    /** Local set of revoked certificate serials, checked before any network call. */
    public Set<String>             localRevokedSerials() { return localRevokedSerials; }

    /** Pluggable live revocation callback; runs after the local CRL and before the store/OCSP. */
    public NipRevocationCheck      revocationCheck()     { return revocationCheck; }

    /** Optional store consulted by serial; a populated {@code revokedAt} rejects the identity. */
    public NipRevocationStore      revocationStore()     { return revocationStore; }

    /** Optional CA OCSP endpoint. The verifier issues {@code GET {ocspUrl}/{nid}}. */
    public String                  ocspUrl()             { return ocspUrl; }

    /** When true, OCSP transport failures pass through. Secure default is fail-closed. */
    public boolean                 ocspFailOpen()        { return ocspFailOpen; }

    /** HttpClient used for OCSP; falls back to {@link HttpClient#newHttpClient()} when null. */
    public HttpClient              httpClient()          { return httpClient; }
    public NipRevocationPolicy.Mode revocationMode()     { return revocationMode; }
    public boolean                 phase3Enforcement()   { return phase3Enforcement; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Map<String, PublicKey>   trustedCaPublicKeys = Map.of();
        private List<X509Certificate>    trustedX509Roots    = List.of();
        private AssuranceLevel           minAssuranceLevel;
        private Set<String>              localRevokedSerials;
        private NipRevocationCheck       revocationCheck;
        private NipRevocationStore       revocationStore;
        private String                   ocspUrl;
        private boolean                  ocspFailOpen;
        private HttpClient               httpClient;
        private NipRevocationPolicy.Mode revocationMode =
            NipRevocationPolicy.Mode.IF_CONFIGURED;
        private boolean                  phase3Enforcement = false;

        public Builder trustedCaPublicKeys(Map<String, PublicKey> v) {
            this.trustedCaPublicKeys = v == null ? Map.of() : v;
            return this;
        }
        public Builder trustedX509Roots(List<X509Certificate> v) {
            this.trustedX509Roots = v == null ? List.of() : v;
            return this;
        }
        public Builder minAssuranceLevel(AssuranceLevel v) {
            this.minAssuranceLevel = v;
            return this;
        }
        public Builder localRevokedSerials(Set<String> v) {
            this.localRevokedSerials = v;
            return this;
        }
        public Builder revocationCheck(NipRevocationCheck v) {
            this.revocationCheck = v;
            return this;
        }
        public Builder revocationStore(NipRevocationStore v) {
            this.revocationStore = v;
            return this;
        }
        public Builder ocspUrl(String v) {
            this.ocspUrl = v;
            return this;
        }
        public Builder ocspFailOpen(boolean v) {
            this.ocspFailOpen = v;
            return this;
        }
        public Builder httpClient(HttpClient v) {
            this.httpClient = v;
            return this;
        }
        public Builder revocationMode(NipRevocationPolicy.Mode v) {
            this.revocationMode = v == null
                ? NipRevocationPolicy.Mode.IF_CONFIGURED : v;
            return this;
        }
        public Builder phase3Enforcement(boolean v) {
            this.phase3Enforcement = v;
            return this;
        }
        public NipVerifierOptions build() {
            return new NipVerifierOptions(this);
        }
    }
}
