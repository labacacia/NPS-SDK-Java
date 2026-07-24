// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.models;

/**
 * Task priority levels (NPS-5 §3.1).
 */
public final class TaskPriority {
    private TaskPriority() {}

    public static final String LOW = "low";
    public static final String NORMAL = "normal";
    public static final String HIGH = "high";
}
