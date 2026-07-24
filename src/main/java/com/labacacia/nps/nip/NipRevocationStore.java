// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

/**
 * Optional CA/certificate store used as a live revocation source for
 * {@link NipIdentVerifier} (NPS-3 §7 step 4).
 *
 * <p>Java parallel of the .NET {@code INipCaStore.GetBySerialAsync} hook. The
 * verifier calls {@link #getBySerial(String)} and rejects the identity when the
 * returned record's {@link Record#revokedAt()} is populated.
 */
public interface NipRevocationStore {

    /**
     * Looks up a certificate record by serial. Return {@code null} when the serial
     * is unknown (treated as not-revoked / pass-through).
     */
    Record getBySerial(String serial);

    /** Minimal revocation record. Matches the .NET {@code NipCertRecord} fields used by the verifier. */
    record Record(String serial, String revokedAt, String revokeReason) {}
}
