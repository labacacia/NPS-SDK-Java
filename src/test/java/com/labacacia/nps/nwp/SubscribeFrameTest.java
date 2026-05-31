// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubscribeFrameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 1. Construction with subscriptionId only
    @Test
    void constructWithSubscriptionIdOnly() {
        SubscribeFrame f = new SubscribeFrame("sub-abc");
        assertEquals("sub-abc", f.subscriptionId);
        assertNull(f.filter);
        assertNull(f.heartbeatIntervalMs);
        assertNull(f.maxEvents);
        assertNull(f.cursor);
    }

    // 2. toDict() only includes non-null fields
    @Test
    void toDictOmitsNullFields() {
        SubscribeFrame f = new SubscribeFrame("sub-abc");
        Map<String, Object> d = f.toDict();
        assertEquals(1, d.size());
        assertEquals("sub-abc", d.get("subscription_id"));
        assertFalse(d.containsKey("filter"));
        assertFalse(d.containsKey("heartbeat_interval_ms"));
        assertFalse(d.containsKey("max_events"));
        assertFalse(d.containsKey("cursor"));
    }

    @Test
    void toDictIncludesAllPresentFields() {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("kind", "sensor");
        SubscribeFrame f = new SubscribeFrame("sub-xyz", filter, 5000, 100, "cursor-tok");
        Map<String, Object> d = f.toDict();
        assertEquals(5, d.size());
        assertEquals("sub-xyz", d.get("subscription_id"));
        assertEquals(filter,     d.get("filter"));
        assertEquals(5000,       d.get("heartbeat_interval_ms"));
        assertEquals(100,        d.get("max_events"));
        assertEquals("cursor-tok", d.get("cursor"));
    }

    // 3. fromDict() round-trip preserving all fields
    @Test
    void fromDictRoundTrip() {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("type", "event");
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("subscription_id",      "sub-rt");
        src.put("filter",               filter);
        src.put("heartbeat_interval_ms", 3000);
        src.put("max_events",            50);
        src.put("cursor",               "cur-1");

        SubscribeFrame f = SubscribeFrame.fromDict(src);
        assertEquals("sub-rt",  f.subscriptionId);
        assertEquals(filter,    f.filter);
        assertEquals(3000,      f.heartbeatIntervalMs);
        assertEquals(50,        f.maxEvents);
        assertEquals("cur-1",   f.cursor);
    }

    @Test
    void fromDictWithRequiredFieldOnly() {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("subscription_id", "sub-min");
        SubscribeFrame f = SubscribeFrame.fromDict(src);
        assertEquals("sub-min", f.subscriptionId);
        assertNull(f.filter);
        assertNull(f.heartbeatIntervalMs);
        assertNull(f.maxEvents);
        assertNull(f.cursor);
    }

    // 4. Jackson round-trip
    @Test
    void jacksonRoundTrip() throws Exception {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("ns", "metrics");
        SubscribeFrame original = new SubscribeFrame("sub-j", filter, 1000, 200, "ck-abc");

        String json = MAPPER.writeValueAsString(original);
        SubscribeFrame restored = MAPPER.readValue(json, SubscribeFrame.class);

        assertEquals(original.subscriptionId,      restored.subscriptionId);
        assertEquals(original.filter,              restored.filter);
        assertEquals(original.heartbeatIntervalMs, restored.heartbeatIntervalMs);
        assertEquals(original.maxEvents,           restored.maxEvents);
        assertEquals(original.cursor,              restored.cursor);
    }

    @Test
    void jacksonOmitsNullsInOutput() throws Exception {
        SubscribeFrame f = new SubscribeFrame("sub-null-test");
        String json = MAPPER.writeValueAsString(f);
        assertFalse(json.contains("filter"));
        assertFalse(json.contains("heartbeat_interval_ms"));
        assertFalse(json.contains("max_events"));
        assertFalse(json.contains("cursor"));
        assertTrue(json.contains("sub-null-test"));
    }
}
