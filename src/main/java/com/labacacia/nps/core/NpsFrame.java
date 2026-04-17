// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

import java.util.Map;

/** Marker interface for all NPS frames. */
public interface NpsFrame {
    FrameType    frameType();
    EncodingTier preferredTier();
    Map<String, Object> toDict();
}
