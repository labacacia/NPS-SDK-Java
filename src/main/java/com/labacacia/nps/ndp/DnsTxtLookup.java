// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import java.util.List;

/**
 * Functional interface for DNS TXT record resolution.
 * Implementations may use JNDI, a third-party resolver, or a test stub.
 */
@FunctionalInterface
public interface DnsTxtLookup {
    /**
     * Returns TXT records for the given hostname; empty list if none or on error.
     *
     * @param hostname the fully-qualified hostname to query (e.g. {@code _nps-node.api.example.com})
     * @return list of TXT record strings; never {@code null}
     * @throws Exception if a non-recoverable error occurs (callers should handle gracefully)
     */
    List<String> lookup(String hostname) throws Exception;
}
