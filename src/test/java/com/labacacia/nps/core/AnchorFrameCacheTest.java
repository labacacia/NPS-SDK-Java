// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

import com.labacacia.nps.core.cache.AnchorFrameCache;
import com.labacacia.nps.core.exception.NpsAnchorNotFoundError;
import com.labacacia.nps.core.exception.NpsAnchorPoisonError;
import com.labacacia.nps.ncp.AnchorFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnchorFrameCacheTest {

    private static Map<String, Object> schema(String... nameType) {
        // nameType: ["name","uint64","price","decimal",...]
        var fields = new java.util.ArrayList<Map<String,Object>>();
        for (int i = 0; i < nameType.length; i += 2) {
            fields.add(Map.of("name", nameType[i], "type", nameType[i+1]));
        }
        return Map.of("fields", fields);
    }

    @Test void computeAnchorIdIsDeterministic() {
        var s = schema("id", "uint64");
        assertEquals(AnchorFrameCache.computeAnchorId(s), AnchorFrameCache.computeAnchorId(s));
        assertTrue(AnchorFrameCache.computeAnchorId(s).matches("sha256:[0-9a-f]{64}"));
    }

    @Test void computeAnchorIdIsFieldOrderIndependent() {
        var s1 = schema("a", "string", "b", "uint64");
        var s2 = schema("b", "uint64", "a", "string");
        assertEquals(AnchorFrameCache.computeAnchorId(s1), AnchorFrameCache.computeAnchorId(s2));
    }

    @Test void setAndGetRoundtrip() {
        var cache  = new AnchorFrameCache();
        var s      = schema("id", "uint64");
        var aid    = AnchorFrameCache.computeAnchorId(s);
        var frame  = new AnchorFrame(aid, s, 3600);
        cache.set(frame);
        assertSame(frame, cache.get(aid));
    }

    @Test void getRequiredReturnsFrame() {
        var cache = new AnchorFrameCache();
        var s     = schema("id", "uint64");
        var aid   = AnchorFrameCache.computeAnchorId(s);
        var frame = new AnchorFrame(aid, s, 3600);
        cache.set(frame);
        assertSame(frame, cache.getRequired(aid));
    }

    @Test void getRequiredThrowsWhenMissing() {
        var cache = new AnchorFrameCache();
        assertThrows(NpsAnchorNotFoundError.class,
            () -> cache.getRequired("sha256:" + "0".repeat(64)));
    }

    @Test void getReturnsNullAfterTtlExpiry() {
        var cache = new AnchorFrameCache();
        long[] now = {0};
        cache.clock = () -> now[0];
        var s   = schema("id", "uint64");
        var aid = AnchorFrameCache.computeAnchorId(s);
        cache.set(new AnchorFrame(aid, s, 10));
        now[0] = 11_000;
        assertNull(cache.get(aid));
    }

    @Test void idempotentSetWithSameSchema() {
        var cache = new AnchorFrameCache();
        var s     = schema("id", "uint64");
        var aid   = AnchorFrameCache.computeAnchorId(s);
        var frame = new AnchorFrame(aid, s, 3600);
        cache.set(frame);
        cache.set(frame);
        assertEquals(1, cache.size());
    }

    @Test void poisonDetectionRaisesError() {
        var cache  = new AnchorFrameCache();
        var schemaA = schema("id", "uint64");
        var schemaB = schema("price", "decimal");
        var aid     = AnchorFrameCache.computeAnchorId(schemaA);
        cache.set(new AnchorFrame(aid, schemaA, 3600));
        assertThrows(NpsAnchorPoisonError.class,
            () -> cache.set(new AnchorFrame(aid, schemaB, 3600)));
    }

    @Test void invalidateRemovesEntry() {
        var cache = new AnchorFrameCache();
        var s     = schema("id", "uint64");
        var aid   = AnchorFrameCache.computeAnchorId(s);
        cache.set(new AnchorFrame(aid, s, 3600));
        cache.invalidate(aid);
        assertNull(cache.get(aid));
    }

    @Test void sizeEvictsExpiredEntries() {
        var cache = new AnchorFrameCache();
        long[] now = {0};
        cache.clock = () -> now[0];
        var s1  = schema("id", "uint64");
        var s2  = schema("x", "string");
        cache.set(new AnchorFrame(AnchorFrameCache.computeAnchorId(s1), s1, 100));
        cache.set(new AnchorFrame(AnchorFrameCache.computeAnchorId(s2), s2, 1));
        now[0] = 2_000;
        assertEquals(1, cache.size());
    }

    @Test void anchorNotFoundErrorCarriesId() {
        var err = new NpsAnchorNotFoundError("sha256:abc");
        assertEquals("sha256:abc", err.getAnchorId());
    }

    @Test void anchorPoisonErrorCarriesId() {
        var err = new NpsAnchorPoisonError("sha256:abc");
        assertEquals("sha256:abc", err.getAnchorId());
    }
}
