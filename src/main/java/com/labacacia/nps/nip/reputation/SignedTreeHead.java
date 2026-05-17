// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.reputation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signed Tree Head (STH) for an NPS-RFC-0004 reputation log.
 * Returned by {@code GET /v1/log/sth} and {@code GET /v1/log/gossip/sth}.
 */
public final class SignedTreeHead {

    private final long   treeSize;
    private final String timestamp;
    private final String sha256RootHash;
    private final String logId;
    private final String signature;

    public SignedTreeHead(long treeSize, String timestamp, String sha256RootHash,
                          String logId, String signature) {
        this.treeSize       = treeSize;
        this.timestamp      = timestamp;
        this.sha256RootHash = sha256RootHash;
        this.logId          = logId;
        this.signature      = signature;
    }

    public long   getTreeSize()       { return treeSize; }
    public String getTimestamp()      { return timestamp; }
    public String getSha256RootHash() { return sha256RootHash; }
    public String getLogId()          { return logId; }
    public String getSignature()      { return signature; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tree_size",        treeSize);
        m.put("timestamp",        timestamp);
        m.put("sha256_root_hash", sha256RootHash);
        m.put("log_id",           logId);
        if (signature != null) m.put("signature", signature);
        return m;
    }

    public static SignedTreeHead fromMap(Map<String, Object> m) {
        return new SignedTreeHead(
            toLong(m.get("tree_size")),
            (String) m.get("timestamp"),
            (String) m.get("sha256_root_hash"),
            (String) m.get("log_id"),
            (String) m.get("signature")
        );
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) return Long.parseLong((String) o);
        return 0L;
    }
}
