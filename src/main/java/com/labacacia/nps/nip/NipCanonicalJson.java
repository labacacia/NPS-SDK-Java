// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON serialization for NIP signed payloads.
 *
 * <p>Matches the algorithm in {@link NipIdentity#sign(Map)} exactly: top-level keys
 * sorted via {@code TreeMap}, nested structures left in source order, compact JSON
 * via Jackson. Exposed publicly here so verifiers and out-of-tree consumers can
 * reproduce the bytes the CA signed without re-implementing the recipe.
 *
 * <p><strong>Note</strong>: this is NOT RFC 8785 (JCS); it is the SDK's shallow
 * canonicalization. Cross-language interop requires both signer and verifier to
 * use the same algorithm — RFC 8785 migration is tracked separately.
 */
public final class NipCanonicalJson {

    private NipCanonicalJson() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Canonicalise a payload to UTF-8 bytes — top-level keys sorted, compact JSON. */
    public static byte[] canonicalize(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsBytes(new TreeMap<>(payload));
        } catch (Exception e) {
            throw new RuntimeException("Canonical JSON serialization failed", e);
        }
    }
}
