// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.cache;

import com.labacacia.nps.core.exception.NpsAnchorNotFoundError;
import com.labacacia.nps.core.exception.NpsAnchorPoisonError;
import com.labacacia.nps.ncp.AnchorFrame;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /**
     * Computes the canonical {@code anchor_id} for a structured FrameSchema, matching the .NET
     * reference SDK's {@code AnchorIdComputer} (NPS-1 §4.1) byte-for-byte.
     *
     * <p>Canonical JSON is RFC 8785 JCS over the STRUCTURED schema:
     * {@code {"fields":[{"name":..,"nullable":true|false,"semantic":..(omitted if null),"type":..}, ...]}}.
     * Per-field JCS key order is exactly {@code name < nullable < semantic < type} (ASCII byte
     * order). Field order is preserved as given (NOT sorted). {@code semantic} is omitted when
     * null/absent; {@code nullable} defaults to {@code false} when absent. anchor_id =
     * {@code "sha256:" + lower-hex(sha256(utf8(canonical)))}.
     */
    public static String computeAnchorId(Map<String, Object> schema) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields =
                (List<Map<String, Object>>) schema.get("fields");
            if (fields == null) fields = List.of();

            StringBuilder sb = new StringBuilder("{\"fields\":[");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) sb.append(',');
                Map<String, Object> f = fields.get(i);
                sb.append('{');

                sb.append("\"name\":");
                appendJcsString(sb, String.valueOf(f.get("name")));

                Object nullableRaw = f.get("nullable");
                boolean nullable = nullableRaw instanceof Boolean b && b;
                sb.append(",\"nullable\":").append(nullable ? "true" : "false");

                Object semantic = f.get("semantic");
                if (semantic != null) {
                    sb.append(",\"semantic\":");
                    appendJcsString(sb, semantic.toString());
                }

                sb.append(",\"type\":");
                appendJcsString(sb, String.valueOf(f.get("type")));

                sb.append('}');
            }
            sb.append("]}");

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Appends a minimally-escaped JSON string literal (RFC 8259), no HTML escaping. */
    private static void appendJcsString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
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
