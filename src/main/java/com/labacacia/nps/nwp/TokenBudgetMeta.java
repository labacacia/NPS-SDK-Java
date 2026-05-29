// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0

package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Wire-level CGN budget descriptor (NWP §5, NWM token_budget block). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class TokenBudgetMeta {

    @JsonProperty("cgn_limit") public final int cgnLimit;
    @JsonProperty("tokenizer") public final String tokenizer;
    @JsonProperty("supported_tokenizers") public final List<String> supportedTokenizers;
    @JsonProperty("token_budget_hint") public final Boolean tokenBudgetHint;
    @JsonProperty("profile") public final String profile;

    public TokenBudgetMeta(int cgnLimit) {
        this(cgnLimit, null, null, true, "cgn.v1");
    }

    public TokenBudgetMeta(int cgnLimit, String tokenizer, List<String> supportedTokenizers,
                           Boolean tokenBudgetHint, String profile) {
        this.cgnLimit = cgnLimit;
        this.tokenizer = tokenizer;
        this.supportedTokenizers = supportedTokenizers;
        this.tokenBudgetHint = tokenBudgetHint;
        this.profile = profile != null ? profile : "cgn.v1";
    }
}
