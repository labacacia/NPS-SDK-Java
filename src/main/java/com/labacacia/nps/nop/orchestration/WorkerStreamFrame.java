// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.nop.models.StreamError;

/**
 * A single frame in a Worker Agent's response stream (NPS-5 §3.4).
 *
 * <p>Engine-facing analog of the .NET {@code AlignStreamFrame}. The final frame has
 * {@link #isFinal()} set to {@code true} and carries either {@link #data()} (success)
 * or {@link #error()} (failure).
 *
 * @param seq       Strictly increasing message sequence number (0-based).
 * @param isFinal   True when this is the final frame in the stream.
 * @param senderNid Sender NID; validated against the node agent NID.
 * @param data      Intermediate or final result data.
 * @param error     Error details when {@code isFinal} is true and the sub-task failed.
 */
public record WorkerStreamFrame(
    long seq,
    boolean isFinal,
    String senderNid,
    JsonNode data,
    StreamError error) {

    /** Intermediate (non-final) data frame. */
    public static WorkerStreamFrame intermediate(long seq, String senderNid, JsonNode data) {
        return new WorkerStreamFrame(seq, false, senderNid, data, null);
    }

    /** Final success frame carrying the result. */
    public static WorkerStreamFrame finalData(long seq, String senderNid, JsonNode data) {
        return new WorkerStreamFrame(seq, true, senderNid, data, null);
    }

    /** Final error frame. */
    public static WorkerStreamFrame finalError(long seq, String senderNid, StreamError error) {
        return new WorkerStreamFrame(seq, true, senderNid, null, error);
    }
}
