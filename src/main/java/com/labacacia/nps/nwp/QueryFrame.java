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
    private final Integer            offset;       // nullable
    private final List<Map<String,Object>> orderBy; // nullable
    private final List<String>       fields;       // nullable
    private final Map<String,Object> vectorSearch; // nullable
    private final Integer            depth;        // nullable

    public QueryFrame(String anchorRef, Map<String,Object> filter,
                      Integer limit, Integer offset,
                      List<Map<String,Object>> orderBy, List<String> fields,
                      Map<String,Object> vectorSearch, Integer depth) {
        this.anchorRef    = anchorRef;
        this.filter       = filter;
        this.limit        = limit;
        this.offset       = offset;
        this.orderBy      = orderBy;
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
    public Integer offset()                { return offset; }
    public List<Map<String,Object>> orderBy() { return orderBy; }
    public List<String> fields()           { return fields; }
    public Map<String,Object> vectorSearch() { return vectorSearch; }
    public Integer depth()                 { return depth; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchor_ref",    anchorRef);
        m.put("filter",        filter);
        m.put("limit",         limit);
        m.put("offset",        offset);
        m.put("order_by",      orderBy);
        m.put("fields",        fields);
        m.put("vector_search", vectorSearch);
        m.put("depth",         depth);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static QueryFrame fromDict(Map<String, Object> d) {
        Object lim = d.get("limit"), off = d.get("offset"), dep = d.get("depth");
        return new QueryFrame(
            (String) d.get("anchor_ref"),
            (Map<String,Object>) d.get("filter"),
            lim instanceof Number n ? n.intValue() : null,
            off instanceof Number n ? n.intValue() : null,
            (List<Map<String,Object>>) d.get("order_by"),
            (List<String>) d.get("fields"),
            (Map<String,Object>) d.get("vector_search"),
            dep instanceof Number n ? n.intValue() : null
        );
    }
}
