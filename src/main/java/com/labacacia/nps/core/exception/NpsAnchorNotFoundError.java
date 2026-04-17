// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.exception;

public class NpsAnchorNotFoundError extends NpsError {
    private final String anchorId;

    public NpsAnchorNotFoundError(String anchorId) {
        super("Anchor not found: " + anchorId);
        this.anchorId = anchorId;
    }

    public String getAnchorId() { return anchorId; }
}
