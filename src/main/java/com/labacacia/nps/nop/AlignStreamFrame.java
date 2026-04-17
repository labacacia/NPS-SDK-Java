// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AlignStreamFrame implements NpsFrame {

    private final String             streamId;
    private final String             taskId;
    private final String             subtaskId;
    private final int                seq;
    private final boolean            isFinal;
    private final String             senderNid;
    private final Map<String,Object> data;       // nullable
    private final Map<String,Object> error;      // nullable {"error_code", "message"}
    private final Integer            windowSize; // nullable

    public AlignStreamFrame(String streamId, String taskId, String subtaskId,
                            int seq, boolean isFinal, String senderNid,
                            Map<String,Object> data, Map<String,Object> error,
                            Integer windowSize) {
        this.streamId   = streamId;
        this.taskId     = taskId;
        this.subtaskId  = subtaskId;
        this.seq        = seq;
        this.isFinal    = isFinal;
        this.senderNid  = senderNid;
        this.data       = data;
        this.error      = error;
        this.windowSize = windowSize;
    }

    @Override public FrameType    frameType()    { return FrameType.ALIGN_STREAM; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String streamId()   { return streamId; }
    public String taskId()     { return taskId; }
    public String subtaskId()  { return subtaskId; }
    public int    seq()        { return seq; }
    public boolean isFinal()  { return isFinal; }
    public String senderNid()  { return senderNid; }
    public Map<String,Object> data()  { return data; }
    public Map<String,Object> error() { return error; }
    public Integer windowSize() { return windowSize; }

    /** Convenience: error code from error map. */
    public String errorCode() {
        return error != null ? (String) error.get("error_code") : null;
    }

    /** Convenience: error message from error map. */
    public String errorMessage() {
        return error != null ? (String) error.get("message") : null;
    }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stream_id",   streamId);
        m.put("task_id",     taskId);
        m.put("subtask_id",  subtaskId);
        m.put("seq",         seq);
        m.put("is_final",    isFinal);
        m.put("sender_nid",  senderNid);
        m.put("data",        data);
        m.put("error",       error);
        m.put("window_size", windowSize);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static AlignStreamFrame fromDict(Map<String, Object> d) {
        Object ws = d.get("window_size");
        return new AlignStreamFrame(
            (String) d.get("stream_id"),
            (String) d.get("task_id"),
            (String) d.get("subtask_id"),
            ((Number) d.get("seq")).intValue(),
            (Boolean) d.get("is_final"),
            (String) d.get("sender_nid"),
            (Map<String,Object>) d.get("data"),
            (Map<String,Object>) d.get("error"),
            ws instanceof Number n ? n.intValue() : null
        );
    }
}
