// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.ncp.CapsFrame;

/**
 * Stateless Bridge Node dispatcher facade (NPS-2 §2A, NPS-CR-0001). Host
 * transports can feed decoded {@link ActionFrame} values here and write the
 * returned {@link CapsFrame}.
 *
 * <p>Direction note: {@link BridgeNode} and {@link BridgeDispatcher} carry the
 * <b>NPS → external</b> direction. {@link McpServerBridge}, {@link A2aServerBridge},
 * and {@link BridgeServerMiddleware} carry the inverse <b>external → NPS</b>
 * direction.
 */
public final class BridgeNode {

    private final BridgeDispatcherRegistry dispatchers;

    /** Create a Bridge Node facade over a dispatcher registry. */
    public BridgeNode(BridgeDispatcherRegistry dispatchers) {
        if (dispatchers == null) {
            throw new NullPointerException("dispatchers");
        }
        this.dispatchers = dispatchers;
    }

    /** Parse {@code bridge_target}, resolve a protocol dispatcher, and invoke it. */
    public CapsFrame dispatch(ActionFrame frame) {
        if (frame == null) {
            throw new NullPointerException("frame");
        }
        BridgeTarget target = BridgeTargetParser.fromActionFrame(frame);
        BridgeDispatcher dispatcher = dispatchers.resolve(target.protocol);
        return dispatcher.dispatch(frame, target);
    }
}
