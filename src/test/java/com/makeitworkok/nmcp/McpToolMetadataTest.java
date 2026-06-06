// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpToolMetadataTest {

    @Test
    void allRegisteredTools_haveAgentUsefulDescriptionsAndValidSchemas() {
        List<McpTool> tools = allTools();
        assertEquals(41, tools.size());

        for (McpTool tool : tools) {
            String description = tool.description();
            assertNotNull(description, tool.name());
            assertTrue(description.trim().length() >= 40,
                    tool.name() + " should have a useful client-facing description");
            assertFalse(description.toLowerCase(Locale.ROOT).contains("todo"), tool.name());

            Map<String, Object> schema = NiagaraJson.parseObject(tool.inputSchema());
            assertEquals("object", schema.get("type"), tool.name());
            assertTrue(schema.containsKey("properties"), tool.name());
        }
    }

    @Test
    void schemaProperties_haveDescriptionsForClientArguments() {
        for (McpTool tool : allTools()) {
            Map<String, Object> schema = NiagaraJson.parseObject(tool.inputSchema());
            Object propertiesObj = schema.get("properties");
            assertTrue(propertiesObj instanceof Map, tool.name());

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) propertiesObj;
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                assertTrue(entry.getValue() instanceof Map,
                        tool.name() + " property " + entry.getKey() + " should be an object schema");
                @SuppressWarnings("unchecked")
                Map<String, Object> propertySchema = (Map<String, Object>) entry.getValue();
                Object description = propertySchema.get("description");
                assertTrue(description instanceof String && ((String) description).trim().length() >= 20,
                        tool.name() + " property " + entry.getKey() + " needs a client-facing description");
            }
        }
    }

    @Test
    void writeModeTools_callOutMutationOrSafeDebugMode() {
        assertDescriptionContains("nmcp.point.write", "write-mode", "in10", "null");
        assertDescriptionContains("nmcp.point.override", "write-mode", "in8", "null");
        assertDescriptionContains("nmcp.alarm.ack", "write-mode", "source ord");
        assertDescriptionContains("nmcp.schedule.write", "write-mode", "defaultoutput");
        assertDescriptionContains("nmcp.history.provisionOnPoint", "write-mode", "debug=true");
        assertDescriptionContains("nmcp.station.restart", "write-mode", "restart");
        assertDescriptionContains("nmcp.driver.discoverAndAdd", "write-mode", "asynchronous");
        assertDescriptionContains("nmcp.wiresheet.apply", "dry-run", "write-mode", "later call");
        assertDescriptionContains("nmcp.wiresheet.layout", "dry-run", "write mode");
        assertDescriptionContains("nmcp.haystack.setRuleset", "write-mode", "ruleset");
        assertDescriptionContains("nmcp.haystack.applyRules", "dryrun=true", "write mode");
    }

    private void assertDescriptionContains(String toolName, String... expected) {
        McpTool tool = findTool(toolName);
        String description = tool.description().toLowerCase(Locale.ROOT);
        for (String value : expected) {
            assertTrue(description.contains(value.toLowerCase(Locale.ROOT)),
                    toolName + " description should contain " + value + ": " + tool.description());
        }
    }

    private McpTool findTool(String name) {
        for (McpTool tool : allTools()) {
            if (name.equals(tool.name())) {
                return tool;
            }
        }
        fail("Tool not found: " + name);
        return null;
    }

    private List<McpTool> allTools() {
        NiagaraSecurity security = new NiagaraSecurity(true, true, 500, Arrays.asList(
                "station:|slot:/Drivers",
                "station:|slot:/Services",
                "station:|slot:/Config"));

        List<McpTool> tools = new ArrayList<>();
        tools.addAll(new NiagaraComponentTools(security, BMcpService.MODULE_VERSION).tools());
        tools.addAll(new NiagaraStationTools(security).tools());
        tools.addAll(new NiagaraBqlTools(security).tools());
        tools.addAll(new NiagaraAlarmTools(security).tools());
        tools.addAll(new NiagaraHistoryTools(security).tools());
        tools.addAll(new NiagaraHistoryWriteTools(security).tools());
        tools.addAll(new NiagaraBacnetTools(security).tools());
        tools.addAll(new NiagaraScheduleTools(security).tools());
        tools.addAll(new NiagaraPointTools(security).tools());
        tools.addAll(new NiagaraEquipmentTools(security).tools());
        tools.addAll(new NiagaraDeviceProfileTool(security).tools());
        tools.addAll(new NiagaraFaultScanTool(security).tools());
        tools.addAll(new NiagaraBuildingBriefTool(security).tools());
        tools.addAll(new NiagaraHaystackTools(security, "haystack-rules.json").tools());
        tools.addAll(new NiagaraWiresheetTools(security).tools());
        tools.addAll(new NiagaraWriteTools(security).tools());
        return tools;
    }
}
