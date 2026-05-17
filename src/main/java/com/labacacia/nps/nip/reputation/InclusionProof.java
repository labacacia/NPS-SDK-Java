// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.reputation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merkle inclusion proof for a single reputation log entry (NPS-RFC-0004).
 * Returned by {@code GET /v1/log/proof?seq=<seq>}.
 */
public final class InclusionProof {

    private final long         leafIndex;
    private final long         treeSize;
    private final String       leafHash;
    private final List<String> auditPath;

    public InclusionProof(long leafIndex, long treeSize, String leafHash, List<String> auditPath) {
        this.leafIndex = leafIndex;
        this.treeSize  = treeSize;
        this.leafHash  = leafHash;
        this.auditPath = auditPath == null ? List.of() : List.copyOf(auditPath);
    }

    public long         getLeafIndex() { return leafIndex; }
    public long         getTreeSize()  { return treeSize; }
    public String       getLeafHash()  { return leafHash; }
    public List<String> getAuditPath() { return auditPath; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("leaf_index", leafIndex);
        m.put("tree_size",  treeSize);
        m.put("leaf_hash",  leafHash);
        m.put("audit_path", new ArrayList<>(auditPath));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static InclusionProof fromMap(Map<String, Object> m) {
        List<String> path = new ArrayList<>();
        Object raw = m.get("audit_path");
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                path.add((String) item);
            }
        }
        return new InclusionProof(
            toLong(m.get("leaf_index")),
            toLong(m.get("tree_size")),
            (String) m.get("leaf_hash"),
            path
        );
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) return Long.parseLong((String) o);
        return 0L;
    }
}
