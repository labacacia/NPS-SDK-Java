// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.ra;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * In-memory {@link IBootstrapTokenStore} for single-node deployments and
 * integration tests. Not durable across restarts.
 */
public final class InMemoryBootstrapTokenStore implements IBootstrapTokenStore {

    private static final SecureRandom RNG = new SecureRandom();

    private static final class Entry {
        final String id;
        final byte[] hash;
        final String label;
        final Instant createdAt;
        final Instant expiresAt;
        boolean consumed;
        boolean revoked;
        Entry(String id, byte[] hash, String label, Instant createdAt, Instant expiresAt) {
            this.id = id; this.hash = hash; this.label = label;
            this.createdAt = createdAt; this.expiresAt = expiresAt;
        }
    }

    private final Object gate = new Object();
    private final List<Entry> tokens = new ArrayList<>();

    @Override
    public String create(String label, Instant expiresAt) {
        byte[] rnd = new byte[16];
        RNG.nextBytes(rnd);
        String raw = "nps-bootstrap-" + HexFormat.of().formatHex(rnd);
        byte[] hash = sha256(raw);
        String id = UUID.randomUUID().toString().replace("-", "");
        synchronized (gate) {
            tokens.add(new Entry(id, hash, label, Instant.now(), expiresAt));
        }
        return raw;
    }

    @Override
    public boolean validateAndConsume(String token) {
        byte[] hash = sha256(token);
        synchronized (gate) {
            for (Entry e : tokens) {
                if (e.consumed || e.revoked) continue;
                if (Instant.now().isAfter(e.expiresAt)) continue;
                if (!MessageDigest.isEqual(hash, e.hash)) continue;
                e.consumed = true;
                return true;
            }
            return false;
        }
    }

    @Override
    public List<BootstrapTokenInfo> list() {
        synchronized (gate) {
            List<BootstrapTokenInfo> out = new ArrayList<>();
            for (Entry e : tokens)
                out.add(new BootstrapTokenInfo(e.id, e.label, e.createdAt, e.expiresAt, e.consumed, e.revoked));
            return out;
        }
    }

    @Override
    public boolean revoke(String tokenId) {
        synchronized (gate) {
            for (Entry e : tokens) {
                if (!e.id.equals(tokenId)) continue;
                if (e.consumed || e.revoked) return false;
                e.revoked = true;
                return true;
            }
            return false;
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
