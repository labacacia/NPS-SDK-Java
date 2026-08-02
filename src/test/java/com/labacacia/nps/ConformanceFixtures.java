// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ConformanceFixtures {
    private ConformanceFixtures() {}

    public static Path resolve(String relativePath) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("spec/conformance").resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
            "Unable to locate conformance fixture: " + relativePath);
    }
}
