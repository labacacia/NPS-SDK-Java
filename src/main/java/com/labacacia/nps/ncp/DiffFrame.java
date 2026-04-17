// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DiffFrame implements NpsFrame {

    private final String                     anchorRef;
    private final int                        baseSeq;
    private final List<Map<String, Object>>  patch;
    private final String                     entityId; // nullable

    public DiffFrame(String anchorRef, int baseSeq,
                     List<Map<String, Object>> patch, String entityId) {
        this.anchorRef = anchorRef;
        this.baseSeq   = baseSeq;
        this.patch     = patch;
        this.entityId  = entityId;
    }

    @Override public FrameType    frameType()    { return FrameType.DIFF; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String anchorRef() { return anchorRef; }
    public int    baseSeq()   { return baseSeq; }
    public List<Map<String, Object>> patch() { return patch; }
    public String entityId()  { return entityId; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchor_ref", anchorRef);
        m.put("base_seq",   baseSeq);
        m.put("patch",      patch);
        m.put("entity_id",  entityId);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static DiffFrame fromDict(Map<String, Object> d) {
        return new DiffFrame(
            (String) d.get("anchor_ref"),
            ((Number) d.get("base_seq")).intValue(),
            (List<Map<String, Object>>) d.get("patch"),
            (String) d.get("entity_id")
        );
    }
}
