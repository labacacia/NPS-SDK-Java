// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

/**
 * Pluggable live revocation callback for {@link NipIdentVerifier} (NPS-3 §7 step 4).
 *
 * <p>Java parallel of the .NET {@code NipRevocationCheck} delegate. It runs after
 * the local CRL check and before the {@link NipRevocationStore} / OCSP checks,
 * allowing hosts to consult an external cache, CA plane, or policy service.
 *
 * <p>Return a failing {@link NipIdentVerifyResult} to reject the identity, or
 * {@code null} / an OK result to continue to the next configured revocation source.
 */
@FunctionalInterface
public interface NipRevocationCheck {
    NipIdentVerifyResult check(IdentFrame frame);
}
