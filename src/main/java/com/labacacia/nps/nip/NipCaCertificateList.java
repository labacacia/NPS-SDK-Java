// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Response from the authenticated certificate inventory endpoint. */
public record NipCaCertificateList(
    @JsonProperty("entries") List<NipCaCertificateRecord> entries
) {}
