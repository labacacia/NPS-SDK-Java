// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/** NWP HTTP header and MIME constants (port of .NET NwpHttpHeaders). */
public final class NwpHttpHeaders {

    private NwpHttpHeaders() {}

    // Request headers
    public static final String AGENT = "X-NWP-Agent";
    public static final String BUDGET = "X-NWP-Budget";
    public static final String IDENT = "X-NWP-Ident";
    public static final String CAPABILITIES = "X-NWP-Capabilities";
    public static final String DEPTH = "X-NWP-Depth";
    public static final String ENCODING = "X-NWP-Encoding";
    public static final String TOKENIZER = "X-NWP-Tokenizer";

    // Response headers
    public static final String SCHEMA = "X-NWP-Schema";
    public static final String TOKENS = "X-NWP-Tokens";
    public static final String TOKENS_NATIVE = "X-NWP-Tokens-Native";
    public static final String TOKENIZER_USED = "X-NWP-Tokenizer-Used";
    public static final String CACHED = "X-NWP-Cached";
    public static final String NODE_TYPE = "X-NWP-Node-Type";
    public static final String REQUEST_ID = "X-NWP-Request-Id";
    public static final String REPUTATION_STATUS = "X-NWP-Reputation-Status";
    public static final String BAN_EXPIRES = "X-NWP-Ban-Expires";

    // MIME types
    public static final String MIME_FRAME = "application/nwp-frame";
    public static final String MIME_LEGACY_FRAME = "application/x-nps-frame";
    public static final String MIME_CAPSULE = "application/nwp-capsule";
    public static final String MIME_ERROR = "application/nwp-error+json";
    public static final String MIME_MANIFEST = "application/nwp-manifest+json";
}
