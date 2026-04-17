// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ErrorFrame implements NpsFrame {

    private final String             status;
    private final String             error;
    private final String             message; // nullable
    private final Map<String,Object> details; // nullable

    public ErrorFrame(String status, String error, String message, Map<String, Object> details) {
        this.status  = status;
        this.error   = error;
        this.message = message;
        this.details = details;
    }

    @Override public FrameType    frameType()    { return FrameType.ERROR; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String status()             { return status; }
    public String error()              { return error; }
    public String message()            { return message; }
    public Map<String,Object> details(){ return details; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status",  status);
        m.put("error",   error);
        m.put("message", message);
        m.put("details", details);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ErrorFrame fromDict(Map<String, Object> d) {
        return new ErrorFrame(
            (String) d.get("status"),
            (String) d.get("error"),
            (String) d.get("message"),
            (Map<String, Object>) d.get("details")
        );
    }
}
