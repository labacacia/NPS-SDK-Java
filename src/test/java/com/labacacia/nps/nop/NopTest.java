// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.NpsRegistries;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NopTest {

    private static final Map<String,Object> DAG = Map.of(
        "nodes", List.of(Map.of("id","n1","action","search","agent","urn:nps:node:a:1")),
        "edges", List.of());

    // ── BackoffStrategy ───────────────────────────────────────────────────────

    @Test void fixedDelayIgnoresAttempt() {
        assertEquals(500, BackoffStrategy.computeDelayMs(BackoffStrategy.FIXED, 500, 30_000, 0));
        assertEquals(500, BackoffStrategy.computeDelayMs(BackoffStrategy.FIXED, 500, 30_000, 5));
    }

    @Test void linearScalesWithAttempt() {
        assertEquals(1000, BackoffStrategy.computeDelayMs(BackoffStrategy.LINEAR, 1000, 30_000, 0));
        assertEquals(3000, BackoffStrategy.computeDelayMs(BackoffStrategy.LINEAR, 1000, 30_000, 2));
    }

    @Test void exponentialDoublesEachAttempt() {
        assertEquals(1000, BackoffStrategy.computeDelayMs(BackoffStrategy.EXPONENTIAL, 1000, 30_000, 0));
        assertEquals(2000, BackoffStrategy.computeDelayMs(BackoffStrategy.EXPONENTIAL, 1000, 30_000, 1));
        assertEquals(8000, BackoffStrategy.computeDelayMs(BackoffStrategy.EXPONENTIAL, 1000, 30_000, 3));
    }

    @Test void delayIsCappedAtMaxMs() {
        assertEquals(5000, BackoffStrategy.computeDelayMs(BackoffStrategy.EXPONENTIAL, 1000, 5000, 10));
    }

    // ── TaskFrame ─────────────────────────────────────────────────────────────

    @Test void taskFrameRoundtrip() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var frame = new TaskFrame("t1", DAG, 5000, "https://cb.example.com/hook",
            Map.of("traceId", "tr1"), "high", 1);
        var out   = (TaskFrame) codec.decode(codec.encode(frame));
        assertEquals("t1",    out.taskId());
        assertEquals(5000,    out.timeoutMs());
        assertEquals("https://cb.example.com/hook", out.callbackUrl());
        assertEquals("high",  out.priority());
        assertEquals(1,       out.depth());
    }

    @Test void taskFrameOptionalFieldsNull() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var out   = (TaskFrame) codec.decode(codec.encode(new TaskFrame("t2", DAG)));
        assertNull(out.timeoutMs());
        assertNull(out.callbackUrl());
    }

    // ── DelegateFrame ─────────────────────────────────────────────────────────

    @Test void delegateFrameRoundtrip() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var frame = new DelegateFrame("t1","sub1","classify","urn:nps:node:a:1",
            Map.of("text","hello"), Map.of("model","gpt-4"), "idem-x");
        var out   = (DelegateFrame) codec.decode(codec.encode(frame));
        assertEquals("sub1",   out.subtaskId());
        assertEquals("idem-x", out.idempotencyKey());
    }

    @Test void delegateFrameOptionals() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var frame = new DelegateFrame("t1","s1","act","urn:nps:node:a:1",null,null,null);
        var out   = (DelegateFrame) codec.decode(codec.encode(frame));
        assertNull(out.inputs());
        assertNull(out.idempotencyKey());
    }

    // ── SyncFrame ─────────────────────────────────────────────────────────────

    @Test void syncFrameRoundtrip() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var frame = new SyncFrame("t1","sync1", List.of("a","b"), 1, "fastest_k", 3000);
        var out   = (SyncFrame) codec.decode(codec.encode(frame));
        assertEquals("sync1",     out.syncId());
        assertEquals(1,           out.minRequired());
        assertEquals("fastest_k", out.aggregate());
        assertEquals(3000,        out.timeoutMs());
    }

    @Test void syncFrameDefaults() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var out   = (SyncFrame) codec.decode(codec.encode(new SyncFrame("t1","s1", List.of("a"))));
        assertEquals(0,      out.minRequired());
        assertEquals("merge",out.aggregate());
        assertNull(out.timeoutMs());
    }

    // ── AlignStreamFrame ──────────────────────────────────────────────────────

    @Test void alignStreamFrameWithError() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var err   = Map.<String,Object>of("error_code","NOP-DELEGATE-FAILED","message","timeout");
        var frame = new AlignStreamFrame("s1","t1","sub1",3,true,"urn:nps:node:a:1",
            Map.of("score",0.9), err, 10);
        var out   = (AlignStreamFrame) codec.decode(codec.encode(frame));
        assertEquals(3,                     out.seq());
        assertTrue(out.isFinal());
        assertEquals("NOP-DELEGATE-FAILED", out.errorCode());
        assertEquals("timeout",             out.errorMessage());
        assertEquals(10,                    out.windowSize());
    }

    @Test void alignStreamFrameNullError() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var frame = new AlignStreamFrame("s1","t1","sub1",0,false,"urn:nps:node:a:1",null,null,null);
        var out   = (AlignStreamFrame) codec.decode(codec.encode(frame));
        assertNull(out.error());
        assertNull(out.errorCode());
    }

    // ── NopTaskStatus ─────────────────────────────────────────────────────────

    @Test void nopTaskStatusGetters() {
        var s = new NopTaskStatus(Map.of("task_id","t1","state","running"));
        assertEquals("t1",            s.taskId());
        assertEquals(TaskState.RUNNING, s.state());
        assertFalse(s.isTerminal());
    }

    @Test void nopTaskStatusTerminalStates() {
        assertTrue(new NopTaskStatus(Map.of("task_id","x","state","completed")).isTerminal());
        assertTrue(new NopTaskStatus(Map.of("task_id","x","state","failed")).isTerminal());
        assertTrue(new NopTaskStatus(Map.of("task_id","x","state","cancelled")).isTerminal());
        assertFalse(new NopTaskStatus(Map.of("task_id","x","state","pending")).isTerminal());
    }

    @Test void nopTaskStatusErrorFields() {
        var s = new NopTaskStatus(Map.of("task_id","t1","state","failed",
            "error_code","NOP-TASK-FAILED","error_message","Agent timeout"));
        assertEquals("NOP-TASK-FAILED",  s.errorCode());
        assertEquals("Agent timeout",    s.errorMessage());
    }

    @Test void nopTaskStatusNodeResults() {
        var s = new NopTaskStatus(Map.of("task_id","t1","state","completed",
            "node_results", Map.of("n1", Map.of("out", 42))));
        assertNotNull(s.nodeResults().get("n1"));
    }

    @Test void nopTaskStatusToString() {
        var s = new NopTaskStatus(Map.of("task_id","t1","state","running"));
        assertTrue(s.toString().contains("t1"));
        assertTrue(s.toString().contains("running"));
    }

    // ── TaskState ─────────────────────────────────────────────────────────────

    @Test void taskStateFromValue() {
        assertEquals(TaskState.COMPLETED, TaskState.fromValue("completed"));
        assertThrows(IllegalArgumentException.class, () -> TaskState.fromValue("unknown"));
    }
}
