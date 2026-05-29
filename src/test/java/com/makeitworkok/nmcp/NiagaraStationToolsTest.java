// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraStationToolsTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void toolNames_areCorrect() {
        NiagaraStationTools tools = new NiagaraStationTools(security());
        List<McpTool> list = tools.tools();
        assertEquals(1, list.size());
        assertEquals("nmcp.station.exportSchema", list.get(0).name());
    }

    @Test
    void exportSchema_outsideAllowlist_rejected() {
        NiagaraStationTools tools = new NiagaraStationTools(security());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("rootOrd", "station:|slot:/Hidden");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void exportSchema_schemaContainsRequestedFlags() {
        NiagaraStationTools tools = new NiagaraStationTools(security());
        String schema = tools.tools().get(0).inputSchema();
        assertTrue(schema.contains("\"rootOrd\""));
        assertTrue(schema.contains("\"includePoints\""));
        assertTrue(schema.contains("\"includeDevices\""));
        assertTrue(schema.contains("\"includeSchedules\""));
        assertTrue(schema.contains("\"includeHistories\""));
        assertTrue(schema.contains("\"includeLinks\""));
    }

    @Test
    void exportSchema_defaultRoot_attemptsResolutionWhenAllowed() {
        NiagaraStationTools tools = new NiagaraStationTools(security());
        McpToolResult result = tools.tools().get(0).call(Collections.<String, Object>emptyMap(), null);
        assertNotNull(result);
    }
}
