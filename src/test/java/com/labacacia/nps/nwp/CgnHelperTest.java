// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0

package com.labacacia.nps.nwp;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CgnHelperTest {

    @Test
    void emptyStringReturnsZero() {
        assertEquals(0, CgnHelper.estimate(""));
    }

    @Test
    void nullStringReturnsZero() {
        assertEquals(0, CgnHelper.estimate(null));
    }

    @Test
    void fourBytesReturnsOne() {
        // "test" = 4 UTF-8 bytes -> ceil(4/4) = 1
        assertEquals(1, CgnHelper.estimate("test"));
    }

    @Test
    void fiveBytesReturnsTwo() {
        // "hello" = 5 UTF-8 bytes -> ceil(5/4) = 2
        assertEquals(2, CgnHelper.estimate("hello"));
    }

    @Test
    void chineseCharactersReturnTwo() {
        // "你好" = 6 UTF-8 bytes -> ceil(6/4) = 2
        assertEquals(2, CgnHelper.estimate("你好"));
    }

    @Test
    void estimateBytesEmptyArrayReturnsZero() {
        assertEquals(0, CgnHelper.estimateBytes(new byte[0]));
    }

    @Test
    void estimateBytesNullReturnsZero() {
        assertEquals(0, CgnHelper.estimateBytes(null));
    }

    @Test
    void estimateJsonOnMapReturnsPositive() throws Exception {
        int n = CgnHelper.estimateJson(Map.of("key", "val"));
        assertTrue(n > 0, "Expected positive CGN for JSON object, got: " + n);
    }

    @Test
    void budgetExceededErrorFields() {
        BudgetExceededError err = new BudgetExceededError(200, 100);
        assertEquals(200, err.requested);
        assertEquals(100, err.limit);
        assertTrue(err.getMessage().contains("200") && err.getMessage().contains("100"));
    }

    @Test
    void tokenBudgetMetaDefaultProfile() {
        TokenBudgetMeta meta = new TokenBudgetMeta(100);
        assertEquals("cgn.v1", meta.profile);
    }

    @Test
    void tokenBudgetMetaCustomProfile() {
        TokenBudgetMeta meta = new TokenBudgetMeta(50, null, null, null, "cgn.v2");
        assertEquals("cgn.v2", meta.profile);
    }

    @Test
    void tokenBudgetMetaNullProfileDefaultsToCgnV1() {
        TokenBudgetMeta meta = new TokenBudgetMeta(50, null, null, null, null);
        assertEquals("cgn.v1", meta.profile);
    }
}
