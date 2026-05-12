// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraEquipmentToolsTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void toolName_isCorrect() {
        NiagaraEquipmentTools tools = new NiagaraEquipmentTools(security());
        List<McpTool> list = tools.tools();
        assertEquals(1, list.size());
        assertEquals("nmcp.equipment.status", list.get(0).name());
    }

    @Test
    void equipmentStatus_returnsError_whenContextNull() {
        NiagaraEquipmentTools tools = new NiagaraEquipmentTools(security());
        McpToolResult result = tools.tools().get(0).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError(), "Expected error when context is null");
    }

    @Test
    void equipmentStatus_inputSchema_isValidJson() {
        NiagaraEquipmentTools tools = new NiagaraEquipmentTools(security());
        String schema = tools.tools().get(0).inputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"type\":\"object\""));
        assertTrue(schema.contains("limit"));
    }

    @Test
    void equipmentStatus_description_mentionsDevices() {
        NiagaraEquipmentTools tools = new NiagaraEquipmentTools(security());
        String desc = tools.tools().get(0).description();
        assertTrue(desc.toLowerCase().contains("device"));
    }
}
