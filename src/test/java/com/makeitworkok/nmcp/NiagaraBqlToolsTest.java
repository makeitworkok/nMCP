// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraBqlToolsTest {

    private NiagaraSecurity security(boolean allowBql) {
        return new NiagaraSecurity(true, allowBql, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void toolNames_areCorrect() {
        NiagaraBqlTools tools = new NiagaraBqlTools(security(true));
        List<McpTool> list = tools.tools();
        assertEquals(1, list.size());
        assertEquals("nmcp.bql.query", list.get(0).name());
    }

    @Test
    void bqlQuery_missingQuery_returnsError() {
        NiagaraBqlTools tools = new NiagaraBqlTools(security(true));
        McpToolResult result = tools.tools().get(0).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("query"));
    }

    @Test
    void bqlQuery_nonSelect_rejected() {
        NiagaraBqlTools tools = new NiagaraBqlTools(security(true));
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "DELETE FROM baja:Component");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void bqlQuery_bqlDisabled_rejected() {
        NiagaraBqlTools tools = new NiagaraBqlTools(security(false));
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "SELECT * FROM control:NumericPoint");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void bqlQuery_ordPrefixedSelect_notRejectedBySelectGuard() {
        NiagaraBqlTools tools = new NiagaraBqlTools(security(true));
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "station:|slot:/|bql:select name from control:ControlPoint");

        McpToolResult result = tools.tools().get(0).call(args, null);

        assertTrue(result.isError());
        // Unit test runtime doesn't include BQL engine; ensure the failure is runtime, not SELECT validation.
        assertFalse(result.getErrorMessage().contains("Only SELECT queries are permitted"));
    }

    @Test
    void bqlQuery_inputSchema_containsQueryAndLimit() {
        NiagaraBqlTools tools = new NiagaraBqlTools(security(true));
        String schema = tools.tools().get(0).inputSchema();
        assertTrue(schema.contains("\"query\""));
        assertTrue(schema.contains("\"limit\""));
        assertTrue(schema.contains("\"offset\""));
        assertTrue(schema.contains("\"debug\""));
    }

    @Test
    void bqlQuery_rejectsNegativeOffset() {
        NiagaraBqlTools tools = new NiagaraBqlTools(security(true));
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "SELECT * FROM control:NumericPoint");
        args.put("offset", Integer.valueOf(-1));

        McpToolResult result = tools.tools().get(0).call(args, null);

        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("offset"));
    }

    @Test
    void bqlQuery_debug_returnsDiagnosticPayloadWithoutBqlRuntime() {
        NiagaraBqlTools tools = new NiagaraBqlTools(security(true));
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "station:|slot:/|bql:select name from control:ControlPoint");
        args.put("debug", Boolean.TRUE);
        args.put("offset", Integer.valueOf(3));

        McpToolResult result = tools.tools().get(0).call(args, null);

        assertFalse(result.isError());
        assertTrue(result.getContent().contains("\"debug\":true"));
        assertTrue(result.getContent().contains("\"offset\":3"));
        assertTrue(result.getContent().contains("\"normalizedQuery\":\"select name from control:ControlPoint\""));
    }
}
