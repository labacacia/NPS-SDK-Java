// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.exception;

public class NpsAnchorPoisonError extends NpsError {
    private final String anchorId;

    public NpsAnchorPoisonError(String anchorId) {
        super("Anchor poison detected for: " + anchorId);
        this.anchorId = anchorId;
    }

    public String getAnchorId() { return anchorId; }
}
