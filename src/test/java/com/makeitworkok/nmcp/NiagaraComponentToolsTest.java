// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraComponentToolsTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void componentSearch_schemaContainsNameFilter() {
        NiagaraComponentTools tools = new NiagaraComponentTools(security(), "0.4.0");
        List<McpTool> list = tools.tools();
        assertEquals(5, list.size());
        assertEquals("nmcp.component.search", list.get(4).name());
        assertTrue(list.get(4).inputSchema().contains("nameFilter"));
    }

    @Test
    void componentChildren_outsideAllowlist_returnsStructuredSecurityError() {
        NiagaraComponentTools tools = new NiagaraComponentTools(security(), "0.8.1");
        McpTool children = tools.tools().get(2);

        McpToolResult result = children.call(
                Collections.<String, Object>singletonMap("ord", "station:|slot:/"),
                null);

        assertTrue(result.isError());
        List<Object> content = result.toMcpContent();
        assertNotNull(content);
        assertFalse(content.isEmpty());

        Object item = content.get(0);
        assertTrue(item instanceof Map);
        Object text = ((Map<?, ?>) item).get("text");
        assertTrue(text instanceof String);

        Map<String, Object> payload = NiagaraJson.parseObject((String) text);
        assertEquals("NMCP_PATH_NOT_ALLOWLISTED", payload.get("code"));
        assertEquals("ord", payload.get("path"));
        assertTrue(String.valueOf(payload.get("hint")).contains("allowlisted root"));

        Object allowedValues = payload.get("allowedValues");
        assertTrue(allowedValues instanceof List);
        assertTrue(((List<?>) allowedValues).contains("station:|slot:/Drivers"));
    }
}