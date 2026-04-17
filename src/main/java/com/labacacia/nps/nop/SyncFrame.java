// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SyncFrame implements NpsFrame {

    private final String       taskId;
    private final String       syncId;
    private final List<String> waitFor;
    private final int          minRequired;
    private final String       aggregate;
    private final Integer      timeoutMs; // nullable

    public SyncFrame(String taskId, String syncId, List<String> waitFor,
                     int minRequired, String aggregate, Integer timeoutMs) {
        this.taskId      = taskId;
        this.syncId      = syncId;
        this.waitFor     = waitFor;
        this.minRequired = minRequired;
        this.aggregate   = aggregate;
        this.timeoutMs   = timeoutMs;
    }

    public SyncFrame(String taskId, String syncId, List<String> waitFor) {
        this(taskId, syncId, waitFor, 0, "merge", null);
    }

    @Override public FrameType    frameType()    { return FrameType.SYNC; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String taskId()       { return taskId; }
    public String syncId()       { return syncId; }
    public List<String> waitFor(){ return waitFor; }
    public int    minRequired()  { return minRequired; }
    public String aggregate()    { return aggregate; }
    public Integer timeoutMs()   { return timeoutMs; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id",      taskId);
        m.put("sync_id",      syncId);
        m.put("wait_for",     waitFor);
        m.put("min_required", minRequired);
        m.put("aggregate",    aggregate);
        m.put("timeout_ms",   timeoutMs);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static SyncFrame fromDict(Map<String, Object> d) {
        Object tm = d.get("timeout_ms"), mr = d.get("min_required");
        String agg = d.get("aggregate") != null ? (String) d.get("aggregate") : "merge";
        return new SyncFrame(
            (String) d.get("task_id"),
            (String) d.get("sync_id"),
            (List<String>) d.get("wait_for"),
            mr instanceof Number n ? n.intValue() : 0,
            agg,
            tm instanceof Number n ? n.intValue() : null
        );
    }
}
