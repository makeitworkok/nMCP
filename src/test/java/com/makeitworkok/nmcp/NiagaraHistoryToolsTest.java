// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraHistoryToolsTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void toolNames_areCorrect() {
        NiagaraHistoryTools tools = new NiagaraHistoryTools(security());
        List<McpTool> list = tools.tools();
        assertEquals(3, list.size());
        assertEquals("nmcp.history.list", list.get(0).name());
        assertEquals("nmcp.history.read", list.get(1).name());
        assertEquals("nmcp.trend.summary", list.get(2).name());
    }

    @Test
    void historyList_returnsError_whenContextNull() {
        NiagaraHistoryTools tools = new NiagaraHistoryTools(security());
        McpToolResult result = tools.tools().get(0).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
    }

    @Test
    void historyRead_returnsError_whenIdMissing() {
        NiagaraHistoryTools tools = new NiagaraHistoryTools(security());
        McpToolResult result = tools.tools().get(1).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("id"));
    }

    @Test
    void historyRead_returnsError_whenContextNull() {
        NiagaraHistoryTools tools = new NiagaraHistoryTools(security());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("id", "someHistory");
        McpToolResult result = tools.tools().get(1).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void historyRead_inputSchema_requiresId() {
        NiagaraHistoryTools tools = new NiagaraHistoryTools(security());
        String schema = tools.tools().get(1).inputSchema();
        assertTrue(schema.contains("\"id\""));
        assertTrue(schema.contains("\"required\""));
    }

    @Test
    void historyList_inputSchema_isValidJson() {
        NiagaraHistoryTools tools = new NiagaraHistoryTools(security());
        String schema = tools.tools().get(0).inputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"type\":\"object\""));
    }

    @Test
    void trendSummary_returnsError_whenIdMissing() {
        NiagaraHistoryTools tools = new NiagaraHistoryTools(security());
        McpToolResult result = tools.tools().get(2).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("id"));
    }
}
