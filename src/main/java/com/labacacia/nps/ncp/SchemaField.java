// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import java.util.LinkedHashMap;
import java.util.Map;

public record SchemaField(String name, String type, String semantic, Boolean nullable) {

    public SchemaField(String name, String type) { this(name, type, null, null); }

    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        if (semantic != null) m.put("semantic", semantic);
        if (nullable != null) m.put("nullable", nullable);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static SchemaField fromDict(Map<String, Object> d) {
        return new SchemaField(
            (String) d.get("name"),
            (String) d.get("type"),
            (String) d.get("semantic"),
            (Boolean) d.get("nullable")
        );
    }
}
