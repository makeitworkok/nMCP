// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraAlarmToolsTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void toolNames_areCorrect() {
        NiagaraAlarmTools tools = new NiagaraAlarmTools(security());
        List<McpTool> list = tools.tools();
        assertEquals(3, list.size());
        assertEquals("nmcp.alarm.query",  list.get(0).name());
        assertEquals("nmcp.alarm.active", list.get(1).name());
        assertEquals("nmcp.alarm.ack",    list.get(2).name());
    }

    @Test
    void alarmQuery_returnsError_whenContextNull() {
        NiagaraAlarmTools tools = new NiagaraAlarmTools(security());
        McpTool query = tools.tools().get(0);
        McpToolResult result = query.call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError(), "Expected error result when context is null");
    }

    @Test
    void alarmActive_returnsError_whenContextNull() {
        NiagaraAlarmTools tools = new NiagaraAlarmTools(security());
        McpTool active = tools.tools().get(1);
        McpToolResult result = active.call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError(), "Expected error result when context is null");
    }

    @Test
    void alarmQuery_respectsLimitArgument() {
        NiagaraAlarmTools tools = new NiagaraAlarmTools(security());
        McpTool query = tools.tools().get(0);
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("limit", 5);
        assertDoesNotThrow(() -> query.call(args, null));
    }

    @Test
    void alarmQuery_inputSchema_isValidJson() {
        NiagaraAlarmTools tools = new NiagaraAlarmTools(security());
        String schema = tools.tools().get(0).inputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"type\":\"object\""));
        assertTrue(schema.contains("limit"));
    }

    @Test
    void alarmQuery_errorMessage_doesNotContainTodo() {
        NiagaraAlarmTools tools = new NiagaraAlarmTools(security());
        McpToolResult result = tools.tools().get(0).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertFalse(result.getErrorMessage().contains("TODO"));
    }

    // -------------------------------------------------------------------------
    // nmcp.alarm.ack — security checks
    // -------------------------------------------------------------------------

    @Test
    void alarmAck_rejectsWhenReadOnly() {
        NiagaraAlarmTools tools = new NiagaraAlarmTools(security()); // readOnly=true
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sourceOrd", "station:|slot:/Drivers/Device/Point");
        McpToolResult result = tools.tools().get(2).call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("read-only")
                || result.getErrorMessage().toLowerCase().contains("readonly"));
    }

    @Test
    void alarmAck_rejectsWhenSourceOrdMissing() {
        NiagaraAlarmTools tools = new NiagaraAlarmTools(
                new NiagaraSecurity(false, true, 100,
                        Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services")));
        McpToolResult result = tools.tools().get(2).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("sourceord"));
    }

    @Test
    void alarmAck_rejectsWhenOrdNotAllowlisted() {
        NiagaraAlarmTools tools = new NiagaraAlarmTools(
                new NiagaraSecurity(false, true, 100,
                        Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services")));
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("sourceOrd", "station:|slot:/Hidden/Device");
        McpToolResult result = tools.tools().get(2).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void alarmAck_inputSchema_requiresSourceOrd() {
        NiagaraAlarmTools tools = new NiagaraAlarmTools(security());
        String schema = tools.tools().get(2).inputSchema();
        assertTrue(schema.contains("\"sourceOrd\""));
        assertTrue(schema.contains("\"required\""));
    }
}
