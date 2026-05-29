// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;
/** Result from a reputation policy evaluation (NPS-RFC-0005 §4.3). */
public final class ReputationDecision {
    public final RepOutcome outcome;
    public final ReputationPolicyRule matchedRule;
    public final String errorCode;
    public ReputationDecision(RepOutcome outcome) { this(outcome, null, null); }
    public ReputationDecision(RepOutcome outcome, ReputationPolicyRule matchedRule, String errorCode) {
        this.outcome = outcome;
        this.matchedRule = matchedRule;
        this.errorCode = errorCode;
    }
    public boolean isAccepted() { return outcome == RepOutcome.ACCEPT; }
}
