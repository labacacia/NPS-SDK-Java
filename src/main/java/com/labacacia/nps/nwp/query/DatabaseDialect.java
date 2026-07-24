// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp.query;

/**
 * Supported SQL dialects for quoting and pagination syntax (NPS-2 §5).
 * Mirror of the .NET {@code DatabaseDialect} enum.
 */
public enum DatabaseDialect {
    SQL_SERVER,
    POSTGRE_SQL
}
