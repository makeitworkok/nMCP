// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraScheduleToolsTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void toolNames_areCorrect() {
        NiagaraScheduleTools tools = new NiagaraScheduleTools(security());
        List<McpTool> list = tools.tools();
        assertEquals(3, list.size());
        assertEquals("nmcp.schedule.read",  list.get(0).name());
        assertEquals("nmcp.schedule.list",  list.get(1).name());
        assertEquals("nmcp.schedule.write", list.get(2).name());
    }

    @Test
    void scheduleRead_returnsError_whenOrdMissing() {
        NiagaraScheduleTools tools = new NiagaraScheduleTools(security());
        McpToolResult result = tools.tools().get(0).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("ord"));
    }

    @Test
    void scheduleRead_returnsError_whenOrdNotAllowlisted() {
        NiagaraScheduleTools tools = new NiagaraScheduleTools(security());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord", "station:|slot:/Hidden/MySchedule");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void scheduleList_returnsError_whenContextNull() {
        NiagaraScheduleTools tools = new NiagaraScheduleTools(security());
        McpToolResult result = tools.tools().get(1).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
    }

    @Test
    void scheduleRead_inputSchema_requiresOrd() {
        NiagaraScheduleTools tools = new NiagaraScheduleTools(security());
        String schema = tools.tools().get(0).inputSchema();
        assertTrue(schema.contains("\"ord\""));
        assertTrue(schema.contains("\"required\""));
    }

    @Test
    void scheduleList_inputSchema_hasOptionalRoot() {
        NiagaraScheduleTools tools = new NiagaraScheduleTools(security());
        String schema = tools.tools().get(1).inputSchema();
        assertTrue(schema.contains("\"root\""));
        assertTrue(schema.contains("station:|slot:/Drivers"));
    }

    // -------------------------------------------------------------------------
    // nmcp.schedule.write — security checks
    // -------------------------------------------------------------------------

    @Test
    void scheduleWrite_rejectsWhenReadOnly() {
        NiagaraScheduleTools tools = new NiagaraScheduleTools(security()); // readOnly=true
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord",   "station:|slot:/Drivers/MySched");
        args.put("state", "occupied");
        McpToolResult result = tools.tools().get(2).call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("read-only")
                || result.getErrorMessage().toLowerCase().contains("readonly"));
    }

    @Test
    void scheduleWrite_rejectsWhenOrdMissing() {
        NiagaraScheduleTools tools = new NiagaraScheduleTools(
                new NiagaraSecurity(false, true, 100,
                        Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services")));
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("state", "occupied");
        McpToolResult result = tools.tools().get(2).call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("ord"));
    }

    @Test
    void scheduleWrite_rejectsWhenStateMissing() {
        NiagaraScheduleTools tools = new NiagaraScheduleTools(
                new NiagaraSecurity(false, true, 100,
                        Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services")));
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord", "station:|slot:/Drivers/MySched");
        McpToolResult result = tools.tools().get(2).call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("state"));
    }

    @Test
    void scheduleWrite_rejectsWhenOrdNotAllowlisted() {
        NiagaraScheduleTools tools = new NiagaraScheduleTools(
                new NiagaraSecurity(false, true, 100,
                        Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services")));
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord",   "station:|slot:/Hidden/MySched");
        args.put("state", "occupied");
        McpToolResult result = tools.tools().get(2).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void scheduleWrite_inputSchema_requiresOrdAndState() {
        NiagaraScheduleTools tools = new NiagaraScheduleTools(security());
        String schema = tools.tools().get(2).inputSchema();
        assertTrue(schema.contains("\"ord\""));
        assertTrue(schema.contains("\"state\""));
        assertTrue(schema.contains("\"required\""));
    }
}
