// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.ncp.ErrorFrame;

/** Invokes local NPS actions for inbound Bridge server adapters. */
public interface BridgeServerActionInvoker {

    /** Invoke a local NPS action and return its frame response. */
    NpsFrame invoke(ActionFrame frame) throws Exception;

    /** Default invoker delegating to {@link BridgeServerOptions#dispatch}. */
    final class Default implements BridgeServerActionInvoker {

        private final BridgeServerOptions options;

        public Default(BridgeServerOptions options) {
            this.options = options;
        }

        @Override
        public NpsFrame invoke(ActionFrame frame) throws Exception {
            if (options.dispatch == null) {
                return new ErrorFrame(
                    "NPS-SERVER-NOT-IMPLEMENTED",
                    BridgeErrorCodes.SERVER_DISPATCHER_MISSING,
                    "BridgeServerOptions.dispatch must be configured before handling inbound Bridge calls.",
                    null);
            }
            return options.dispatch.dispatch(frame);
        }
    }
}
