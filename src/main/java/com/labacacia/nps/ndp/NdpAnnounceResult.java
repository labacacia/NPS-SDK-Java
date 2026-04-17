// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

public record NdpAnnounceResult(boolean isValid, String errorCode, String message) {

    public static NdpAnnounceResult ok() {
        return new NdpAnnounceResult(true, null, null);
    }

    public static NdpAnnounceResult fail(String errorCode, String message) {
        return new NdpAnnounceResult(false, errorCode, message);
    }
}
