// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CapsFrame implements NpsFrame {

    private final String                    anchorRef;
    private final int                       count;
    private final List<Map<String, Object>> data;
    private final String                    nextCursor;    // nullable
    private final Integer                   tokenEst;      // nullable
    private final Boolean                   cached;        // nullable
    private final String                    tokenizerUsed; // nullable

    public CapsFrame(String anchorRef, int count, List<Map<String, Object>> data,
                     String nextCursor, Integer tokenEst, Boolean cached, String tokenizerUsed) {
        this.anchorRef     = anchorRef;
        this.count         = count;
        this.data          = data;
        this.nextCursor    = nextCursor;
        this.tokenEst      = tokenEst;
        this.cached        = cached;
        this.tokenizerUsed = tokenizerUsed;
    }

    public CapsFrame(String anchorRef, int count, List<Map<String, Object>> data) {
        this(anchorRef, count, data, null, null, null, null);
    }

    @Override public FrameType    frameType()    { return FrameType.CAPS; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String anchorRef()     { return anchorRef; }
    public int    count()         { return count; }
    public List<Map<String, Object>> data() { return data; }
    public String nextCursor()    { return nextCursor; }
    public Integer tokenEst()     { return tokenEst; }
    public Boolean cached()       { return cached; }
    public String tokenizerUsed() { return tokenizerUsed; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchor_ref",     anchorRef);
        m.put("count",          count);
        m.put("data",           data);
        m.put("next_cursor",    nextCursor);
        m.put("token_est",      tokenEst);
        m.put("cached",         cached);
        m.put("tokenizer_used", tokenizerUsed);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static CapsFrame fromDict(Map<String, Object> d) {
        Object te = d.get("token_est");
        return new CapsFrame(
            (String) d.get("anchor_ref"),
            ((Number) d.get("count")).intValue(),
            (List<Map<String, Object>>) d.get("data"),
            (String) d.get("next_cursor"),
            te instanceof Number n ? n.intValue() : null,
            (Boolean) d.get("cached"),
            (String) d.get("tokenizer_used")
        );
    }
}
