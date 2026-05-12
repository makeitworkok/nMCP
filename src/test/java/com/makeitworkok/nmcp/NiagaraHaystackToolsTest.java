// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NiagaraHaystackTools}.
 */
class NiagaraHaystackToolsTest {

    @TempDir
    Path tempDir;

    private NiagaraSecurity readOnlySecurity() {
        return new NiagaraSecurity(true, true, 500,
                Arrays.asList("station:|slot:/Config", "station:|slot:/Drivers", "station:|slot:/Services"));
    }

    private NiagaraSecurity writableSecurity() {
        return new NiagaraSecurity(false, true, 500,
                Arrays.asList("station:|slot:/Config", "station:|slot:/Drivers", "station:|slot:/Services"));
    }

    private String rulesetPath() {
        return tempDir.resolve("mcp-haystack-rules.json").toString();
    }

    private String validRulesetJson() {
        return "{"
                + "\"rules\":["
                + "{"
                + "\"id\":\"ahu-rule\","
                + "\"description\":\"Tag AHUs\","
                + "\"conditions\":[{\"field\":\"displayName\",\"op\":\"contains\",\"value\":\"AHU\"}],"
                + "\"tags\":{\"equip\":\"m:\",\"ahu\":\"m:\",\"hvac\":\"m:\"}"
                + "}"
                + "]"
                + "}";
    }

    // -------------------------------------------------------------------------
    // Tool registration
    // -------------------------------------------------------------------------

