// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Thread-safe in-memory NDP node registry with TTL eviction. */
public final class InMemoryNdpRegistry {

    private record Entry(AnnounceFrame frame, long expiresAtMs) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /** Replaceable clock for testing (epoch-millis). */
    public LongSupplier clock = System::currentTimeMillis;

    public void announce(AnnounceFrame frame) {
        if (frame.ttl() == 0) {
            store.remove(frame.nid());
            return;
        }
        long expiresAt = clock.getAsLong() + (long) frame.ttl() * 1000L;
        store.put(frame.nid(), new Entry(frame, expiresAt));
    }

    public AnnounceFrame getByNid(String nid) {
        Entry entry = store.get(nid);
        if (entry == null) return null;
        if (clock.getAsLong() > entry.expiresAtMs()) {
            store.remove(nid);
            return null;
        }
        return entry.frame();
    }

    public record ResolveResult(String host, int port, int ttl) {}

    public ResolveResult resolve(String target) {
        long now = clock.getAsLong();
        for (var it = store.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            if (now > e.getValue().expiresAtMs()) { it.remove(); continue; }
            AnnounceFrame f = e.getValue().frame();
            if (!nwpTargetMatchesNid(f.nid(), target)) continue;
            if (f.addresses().isEmpty()) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> addr = f.addresses().get(0);
            return new ResolveResult(
                (String) addr.get("host"),
                ((Number) addr.get("port")).intValue(),
                f.ttl()
            );
        }
        return null;
    }

    public List<AnnounceFrame> getAll() {
        long now = clock.getAsLong();
        List<AnnounceFrame> result = new ArrayList<>();
        for (var it = store.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            if (now > e.getValue().expiresAtMs()) { it.remove(); continue; }
            result.add(e.getValue().frame());
        }
        return result;
    }

    // ── NID ↔ target matching ─────────────────────────────────────────────────

    /** NID: {@code urn:nps:node:{authority}:{path}} · target: {@code nwp://{authority}/{path}} */
    public static boolean nwpTargetMatchesNid(String nid, String target) {
        String[] parts = nid.split(":");
        if (parts.length < 5) return false;
        if (!"urn".equals(parts[0]) || !"nps".equals(parts[1]) || !"node".equals(parts[2])) return false;
        if (!target.startsWith("nwp://")) return false;

        String nidAuthority = parts[3];
        String nidPath      = parts[4];
        String rest         = target.substring("nwp://".length());
        int slash           = rest.indexOf('/');
        if (slash == -1) return false;

        String urlAuthority = rest.substring(0, slash);
        String urlPath      = rest.substring(slash + 1);

        if (!urlAuthority.equals(nidAuthority)) return false;
        return urlPath.equals(nidPath) || urlPath.startsWith(nidPath + "/");
    }
}
