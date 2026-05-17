// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/** A node's metadata changed. Wire {@code event_type}: {@code member_updated}. */
public final class MemberUpdatedEvent extends TopologyEvent {

    public String nid;
    public MemberChanges changes;

    public MemberUpdatedEvent() {}

    public MemberUpdatedEvent(long version, String nid, MemberChanges changes) {
        super(version);
        this.nid     = nid;
        this.changes = changes;
    }
}
