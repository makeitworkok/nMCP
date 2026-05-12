// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraPointToolsTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void toolNames_areCorrect() {
        NiagaraPointTools tools = new NiagaraPointTools(security());
        List<McpTool> list = tools.tools();
        assertEquals(2, list.size());
        assertEquals("nmcp.point.read",   list.get(0).name());
        assertEquals("nmcp.point.search", list.get(1).name());
    }

    @Test
    void pointRead_returnsError_whenOrdMissing() {
        NiagaraPointTools tools = new NiagaraPointTools(security());
        McpToolResult result = tools.tools().get(0).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("ord"));
    }

    @Test
    void pointRead_returnsError_whenOrdNotAllowlisted() {
        NiagaraPointTools tools = new NiagaraPointTools(security());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord", "station:|slot:/Hidden/SomePoint");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void pointSearch_usesDefaultRoot_whenRootNotProvided() {
        NiagaraPointTools tools = new NiagaraPointTools(security());
        McpToolResult result = tools.tools().get(1).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
    }

    @Test
    void pointSearch_returnsError_whenCustomRootNotAllowlisted() {
        NiagaraPointTools tools = new NiagaraPointTools(security());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("root", "station:|slot:/Hidden");
        McpToolResult result = tools.tools().get(1).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void pointRead_inputSchema_requiresOrd() {
        NiagaraPointTools tools = new NiagaraPointTools(security());
        String schema = tools.tools().get(0).inputSchema();
        assertTrue(schema.contains("\"ord\""));
        assertTrue(schema.contains("\"required\""));
    }

    @Test
    void pointSearch_inputSchema_hasFilters() {
        NiagaraPointTools tools = new NiagaraPointTools(security());
        String schema = tools.tools().get(1).inputSchema();
        assertTrue(schema.contains("nameFilter"));
        assertTrue(schema.contains("typeFilter"));
        assertTrue(schema.contains("station:|slot:/Drivers"));
    }
}
