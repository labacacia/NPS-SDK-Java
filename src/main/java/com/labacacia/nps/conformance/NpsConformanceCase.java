// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.conformance;

public record NpsConformanceCase(
    String id,
    String profile,
    String requirement,
    String title,
    boolean optional
) {}
