// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StreamFrame implements NpsFrame {

    private final String                     streamId;
    private final int                        seq;
    private final boolean                    isLast;
    private final List<Map<String, Object>>  data;
    private final String                     anchorRef;  // nullable
    private final Integer                    windowSize; // nullable

    public StreamFrame(String streamId, int seq, boolean isLast,
                       List<Map<String, Object>> data, String anchorRef, Integer windowSize) {
        this.streamId   = streamId;
        this.seq        = seq;
        this.isLast     = isLast;
        this.data       = data;
        this.anchorRef  = anchorRef;
        this.windowSize = windowSize;
    }

    @Override public FrameType    frameType()    { return FrameType.STREAM; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String streamId()   { return streamId; }
    public int    seq()        { return seq; }
    public boolean isLast()   { return isLast; }
    public List<Map<String, Object>> data() { return data; }
    public String anchorRef()  { return anchorRef; }
    public Integer windowSize() { return windowSize; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stream_id",   streamId);
        m.put("seq",         seq);
        m.put("is_last",     isLast);
        m.put("data",        data);
        m.put("anchor_ref",  anchorRef);
        m.put("window_size", windowSize);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static StreamFrame fromDict(Map<String, Object> d) {
        Object ws = d.get("window_size");
        return new StreamFrame(
            (String) d.get("stream_id"),
            ((Number) d.get("seq")).intValue(),
            (Boolean) d.get("is_last"),
            (List<Map<String, Object>>) d.get("data"),
            (String) d.get("anchor_ref"),
            ws instanceof Number n ? n.intValue() : null
        );
    }
}
