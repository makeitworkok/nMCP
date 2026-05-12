// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraFaultScanToolTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void toolName_isCorrect() {
        NiagaraFaultScanTool tool = new NiagaraFaultScanTool(security());
        assertEquals(1, tool.tools().size());
        assertEquals("nmcp.fault.scan", tool.tools().get(0).name());
    }

    @Test
    void returnsError_whenRootNotAllowlisted() {
        NiagaraFaultScanTool tool = new NiagaraFaultScanTool(security());
        McpToolResult result = tool.tools().get(0).call(
                Collections.<String, Object>singletonMap("root", "station:|slot:/System"), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("allowlisted"));
    }
}