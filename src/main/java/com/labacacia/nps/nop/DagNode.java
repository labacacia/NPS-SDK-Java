// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a single node in a NOP DAG (the value type for entries in
 * {@link TaskFrame#dag()}).
 *
 * <p>Saga compensation fields ({@code compensateAction} and
 * {@code compensateParamsMapping}) are optional and were added in alpha.9.
 */
public final class DagNode {

    private final String             action;
    private final List<String>       depends;             // nullable
    private final Map<String,Object> paramsMapping;       // nullable
    private final Integer            retries;             // nullable
    private final String             compensateAction;    // nullable
    private final Map<String,Object> compensateParamsMapping; // nullable

    public DagNode(String action, List<String> depends, Map<String,Object> paramsMapping,
                   Integer retries, String compensateAction,
                   Map<String,Object> compensateParamsMapping) {
        this.action                  = action;
        this.depends                 = depends;
        this.paramsMapping           = paramsMapping;
        this.retries                 = retries;
        this.compensateAction        = compensateAction;
        this.compensateParamsMapping = compensateParamsMapping;
    }

    public DagNode(String action) {
        this(action, null, null, null, null, null);
    }

    public String action()                            { return action; }
    public List<String> depends()                     { return depends; }
    public Map<String,Object> paramsMapping()         { return paramsMapping; }
    public Integer retries()                          { return retries; }
    public String compensateAction()                  { return compensateAction; }
    public Map<String,Object> compensateParamsMapping() { return compensateParamsMapping; }

    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action",                    action);
        m.put("depends",                   depends);
        m.put("params_mapping",            paramsMapping);
        m.put("retries",                   retries);
        m.put("compensate_action",         compensateAction);
        m.put("compensate_params_mapping", compensateParamsMapping);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static DagNode fromDict(Map<String, Object> d) {
        Object ret = d.get("retries");
        return new DagNode(
            (String) d.get("action"),
            (List<String>) d.get("depends"),
            (Map<String,Object>) d.get("params_mapping"),
            ret instanceof Number n ? n.intValue() : null,
            (String) d.get("compensate_action"),
            (Map<String,Object>) d.get("compensate_params_mapping")
        );
    }
}
