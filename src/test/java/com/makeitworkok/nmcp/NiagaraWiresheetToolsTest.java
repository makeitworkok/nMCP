// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NiagaraWiresheetToolsTest {

    private NiagaraSecurity security() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    private NiagaraSecurity writableSecurity() {
        return new NiagaraSecurity(false, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    @Test
    void wiresheetTools_namesAndCount() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        List<McpTool> list = tools.tools();
        assertEquals(4, list.size());
        assertEquals("nmcp.wiresheet.plan", list.get(0).name());
        assertEquals("nmcp.wiresheet.diff", list.get(1).name());
        assertEquals("nmcp.wiresheet.apply", list.get(2).name());
        assertEquals("nmcp.wiresheet.links", list.get(3).name());
    }

    @Test
    void plan_returnsValidFalse_forStrictNonControlType() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);

        Map<String, Object> createOp = new LinkedHashMap<>();
        createOp.put("type", "createComponent");
        createOp.put("parentOrd", "station:|slot:/Drivers/TestNetwork");
        createOp.put("name", "BadType");
        createOp.put("componentType", "modbusTcp:ModbusClient");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("strict", Boolean.TRUE);
        args.put("operations", Collections.<Object>singletonList(createOp));

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());
        assertTrue(result.getContent().contains("\"valid\":false"));
        assertTrue(result.getContent().contains("unsupported componentType"));
    }

    @Test
    void diff_rejectsNonAllowlistedRoot() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool diff = tools.tools().get(1);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/System");
        args.put("operations", Collections.emptyList());

        McpToolResult result = diff.call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("allowlisted"));
    }

    @Test
    void plan_sortsExecutionCreateThenSetThenLink() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);

        Map<String, Object> link = new LinkedHashMap<>();
        link.put("type", "link");
        link.put("from", "station:|slot:/Drivers/Net/Out1/out");
        link.put("to", "station:|slot:/Drivers/Net/In1/in");

        Map<String, Object> setSlot = new LinkedHashMap<>();
        setSlot.put("type", "setSlot");
        setSlot.put("componentOrd", "station:|slot:/Drivers/Net/Point1");
        setSlot.put("slot", "facets");
        setSlot.put("value", "{}{}");

        Map<String, Object> create = new LinkedHashMap<>();
        create.put("type", "createComponent");
        create.put("parentOrd", "station:|slot:/Drivers/Net");
        create.put("name", "Point1");
        create.put("componentType", "control:NumericPoint");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", Arrays.<Object>asList(link, setSlot, create));

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> executionPlan = (List<Object>) parsed.get("executionPlan");
        assertEquals(3, executionPlan.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) executionPlan.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) executionPlan.get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> third = (Map<String, Object>) executionPlan.get(2);
        assertEquals("createComponent", first.get("type"));
        assertEquals("setSlot", second.get("type"));
        assertEquals("link", third.get("type"));
    }

    @Test
    void apply_rejectsWriteWhenReadOnly_andDryRunFalse() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool apply = tools.tools().get(2);

        Map<String, Object> create = new LinkedHashMap<>();
        create.put("type", "createComponent");
        create.put("parentOrd", "station:|slot:/Drivers/Net");
        create.put("name", "Point1");
        create.put("componentType", "control:NumericPoint");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("dryRun", Boolean.FALSE);
        args.put("operations", Collections.<Object>singletonList(create));

        McpToolResult result = apply.call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("read-only"));
    }

    @Test
    void apply_defaultsToDryRun_andSkipsDuplicateOps() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(writableSecurity());
        McpTool apply = tools.tools().get(2);

        Map<String, Object> create1 = new LinkedHashMap<>();
        create1.put("type", "createComponent");
        create1.put("parentOrd", "station:|slot:/Drivers/Net");
        create1.put("name", "Point1");
        create1.put("componentType", "control:NumericPoint");

        Map<String, Object> create2 = new LinkedHashMap<>();
        create2.put("type", "createComponent");
        create2.put("parentOrd", "station:|slot:/Drivers/Net");
        create2.put("name", "Point1");
        create2.put("componentType", "control:NumericPoint");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", Arrays.<Object>asList(create1, create2));

        McpToolResult result = apply.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        assertEquals(Boolean.TRUE, parsed.get("dryRun"));
        assertEquals(1, ((Number) parsed.get("applied")).intValue());
        assertEquals(1, ((Number) parsed.get("skipped")).intValue());
    }

    @Test
    void apply_nonDryRun_reportsFailureWhenCreateCannotExecuteInStubRuntime() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(writableSecurity());
        McpTool apply = tools.tools().get(2);

        Map<String, Object> create = new LinkedHashMap<>();
        create.put("type", "createComponent");
        create.put("parentOrd", "station:|slot:/Drivers/Net");
        create.put("name", "Point1");
        create.put("componentType", "control:BooleanWritable");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("dryRun", Boolean.FALSE);
        args.put("operations", Collections.<Object>singletonList(create));

        McpToolResult result = apply.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        assertEquals(Boolean.FALSE, parsed.get("dryRun"));
        assertEquals(1, ((Number) parsed.get("failed")).intValue());
    }

    @Test
    void plan_defaultsLinkEndpointsToOutAndIn10_whenSlotsAreOmitted() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);

        Map<String, Object> link = new LinkedHashMap<>();
        link.put("type", "link");
        link.put("from", "station:|slot:/Drivers/Net/SrcPoint");
        link.put("to", "station:|slot:/Drivers/Net/ZoneFanCmd");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", Collections.<Object>singletonList(link));

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) parsed.get("normalizedOperations");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) normalized.get(0);

        assertEquals("station:|slot:/Drivers/Net/SrcPoint/out", first.get("from"));
        assertEquals("station:|slot:/Drivers/Net/ZoneFanCmd/in10", first.get("to"));
    }

    @Test
    void plan_defaultsSetSlotToFallback_whenSlotAndPriorityAreOmitted() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);

        Map<String, Object> set = new LinkedHashMap<>();
        set.put("type", "setSlot");
        set.put("componentOrd", "station:|slot:/Drivers/Net/SrcPoint");
        set.put("value", "true");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", Collections.<Object>singletonList(set));

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) parsed.get("normalizedOperations");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) normalized.get(0);

        assertEquals("fallback", first.get("slot"));
    }

    // ---- v0.5.0 tests ----

    @Test
    void plan_kitControlGreaterThan_passesValidation_inNonStrictMode() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);

        Map<String, Object> createOp = new LinkedHashMap<>();
        createOp.put("type", "createComponent");
        createOp.put("parentOrd", "station:|slot:/Drivers/sandbox");
        createOp.put("name", "CoolComparator");
        createOp.put("componentType", "kitControl:GreaterThan");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("strict", Boolean.FALSE);
        args.put("operations", Collections.<Object>singletonList(createOp));

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());
        assertTrue(result.getContent().contains("\"valid\":true"),
                "Non-strict mode should accept kitControl:GreaterThan");
    }

    @Test
    void plan_kitControlGreaterThan_rejectedInStrictMode() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);

        Map<String, Object> createOp = new LinkedHashMap<>();
        createOp.put("type", "createComponent");
        createOp.put("parentOrd", "station:|slot:/Drivers/sandbox");
        createOp.put("name", "CoolComparator");
        createOp.put("componentType", "kitControl:GreaterThan");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("strict", Boolean.TRUE);
        args.put("operations", Collections.<Object>singletonList(createOp));

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());
        assertTrue(result.getContent().contains("\"valid\":false"),
                "Strict mode should reject kitControl:GreaterThan");
        assertTrue(result.getContent().contains("unsupported componentType"),
                "Error message should mention unsupported componentType");
    }

    @Test
    void plan_facets_preservedInNormalizedOperation() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);

        Map<String, Object> facets = new LinkedHashMap<>();
        facets.put("units", "degreesFahrenheit");
        facets.put("precision", Integer.valueOf(1));
        facets.put("min", Double.valueOf(40.0));
        facets.put("max", Double.valueOf(110.0));

        Map<String, Object> createOp = new LinkedHashMap<>();
        createOp.put("type", "createComponent");
        createOp.put("parentOrd", "station:|slot:/Drivers/sandbox");
        createOp.put("name", "SpaceTemperature");
        createOp.put("componentType", "control:NumericWritable");
        createOp.put("facets", facets);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", Collections.<Object>singletonList(createOp));

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());
        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) parsed.get("normalizedOperations");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) normalized.get(0);

        Object facetsOut = first.get("facets");
        assertTrue(facetsOut != null, "facets must be preserved in normalized operation");
        String facetsStr = String.valueOf(facetsOut);
        assertTrue(facetsStr.contains("degreesFahrenheit"), "units must survive round-trip");
    }

    @Test
    void plan_alphabeticLinkSlots_notGivenDefaultSlotSuffix() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);

        Map<String, Object> link = new LinkedHashMap<>();
        link.put("type", "link");
        link.put("from", "station:|slot:/Drivers/sandbox/SpaceTemperature/out");
        link.put("to", "station:|slot:/Drivers/sandbox/CoolComparator/inA");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", Collections.<Object>singletonList(link));

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) parsed.get("normalizedOperations");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) normalized.get(0);

        String to = String.valueOf(first.get("to"));
        assertTrue(to.endsWith("/inA"),
                "inA slot must not have /in10 appended — got: " + to);
        assertFalse(to.endsWith("/inA/in10"), "inA must not get double suffix");
    }

    @Test
    void plan_thermostat_dryRun_allOperationsValid() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);

        String root = "station:|slot:/Drivers/sandbox/Thermostat";

        // Call 1 — points with facets
        Map<String, Object> facetsF = new LinkedHashMap<>();
        facetsF.put("units", "degreesFahrenheit");
        facetsF.put("precision", Integer.valueOf(1));
        facetsF.put("min", Double.valueOf(40.0));
        facetsF.put("max", Double.valueOf(110.0));

        Map<String, Object> facetsBool = new LinkedHashMap<>();
        facetsBool.put("trueText", "Cooling");
        facetsBool.put("falseText", "Idle");

        List<Object> call1Ops = Arrays.<Object>asList(
                createComp(root, "SpaceTemperature", "control:NumericWritable", facetsF),
                createComp(root, "CoolSetpoint", "control:NumericWritable", facetsF),
                createComp(root, "HeatSetpoint", "control:NumericWritable", facetsF),
                createComp(root, "Deadband", "control:NumericWritable", facetsF),
                createComp(root, "CoolOutput", "control:BooleanWritable", facetsBool),
                createComp(root, "HeatOutput", "control:BooleanWritable", facetsBool));

        Map<String, Object> args1 = new LinkedHashMap<>();
        args1.put("rootOrd", root);
        args1.put("operations", call1Ops);

        McpToolResult r1 = plan.call(args1, null);
        assertFalse(r1.isError(), "Call 1 plan failed: " + r1.getContent());
        assertTrue(r1.getContent().contains("\"valid\":true"), "Call 1 must be valid: " + r1.getContent());

        // Call 3 — kitControl logic (strict=false for kitControl types)
        List<Object> call3Ops = Arrays.<Object>asList(
                createComp(root, "CoolOffset", "kitControl:Add", null),
                createComp(root, "HeatOffset", "kitControl:Subtract", null),
                createComp(root, "CoolComparator", "kitControl:GreaterThan", null),
                createComp(root, "HeatComparator", "kitControl:LessThan", null),
                linkOp(root + "/CoolSetpoint/out", root + "/CoolOffset/inA"),
                linkOp(root + "/Deadband/out", root + "/CoolOffset/inB"),
                linkOp(root + "/SpaceTemperature/out", root + "/CoolComparator/inA"),
                linkOp(root + "/CoolOffset/out", root + "/CoolComparator/inB"),
                linkOp(root + "/CoolComparator/out", root + "/CoolOutput/in16"),
                linkOp(root + "/SpaceTemperature/out", root + "/HeatComparator/inA"),
                linkOp(root + "/HeatOffset/out", root + "/HeatComparator/inB"),
                linkOp(root + "/HeatComparator/out", root + "/HeatOutput/in16"));

        Map<String, Object> args3 = new LinkedHashMap<>();
        args3.put("rootOrd", root);
        args3.put("strict", Boolean.FALSE);
        args3.put("operations", call3Ops);

        McpToolResult r3 = plan.call(args3, null);
        assertFalse(r3.isError(), "Call 3 plan failed: " + r3.getContent());
        assertTrue(r3.getContent().contains("\"valid\":true"), "Call 3 must be valid: " + r3.getContent());
    }

    private static Map<String, Object> createComp(String parentOrd, String name,
                                                   String componentType, Map<String, Object> facets) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("type", "createComponent");
        op.put("parentOrd", parentOrd);
        op.put("name", name);
        op.put("componentType", componentType);
        if (facets != null) {
            op.put("facets", facets);
        }
        return op;
    }

    private static Map<String, Object> linkOp(String from, String to) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("type", "link");
        op.put("from", from);
        op.put("to", to);
        return op;
    }

    // ---- v0.6.0 kitControl expansion tests ----

    /** All 10 HVAC-priority types must pass plan validation in non-strict mode. */
    @Test
    void plan_hvacPriorityTypes_allPassNonStrictValidation() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);
        String parent = "station:|slot:/Drivers/sandbox";

        List<Object> ops = Arrays.<Object>asList(
                createComp(parent, "PidLoop",       "kitControl:LoopPoint",      null),
                createComp(parent, "NumSw",         "kitControl:NumericSwitch",  null),
                createComp(parent, "BoolSw",        "kitControl:BooleanSwitch",  null),
                createComp(parent, "EnumSw",        "kitControl:EnumSwitch",     null),
                createComp(parent, "BoolDly",       "kitControl:BooleanDelay",   null),
                createComp(parent, "NumDly",        "kitControl:NumericDelay",   null),
                createComp(parent, "LinScale",      "kitControl:Line",           null),
                createComp(parent, "RunHours",      "kitControl:Counter",        null),
                createComp(parent, "NumLatch",      "kitControl:NumericLatch",   null),
                createComp(parent, "MuxSel",        "kitControl:MuxSwitch",      null)
        );

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("strict", Boolean.FALSE);
        args.put("operations", ops);

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());
        assertTrue(result.getContent().contains("\"valid\":true"),
                "All 10 HVAC priority types must pass non-strict validation: " + result.getContent());
    }

    /** Companion types added in v0.6.0 expansion also pass non-strict validation. */
    @Test
    void plan_companionTypes_passNonStrictValidation() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);
        String parent = "station:|slot:/Drivers/sandbox";

        List<Object> ops = Arrays.<Object>asList(
                createComp(parent, "OneShot1",  "kitControl:OneShot",       null),
                createComp(parent, "StrSw",     "kitControl:StringSwitch",  null),
                createComp(parent, "BoolLatch", "kitControl:BooleanLatch",  null),
                createComp(parent, "EnumLatch", "kitControl:EnumLatch",     null),
                createComp(parent, "NumSel",    "kitControl:NumericSelect", null),
                createComp(parent, "BoolSel",   "kitControl:BooleanSelect", null),
                createComp(parent, "Nand1",     "kitControl:Nand",          null),
                createComp(parent, "Nor1",      "kitControl:Nor",           null),
                createComp(parent, "Xor1",      "kitControl:Xor",           null),
                createComp(parent, "Neg1",      "kitControl:Negative",      null),
                createComp(parent, "Sqrt1",     "kitControl:SquareRoot",    null),
                createComp(parent, "Deriv1",    "kitControl:Derivative",    null),
                createComp(parent, "MinMaxAvg1","kitControl:MinMaxAvg",     null)
        );

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("strict", Boolean.FALSE);
        args.put("operations", ops);

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());
        assertTrue(result.getContent().contains("\"valid\":true"),
                "Companion types must pass non-strict validation: " + result.getContent());
    }

    /** LoopPoint input slots must not receive the default /in10 suffix. */
    @Test
    void plan_loopPoint_slotNames_notGivenDefaultSuffix() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);
        String root = "station:|slot:/Drivers/sandbox";

        List<Object> ops = Arrays.<Object>asList(
                linkOp(root + "/SpaceTemp/out",  root + "/PidLoop/controlledVariable"),
                linkOp(root + "/Setpoint/out",   root + "/PidLoop/setpoint")
        );

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", ops);

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) parsed.get("normalizedOperations");

        @SuppressWarnings("unchecked")
        Map<String, Object> cvLink = (Map<String, Object>) normalized.get(0);
        String cvTo = String.valueOf(cvLink.get("to"));
        assertTrue(cvTo.endsWith("/controlledVariable"),
                "controlledVariable must not get /in10 appended: " + cvTo);

        @SuppressWarnings("unchecked")
        Map<String, Object> spLink = (Map<String, Object>) normalized.get(1);
        String spTo = String.valueOf(spLink.get("to"));
        assertTrue(spTo.endsWith("/setpoint"),
                "setpoint must not get /in10 appended: " + spTo);
    }

    /** Switch slots (inSwitch, inTrue, inFalse) must not receive the default /in10 suffix. */
    @Test
    void plan_switchSlots_notGivenDefaultSuffix() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);
        String root = "station:|slot:/Drivers/sandbox";

        List<Object> ops = Arrays.<Object>asList(
                linkOp(root + "/OccupancyCmd/out", root + "/CoolSwitch/inSwitch"),
                linkOp(root + "/OccSP/out",        root + "/CoolSwitch/inTrue"),
                linkOp(root + "/UnoccSP/out",      root + "/CoolSwitch/inFalse")
        );

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", ops);

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) parsed.get("normalizedOperations");

        for (Object opObj : normalized) {
            @SuppressWarnings("unchecked")
            Map<String, Object> op = (Map<String, Object>) opObj;
            String to = String.valueOf(op.get("to"));
            assertFalse(to.endsWith("/in10"),
                    "Switch slot must not get /in10 appended: " + to);
        }
    }

    /** BooleanDelay slots (in, onDelay, offDelay, out) must not receive default suffix. */
    @Test
    void plan_booleanDelaySlots_notGivenDefaultSuffix() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);
        String root = "station:|slot:/Drivers/sandbox";

        List<Object> ops = Arrays.<Object>asList(
                linkOp(root + "/FanCmd/out", root + "/FanDelay/in"),
                linkOp(root + "/FanDelay/out", root + "/FanRelay/in16")
        );

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", ops);

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) parsed.get("normalizedOperations");

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) normalized.get(0);
        String firstTo = String.valueOf(first.get("to"));
        assertTrue(firstTo.endsWith("/in"),
                "BooleanDelay 'in' slot must not get /in10 appended: " + firstTo);
    }

    /** Line (linear scale) inA/inB/inC/inD slots must not receive default suffix. */
    @Test
    void plan_lineScaleSlots_notGivenDefaultSuffix() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);
        String root = "station:|slot:/Drivers/sandbox";

        List<Object> ops = Arrays.<Object>asList(
                linkOp(root + "/RawSensor/out", root + "/TempScale/in")
        );

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", ops);

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) parsed.get("normalizedOperations");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) normalized.get(0);
        String to = String.valueOf(first.get("to"));
        assertTrue(to.endsWith("/in"),
                "Line 'in' slot must not get /in10 appended: " + to);
    }

    /** Counter slots (countUp, countDown) must not receive default suffix. */
    @Test
    void plan_counterSlots_notGivenDefaultSuffix() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);
        String root = "station:|slot:/Drivers/sandbox";

        List<Object> ops = Arrays.<Object>asList(
                linkOp(root + "/FanStatus/out", root + "/RunCounter/countUp")
        );

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", ops);

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) parsed.get("normalizedOperations");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) normalized.get(0);
        String to = String.valueOf(first.get("to"));
        assertTrue(to.endsWith("/countUp"),
                "Counter 'countUp' slot must not get /in10 appended: " + to);
    }

    /** NumericSelect inA..inJ slots must not receive default suffix. */
    @Test
    void plan_numericSelectSlots_notGivenDefaultSuffix() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);
        String root = "station:|slot:/Drivers/sandbox";

        List<Object> ops = Arrays.<Object>asList(
                linkOp(root + "/SP1/out", root + "/SpSelect/inA"),
                linkOp(root + "/SP2/out", root + "/SpSelect/inB"),
                linkOp(root + "/SP3/out", root + "/SpSelect/inC")
        );

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", ops);

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) parsed.get("normalizedOperations");
        for (Object opObj : normalized) {
            @SuppressWarnings("unchecked")
            Map<String, Object> op = (Map<String, Object>) opObj;
            String to = String.valueOf(op.get("to"));
            assertFalse(to.endsWith("/in10"),
                    "NumericSelect inA/inB/inC must not get /in10 appended: " + to);
        }
    }

    @Test
    void plan_mapsSetSlotPriorityToInSlot_whenPriorityProvided() {
        NiagaraWiresheetTools tools = new NiagaraWiresheetTools(security());
        McpTool plan = tools.tools().get(0);

        Map<String, Object> set = new LinkedHashMap<>();
        set.put("type", "setSlot");
        set.put("componentOrd", "station:|slot:/Drivers/Net/SrcPoint");
        set.put("priority", Integer.valueOf(10));
        set.put("value", "true");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootOrd", "station:|slot:/Drivers");
        args.put("operations", Collections.<Object>singletonList(set));

        McpToolResult result = plan.call(args, null);
        assertFalse(result.isError());

        Map<String, Object> parsed = NiagaraJson.parseObject(result.getContent());
        @SuppressWarnings("unchecked")
        List<Object> normalized = (List<Object>) parsed.get("normalizedOperations");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) normalized.get(0);

        assertEquals("in10", first.get("slot"));
    }
}