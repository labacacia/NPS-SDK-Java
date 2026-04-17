// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.util.Map;

public record AsyncActionResponse(String taskId, String status, String pollUrl) {

    public static AsyncActionResponse fromDict(Map<String, Object> d) {
        return new AsyncActionResponse(
            (String) d.get("task_id"),
            (String) d.get("status"),
            (String) d.get("poll_url")
        );
    }
}
