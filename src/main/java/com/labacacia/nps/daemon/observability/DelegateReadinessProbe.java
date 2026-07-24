// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.daemon.observability;

import java.util.function.Supplier;

/**
 * Inline {@link ReadinessProbe} wrapping a lambda, so callers can register a
 * check without authoring a class per dependency. Port of the .NET
 * {@code DelegateReadinessProbe}.
 */
public final class DelegateReadinessProbe implements ReadinessProbe {

    private final String name;
    private final Supplier<String> check;

    public DelegateReadinessProbe(String name, Supplier<String> check) {
        this.name = name;
        this.check = check;
    }

    @Override public String name()  { return name; }
    @Override public String check() { return check.get(); }
}
