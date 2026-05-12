// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraBuildingBriefToolTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void toolName_isCorrect() {
        NiagaraBuildingBriefTool tool = new NiagaraBuildingBriefTool(security());
        assertEquals(1, tool.tools().size());
        assertEquals("nmcp.building.brief", tool.tools().get(0).name());
    }

    @Test
    void doesNotThrow_whenContextNull() {
        NiagaraBuildingBriefTool tool = new NiagaraBuildingBriefTool(security());
        McpToolResult result = tool.tools().get(0).call(Collections.<String, Object>emptyMap(), null);
        assertNotNull(result);
    }
}