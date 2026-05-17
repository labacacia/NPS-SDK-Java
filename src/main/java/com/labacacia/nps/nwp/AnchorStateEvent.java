// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Anchor internal-state change. Wire {@code event_type}: {@code anchor_state}.
 * Example field tag: {@code "version_rebased"} (NPS-2 §12.3 restart-and-rebase).
 */
public final class AnchorStateEvent extends TopologyEvent {

    public String field;
    public JsonNode details;

    public AnchorStateEvent() {}

    public AnchorStateEvent(long version, String field, JsonNode details) {
        super(version);
        this.field   = field;
        this.details = details;
    }
}
