// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraComponentToolsTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void componentSearch_schemaContainsNameFilter() {
        NiagaraComponentTools tools = new NiagaraComponentTools(security(), "0.4.0");
        List<McpTool> list = tools.tools();
        assertEquals(5, list.size());
        assertEquals("nmcp.component.search", list.get(4).name());
        assertTrue(list.get(4).inputSchema().contains("nameFilter"));
    }
}