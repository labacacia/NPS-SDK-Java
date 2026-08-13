// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/** Authenticated NID and deployment security scope that own a context. */
public record LlmContextOwner(String nid, String securityScope) {}
