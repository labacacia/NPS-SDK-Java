// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.labacacia.nps.core.exception.NpsError;

/**
 * Raised by {@link NdpRegistry#resolveCluster(String)} when a {@code cluster_anchor}
 * has more than one live member sharing the top {@code cluster_epoch} — split brain.
 *
 * <p>NPS-CR-0009 / NDP v0.10 §9. The registry MUST NOT pick one arbitrarily; the
 * ambiguity is surfaced to the caller as {@code NDP-CLUSTER-SPLIT}
 * ({@code NPS-CLIENT-CONFLICT}, HTTP 409).</p>
 */
public final class NdpClusterSplitException extends NpsError {

    private final String clusterAnchor;
    private final long   epoch;

    public NdpClusterSplitException(String clusterAnchor, long epoch) {
        super("NDP-CLUSTER-SPLIT: cluster '" + clusterAnchor
            + "' has multiple live active Anchors at epoch " + epoch + ".");
        this.clusterAnchor = clusterAnchor;
        this.epoch         = epoch;
    }

    public String clusterAnchor() { return clusterAnchor; }
    public long   epoch()         { return epoch; }

    /** Always {@link NdpErrorCodes#NDP_CLUSTER_SPLIT}. */
    public String errorCode() { return NdpErrorCodes.NDP_CLUSTER_SPLIT; }
}
