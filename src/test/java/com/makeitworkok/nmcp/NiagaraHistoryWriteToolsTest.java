// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraHistoryWriteToolsTest {

    private NiagaraSecurity readOnlySecurity() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    private NiagaraSecurity writeSecurity() {
        return new NiagaraSecurity(false, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void toolNames_areCorrect() {
        NiagaraHistoryWriteTools tools = new NiagaraHistoryWriteTools(readOnlySecurity());
        List<McpTool> list = tools.tools();
        assertEquals(1, list.size());
        assertEquals("nmcp.history.provisionOnPoint", list.get(0).name());
    }

    @Test
    void provision_missingPointOrd_rejected() {
        NiagaraHistoryWriteTools tools = new NiagaraHistoryWriteTools(writeSecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("historyId", "ZoneTempHistory");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("pointord"));
    }

    @Test
    void provision_missingHistoryId_rejected() {
        NiagaraHistoryWriteTools tools = new NiagaraHistoryWriteTools(writeSecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("pointOrd", "station:|slot:/Drivers/Point1");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("historyid"));
    }

    @Test
    void provision_rejectsWhenReadOnly() {
        NiagaraHistoryWriteTools tools = new NiagaraHistoryWriteTools(readOnlySecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("pointOrd", "station:|slot:/Drivers/Point1");
        args.put("historyId", "ZoneTempHistory");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("read-only")
                || result.getErrorMessage().toLowerCase().contains("readonly"));
    }

    @Test
    void provision_rejectsOutsideAllowlist() {
        NiagaraHistoryWriteTools tools = new NiagaraHistoryWriteTools(writeSecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("pointOrd", "station:|slot:/Hidden/Point1");
        args.put("historyId", "ZoneTempHistory");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void provision_schemaContainsMainFields() {
        NiagaraHistoryWriteTools tools = new NiagaraHistoryWriteTools(readOnlySecurity());
        String schema = tools.tools().get(0).inputSchema();
        assertTrue(schema.contains("\"pointOrd\""));
        assertTrue(schema.contains("\"historyId\""));
        assertTrue(schema.contains("\"enabled\""));
        assertTrue(schema.contains("\"sampleIntervalMs\""));
        assertTrue(schema.contains("\"retentionCount\""));
    }
}