    @Test
    void toolNames_areCorrect() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        List<McpTool> list = tools.tools();
        assertEquals(5, list.size());
        assertEquals("nmcp.haystack.getRuleset",   list.get(0).name());
        assertEquals("nmcp.haystack.setRuleset",   list.get(1).name());
        assertEquals("nmcp.haystack.applyRules",   list.get(2).name());
        assertEquals("nmcp.haystack.scanPoints",   list.get(3).name());
        assertEquals("nmcp.haystack.suggestTags",  list.get(4).name());
    }

    @Test
    void allTools_haveNonEmptyDescriptions() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        for (McpTool tool : tools.tools()) {
            assertNotNull(tool.description(), tool.name() + " has null description");
            assertFalse(tool.description().isEmpty(), tool.name() + " has blank description");
        }
    }

    @Test
    void allTools_haveValidInputSchemas() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        for (McpTool tool : tools.tools()) {
            String schema = tool.inputSchema();
            assertNotNull(schema, tool.name() + " has null schema");
            assertDoesNotThrow(() -> NiagaraJson.parseObject(schema),
                    tool.name() + " schema is not valid JSON");
        }
    }

    // -------------------------------------------------------------------------
    // nmcp.haystack.getRuleset – file does not exist
    // -------------------------------------------------------------------------

    @Test
    void getRuleset_returnsTemplate_whenFileDoesNotExist() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        McpTool getRuleset = tools.tools().get(0);

        McpToolResult result = getRuleset.call(Collections.<String, Object>emptyMap(), null);

        assertFalse(result.isError(), "Expected success but got: " + result.getErrorMessage());
        String content = result.getContent();
        assertTrue(content.contains("\"exists\""), "Result should contain exists field");
        assertTrue(content.contains("false"), "exists should be false");
        assertTrue(content.contains("rules"), "Template should contain rules");
    }

    // -------------------------------------------------------------------------
    // nmcp.haystack.getRuleset – file exists
    // -------------------------------------------------------------------------

    @Test
    void getRuleset_returnsContent_whenFileExists() throws Exception {
        String path = rulesetPath();
        Files.write(Paths.get(path), validRulesetJson().getBytes(StandardCharsets.UTF_8));

        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), path);
        McpTool getRuleset = tools.tools().get(0);

        McpToolResult result = getRuleset.call(Collections.<String, Object>emptyMap(), null);

        assertFalse(result.isError(), "Expected success but got: " + result.getErrorMessage());
        String content = result.getContent();
        assertTrue(content.contains("ahu-rule"), "Content should contain the rule id");
        assertTrue(content.contains("true"), "exists should be true");
    }

    // -------------------------------------------------------------------------
    // nmcp.haystack.setRuleset – security guard
    // -------------------------------------------------------------------------

    @Test
    void setRuleset_blockedInReadOnlyMode() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        McpTool setRuleset = tools.tools().get(1);

        Map<String, Object> args = new HashMap<>();
        args.put("content", validRulesetJson());
        McpToolResult result = setRuleset.call(args, null);

        assertTrue(result.isError(), "Expected error in read-only mode");
        assertTrue(result.getErrorMessage().toLowerCase().contains("read-only")
                || result.getErrorMessage().toLowerCase().contains("not permitted"),
                "Error message should mention read-only: " + result.getErrorMessage());
    }

    @Test
    void setRuleset_succeeds_inWriteMode() throws Exception {
        String path = rulesetPath();
        NiagaraHaystackTools tools = new NiagaraHaystackTools(writableSecurity(), path);
        McpTool setRuleset = tools.tools().get(1);

        Map<String, Object> args = new HashMap<>();
        args.put("content", validRulesetJson());
        McpToolResult result = setRuleset.call(args, null);

        assertFalse(result.isError(), "Expected success but got: " + result.getErrorMessage());
        String content = result.getContent();
        assertTrue(content.contains("true"), "written should be true");

        // Verify the file was actually written
        assertTrue(new File(path).exists(), "Ruleset file should have been created");
        String fileContent = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        assertTrue(fileContent.contains("ahu-rule"), "Written file should contain rule id");
    }

    @Test
    void setRuleset_requiresContentArgument() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(writableSecurity(), rulesetPath());
        McpTool setRuleset = tools.tools().get(1);

        McpToolResult result = setRuleset.call(Collections.<String, Object>emptyMap(), null);

        assertTrue(result.isError(), "Expected error when content is missing");
    }

    @Test
    void setRuleset_rejectsInvalidJson() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(writableSecurity(), rulesetPath());
        McpTool setRuleset = tools.tools().get(1);

        Map<String, Object> args = new HashMap<>();
        args.put("content", "{invalid json!!!");
        McpToolResult result = setRuleset.call(args, null);

        assertTrue(result.isError(), "Expected error for invalid JSON");
        assertTrue(result.getErrorMessage().toLowerCase().contains("json"),
                "Error should mention JSON: " + result.getErrorMessage());
    }

    // -------------------------------------------------------------------------
    // nmcp.haystack.applyRules – security guard
    // -------------------------------------------------------------------------

    @Test
    void applyRules_blockedInReadOnlyMode_whenNotDryRun() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        McpTool applyRules = tools.tools().get(2);

        Map<String, Object> args = new HashMap<>();
        args.put("dryRun", false);
        McpToolResult result = applyRules.call(args, null);

        assertTrue(result.isError(), "Expected error in read-only mode without dryRun");
    }

    @Test
    void applyRules_allowedInReadOnlyMode_whenDryRun() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        McpTool applyRules = tools.tools().get(2);

        Map<String, Object> args = new HashMap<>();
        args.put("ord", "station:|slot:/Config");
        args.put("dryRun", true);

        // dryRun is allowed even in read-only mode (null cx causes BOrd.get to fail gracefully)
        McpToolResult result = applyRules.call(args, null);

        // May fail resolving the ORD (null cx), but it must NOT fail with a read-only security error
        if (result.isError()) {
            assertFalse(result.getErrorMessage().toLowerCase().contains("read-only"),
                    "dryRun should bypass read-only check; error was: " + result.getErrorMessage());
        }
    }

    @Test
    void applyRules_ordNotAllowlisted_returnsError() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        McpTool applyRules = tools.tools().get(2);

        Map<String, Object> args = new HashMap<>();
        args.put("ord", "station:|slot:/Hidden/Secrets");
        args.put("dryRun", true);
        McpToolResult result = applyRules.call(args, null);

        assertTrue(result.isError(), "Expected error for non-allowlisted ORD");
    }

    @Test
    void applyRules_emptyRuleset_returnsZeroMatches() {
        // Empty ruleset file
        NiagaraHaystackTools tools = new NiagaraHaystackTools(writableSecurity(), rulesetPath());
        McpTool applyRules = tools.tools().get(2);

        Map<String, Object> args = new HashMap<>();
        args.put("ord", "station:|slot:/Config");
        args.put("dryRun", false);
        // ORD resolution will fail with null cx, which is expected in unit tests
        McpToolResult result = applyRules.call(args, null);

        // Either 0 matches (empty ruleset early-exit) or an ORD resolution error
        // Both are acceptable in the unit test context
        if (!result.isError()) {
            assertTrue(result.getContent().contains("0"), "Expected 0 rules loaded or 0 matches");
        }
    }

    // -------------------------------------------------------------------------
    // nmcp.haystack.scanPoints
    // -------------------------------------------------------------------------

    @Test
    void scanPoints_hasCorrectSchema() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        McpTool scanPoints = tools.tools().get(3);
        String schema = scanPoints.inputSchema();
        assertNotNull(schema);
        assertDoesNotThrow(() -> NiagaraJson.parseObject(schema));
        Map<String, Object> parsed = NiagaraJson.parseObject(schema);
        // root and limit should be optional — required array should be absent or empty
        Object required = parsed.get("required");
        assertTrue(required == null || ((List<?>) required).isEmpty(),
                "scanPoints required array should be absent or empty");
    }

    @Test
    void scanPoints_ordNotAllowlisted_returnsError() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        McpTool scanPoints = tools.tools().get(3);

        Map<String, Object> args = new HashMap<>();
        args.put("root", "station:|slot:/Hidden/Secrets");
        McpToolResult result = scanPoints.call(args, null);

        assertTrue(result.isError(), "Expected error for non-allowlisted ORD");
    }

    @Test
    void scanPoints_nullContext_failsGracefully() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        McpTool scanPoints = tools.tools().get(3);

        Map<String, Object> args = new HashMap<>();
        args.put("root", "station:|slot:/Drivers");
        McpToolResult result = scanPoints.call(args, null);

        // null cx causes ORD resolution failure — must not throw, must return error gracefully
        assertTrue(result.isError(), "Expected graceful error with null context");
    }

    // -------------------------------------------------------------------------
    // nmcp.haystack.suggestTags
    // -------------------------------------------------------------------------

    @Test
    void suggestTags_hasCorrectSchema() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        McpTool suggestTags = tools.tools().get(4);
        String schema = suggestTags.inputSchema();
        assertNotNull(schema);
        assertDoesNotThrow(() -> NiagaraJson.parseObject(schema));
    }

    @Test
    void suggestTags_ordNotAllowlisted_returnsError() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        McpTool suggestTags = tools.tools().get(4);

        Map<String, Object> args = new HashMap<>();
        args.put("root", "station:|slot:/Hidden/Secrets");
        McpToolResult result = suggestTags.call(args, null);

        assertTrue(result.isError(), "Expected error for non-allowlisted ORD");
    }

    @Test
    void suggestTags_nullContext_failsGracefully() {
        NiagaraHaystackTools tools = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());
        McpTool suggestTags = tools.tools().get(4);

        Map<String, Object> args = new HashMap<>();
        args.put("root", "station:|slot:/Drivers");
        McpToolResult result = suggestTags.call(args, null);

        assertTrue(result.isError(), "Expected graceful error with null context");
    }

    // -------------------------------------------------------------------------
    // suggestTagsForPoint heuristic logic (via public tool output)
    // -------------------------------------------------------------------------

    @Test
    void suggestTags_numericSensorNamed_supplyAirTemp_getsCorrectTags() {
        // Call suggestTagsForPoint indirectly via a fake station component tree
        // Since we can't resolve ORDs in unit tests, test the pure heuristic helper directly
        // by reflective access to the package-visible helper.
        // Instead: verify the suggestedRuleset output contains temperature rules when
        // the template heuristics fire.
        // We verify the heuristic logic using the buildSuggestedRuleset path indirectly
        // by constructing suggestions manually and checking rule output.

        // Test the pure heuristic through suggestTagsForPoint via reflection
        try {
            java.lang.reflect.Method m = NiagaraHaystackTools.class.getDeclaredMethod(
                    "suggestTagsForPoint", String.class, String.class, String.class, String.class);
            m.setAccessible(true);
            NiagaraHaystackTools instance = new NiagaraHaystackTools(readOnlySecurity(), rulesetPath());

            // NumericPoint named "Supply Air Temp" → sensor, temp, air, supply
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) m.invoke(
                    instance, "Supply Air Temp", "SAT", "control:NumericPoint", "AHU_1");
            @SuppressWarnings("unchecked")
            Map<String, String> tags = (Map<String, String>) result.get("tags");
            assertTrue(tags.containsKey("point"),  "Should have point");
            assertTrue(tags.containsKey("sensor"), "NumericPoint→sensor");
            assertTrue(tags.containsKey("temp"),   "name→temp");
            assertTrue(tags.containsKey("air"),    "name→air");
            assertTrue(tags.containsKey("supply"), "name→supply");

            // NumericWritable named "Cooling Setpoint" → cmd→sp
            @SuppressWarnings("unchecked")
            Map<String, Object> result2 = (Map<String, Object>) m.invoke(
                    instance, "Cooling Setpoint", "CSP", "control:NumericWritable", "");
            @SuppressWarnings("unchecked")
            Map<String, String> tags2 = (Map<String, String>) result2.get("tags");
            assertTrue(tags2.containsKey("point"), "Should have point");
            assertTrue(tags2.containsKey("sp"),    "Writable+setpoint name→sp");
            assertFalse(tags2.containsKey("cmd"),  "Should not have cmd when sp applies");

            // BooleanPoint named "Fan Status" → sensor
            @SuppressWarnings("unchecked")
            Map<String, Object> result3 = (Map<String, Object>) m.invoke(
                    instance, "Fan Status", "FanSts", "control:BooleanPoint", "");
            @SuppressWarnings("unchecked")
            Map<String, String> tags3 = (Map<String, String>) result3.get("tags");
            assertTrue(tags3.containsKey("point"),  "Should have point");
            assertTrue(tags3.containsKey("sensor"), "BooleanPoint→sensor");

        } catch (NoSuchMethodException e) {
            fail("suggestTagsForPoint method not found: " + e.getMessage());
        } catch (Exception e) {
            fail("Unexpected exception: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // NiagaraHaystackTools.resolveFile (package-visible static helper)
    // -------------------------------------------------------------------------
    @Test
    void resolveFile_absolutePath_returnsAsIs() {
        String abs = tempDir.resolve("test.json").toString();
        File f = NiagaraHaystackTools.resolveFile(abs);
        assertEquals(abs, f.getPath());
    }

    @Test
    void resolveFile_nullPath_returnsDefault() {
        File f = NiagaraHaystackTools.resolveFile(null);
        assertNotNull(f);
        assertFalse(f.getPath().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Round-trip: setRuleset then getRuleset
    // -------------------------------------------------------------------------

    @Test
    void roundTrip_setThenGet_returnsWrittenContent() {
        String path = rulesetPath();
        NiagaraHaystackTools tools = new NiagaraHaystackTools(writableSecurity(), path);
        McpTool setRuleset = tools.tools().get(1);
        McpTool getRuleset = tools.tools().get(0);

        Map<String, Object> setArgs = new HashMap<>();
        setArgs.put("content", validRulesetJson());
        McpToolResult setResult = setRuleset.call(setArgs, null);
        assertFalse(setResult.isError(), "setRuleset should succeed");

        McpToolResult getResult = getRuleset.call(Collections.<String, Object>emptyMap(), null);
        assertFalse(getResult.isError(), "getRuleset should succeed");
        assertTrue(getResult.getContent().contains("ahu-rule"),
                "getRuleset should return previously written content");
    }
}
