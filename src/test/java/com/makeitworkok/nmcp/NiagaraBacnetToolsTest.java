// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraBacnetToolsTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(false, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services"));
    }

    private NiagaraSecurity readOnlySecurity() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services"));
    }

    // -------------------------------------------------------------------------
    // Tool registration
    // -------------------------------------------------------------------------

    @Test
    void tools_returns_two_tools() {
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        List<McpTool> list = tools.tools();
        assertEquals(2, list.size());
    }

    @Test
    void toolNames_areCorrect() {
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        List<McpTool> list = tools.tools();
        assertEquals("nmcp.bacnet.devices",  list.get(0).name());
        assertEquals("nmcp.bacnet.discover", list.get(1).name());
    }

    // -------------------------------------------------------------------------
    // nmcp.bacnet.devices
    // -------------------------------------------------------------------------

    @Test
    void bacnetDevices_missingNetworkOrd_returnsError() {
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        McpToolResult result = tools.tools().get(0).call(
                Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("networkOrd"));
    }

    @Test
    void bacnetDevices_notAllowlisted_returnsError() {
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        Map<String, Object> args = new HashMap<>();
        args.put("networkOrd", "station:|slot:/SomethingElse/BacnetNetwork");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void bacnetDevices_allowlisted_withNullContext_returnsError() {
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        Map<String, Object> args = new HashMap<>();
        args.put("networkOrd", "station:|slot:/Drivers/BacnetNetwork");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void bacnetDevices_stripsLocalPrefix() {
        // local:|station:|slot:/SomethingElse is not allowlisted — should error on allowlist
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        Map<String, Object> args = new HashMap<>();
        args.put("networkOrd", "local:|station:|slot:/SomethingElse/Net");
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void bacnetDevices_inputSchema_requiresNetworkOrd() {
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        String schema = tools.tools().get(0).inputSchema();
        assertTrue(schema.contains("\"networkOrd\""));
        assertTrue(schema.contains("\"required\""));
    }

    // -------------------------------------------------------------------------
    // nmcp.bacnet.discover
    // -------------------------------------------------------------------------

    @Test
    void bacnetDiscover_missingNetworkOrd_returnsError() {
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        McpToolResult result = tools.tools().get(1).call(
                Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("networkOrd"));
    }

    @Test
    void bacnetDiscover_notAllowlisted_returnsError() {
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        Map<String, Object> args = new HashMap<>();
        args.put("networkOrd", "station:|slot:/Hidden/BacnetNetwork");
        McpToolResult result = tools.tools().get(1).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void bacnetDiscover_allowlisted_withNullContext_returnsError() {
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        Map<String, Object> args = new HashMap<>();
        args.put("networkOrd", "station:|slot:/Drivers/BacnetNetwork");
        McpToolResult result = tools.tools().get(1).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void bacnetDiscover_inputSchema_requiresNetworkOrd() {
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        String schema = tools.tools().get(1).inputSchema();
        assertTrue(schema.contains("\"networkOrd\""));
        assertTrue(schema.contains("\"required\""));
    }

    @Test
    void bacnetDiscover_description_mentionsWhoIs() {
        NiagaraBacnetTools tools = new NiagaraBacnetTools(security());
        String desc = tools.tools().get(1).description();
        assertTrue(desc.contains("WhoIs") || desc.contains("whois") || desc.contains("in-memory"));
    }
}
