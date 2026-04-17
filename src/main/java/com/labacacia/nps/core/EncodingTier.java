// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

/** NPS wire-encoding tiers. */
public enum EncodingTier {
    /** Tier-1: UTF-8 JSON (human-readable, debugging / interop). */
    JSON,
    /** Tier-2: MsgPack (production default, ~60% size reduction). */
    MSGPACK;
}
