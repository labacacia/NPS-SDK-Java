// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.telemetry;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.FrameRegistry;
import com.labacacia.nps.ncp.CapsFrame;
import com.labacacia.nps.ncp.NcpFrameRegistrar;
import com.labacacia.nps.nop.orchestration.NopTelemetry;
import com.labacacia.nps.nwp.NwpFrameRegistrar;
import com.labacacia.nps.nwp.NwpNativeNodeServer;
import com.labacacia.nps.nwp.QueryFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the NWP dispatch and NOP orchestrator call-sites emit real instruments. */
class InstrumentationWiringTest {

    private static FrameRegistry registry() {
        var r = new FrameRegistry();
        NcpFrameRegistrar.register(r);
        NwpFrameRegistrar.register(r);
        return r;
    }

    @Test void nwpDispatchIncrementsFramesAndRecordsSpan() {
        long before = NwpInstrumentation.FRAMES_PROCESSED.value();
        long spansBefore = NwpInstrumentation.TRACER.endedSpans().size();

        var codec = new NpsFrameCodec(registry());
        var server = new NwpNativeNodeServer(codec, EncodingTier.MSGPACK, "native:test",
            query -> new CapsFrame("native:test", 1, List.of(Map.<String, Object>of("id", 1))),
            null);
        NpsFrame resp = server.dispatch(new QueryFrame("sha256:a", null, null, null, null, null, null, null));

        assertInstanceOf(CapsFrame.class, resp);
        assertEquals(before + 1, NwpInstrumentation.FRAMES_PROCESSED.value());
        assertTrue(NwpInstrumentation.TRACER.endedSpans().size() > spansBefore);
    }

    @Test void nwpDispatchErrorIncrementsErrorCounter() {
        long before = NwpInstrumentation.FRAME_ERRORS.value();
        var codec = new NpsFrameCodec(registry());
        // No query handler configured ⇒ dispatch returns an ErrorFrame.
        var server = new NwpNativeNodeServer(codec, EncodingTier.MSGPACK, "native:test", null, null);
        server.dispatch(new QueryFrame("sha256:a", null, null, null, null, null, null, null));
        assertEquals(before + 1, NwpInstrumentation.FRAME_ERRORS.value());
    }

    @Test void nopTelemetryDelegatesToInstruments() {
        long completed = NopInstrumentation.TASKS_COMPLETED.value();
        long failed    = NopInstrumentation.TASKS_FAILED.value();
        long retries   = NopInstrumentation.NODE_RETRIES.value();
        long durCount  = NopInstrumentation.TASK_DURATION_MS.count();

        NopTelemetry.taskCompleted();
        NopTelemetry.taskFailed();
        NopTelemetry.nodeRetry();
        NopTelemetry.recordTaskDuration(12.5, "success");

        assertEquals(completed + 1, NopInstrumentation.TASKS_COMPLETED.value());
        assertEquals(failed + 1,    NopInstrumentation.TASKS_FAILED.value());
        assertEquals(retries + 1,   NopInstrumentation.NODE_RETRIES.value());
        assertEquals(durCount + 1,  NopInstrumentation.TASK_DURATION_MS.count());
    }
}
