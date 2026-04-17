// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.cache;

import com.labacacia.nps.core.exception.NpsAnchorNotFoundError;
import com.labacacia.nps.core.exception.NpsAnchorPoisonError;
import com.labacacia.nps.ncp.AnchorFrame;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Thread-safe cache for {@link AnchorFrame} instances, keyed by sha256 anchor ID. */
public final class AnchorFrameCache {

    private record Entry(AnchorFrame frame, long expiresAtMs) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /** Replaceable clock for testing (returns epoch-millis). */
    public LongSupplier clock = System::currentTimeMillis;

    // ── Anchor ID computation ─────────────────────────────────────────────────

    public static String computeAnchorId(Map<String, Object> schema) {
        try {
            // Sort fields by name for deterministic hashing
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields =
                new ArrayList<>((List<Map<String, Object>>) schema.get("fields"));
            fields.sort(Comparator.comparing(f -> (String) f.get("name")));

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) sb.append(",");
                Map<String, Object> f = fields.get(i);
                sb.append("{\"name\":\"").append(f.get("name"))
                  .append("\",\"type\":\"").append(f.get("type")).append("\"}");
            }
            sb.append("]");

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── Cache operations ──────────────────────────────────────────────────────

    public void set(AnchorFrame frame) {
        long now      = clock.getAsLong();
        long expiresAt = now + (long) frame.ttl() * 1000L;

        store.compute(frame.anchorId(), (id, existing) -> {
            if (existing != null && now <= existing.expiresAtMs()) {
                // Check for poison: same id, different schema
                if (!dictEqual(existing.frame().schema(), frame.schema())) {
                    throw new NpsAnchorPoisonError(id);
                }
                return existing; // idempotent
            }
            return new Entry(frame, expiresAt);
        });
    }

    public AnchorFrame get(String anchorId) {
        Entry entry = store.get(anchorId);
        if (entry == null) return null;
        if (clock.getAsLong() > entry.expiresAtMs()) {
            store.remove(anchorId);
            return null;
        }
        return entry.frame();
    }

    public AnchorFrame getRequired(String anchorId) {
        AnchorFrame frame = get(anchorId);
        if (frame == null) throw new NpsAnchorNotFoundError(anchorId);
        return frame;
    }

    public void invalidate(String anchorId) {
        store.remove(anchorId);
    }

    /** Returns count of non-expired entries, evicting expired ones as a side-effect. */
    public int size() {
        long now = clock.getAsLong();
        store.entrySet().removeIf(e -> now > e.getValue().expiresAtMs());
        return store.size();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean dictEqual(Map<String, Object> a, Map<String, Object> b) {
        return a.equals(b);
    }
}
