// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/** A node left the cluster. Wire {@code event_type}: {@code member_left}. */
public final class MemberLeftEvent extends TopologyEvent {

    public String nid;

    public MemberLeftEvent() {}

    public MemberLeftEvent(long version, String nid) {
        super(version);
        this.nid = nid;
    }
}
