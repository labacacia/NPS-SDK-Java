// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.reputation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Observation time window for a reputation log entry (NPS-RFC-0004).
 * Both {@code start} and {@code end} are ISO-8601 timestamps.
 */
public final class ObservationWindow {

    public final String start;
    public final String end;

    public ObservationWindow(String start, String end) {
        this.start = start;
        this.end   = end;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start", start);
        m.put("end",   end);
        return m;
    }

    public static ObservationWindow fromMap(Map<String, Object> m) {
        return new ObservationWindow(
            (String) m.get("start"),
            (String) m.get("end")
        );
    }
}
