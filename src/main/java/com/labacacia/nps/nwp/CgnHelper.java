// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0

package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;

/** CGN (Cognon) estimation helpers per token-budget.md §2.2. */
public final class CgnHelper {

    private static final int BYTES_PER_CGN = 4;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CgnHelper() {}

    /** CGN = ceil(UTF-8 bytes / 4). Returns 0 for null or empty. */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return (bytes + BYTES_PER_CGN - 1) / BYTES_PER_CGN;
    }

    /** CGN estimate for a raw byte array. */
    public static int estimateBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return 0;
        return (bytes.length + BYTES_PER_CGN - 1) / BYTES_PER_CGN;
    }

    /** Serialize obj as compact JSON then estimate CGN. */
    public static int estimateJson(Object obj) throws Exception {
        return estimateBytes(MAPPER.writeValueAsBytes(obj));
    }

    /** Sum CGN estimates for a list of JSON-serializable objects. */
    public static int estimateRows(java.util.List<?> rows) throws Exception {
        int total = 0;
        for (Object r : rows) total += estimateJson(r);
        return total;
    }
}
