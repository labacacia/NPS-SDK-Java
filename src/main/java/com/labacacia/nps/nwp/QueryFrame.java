// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QueryFrame implements NpsFrame {

    private final String             anchorRef;    // nullable
    private final Map<String,Object> filter;       // nullable
    private final Integer            limit;        // nullable
    private final String             cursor;       // nullable (spec §5.2: opaque pagination cursor)
    private final List<Map<String,Object>> order;  // nullable (serialization key: "order")
    private final List<String>       fields;       // nullable
    /**
     * Vector-search options map. Expected keys per spec §5.4:
     * {@code top_k} (int), {@code threshold} (float), {@code vector} (list of floats).
     * Use {@code "top_k"} not {@code "k"}.
     */
    private final Map<String,Object> vectorSearch; // nullable
    private final Integer            depth;        // nullable

    public QueryFrame(String anchorRef, Map<String,Object> filter,
                      Integer limit, String cursor,
                      List<Map<String,Object>> order, List<String> fields,
                      Map<String,Object> vectorSearch, Integer depth) {
        this.anchorRef    = anchorRef;
        this.filter       = filter;
        this.limit        = limit;
        this.cursor       = cursor;
        this.order        = order;
        this.fields       = fields;
        this.vectorSearch = vectorSearch;
        this.depth        = depth;
    }

    public QueryFrame() { this(null, null, null, null, null, null, null, null); }

    @Override public FrameType    frameType()    { return FrameType.QUERY; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String anchorRef()              { return anchorRef; }
    public Map<String,Object> filter()     { return filter; }
    public Integer limit()                 { return limit; }
    public String cursor()                 { return cursor; }
    public List<Map<String,Object>> order() { return order; }
    public List<String> fields()           { return fields; }
    public Map<String,Object> vectorSearch() { return vectorSearch; }
    public Integer depth()                 { return depth; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchor_ref",    anchorRef);
        m.put("filter",        filter);
        m.put("limit",         limit);
        m.put("cursor",        cursor);
        m.put("order",         order);
        m.put("fields",        fields);
        m.put("vector_search", vectorSearch);
        m.put("depth",         depth);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static QueryFrame fromDict(Map<String, Object> d) {
        Object lim = d.get("limit"), dep = d.get("depth");
        return new QueryFrame(
            (String) d.get("anchor_ref"),
            (Map<String,Object>) d.get("filter"),
            lim instanceof Number n ? n.intValue() : null,
            (String) d.get("cursor"),
            (List<Map<String,Object>>) d.get("order"),
            (List<String>) d.get("fields"),
            (Map<String,Object>) d.get("vector_search"),
            dep instanceof Number n ? n.intValue() : null
        );
    }
}
