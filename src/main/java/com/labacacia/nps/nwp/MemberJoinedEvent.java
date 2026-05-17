// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/** A new node joined the cluster. Wire {@code event_type}: {@code member_joined}. */
public final class MemberJoinedEvent extends TopologyEvent {

    public MemberInfo member;

    public MemberJoinedEvent() {}

    public MemberJoinedEvent(long version, MemberInfo member) {
        super(version);
        this.member = member;
    }
}
