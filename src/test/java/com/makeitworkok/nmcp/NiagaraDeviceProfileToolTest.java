// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraDeviceProfileToolTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void toolName_isCorrect() {
        NiagaraDeviceProfileTool tools = new NiagaraDeviceProfileTool(security());
        List<McpTool> list = tools.tools();
        assertEquals(1, list.size());
        assertEquals("nmcp.device.profile", list.get(0).name());
    }

    @Test
    void deviceProfile_returnsError_whenDeviceOrdMissing() {
        NiagaraDeviceProfileTool tools = new NiagaraDeviceProfileTool(security());
        McpToolResult result = tools.tools().get(0).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("deviceord"));
    }

    @Test
    void deviceProfile_returnsError_whenDeviceOrdNotAllowlisted() {
        NiagaraDeviceProfileTool tools = new NiagaraDeviceProfileTool(security());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("deviceOrd", "station:|slot:/Hidden/DeviceA");

        McpToolResult result = tools.tools().get(0).call(args, null);

        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("allowlisted")
                || result.getErrorMessage().toLowerCase().contains("path"));
    }

    @Test
    void deviceProfile_inputSchema_containsCoreArguments() {
        NiagaraDeviceProfileTool tools = new NiagaraDeviceProfileTool(security());
        String schema = tools.tools().get(0).inputSchema();
        assertTrue(schema.contains("\"deviceOrd\""));
        assertTrue(schema.contains("\"limit\""));
        assertTrue(schema.contains("\"includeDescendants\""));
        assertTrue(schema.contains("\"required\""));
    }

    @Test
    void deviceProfile_description_mentionsOnboardingIntent() {
        NiagaraDeviceProfileTool tools = new NiagaraDeviceProfileTool(security());
        String description = tools.tools().get(0).description().toLowerCase();
        assertTrue(description.contains("onboard") || description.contains("triage"));
    }
}
