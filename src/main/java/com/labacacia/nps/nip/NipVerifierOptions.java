// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

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
 */
public final class NipVerifierOptions {

    private final Map<String, PublicKey>   trustedCaPublicKeys;
    private final List<X509Certificate>    trustedX509Roots;
    private final AssuranceLevel           minAssuranceLevel;

    private NipVerifierOptions(Builder b) {
        this.trustedCaPublicKeys = b.trustedCaPublicKeys;
        this.trustedX509Roots    = b.trustedX509Roots;
        this.minAssuranceLevel   = b.minAssuranceLevel;
    }

    public Map<String, PublicKey>  trustedCaPublicKeys() { return trustedCaPublicKeys; }
    public List<X509Certificate>   trustedX509Roots()    { return trustedX509Roots; }
    public AssuranceLevel          minAssuranceLevel()   { return minAssuranceLevel; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Map<String, PublicKey>   trustedCaPublicKeys = Map.of();
        private List<X509Certificate>    trustedX509Roots    = List.of();
        private AssuranceLevel           minAssuranceLevel;

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
        public NipVerifierOptions build() {
            return new NipVerifierOptions(this);
        }
    }
}
