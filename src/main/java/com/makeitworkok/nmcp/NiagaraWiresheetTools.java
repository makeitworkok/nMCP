// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.BValue;
import javax.baja.sys.Context;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v0.4 scaffold for wiresheet automation.
 *
 * <p>This first pass is intentionally validator-only and read-only:
 * plan/diff evaluate payloads but do not mutate the station.
 */
public final class NiagaraWiresheetTools {

    private final NiagaraSecurity security;
    private static final List<Object> ALLOWED_OPERATION_TYPES = Collections.unmodifiableList(
            Arrays.<Object>asList("createComponent", "setSlot", "link", "addCompositePin"));
    private static final Map<String, List<Object>> REQUIRED_FIELDS_BY_TYPE = buildRequiredFieldsByType();
    private static final Map<String, List<Object>> OPTIONAL_FIELDS_BY_TYPE = buildOptionalFieldsByType();

    public NiagaraWiresheetTools(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(wiresheetPlan());
        list.add(wiresheetDiff());
        list.add(wiresheetApply());
        list.add(wiresheetSchema());
        list.add(wiresheetLinks());
        list.add(wiresheetLayout());
        return list;
    }

        private McpTool wiresheetSchema() {
        return new McpTool() {
            @Override public String name() { return "nmcp.wiresheet.schema"; }

            @Override public String description() {
            return "Returns authoritative wiresheet operation schema for autonomous clients. "
                + "Read-only introspection tool.";
            }

            @Override public String inputSchema() {
            return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
            String sampleRoot = "station:|slot:/Drivers/sandbox";
            List<Object> sampleOps = new ArrayList<>();
            sampleOps.add(NiagaraJson.obj(
                "type", "createComponent",
                "parentOrd", sampleRoot,
                "name", "SpaceTemperature",
                "componentType", "control:NumericWritable"));
            sampleOps.add(NiagaraJson.obj(
                "type", "setSlot",
                "componentOrd", sampleRoot + "/SpaceTemperature",
                "slot", "in10",
                "value", Double.valueOf(72.0)));
            sampleOps.add(NiagaraJson.obj(
                "type", "link",
                "from", sampleRoot + "/SpaceTemperature/out",
                "to", sampleRoot + "/CoolingCall/in10"));
            sampleOps.add(NiagaraJson.obj(
                "type", "addCompositePin",
                "folderOrd", sampleRoot,
                "pinName", "TemperatureIn",
                "targetComponentOrd", sampleRoot + "/SpaceTemperature",
                "targetSlot", "in10",
                "direction", "in"));

            Map<String, Object> operationSchema = new LinkedHashMap<>();
            for (Object opTypeObj : ALLOWED_OPERATION_TYPES) {
                String opType = String.valueOf(opTypeObj);
                operationSchema.put(opType, NiagaraJson.obj(
                    "required", REQUIRED_FIELDS_BY_TYPE.get(opType),
                    "optional", OPTIONAL_FIELDS_BY_TYPE.get(opType)));
            }

            return McpToolResult.success(NiagaraJson.obj(
                "operationTypes", ALLOWED_OPERATION_TYPES,
                "operations", operationSchema,
                "minimalValidPayload", NiagaraJson.obj(
                    "rootOrd", sampleRoot,
                    "strict", Boolean.TRUE,
                    "dryRun", Boolean.TRUE,
                    "operations", sampleOps)));
            }
        };
        }

    private McpTool wiresheetLinks() {
        return new McpTool() {
            @Override public String name() { return "nmcp.wiresheet.links"; }

            @Override public String description() {
                return "Inspects runtime wiresheet links for a component and optional slot. "
                        + "Read-only diagnostic tool for validating applied links.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\"," 
                        + "\"properties\":{"
                        + "  \"componentOrd\":{\"type\":\"string\",\"description\":\"Component ORD to inspect\"},"
                        + "  \"slot\":{\"type\":\"string\",\"description\":\"Optional slot name filter (for example out or in10)\"}"
                        + "},"
                        + "\"required\":[\"componentOrd\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String componentOrd = asString(arguments.get("componentOrd"));
                String slot = asString(arguments.get("slot"));
                if (componentOrd == null || componentOrd.trim().isEmpty()) {
                    return McpToolResult.error("Missing required argument: componentOrd");
                }
                try {
                    security.checkAllowlist(componentOrd);
                    Object componentObj = BOrd.make(localOrd(componentOrd)).get(null, cx);
                    if (!(componentObj instanceof BComponent)) {
                        return McpToolResult.error("component ORD is not a component: " + componentOrd);
                    }

                    Object slotObj = null;
                    if (slot != null && !slot.trim().isEmpty()) {
                        slotObj = resolveSlotObject(componentObj, slot);
                        if (slotObj == null) {
                            return McpToolResult.success(NiagaraJson.obj(
                                    "componentOrd", componentOrd,
                                    "slot", slot,
                                    "slotFound", Boolean.FALSE,
                                    "linkCount", Integer.valueOf(0),
                                    "links", new ArrayList<Object>()));
                        }
                    }

                    List<Object> links = collectLinks(componentObj, slotObj);
                    return McpToolResult.success(NiagaraJson.obj(
                            "componentOrd", componentOrd,
                            "slot", slot,
                            "slotFound", Boolean.valueOf(slotObj != null || slot == null || slot.trim().isEmpty()),
                            "linkCount", Integer.valueOf(links.size()),
                            "links", links));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    return McpToolResult.error("runtime link inspection error: " + e.getMessage());
                }
            }
        };
    }

    private McpTool wiresheetLayout() {
        return new McpTool() {
            @Override public String name() { return "nmcp.wiresheet.layout"; }

            @Override public String description() {
                return "Applies deterministic readability layout rules (no-overlap, left-to-right flow, "
                        + "comment proximity) by setting wsAnnotation. Dry-run default.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\"," 
                        + "\"properties\":{"
                        + "  \"rootOrd\":{\"type\":\"string\",\"description\":\"Container ORD whose child components are laid out\"},"
                        + "  \"dryRun\":{\"type\":\"boolean\",\"description\":\"If true, only return planned moves (default true)\"},"
                        + "  \"originX\":{\"type\":\"integer\",\"description\":\"Grid origin X (default 1)\"},"
                        + "  \"originY\":{\"type\":\"integer\",\"description\":\"Grid origin Y (default 1)\"},"
                        + "  \"spacingX\":{\"type\":\"integer\",\"description\":\"Horizontal grid spacing (default 24)\"},"
                        + "  \"spacingY\":{\"type\":\"integer\",\"description\":\"Vertical grid spacing (default 4)\"},"
                        + "  \"width\":{\"type\":\"integer\",\"description\":\"wsAnnotation width (default 20)\"},"
                        + "  \"height\":{\"type\":\"integer\",\"description\":\"wsAnnotation height (default 2)\"},"
                        + "  \"components\":{\"type\":\"array\",\"description\":\"Optional explicit components for planning/tests\"},"
                        + "  \"links\":{\"type\":\"array\",\"description\":\"Optional explicit links for topological layering\"}"
                        + "},"
                        + "\"required\":[\"rootOrd\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                if (arguments == null) {
                    return McpToolResult.error("Missing required arguments object");
                }

                String rootOrd = asString(arguments.get("rootOrd"));
                if (rootOrd == null || rootOrd.trim().isEmpty()) {
                    return McpToolResult.error("Missing required argument: rootOrd");
                }

                try {
                    security.checkAllowlist(rootOrd);
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                }

                Object rawDryRun = arguments.get("dryRun");
                boolean dryRun = rawDryRun == null ? true : asBoolean(rawDryRun);
                if (!dryRun) {
                    try {
                        security.checkReadOnly();
                    } catch (NiagaraSecurity.McpSecurityException e) {
                        return McpToolResult.error(e.getMessage());
                    }
                }

                int originX = intOrDefault(arguments.get("originX"), 1);
                int originY = intOrDefault(arguments.get("originY"), 1);
                int spacingX = Math.max(1, intOrDefault(arguments.get("spacingX"), 24));
                int spacingY = Math.max(1, intOrDefault(arguments.get("spacingY"), 4));
                int width = Math.max(1, intOrDefault(arguments.get("width"), 20));
                int height = Math.max(1, intOrDefault(arguments.get("height"), 2));

                List<LayoutNode> nodes;
                try {
                    nodes = readLayoutNodes(arguments.get("components"), rootOrd, cx);
                } catch (Throwable e) {
                    return McpToolResult.error("layout node discovery failed: " + e.getMessage());
                }

                if (nodes.isEmpty()) {
                    return McpToolResult.success(NiagaraJson.obj(
                            "rootOrd", rootOrd,
                            "dryRun", Boolean.valueOf(dryRun),
                            "mode", "rules-v1",
                            "componentCount", Integer.valueOf(0),
                            "changed", Integer.valueOf(0),
                            "unchanged", Integer.valueOf(0),
                            "moves", new ArrayList<Object>()));
                }

                Map<String, LayoutNode> byOrd = new LinkedHashMap<>();
                for (LayoutNode node : nodes) {
                    byOrd.put(node.ord, node);
                }

                List<LayoutEdge> edges = readLayoutEdges(arguments.get("links"), byOrd);
                assignLayers(nodes, edges, byOrd);
                assignGridPositions(nodes, edges, originX, originY, spacingX, spacingY, width, height);

                List<Object> moves = new ArrayList<>();
                int changed = 0;
                int unchanged = 0;
                int failed = 0;

                for (LayoutNode node : nodes) {
                    boolean isChanged = node.targetWsAnnotation != null
                            && !node.targetWsAnnotation.equals(node.currentWsAnnotation);
                    if (isChanged) {
                        changed++;
                    } else {
                        unchanged++;
                    }

                    if (dryRun || !isChanged) {
                        moves.add(NiagaraJson.obj(
                                "ord", node.ord,
                                "name", node.name,
                                "type", node.type,
                                "isComment", Boolean.valueOf(node.comment),
                                "layer", Integer.valueOf(node.layer),
                                "from", node.currentWsAnnotation,
                                "to", node.targetWsAnnotation,
                                "status", isChanged ? "planned" : "unchanged"));
                        continue;
                    }

                    try {
                        security.checkAllowlist(node.ord);
                        Map<String, Object> op = NiagaraJson.obj(
                                "index", Integer.valueOf(-1),
                                "type", "setSlot",
                                "componentOrd", node.ord,
                                "slot", "wsAnnotation",
                                "value", node.targetWsAnnotation);
                        Map<String, Object> step = executeSetSlot(op, cx);
                        String status = asString(step.get("status"));
                        if (!"applied".equals(status)) {
                            failed++;
                        }
                        moves.add(NiagaraJson.obj(
                                "ord", node.ord,
                                "name", node.name,
                                "type", node.type,
                                "isComment", Boolean.valueOf(node.comment),
                                "layer", Integer.valueOf(node.layer),
                                "from", node.currentWsAnnotation,
                                "to", node.targetWsAnnotation,
                                "status", status,
                                "reason", step.get("reason")));
                    } catch (Throwable e) {
                        failed++;
                        moves.add(NiagaraJson.obj(
                                "ord", node.ord,
                                "name", node.name,
                                "type", node.type,
                                "isComment", Boolean.valueOf(node.comment),
                                "layer", Integer.valueOf(node.layer),
                                "from", node.currentWsAnnotation,
                                "to", node.targetWsAnnotation,
                                "status", "failed",
                                "reason", e.getMessage()));
                    }
                }

                Map<String, Object> result = NiagaraJson.obj(
                        "rootOrd", rootOrd,
                        "dryRun", Boolean.valueOf(dryRun),
                        "mode", "rules-v1",
                        "componentCount", Integer.valueOf(nodes.size()),
                        "linkCount", Integer.valueOf(edges.size()),
                        "changed", Integer.valueOf(changed),
                        "unchanged", Integer.valueOf(unchanged),
                        "failed", Integer.valueOf(failed),
                        "moves", moves);
                if (edges.isEmpty()) {
                    result.put("warning", "No links supplied; used deterministic name-based layering.");
                }
                return McpToolResult.success(result);
            }
        };
    }

    private McpTool wiresheetPlan() {
        return new McpTool() {
            @Override public String name() { return "nmcp.wiresheet.plan"; }

            @Override public String description() {
                return "Validates and normalizes a declarative wiresheet operation list. "
                        + "Read-only and safe for dry-run planning.";
            }

            @Override public String inputSchema() {
                return baseInputSchema();
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                ValidationResult vr = validate(arguments);
                if (!vr.errors.isEmpty()) {
                    return validationError(vr.errors.get(0));
                }
                List<Map<String, Object>> plan = sortForExecution(vr.normalizedOperations);
                return McpToolResult.success(NiagaraJson.obj(
                        "rootOrd", vr.rootOrd,
                        "strict", Boolean.valueOf(vr.strict),
                        "valid", Boolean.TRUE,
                        "errors", new ArrayList<Object>(),
                        "warnings", vr.warnings,
                        "summary", NiagaraJson.obj(
                                "operationCount", Integer.valueOf(vr.normalizedOperations.size()),
                                "createCount", Integer.valueOf(countType(vr.normalizedOperations, "createComponent")),
                                "updateCount", Integer.valueOf(countType(vr.normalizedOperations, "setSlot")),
                                "linkCount", Integer.valueOf(countType(vr.normalizedOperations, "link"))),
                        "normalizedOperations", vr.normalizedOperations,
                        "executionPlan", plan));
            }
        };
    }

    private McpTool wiresheetDiff() {
        return new McpTool() {
            @Override public String name() { return "nmcp.wiresheet.diff"; }

            @Override public String description() {
                return "Computes a deterministic desired-state diff from declarative wiresheet operations. "
                        + "Read-only scaffold for v0.4 MVP.";
            }

            @Override public String inputSchema() {
                return baseInputSchema();
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                ValidationResult vr = validate(arguments);
                if (!vr.errors.isEmpty()) {
                    return validationError(vr.errors.get(0));
                }

                List<Object> creates = new ArrayList<>();
                List<Object> updates = new ArrayList<>();
                List<Object> linksToAdd = new ArrayList<>();
                List<Object> alreadyPresent = new ArrayList<>();

                for (Map<String, Object> op : vr.normalizedOperations) {
                    String type = asString(op.get("type"));
                    if ("createComponent".equals(type)) {
                        creates.add(op);
                    } else if ("setSlot".equals(type)) {
                        updates.add(op);
                    } else if ("link".equals(type)) {
                        linksToAdd.add(op);
                    }
                }

                return McpToolResult.success(NiagaraJson.obj(
                        "rootOrd", vr.rootOrd,
                        "strict", Boolean.valueOf(vr.strict),
                    "valid", Boolean.TRUE,
                    "errors", new ArrayList<Object>(),
                        "warnings", vr.warnings,
                        "creates", creates,
                        "updates", updates,
                        "linksToAdd", linksToAdd,
                        "alreadyPresent", alreadyPresent,
                        "summary", NiagaraJson.obj(
                                "createCount", Integer.valueOf(creates.size()),
                                "updateCount", Integer.valueOf(updates.size()),
                                "linkCount", Integer.valueOf(linksToAdd.size()),
                                "alreadyPresentCount", Integer.valueOf(alreadyPresent.size()))));
            }
        };
    }

    private McpTool wiresheetApply() {
        return new McpTool() {
            @Override public String name() { return "nmcp.wiresheet.apply"; }

            @Override public String description() {
                return "Applies declarative wiresheet operations with dry-run default and write-mode gating. "
                        + "Current v0.4 phase is scaffolded for deterministic execution reporting.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"rootOrd\":{\"type\":\"string\",\"description\":\"Allowlisted root ORD for apply scope\"},"
                        + "  \"operations\":{\"type\":\"array\",\"description\":\"Declarative operation list. "
                        + "Operation types: createComponent, setSlot, link, addCompositePin. "
                        + "createComponent: requires parentOrd, name, componentType. "
                        + "Supported componentType values: "
                        + "control:NumericWritable, control:BooleanWritable, control:EnumWritable, control:StringWritable, "
                        + "baja:Folder, baja:TextBlock, "
                        + "kitControl:LoopPoint, "
                        + "kitControl:NumericSwitch, kitControl:BooleanSwitch, kitControl:EnumSwitch, kitControl:StringSwitch, kitControl:MuxSwitch, "
                        + "kitControl:BooleanDelay, kitControl:NumericDelay, kitControl:OneShot, "
                        + "kitControl:Line, "
                        + "kitControl:Counter, kitControl:Ramp, kitControl:MinMaxAvg, "
                        + "kitControl:BooleanLatch, kitControl:NumericLatch, kitControl:EnumLatch, kitControl:StringLatch, "
                        + "kitControl:NumericSelect, kitControl:BooleanSelect, kitControl:EnumSelect, kitControl:StringSelect, "
                        + "kitControl:Add, kitControl:Subtract, kitControl:Multiply, kitControl:Divide, "
                        + "kitControl:Average, kitControl:Minimum, kitControl:Maximum, kitControl:AbsValue, kitControl:Limiter, "
                        + "kitControl:Negative, kitControl:Modulus, kitControl:SquareRoot, kitControl:Derivative, "
                        + "kitControl:GreaterThan, kitControl:LessThan, kitControl:GreaterThanEqual, kitControl:LessThanEqual, "
                        + "kitControl:Equal, kitControl:NotEqual, kitControl:Hysteresis, "
                        + "kitControl:And, kitControl:Or, kitControl:Not, kitControl:Nand, kitControl:Nor, kitControl:Xor. "
                        + "Slot names by type: "
                        + "LoopPoint: controlledVariable (CV), setpoint (SP), out; setSlot tuning: proportionalConstant, integralConstant, derivativeConstant, bias, maximumOutput, minimumOutput, loopAction (Direct/Reverse). "
                        + "NumericSwitch/BooleanSwitch/EnumSwitch: inSwitch (bool selector), inTrue, inFalse, out. "
                        + "BooleanDelay: in, onDelay, offDelay, out, outNot. "
                        + "NumericDelay: in, updateTime, maxStepSize, out. "
                        + "OneShot: in, time, out, outNot. "
                        + "Line (linear scale): inA=x1, inB=y1, inC=x2, inD=y2, in (live input), out; limitBelow/limitAbove clamp. "
                        + "Counter: countUp, countDown, presetValue, countIncrement, clearIn, out. "
                        + "NumericSelect/BooleanSelect/EnumSelect: inA..inJ (up to 10 inputs), out. "
                        + "Math/Logic blocks: inA, inB (up to inD for quad blocks). "
                        + "Optional facets: units (string, e.g. degreesFahrenheit), precision (int), min (number), max (number), trueText (string), falseText (string). "
                        + "addCompositePin: requires folderOrd, pinName, targetComponentOrd, targetSlot, "
                        + "direction ('in' or 'out'). pinName must not collide with any existing child component name.\"},"
                        + "  \"strict\":{\"type\":\"boolean\",\"description\":\"Enable strict type checks (default true). "
                        + "When false, kitControl: and baja: prefix types pass validation.\"},"
                        + "  \"dryRun\":{\"type\":\"boolean\",\"description\":\"If true, no station mutation is attempted (default true)\"},"
                        + "  \"requestId\":{\"type\":\"string\",\"description\":\"Optional correlation id for logs/replay\"}"
                        + "},"
                        + "\"required\":[\"rootOrd\",\"operations\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                ValidationResult vr = validate(arguments);
                if (!vr.errors.isEmpty()) {
                    return validationError(vr.errors.get(0));
                }

                Object rawDryRun = arguments.get("dryRun");
                boolean dryRun = rawDryRun == null ? true : asBoolean(rawDryRun);
                String requestId = asString(arguments.get("requestId"));

                if (!dryRun) {
                    try {
                        security.checkReadOnly();
                    } catch (NiagaraSecurity.McpSecurityException e) {
                        return securityError(
                                e,
                                "arguments.dryRun",
                                "Set dryRun=true for planning, or set BMcpService.readOnly=false to allow writes.");
                    }
                }

                List<Map<String, Object>> executionPlan = sortForExecution(vr.normalizedOperations);
                List<Object> stepResults = new ArrayList<>();
                Map<String, Integer> seen = new LinkedHashMap<>();
                int applied = 0;
                int skipped = 0;
                int failed = 0;

                for (Map<String, Object> op : executionPlan) {
                    String key = operationKey(op);
                    Integer previous = seen.get(key);
                    if (previous != null) {
                        skipped++;
                        stepResults.add(NiagaraJson.obj(
                                "index", op.get("index"),
                                "type", op.get("type"),
                                "status", "skipped",
                                "reason", "duplicate operation in request",
                                "duplicateOf", previous));
                        continue;
                    }
                    seen.put(key, asInt(op.get("index")));

                    if (dryRun) {
                        applied++;
                        stepResults.add(NiagaraJson.obj(
                                "index", op.get("index"),
                                "type", op.get("type"),
                                "status", "planned",
                                "dryRun", Boolean.TRUE));
                    } else {
                        Map<String, Object> step = executeApplyOperation(op, cx);
                        String status = asString(step.get("status"));
                        if ("applied".equals(status)) {
                            applied++;
                        } else if ("skipped".equals(status)) {
                            skipped++;
                        } else {
                            failed++;
                        }
                        stepResults.add(step);
                    }
                }

                return McpToolResult.success(NiagaraJson.obj(
                        "rootOrd", vr.rootOrd,
                        "requestId", requestId,
                        "strict", Boolean.valueOf(vr.strict),
                        "dryRun", Boolean.valueOf(dryRun),
                        "applied", Integer.valueOf(applied),
                        "skipped", Integer.valueOf(skipped),
                        "failed", Integer.valueOf(failed),
                        "stepResults", stepResults));
            }
        };
    }

    private Map<String, Object> executeApplyOperation(Map<String, Object> op, Context cx) {
        String type = asString(op.get("type"));
        if ("createComponent".equals(type)) {
            return executeCreateComponent(op, cx);
        }
        if ("setSlot".equals(type)) {
            return executeSetSlot(op, cx);
        }
        if ("link".equals(type)) {
            return executeLink(op, cx);
        }
        if ("addCompositePin".equals(type)) {
            return executeAddCompositePin(op, cx);
        }
        return NiagaraJson.obj(
                "index", op.get("index"),
                "type", type,
                "status", "failed",
                "reason", "unsupported operation type");
    }

    private Map<String, Object> executeSetSlot(Map<String, Object> op, Context cx) {
        String componentOrd = asString(op.get("componentOrd"));
        String slot = normalizeSetSlot(asString(op.get("slot")), asPriority(op.get("priority")));
        Object value = op.get("value");
        try {
            Object componentObj = BOrd.make(localOrd(componentOrd)).get(null, cx);
            if (!(componentObj instanceof BComponent)) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "setSlot",
                        "status", "failed",
                        "reason", "component ORD is not a component: " + componentOrd);
            }

            if (slot == null || slot.trim().isEmpty()) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "setSlot",
                        "status", "failed",
                        "reason", "slot resolution failed for setSlot",
                        "slot", slot);
            }

            if ("wsAnnotation".equalsIgnoreCase(slot) && !(value instanceof BValue)) {
                Object annotation = parseWsAnnotation(String.valueOf(value));
                if (annotation != null) {
                    value = annotation;
                }
            }

            if ("wsAnnotation".equalsIgnoreCase(slot)
                    && writeWsAnnotation(componentObj, slot, value, cx)) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "setSlot",
                        "status", "applied",
                        "componentOrd", componentOrd,
                        "slot", slot,
                        "resolvedSlot", slot,
                        "value", value);
            }

            // Keep compatibility for explicit out writes while also supporting priority-array slot writes.
            if ("out".equalsIgnoreCase(slot) && invokeSetter(componentObj, "setOut", value)) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "setSlot",
                        "status", "applied",
                        "componentOrd", componentOrd,
                        "slot", slot,
                        "resolvedSlot", "out",
                        "value", value);
            }

            for (String candidate : slotWriteCandidates(slot)) {
                Object slotObj = resolveSlotObject(componentObj, candidate);
                if (slotObj == null) {
                    continue;
                }
                if (invokeSlotSetter(componentObj, candidate, slotObj, value)) {
                    return NiagaraJson.obj(
                            "index", op.get("index"),
                            "type", "setSlot",
                            "status", "applied",
                            "componentOrd", componentOrd,
                            "slot", slot,
                            "resolvedSlot", candidate,
                            "value", value);
                }
            }

            if ("out".equalsIgnoreCase(slot) && invokeSetter(componentObj, "setOut", value)) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "setSlot",
                        "status", "applied",
                        "componentOrd", componentOrd,
                        "slot", slot,
                        "resolvedSlot", "out",
                        "value", value);
            }

            // Try specific property setter: setXxx(value) pattern for non-standard slot types
            // (e.g. setProportionalConstant(double), setCountIncrement(float))
            if (slot.length() > 0) {
                String capSlot = Character.toUpperCase(slot.charAt(0)) + slot.substring(1);
                if (invokeSetter(componentObj, "set" + capSlot, value)) {
                    return NiagaraJson.obj(
                            "index", op.get("index"),
                            "type", "setSlot",
                            "status", "applied",
                            "componentOrd", componentOrd,
                            "slot", slot,
                            "resolvedSlot", slot,
                            "value", value);
                }
            }

            if (!invokeSetter(componentObj, "setOut", value)
                    && !invokeSlotSetter(componentObj, slot, resolveSlotObject(componentObj, slot), value)) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "setSlot",
                        "status", "failed",
                        "reason", "no compatible runtime set method found for slot '" + slot + "'");
            }

            return NiagaraJson.obj(
                    "index", op.get("index"),
                    "type", "setSlot",
                    "status", "applied",
                    "componentOrd", componentOrd,
                    "slot", slot,
                    "resolvedSlot", slot,
                    "value", value);
        } catch (Throwable e) {
            return NiagaraJson.obj(
                    "index", op.get("index"),
                    "type", "setSlot",
                    "status", "failed",
                    "reason", "runtime setSlot error: " + e.getMessage());
        }
    }

    // Helper to track which link method was matched
    private static class LinkInvocationResult {
        String matchedMethod = null;
        int matchedArgCount = 0;
        String matchedOnObject = null;
        String matchedSignature = null;
        int matchedCandidateIndex = -1;
        List<Object> matchedArgs = null;
    }

    private boolean tryInvokeLinkMethodsWithDiagnostics(String[] methodNames,
                                                        Object source,
                                                        String sourceSlot,
                                                        Object sourceSlotObj,
                                                        Object target,
                                                        String targetSlot,
                                                        Object targetSlotObj,
                                                        LinkInvocationResult result) {
        if (source == null) {
            return false;
        }
        for (String methodName : methodNames) {
            if (invokeLinkMethodWithDiagnostics(source, methodName, sourceSlot, sourceSlotObj, target, targetSlot, targetSlotObj, result)) {
                return true;
            }
        }
        return false;
    }

    private boolean writeWsAnnotation(Object componentObj, String slotName, Object value, Context cx) {
        Object annotation = value;
        if (annotation != null && !isWsAnnotationType(annotation.getClass())) {
            annotation = parseWsAnnotation(String.valueOf(value));
        }
        if (annotation == null) {
            return false;
        }
        try {
            Class<?> propertyClass = Class.forName("javax.baja.sys.Property");
            Class<?> bValueClass = Class.forName("javax.baja.sys.BValue");

            Object slotObj = null;
            try {
                Method getProperty = componentObj.getClass().getMethod("getProperty", String.class);
                slotObj = getProperty.invoke(componentObj, slotName);
            } catch (Throwable ignored) {
            }

            if (slotObj != null) {
                for (Method method : componentObj.getClass().getMethods()) {
                    if (!"set".equals(method.getName()) || method.getParameterCount() != 3) {
                        continue;
                    }
                    Class<?>[] params = method.getParameterTypes();
                    if (params[0].isAssignableFrom(propertyClass)
                            && params[1].isAssignableFrom(annotation.getClass())
                            && Context.class.isAssignableFrom(params[2])) {
                        method.invoke(componentObj, slotObj, annotation, cx);
                        return true;
                    }
                }
            }

            for (Method method : componentObj.getClass().getMethods()) {
                if (!"add".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 4
                        && String.class.equals(params[0])
                        && params[1].isAssignableFrom(annotation.getClass())
                        && (int.class.equals(params[2]) || Integer.class.equals(params[2]))
                        && Context.class.isAssignableFrom(params[3])) {
                    method.invoke(componentObj, slotName, annotation, Integer.valueOf(4096), cx);
                    return true;
                }
                if (params.length == 5
                        && String.class.equals(params[0])
                        && params[1].isAssignableFrom(annotation.getClass())
                        && (int.class.equals(params[2]) || Integer.class.equals(params[2]))
                        && params[4].isAssignableFrom(Context.class)) {
                    method.invoke(componentObj, slotName, annotation, Integer.valueOf(4096), null, cx);
                    return true;
                }
                if (params.length == 4
                        && String.class.equals(params[0])
                        && bValueClass.isAssignableFrom(params[1])
                        && (int.class.equals(params[2]) || Integer.class.equals(params[2]))
                        && Context.class.isAssignableFrom(params[3])) {
                    method.invoke(componentObj, slotName, annotation, Integer.valueOf(4096), cx);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean invokeLinkMethodWithDiagnostics(Object source,
                                                    String methodName,
                                                    String sourceSlot,
                                                    Object sourceSlotObj,
                                                    Object target,
                                                    String targetSlot,
                                                    Object targetSlotObj,
                                                    LinkInvocationResult result) {
        if (source == null) {
            return false;
        }
        for (Method method : source.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            try {
                if (params.length >= 2 && params.length <= 4) {
                    List<Object[]> candidates = linkArgCandidates(
                            sourceSlot, sourceSlotObj, target, targetSlot, targetSlotObj);
                    for (Object[] candidate : candidates) {
                        int candidateIndex = candidates.indexOf(candidate);
                        if (candidate.length != params.length) {
                            continue;
                        }
                        Object[] args = new Object[params.length];
                        boolean ok = true;
                        for (int i = 0; i < params.length; i++) {
                            if (!adaptValueForType(candidate[i], params[i], args, i)) {
                                ok = false;
                                break;
                            }
                        }
                        if (!ok) {
                            continue;
                        }
                        method.invoke(source, args);
                        // Track which method was matched
                        result.matchedMethod = methodName;
                        result.matchedArgCount = params.length;
                        result.matchedOnObject = source.getClass().getSimpleName();
                        result.matchedSignature = methodName + signatureSuffix(method);
                        result.matchedCandidateIndex = candidateIndex;
                        result.matchedArgs = describeArgs(args);
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean tryInvokePreferredTargetLinkWithDiagnostics(Object targetObj,
                                                                Object sourceObj,
                                                                String sourceSlot,
                                                                Object sourceSlotObj,
                                                                String targetSlot,
                                                                Object targetSlotObj,
                                                                LinkInvocationResult result) {
        if (targetObj == null) {
            return false;
        }

        String[] preferredMethods = new String[]{"makeLink", "linkTo"};
        for (String methodName : preferredMethods) {
            for (Method method : targetObj.getClass().getMethods()) {
                if (!methodName.equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                List<Object[]> candidates = preferredTargetLinkCandidates(
                        params.length,
                        sourceObj,
                        sourceSlot,
                        sourceSlotObj,
                        targetSlot,
                        targetSlotObj);
                for (int i = 0; i < candidates.size(); i++) {
                    Object[] candidate = candidates.get(i);
                    if (candidate.length != params.length) {
                        continue;
                    }
                    Object[] args = new Object[params.length];
                    boolean ok = true;
                    for (int p = 0; p < params.length; p++) {
                        if (!adaptValueForType(candidate[p], params[p], args, p)) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) {
                        continue;
                    }
                    try {
                        method.invoke(targetObj, args);
                        result.matchedMethod = methodName;
                        result.matchedArgCount = params.length;
                        result.matchedOnObject = targetObj.getClass().getSimpleName();
                        result.matchedSignature = methodName + signatureSuffix(method);
                        result.matchedCandidateIndex = Integer.valueOf(100 + i).intValue();
                        result.matchedArgs = describeArgs(args);
                        return true;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    private List<Object[]> preferredTargetLinkCandidates(int argCount,
                                                         Object sourceObj,
                                                         String sourceSlot,
                                                         Object sourceSlotObj,
                                                         String targetSlot,
                                                         Object targetSlotObj) {
        List<Object[]> list = new ArrayList<>();
        if (argCount == 3) {
            // Preferred target-side semantics from Niagara APIs:
            // target.linkTo(sourceComp, sourceSlot, targetSlot)
            list.add(new Object[]{sourceObj, sourceSlotObj, targetSlotObj});
            list.add(new Object[]{sourceObj, sourceSlot, targetSlot});
            list.add(new Object[]{sourceObj, sourceSlotObj, targetSlot});
            list.add(new Object[]{sourceObj, sourceSlot, targetSlotObj});
            return list;
        }
        if (argCount == 4) {
            // Named-link variants usually include propertyName first.
            String autoName = "mcpLink";
            list.add(new Object[]{autoName, sourceObj, sourceSlotObj, targetSlotObj});
            list.add(new Object[]{autoName, sourceObj, sourceSlot, targetSlot});
            list.add(new Object[]{autoName, sourceObj, sourceSlotObj, targetSlot});
            list.add(new Object[]{autoName, sourceObj, sourceSlot, targetSlotObj});
            return list;
        }
        return list;
    }

    private Map<String, Object> executeLink(Map<String, Object> op, Context cx) {
        String from = asString(op.get("from"));
        String to = asString(op.get("to"));
        Endpoint src = parseEndpoint(from, "out");
        Endpoint dst = parseEndpoint(to, "in10");
        if (src == null || dst == null) {
            return NiagaraJson.obj(
                    "index", op.get("index"),
                    "type", "link",
                    "status", "failed",
                    "reason", "invalid endpoint format, expected <componentOrd>/<slot>");
        }

        try {
            Object sourceObj = BOrd.make(localOrd(src.componentOrd)).get(null, cx);
            Object targetObj = BOrd.make(localOrd(dst.componentOrd)).get(null, cx);
            if (!(sourceObj instanceof BComponent) || !(targetObj instanceof BComponent)) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "link",
                        "status", "failed",
                        "reason", "source or target ORD is not a component");
            }

                Object sourceSlotObj = resolveSlotObject(sourceObj, src.slot);
                Object targetSlotObj = resolveSlotObject(targetObj, dst.slot);
                    if (sourceSlotObj == null || targetSlotObj == null) {
                    return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "link",
                        "status", "failed",
                        "reason", "source or target slot not found",
                        "sourceSlotFound", Boolean.valueOf(sourceSlotObj != null),
                        "targetSlotFound", Boolean.valueOf(targetSlotObj != null),
                        "from", from,
                        "to", to);
                    }

                // Prefer explicit target-side makeLink/linkTo semantics first.
                // Only fall back to broad reflective matching if those do not fit
                // the runtime's available signatures.
                String[] linkMethods = new String[]{
                        "linkTo", "makeLink", "link", "addLink", "linkFrom"
                };
                LinkInvocationResult result = new LinkInvocationResult();
                if (tryInvokePreferredTargetLinkWithDiagnostics(targetObj, sourceObj, src.slot, sourceSlotObj, dst.slot, targetSlotObj, result)
                    || tryInvokeLinkMethodsWithDiagnostics(linkMethods, targetObj, src.slot, sourceSlotObj, sourceObj, dst.slot, targetSlotObj, result)
                        || tryInvokeLinkMethodsWithDiagnostics(linkMethods, targetSlotObj, src.slot, sourceSlotObj, sourceObj, dst.slot, targetSlotObj, result)) {
                // linkTo() called with a null Context may leave BLink.getTo()/getFrom() null
                // because BComponent.getOrd() returns null in that context.  Fix the ORDs up
                // explicitly so that Workbench "Goto Linked Component" works.
                String ordFix = tryFixLinkOrds(
                    targetObj,
                    preferredComponentOrd(sourceObj, src.componentOrd),
                    preferredComponentOrd(targetObj, dst.componentOrd),
                    src.slot,
                    dst.slot);
                List<Object> linksAfter = collectLinks(targetObj, null);
                return NiagaraJson.obj(
                    "index", op.get("index"),
                    "type", "link",
                    "status", "applied",
                    "from", from,
                    "to", to,
                    "matchedMethod", result.matchedMethod,
                    "matchedArgumentCount", result.matchedArgCount,
                    "matchedOnObject", result.matchedOnObject,
                        "matchedSignature", result.matchedSignature,
                        "matchedCandidateIndex", Integer.valueOf(result.matchedCandidateIndex),
                        "matchedArgs", result.matchedArgs,
                    "ordFix", ordFix,
                            "ordStateAfter", collectLinkOrdState(targetObj),
                    "linksAfter", linksAfter);
            }

            return NiagaraJson.obj(
                    "index", op.get("index"),
                    "type", "link",
                    "status", "failed",
                    "reason", "no compatible runtime link method found (linkTo/link/addLink/linkFrom)",
                    "sourceHints", methodHints(sourceObj),
                    "targetHints", methodHints(targetObj),
                    "sourceSlotHints", methodHints(sourceSlotObj),
                    "targetSlotHints", methodHints(targetSlotObj),
                    "from", from,
                    "to", to);
        } catch (Throwable e) {
            return NiagaraJson.obj(
                    "index", op.get("index"),
                    "type", "link",
                    "status", "failed",
                    "reason", "runtime link error: " + e.getMessage(),
                    "from", from,
                    "to", to);
        }
    }

    private Map<String, Object> executeAddCompositePin(Map<String, Object> op, Context cx) {
        String folderOrd = asString(op.get("folderOrd"));
        String pinName = asString(op.get("pinName"));
        String targetComponentOrd = asString(op.get("targetComponentOrd"));
        String targetSlot = asString(op.get("targetSlot"));
        String direction = asString(op.get("direction"));

        try {
            Object folderObj = BOrd.make(localOrd(folderOrd)).get(null, cx);
            Object targetObj = BOrd.make(localOrd(targetComponentOrd)).get(null, cx);
            if (!(folderObj instanceof BComponent) || !(targetObj instanceof BComponent)) {
                return NiagaraJson.obj("index", op.get("index"), "type", "addCompositePin",
                        "status", "failed",
                        "reason", "folderOrd or targetComponentOrd did not resolve to a component");
            }
            BComponent folder = (BComponent) folderObj;
            BComponent targetComp = (BComponent) targetObj;

            Object slotObj = resolveSlotObject(targetComp, targetSlot);
            if (slotObj == null) {
                return NiagaraJson.obj("index", op.get("index"), "type", "addCompositePin",
                        "status", "failed",
                        "reason", "slot '" + targetSlot + "' not found on " + targetComponentOrd);
            }

            // Get the type instance from the slot: slot.getType().getInstance()
            Object typeInstance = null;
            try {
                Object slotType = slotObj.getClass().getMethod("getType").invoke(slotObj);
                typeInstance = slotType.getClass().getMethod("getInstance").invoke(slotType);
            } catch (Throwable ignored) {}
            if (typeInstance == null) {
                return NiagaraJson.obj("index", op.get("index"), "type", "addCompositePin",
                        "status", "failed",
                        "reason", "could not determine type for slot '" + targetSlot + "'");
            }

            // BFacets.NULL as default; try slot-specific facets first
            Object nullFacets = null;
            try {
                nullFacets = Class.forName("javax.baja.sys.BFacets").getField("NULL").get(null);
            } catch (Throwable ignored) {}

            Object facetsObj = nullFacets;
            try {
                for (Method m : targetComp.getClass().getMethods()) {
                    if ("getSlotFacets".equals(m.getName()) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0].isInstance(slotObj)) {
                        Object f = m.invoke(targetComp, slotObj);
                        if (f != null) { facetsObj = f; }
                        break;
                    }
                }
            } catch (Throwable ignored) {}

            // Output pins are read-only from outside (flag 1); input pins are writable (flag 0)
            int pinFlags = "out".equals(direction) ? 1 : 0;

            // Add the dynamic pin slot to the folder.
            // BComponent.add(String name, BValue value, int flags, BFacets facets, Context ctx)
            // Relax the BValue check: just match String first + int/Integer third among 5-param adds,
            // then try invocation; if the cast fails internally it throws and we move to the next.
            boolean pinAdded = false;
            List<String> addMethodsFound = new ArrayList<>();
            String lastAddError = null;
            for (Method m : folder.getClass().getMethods()) {
                if (!"add".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                addMethodsFound.add(m.getName() + signatureSuffix(m));
                if (p.length == 5 && String.class.equals(p[0])
                        && (int.class.equals(p[2]) || Integer.class.equals(p[2]))) {
                    try {
                        m.invoke(folder, pinName, typeInstance, Integer.valueOf(pinFlags), facetsObj, cx);
                        pinAdded = true;
                        break;
                    } catch (Throwable e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        lastAddError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                    }
                }
            }
            if (!pinAdded) {
                return NiagaraJson.obj("index", op.get("index"), "type", "addCompositePin",
                        "status", "failed",
                        "pin", pinName,
                        "direction", direction,
                        "reason", "could not add dynamic pin slot to folder",
                        "addError", lastAddError,
                        "typeInstanceClass", typeInstance == null ? "null" : typeInstance.getClass().getName());
            }

            // Build the composite BLink
            // Output: link FROM targetComp/targetSlot TO folder/pinName  (child drives the pin outward)
            //         new BLink(targetComp.getHandleOrd(), targetSlot, pinName, true)
            //         folder.add(null, link, 4096, BFacets.NULL, cx)
            // Input:  link FROM folder/pinName TO targetComp/targetSlot  (external driver enters child)
            //         new BLink(folder.getHandleOrd(), pinName, targetSlot, true)
            //         targetComp.add(null, link, 4096, BFacets.NULL, cx)
            BOrd sourceOrd = "out".equals(direction)
                    ? targetComp.getHandleOrd() : folder.getHandleOrd();
            String sourceSlotName = "out".equals(direction) ? targetSlot : pinName;
            String destSlotName   = "out".equals(direction) ? pinName    : targetSlot;
            BComponent linkOwner  = "out".equals(direction) ? folder     : targetComp;

            Object link = null;
            try {
                Class<?> bLinkClass = Class.forName("javax.baja.sys.BLink");
                java.lang.reflect.Constructor<?> ctor = bLinkClass.getConstructor(
                        BOrd.class, String.class, String.class, boolean.class);
                link = ctor.newInstance(sourceOrd, sourceSlotName, destSlotName, Boolean.TRUE);
            } catch (Throwable e) {
                return NiagaraJson.obj("index", op.get("index"), "type", "addCompositePin",
                        "status", "failed",
                        "reason", "pin slot added but BLink creation failed: " + e.getMessage());
            }

            boolean linkAdded = false;
            for (Method m : linkOwner.getClass().getMethods()) {
                if (!"add".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 5 && p[1].isInstance(link)
                        && (int.class.equals(p[2]) || Integer.class.equals(p[2]))) {
                    try {
                        m.invoke(linkOwner, null, link, Integer.valueOf(4096), nullFacets, cx);
                        linkAdded = true;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            return NiagaraJson.obj(
                    "index", op.get("index"),
                    "type", "addCompositePin",
                    "status", linkAdded ? "applied" : "failed",
                    "reason", linkAdded ? "" : "pin slot added but composite BLink wiring failed",
                    "folderOrd", folderOrd,
                    "pin", pinName,
                    "direction", direction);
        } catch (Throwable e) {
            return NiagaraJson.obj("index", op.get("index"), "type", "addCompositePin",
                    "status", "failed", "reason", e.getMessage());
        }
    }

    private Map<String, Object> executeCreateComponent(Map<String, Object> op, Context cx) {
        String parentOrd = asString(op.get("parentOrd"));
        String name = asString(op.get("name"));
        String componentType = asString(op.get("componentType"));

        try {
            Object parentObj = BOrd.make(localOrd(parentOrd)).get(null, cx);
            if (!(parentObj instanceof BComponent)) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "createComponent",
                        "status", "failed",
                        "reason", "parent ORD is not a component: " + parentOrd);
            }
            BComponent parent = (BComponent) parentObj;

            if (findChildByName(parent, name) != null) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "createComponent",
                        "status", "skipped",
                        "reason", "component already exists",
                        "ord", parentOrd + "/" + name);
            }

            Object child = instantiateComponent(componentType);
            if (child == null) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "createComponent",
                        "status", "failed",
                        "reason", "unsupported or unavailable componentType for runtime create: " + componentType);
            }

            if (!addChildComponent(parentObj, name, child)) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "createComponent",
                        "status", "failed",
                        "reason", "failed to add child component via runtime API reflection");
            }

            BComponent created = findChildByName(parent, name);
            if (created == null) {
                return NiagaraJson.obj(
                        "index", op.get("index"),
                        "type", "createComponent",
                        "status", "failed",
                        "reason", "component add call completed but child not found after create");
            }

            Object rawFacets = op.get("facets");
            String facetsResult = null;
            if (rawFacets instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> facetsMap = (Map<String, Object>) rawFacets;
                facetsResult = applyFacets(created, facetsMap);
            }

            Map<String, Object> result = NiagaraJson.obj(
                    "index", op.get("index"),
                    "type", "createComponent",
                    "status", "applied",
                    "ord", parentOrd + "/" + name,
                    "componentType", componentType);
            if (facetsResult != null) {
                result.put("facets", facetsResult);
            }
            return result;
        } catch (Throwable e) {
            return NiagaraJson.obj(
                    "index", op.get("index"),
                    "type", "createComponent",
                    "status", "failed",
                    "reason", "runtime create error: " + e.getMessage());
        }
    }

    private String applyFacets(BComponent comp, Map<String, Object> facets) {
        if (facets == null || facets.isEmpty()) {
            return null;
        }
        try {
            String units = asString(facets.get("units"));
            Object precisionRaw = facets.get("precision");
            Object minRaw = facets.get("min");
            Object maxRaw = facets.get("max");
            String trueText = asString(facets.get("trueText"));
            String falseText = asString(facets.get("falseText"));

            Object bFacets = null;

            if (trueText != null || falseText != null) {
                bFacets = buildBooleanFacets(
                        trueText != null ? trueText : "true",
                        falseText != null ? falseText : "false");
            } else if (units != null || precisionRaw != null || minRaw != null || maxRaw != null) {
                int precision = precisionRaw instanceof Number
                        ? ((Number) precisionRaw).intValue() : 1;
                double min = minRaw instanceof Number ? ((Number) minRaw).doubleValue() : Double.NaN;
                double max = maxRaw instanceof Number ? ((Number) maxRaw).doubleValue() : Double.NaN;
                bFacets = buildNumericFacets(units, precision, min, max);
            }

            if (bFacets == null) {
                return "skipped:no-recognizable-facet-keys";
            }
            if (invokeSetter(comp, "setFacets", bFacets)) {
                return "applied";
            }
            return "failed:setFacets-not-found";
        } catch (Throwable e) {
            return "failed:" + e.getMessage();
        }
    }

    private Object buildBooleanFacets(String trueText, String falseText) {
        try {
            Class<?> bFacetsClass = Class.forName("javax.baja.sys.BFacets");
            java.lang.reflect.Method m = bFacetsClass.getMethod("makeBoolean", String.class, String.class);
            return m.invoke(null, trueText, falseText);
        } catch (Throwable e) {
            return null;
        }
    }

    private Object buildNumericFacets(String unitName, int precision, double min, double max) {
        try {
            Class<?> bFacetsClass = Class.forName("javax.baja.sys.BFacets");
            Class<?> bUnitClass = Class.forName("javax.baja.units.BUnit");

            Object unit = null;
            if (unitName != null && !unitName.isEmpty()) {
                try {
                    java.lang.reflect.Method getUnit = bUnitClass.getMethod("getUnit", String.class);
                    unit = getUnit.invoke(null, unitName);
                } catch (Throwable ignored) {
                }
            }

            if (!Double.isNaN(min) && !Double.isNaN(max)) {
                // makeNumeric(BUnit, int, double, double) — confirmed in bytecode
                java.lang.reflect.Method m = bFacetsClass.getMethod(
                        "makeNumeric", bUnitClass, int.class, double.class, double.class);
                return m.invoke(null, unit, Integer.valueOf(precision), Double.valueOf(min), Double.valueOf(max));
            }
            if (unit != null) {
                java.lang.reflect.Method m = bFacetsClass.getMethod("makeNumeric", bUnitClass, int.class);
                return m.invoke(null, unit, Integer.valueOf(precision));
            }
            java.lang.reflect.Method m = bFacetsClass.getMethod("makeNumeric", int.class);
            return m.invoke(null, Integer.valueOf(precision));
        } catch (Throwable e) {
            return null;
        }
    }

    private BComponent findChildByName(BComponent parent, String name) {
        try {
            BComponent[] children = parent.getChildComponents();
            if (children == null) {
                return null;
            }
            for (BComponent child : children) {
                try {
                    if (name.equals(child.getName())) {
                        return child;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private List<String> slotWriteCandidates(String slot) {
        List<String> candidates = new ArrayList<>();
        if (slot == null || slot.trim().isEmpty()) {
            candidates.add("fallback");
            return candidates;
        }
        String s = slot.trim();
        if ("out".equalsIgnoreCase(s)) {
            candidates.add("fallback");
            candidates.add("out");
            return candidates;
        }
        candidates.add(s);
        return candidates;
    }

    private Object instantiateComponent(String componentType) {
        if (componentType == null) {
            return null;
        }
        if ("control:BooleanWritable".equals(componentType)) {
            return instantiateByClassNames(
                    "javax.baja.control.BBooleanWritable",
                    "com.tridium.control.BBooleanWritable");
        }
        if ("control:NumericWritable".equals(componentType)) {
            return instantiateByClassNames(
                    "javax.baja.control.BNumericWritable",
                    "com.tridium.control.BNumericWritable");
        }
        if ("control:EnumWritable".equals(componentType)) {
            return instantiateByClassNames(
                    "javax.baja.control.BEnumWritable",
                    "com.tridium.control.BEnumWritable");
        }
        if ("control:StringWritable".equals(componentType)) {
            return instantiateByClassNames(
                    "javax.baja.control.BStringWritable",
                    "com.tridium.control.BStringWritable");
        }
        // kitControl — try Niagara's type registry first (bypasses classloader issues),
        // then fall back to direct Class.forName with the module dependency classloader.
        // Package: com.tridium.kitControl (capital C), all confirmed via bytecode inspection.
        if (componentType.startsWith("kitControl:")) {
            // Primary: use Niagara Sys.findType(typeSpec) which resolves across all loaded modules
            Object fromRegistry = instantiateViaTypeRegistry(componentType);
            if (fromRegistry != null) {
                return fromRegistry;
            }
            // Fallback: direct class name lookup using confirmed bytecode paths
            String typeName = componentType.substring("kitControl:".length());
            return instantiateKitControlByName(typeName);
        }
        if ("baja:Folder".equals(componentType)) {
            return instantiateByClassNames("javax.baja.util.BFolder");
        }
        if ("baja:TextBlock".equals(componentType) || "baja:WsTextBlock".equals(componentType)) {
            return instantiateByClassNames("javax.baja.util.BWsTextBlock");
        }
        // Generic baja:/nre: types — try the type registry first, then class name convention
        if (componentType.startsWith("baja:") || componentType.startsWith("nre:")) {
            Object fromRegistry = instantiateViaTypeRegistry(componentType);
            if (fromRegistry != null) {
                return fromRegistry;
            }
        }
        return null;
    }

    private Object instantiateByClassNames(String... classNames) {
        for (String className : classNames) {
            try {
                Class<?> cls = Class.forName(className);
                Object fromMake = instantiateViaMake(cls);
                if (fromMake != null) {
                    return fromMake;
                }
                return cls.getDeclaredConstructor().newInstance();
            } catch (Throwable ignored) {
            }
        }
        // Fallback: try loading through the thread context classloader (cross-module classloader)
        for (String className : classNames) {
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) {
                    cl = NiagaraWiresheetTools.class.getClassLoader();
                }
                Class<?> cls = Class.forName(className, true, cl);
                Object fromMake = instantiateViaMake(cls);
                if (fromMake != null) {
                    return fromMake;
                }
                return cls.getDeclaredConstructor().newInstance();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object instantiateViaTypeRegistry(String typeSpec) {
        try {
            // Sys.findType(String) looks up any loaded module's type by "module:TypeName" spec
            java.lang.reflect.Method findType = javax.baja.sys.Sys.class.getMethod("findType", String.class);
            Object type = findType.invoke(null, typeSpec);
            if (type == null) {
                return null;
            }
            // Type.newBObject() is the standard Niagara factory for creating component instances
            for (String factory : new String[]{"newBObject", "newInstance", "make"}) {
                try {
                    java.lang.reflect.Method m = type.getClass().getMethod(factory);
                    Object instance = m.invoke(type);
                    if (instance != null) {
                        return instance;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final java.util.Map<String, String> KITCONTROL_CLASS_MAP;
    static {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        // Logic — confirmed via bytecode: all comparators are in logic package
        m.put("And",              "com.tridium.kitControl.logic.BAnd");
        m.put("Or",               "com.tridium.kitControl.logic.BOr");
        m.put("Not",              "com.tridium.kitControl.logic.BNot");
        m.put("Nand",             "com.tridium.kitControl.logic.BNand");
        m.put("Nor",              "com.tridium.kitControl.logic.BNor");
        m.put("Xor",              "com.tridium.kitControl.logic.BXor");
        m.put("GreaterThan",      "com.tridium.kitControl.logic.BGreaterThan");
        m.put("LessThan",         "com.tridium.kitControl.logic.BLessThan");
        m.put("GreaterThanEqual", "com.tridium.kitControl.logic.BGreaterThanEqual");
        m.put("LessThanEqual",    "com.tridium.kitControl.logic.BLessThanEqual");
        m.put("Equal",            "com.tridium.kitControl.logic.BEqual");
        m.put("NotEqual",         "com.tridium.kitControl.logic.BNotEqual");
        m.put("Hysteresis",       "com.tridium.kitControl.logic.BHysteresis");
        // Math
        m.put("Add",          "com.tridium.kitControl.math.BAdd");
        m.put("Subtract",     "com.tridium.kitControl.math.BSubtract");
        m.put("Multiply",     "com.tridium.kitControl.math.BMultiply");
        m.put("Divide",       "com.tridium.kitControl.math.BDivide");
        m.put("Average",      "com.tridium.kitControl.math.BAverage");
        m.put("Minimum",      "com.tridium.kitControl.math.BMinimum");
        m.put("Maximum",      "com.tridium.kitControl.math.BMaximum");
        m.put("AbsValue",     "com.tridium.kitControl.math.BAbsValue");
        m.put("Limiter",      "com.tridium.kitControl.math.BLimiter");
        m.put("Negative",     "com.tridium.kitControl.math.BNegative");
        m.put("Modulus",      "com.tridium.kitControl.math.BModulus");
        m.put("SquareRoot",   "com.tridium.kitControl.math.BSquareRoot");
        m.put("Derivative",   "com.tridium.kitControl.math.BDerivative");
        // Line: linear scale/map. Slots: inA=x1, inB=y1, inC=x2, inD=y2, in=live input, out=scaled output.
        // limitBelow/limitAbove clamp the output to the defined range.
        m.put("Line",         "com.tridium.kitControl.math.BLine");
        // LoopPoint: PID feedback controller. Slots: controlledVariable (CV input), setpoint (SP input),
        // loopEnable, loopAction (Direct/Reverse), proportionalConstant (Kp), integralConstant (Ti),
        // derivativeConstant (Td), bias, maximumOutput, minimumOutput, rampTime, out (control output).
        m.put("LoopPoint",    "com.tridium.kitControl.BLoopPoint");
        // Switches: inSwitch (boolean selector), inTrue (value when true), inFalse (value when false), out.
        m.put("NumericSwitch",  "com.tridium.kitControl.util.BNumericSwitch");
        m.put("BooleanSwitch",  "com.tridium.kitControl.util.BBooleanSwitch");
        m.put("EnumSwitch",     "com.tridium.kitControl.util.BEnumSwitch");
        m.put("StringSwitch",   "com.tridium.kitControl.util.BStringSwitch");
        // MuxSwitch excluded: BMuxSwitch cannot be instantiated by the runtime
        // (unsupported or unavailable componentType error on all Niagara 4 stations tested).
        // Latches: store the last value written to in; output on out.
        m.put("BooleanLatch",   "com.tridium.kitControl.util.BBooleanLatch");
        m.put("NumericLatch",   "com.tridium.kitControl.util.BNumericLatch");
        m.put("EnumLatch",      "com.tridium.kitControl.util.BEnumLatch");
        m.put("StringLatch",    "com.tridium.kitControl.util.BStringLatch");
        // Select: output = input at index selector; slots inA..inJ, out.
        m.put("NumericSelect",  "com.tridium.kitControl.util.BNumericSelect");
        m.put("BooleanSelect",  "com.tridium.kitControl.util.BBooleanSelect");
        m.put("EnumSelect",     "com.tridium.kitControl.util.BEnumSelect");
        m.put("StringSelect",   "com.tridium.kitControl.util.BStringSelect");
        // Counter: slots countUp, countDown, presetValue, countIncrement, clearIn, out.
        m.put("Counter",        "com.tridium.kitControl.util.BCounter");
        // Ramp: periodic waveform generator. Slots: enabled, period, amplitude, offset, updateInterval, waveform.
        m.put("Ramp",           "com.tridium.kitControl.util.BRamp");
        // Timers. BBooleanDelay slots: in, onDelay, offDelay, out, outNot.
        // BNumericDelay slots: in, updateTime, maxStepSize, out.
        // BOneShot slots: in, time, out, outNot.
        m.put("BooleanDelay",   "com.tridium.kitControl.timer.BBooleanDelay");
        m.put("NumericDelay",   "com.tridium.kitControl.timer.BNumericDelay");
        m.put("OneShot",        "com.tridium.kitControl.timer.BOneShot");
        m.put("MinMaxAvg",      "com.tridium.kitControl.util.BMinMaxAvg");
        KITCONTROL_CLASS_MAP = m;
    }

    private Object instantiateKitControlByName(String typeName) {
        String className = KITCONTROL_CLASS_MAP.get(typeName);
        if (className != null) {
            return instantiateByClassNames(className);
        }
        return null;
    }

    private Object instantiateViaMake(Class<?> cls) {
        try {
            Method make = cls.getMethod("make");
            if ((make.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                return make.invoke(null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean addChildComponent(Object parent, String name, Object child) {
        for (Method m : parent.getClass().getMethods()) {
            if (!("add".equals(m.getName()) || "addChild".equals(m.getName()))) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 2) {
                continue;
            }
            if (!String.class.equals(params[0])) {
                continue;
            }
            if (!params[1].isAssignableFrom(child.getClass())) {
                continue;
            }
            try {
                m.invoke(parent, name, child);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean invokeSetter(Object target, String methodName, Object value) {
        Method[] methods = target.getClass().getMethods();
        for (Method method : methods) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) {
                continue;
            }
            Object converted = convertScalar(value, params[0]);
            if (converted == null && params[0].isPrimitive()) {
                continue;
            }
            try {
                method.invoke(target, converted);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean invokeSlotSetter(Object target, String slotName, Object slotObj, Object value) {
        if (invokeSlotSetterOnTarget(target, slotName, slotObj, value)) {
            return true;
        }
        if (slotObj != null && invokeSlotSetterOnTarget(slotObj, slotName, slotObj, value)) {
            return true;
        }
        return false;
    }

    private boolean invokeSlotSetterOnTarget(Object target, String slotName, Object slotObj, Object value) {
        if (target == null) {
            return false;
        }
        for (Method method : target.getClass().getMethods()) {
            String name = method.getName();
            if (!("set".equals(name) || "setSlot".equals(name) || "write".equals(name))) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 1 || params.length > 3) {
                continue;
            }
            for (Object[] candidate : setArgCandidates(slotName, slotObj, value)) {
                if (candidate.length != params.length) {
                    continue;
                }
                Object[] args = new Object[params.length];
                boolean ok = true;
                for (int i = 0; i < params.length; i++) {
                    if (!adaptValueForType(candidate[i], params[i], args, i)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                try {
                    method.invoke(target, args);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private List<Object[]> setArgCandidates(String slotName, Object slotObj, Object value) {
        List<Object[]> list = new ArrayList<>();
        list.add(new Object[]{slotObj, value});
        list.add(new Object[]{slotName, value});
        list.add(new Object[]{value});
        list.add(new Object[]{slotObj, value, null});
        list.add(new Object[]{slotName, value, null});
        return list;
    }

    private boolean invokeLinkMethod(Object source,
                                     String methodName,
                                     String sourceSlot,
                                     Object sourceSlotObj,
                                     Object target,
                                     String targetSlot,
                                     Object targetSlotObj) {
        if (source == null) {
            return false;
        }
        for (Method method : source.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            try {
                if (params.length >= 2 && params.length <= 4) {
                    List<Object[]> candidates = linkArgCandidates(
                            sourceSlot, sourceSlotObj, target, targetSlot, targetSlotObj);
                    for (Object[] candidate : candidates) {
                        if (candidate.length != params.length) {
                            continue;
                        }
                        Object[] args = new Object[params.length];
                        boolean ok = true;
                        for (int i = 0; i < params.length; i++) {
                            if (!adaptValueForType(candidate[i], params[i], args, i)) {
                                ok = false;
                                break;
                            }
                        }
                        if (!ok) {
                            continue;
                        }
                        method.invoke(source, args);
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean tryInvokeLinkMethods(String[] methodNames,
                                         Object source,
                                         String sourceSlot,
                                         Object sourceSlotObj,
                                         Object target,
                                         String targetSlot,
                                         Object targetSlotObj) {
        if (source == null) {
            return false;
        }
        for (String methodName : methodNames) {
            if (invokeLinkMethod(source, methodName, sourceSlot, sourceSlotObj, target, targetSlot, targetSlotObj)) {
                return true;
            }
        }
        return false;
    }

    private List<Object> methodHints(Object target) {
        List<Object> hints = new ArrayList<>();
        if (target == null) {
            return hints;
        }
        for (Method m : target.getClass().getMethods()) {
            String lower = m.getName().toLowerCase();
            if (!(lower.contains("link") || lower.contains("bind")
                    || lower.contains("connect") || lower.contains("wire"))) {
                continue;
            }
            hints.add(m.getName() + signatureSuffix(m));
            if (hints.size() >= 16) {
                break;
            }
        }
        return hints;
    }

    private String signatureSuffix(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i].getSimpleName());
        }
        sb.append(')');
        return sb.toString();
    }

    private List<Object> describeArgs(Object[] args) {
        List<Object> out = new ArrayList<>();
        if (args == null) {
            return out;
        }
        for (Object arg : args) {
            if (arg == null) {
                out.add("null");
                continue;
            }
            out.add(arg.getClass().getSimpleName() + ":" + String.valueOf(arg));
        }
        return out;
    }

    private List<Object[]> linkArgCandidates(String sourceSlot,
                                             Object sourceSlotObj,
                                             Object target,
                                             String targetSlot,
                                             Object targetSlotObj) {
        List<Object[]> list = new ArrayList<>();
        // All candidates include the target component object so the link is registered
        // with a non-null target.  Slot-only forms (no target component) are intentionally
        // excluded — they produce a broken link with a null target as seen in audit logs.

        // 3-arg: canonical Baja form — source.linkTo(sourceSlot, targetComp, targetSlot)
        list.add(new Object[]{sourceSlotObj, target, targetSlotObj});
        list.add(new Object[]{sourceSlot,    target, targetSlot});
        list.add(new Object[]{sourceSlotObj, target, targetSlot});
        list.add(new Object[]{sourceSlot,    target, targetSlotObj});

        // 3-arg: target-side Baja form — target.linkTo(sourceComp, sourceSlot, targetSlot)
        list.add(new Object[]{target, sourceSlotObj, targetSlotObj});
        list.add(new Object[]{target, sourceSlot,    targetSlot});

        // Keep the swapped slot ordering as a lower-priority fallback for other runtimes.
        list.add(new Object[]{target, targetSlotObj, sourceSlotObj});
        list.add(new Object[]{target, targetSlot,    sourceSlot});

        // 4-arg: with trailing flag/null variants
        list.add(new Object[]{sourceSlotObj, target, targetSlotObj, null});
        list.add(new Object[]{sourceSlotObj, target, targetSlotObj, Boolean.FALSE});
        list.add(new Object[]{sourceSlotObj, target, targetSlotObj, Integer.valueOf(0)});
        list.add(new Object[]{sourceSlot,    target, targetSlot,    null});
        list.add(new Object[]{sourceSlot,    target, targetSlot,    Boolean.FALSE});
        list.add(new Object[]{sourceSlot,    target, targetSlot,    Integer.valueOf(0)});
        list.add(new Object[]{target, sourceSlotObj, targetSlotObj, null});

        // 4-arg: other orderings
        list.add(new Object[]{sourceSlot,    target,       sourceSlotObj, targetSlotObj});
        list.add(new Object[]{sourceSlot,    sourceSlotObj, target,       targetSlotObj});
        list.add(new Object[]{sourceSlotObj, sourceSlot,    target,       targetSlot});

        // 2-arg: still include target component
        list.add(new Object[]{target, targetSlotObj});
        list.add(new Object[]{target, targetSlot});
        return list;
    }

    private Object resolveSlotObject(Object component, String slotName) {
        if (component == null || slotName == null) {
            return null;
        }
        for (Method method : component.getClass().getMethods()) {
            if (!"getSlot".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && String.class.equals(params[0])) {
                try {
                    return method.invoke(component, slotName);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private boolean adaptValueForType(Object value, Class<?> targetType, Object[] outArgs, int index) {
        if (value == null && !targetType.isPrimitive()) {
            outArgs[index] = null;
            return true;
        }
        Object converted = convertScalar(value, targetType);
        if (converted == null && targetType.isPrimitive()) {
            return false;
        }
        if (converted == null && value != null && !targetType.isPrimitive()) {
            return false;
        }
        outArgs[index] = converted;
        return true;
    }

    private Object convertScalar(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        String text = value.toString();
        try {
            if (isWsAnnotationType(targetType)) {
                Object annotation = parseWsAnnotation(text);
                if (annotation != null) {
                    return annotation;
                }
            }
            if (Boolean.TYPE.equals(targetType) || Boolean.class.equals(targetType)) {
                return Boolean.valueOf(Boolean.parseBoolean(text));
            }
            if (Double.TYPE.equals(targetType) || Double.class.equals(targetType)) {
                return Double.valueOf(Double.parseDouble(text));
            }
            if (Float.TYPE.equals(targetType) || Float.class.equals(targetType)) {
                return Float.valueOf(Float.parseFloat(text));
            }
            if (Integer.TYPE.equals(targetType) || Integer.class.equals(targetType)) {
                return Integer.valueOf(Integer.parseInt(text));
            }
            if (Long.TYPE.equals(targetType) || Long.class.equals(targetType)) {
                return Long.valueOf(Long.parseLong(text));
            }
            if (String.class.equals(targetType)) {
                return text;
            }
            if (targetType.isEnum()) {
                @SuppressWarnings("unchecked")
                Class<? extends Enum> enumType = (Class<? extends Enum>) targetType;
                return Enum.valueOf(enumType, text);
            }
            Object baja = tryBajaFactory(targetType, text);
            if (baja != null) {
                return baja;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private boolean isWsAnnotationType(Class<?> targetType) {
        if (targetType == null) {
            return false;
        }
        String name = targetType.getName();
        return "javax.baja.util.BWsAnnotation".equals(name) || "javax.baja.util.WsAnnotation".equals(name);
    }

    private Object parseWsAnnotation(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            int p = Integer.parseInt(parts[0].trim());
            int q = Integer.parseInt(parts[1].trim());
            int w = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 20;
            int h = parts.length > 3 ? Integer.parseInt(parts[3].trim()) : 2;

            Class<?> cls = Class.forName("javax.baja.util.BWsAnnotation");
            for (String factory : new String[]{"make"}) {
                try {
                    Method m = cls.getMethod(factory, int.class, int.class, int.class, int.class);
                    Object value = m.invoke(null, Integer.valueOf(p), Integer.valueOf(q), Integer.valueOf(w), Integer.valueOf(h));
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(int.class, int.class, int.class, int.class);
                ctor.setAccessible(true);
                return ctor.newInstance(Integer.valueOf(p), Integer.valueOf(q), Integer.valueOf(w), Integer.valueOf(h));
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private Object tryBajaFactory(Class<?> targetType, String text) {
        for (Method method : targetType.getMethods()) {
            String name = method.getName();
            if (!("make".equals(name)
                    || "valueOf".equals(name)
                    || "decodeFromString".equals(name)
                    || "fromString".equals(name)
                    || "parse".equals(name))) {
                continue;
            }
            if ((method.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) {
                continue;
            }
            try {
                if (String.class.equals(params[0])) {
                    return method.invoke(null, text);
                }
                if (Boolean.TYPE.equals(params[0]) || Boolean.class.equals(params[0])) {
                    return method.invoke(null, Boolean.valueOf(Boolean.parseBoolean(text)));
                }
                if (Double.TYPE.equals(params[0]) || Double.class.equals(params[0])) {
                    return method.invoke(null, Double.valueOf(Double.parseDouble(text)));
                }
                if (Float.TYPE.equals(params[0]) || Float.class.equals(params[0])) {
                    return method.invoke(null, Float.valueOf(Float.parseFloat(text)));
                }
                if (Integer.TYPE.equals(params[0]) || Integer.class.equals(params[0])) {
                    return method.invoke(null, Integer.valueOf(Integer.parseInt(text)));
                }
                if (Long.TYPE.equals(params[0]) || Long.class.equals(params[0])) {
                    return method.invoke(null, Long.valueOf(Long.parseLong(text)));
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Endpoint parseEndpoint(String endpoint, String defaultSlot) {
        if (endpoint == null) {
            return null;
        }
        if (!hasExplicitLinkSlot(endpoint)) {
            if (defaultSlot == null || defaultSlot.isEmpty()) {
                return null;
            }
            return new Endpoint(endpoint, defaultSlot);
        }
        int cut = endpoint.lastIndexOf('/');
        if (cut <= 0 || cut >= endpoint.length() - 1) {
            return null;
        }
        String componentOrd = endpoint.substring(0, cut);
        String slot = endpoint.substring(cut + 1);
        if (componentOrd.isEmpty() || slot.isEmpty()) {
            return null;
        }
        return new Endpoint(componentOrd, slot);
    }

    private boolean hasExplicitLinkSlot(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        int cut = endpoint.lastIndexOf('/');
        if (cut <= 0 || cut >= endpoint.length() - 1) {
            return false;
        }
        String tail = endpoint.substring(cut + 1);
        // Niagara slot names are camelCase starting with a lowercase letter (e.g. out, in10, inA,
        // controlledVariable, inSwitch, countUp, onDelay).  Component names use PascalCase.
        // Any plain alphanumeric word beginning with a lowercase letter is treated as an explicit slot.
        if (tail.matches("[a-z][a-zA-Z0-9]*")) {
            return true;
        }
        return false;
    }

        /**
         * Workbench hyperlink navigation uses relation ORD metadata.  In some runtime contexts
         * (such as linking from tool code with null Context), those ORD fields can remain null.
         * This method repairs null relation fields on the created link objects.
         */
        private String tryFixLinkOrds(Object targetComp,
                                      BOrd sourceComponentOrd,
                                      BOrd targetComponentOrd,
                                      String sourceSlot,
                                      String targetSlot) {
            try {
                for (Method m : targetComp.getClass().getMethods()) {
                    if (!"getLinks".equals(m.getName()) || m.getParameterCount() != 0) {
                        continue;
                    }
                    Object linksObj;
                    try {
                        linksObj = m.invoke(targetComp);
                    } catch (Throwable e) {
                        return "getLinks-invoke-error:" + e.getMessage();
                    }
                    if (linksObj == null) {
                        return "links-null";
                    }
                    int fixed = 0;
                    int checked = 0;
                    Iterable<?> iterable = null;
                    if (linksObj instanceof Iterable) {
                        iterable = (Iterable<?>) linksObj;
                    } else if (linksObj.getClass().isArray()) {
                        List<Object> tmp = new ArrayList<>();
                        int len = java.lang.reflect.Array.getLength(linksObj);
                        for (int i = 0; i < len; i++) {
                            tmp.add(java.lang.reflect.Array.get(linksObj, i));
                        }
                        iterable = tmp;
                    } else {
                        return "links-not-iterable:" + linksObj.getClass().getSimpleName();
                    }
                    for (Object link : iterable) {
                        if (link == null) {
                            continue;
                        }
                        checked++;
                        // BRelation/BLink fields used by Workbench hyperlink handling.
                        fixed += trySetOrdIfNull(link, "getEndpointOrd", "setEndpointOrd", sourceComponentOrd);
                        fixed += trySetOrdIfNull(link, "getSourceOrd", "setSourceOrd", sourceComponentOrd);
                        fixed += trySetOrdIfNull(link, "getTargetOrd", "setTargetOrd", targetComponentOrd);

                        fixed += trySetStringIfBlank(link, "getSourceSlotName", "setSourceSlotName", sourceSlot);
                        fixed += trySetStringIfBlank(link, "getTargetSlotName", "setTargetSlotName", targetSlot);
                    }
                    return "checked:" + checked + ",fixed:" + fixed;
                }
                return "no-getLinks-method";
            } catch (Throwable e) {
                return "error:" + e.getMessage();
            }
        }

        private int trySetOrdIfNull(Object blink, String getter, String setter, BOrd newOrd) {
            try {
                Method getM;
                try {
                    getM = blink.getClass().getMethod(getter);
                } catch (NoSuchMethodException e) {
                    return 0;
                }
                Object current;
                try {
                    current = getM.invoke(blink);
                } catch (Throwable e) {
                    return 0;
                }
                if (!isOrdUnset(current)) {
                    return 0; // already set, nothing to fix
                }
                for (Method sm : blink.getClass().getMethods()) {
                    if (!setter.equals(sm.getName()) || sm.getParameterCount() != 1) {
                        continue;
                    }
                    if (sm.getParameterTypes()[0].isAssignableFrom(newOrd.getClass())) {
                        try {
                            sm.invoke(blink, newOrd);
                            return 1;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return 0;
        }

        private boolean isOrdUnset(Object ord) {
            if (ord == null) {
                return true;
            }
            String s = String.valueOf(ord).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                return true;
            }
            // Some Niagara ORD implementations expose isNull().
            try {
                Method m = ord.getClass().getMethod("isNull");
                Object v = m.invoke(ord);
                if (Boolean.TRUE.equals(v)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        private int trySetStringIfBlank(Object target, String getter, String setter, String value) {
            if (value == null || value.trim().isEmpty()) {
                return 0;
            }
            try {
                Method getM;
                try {
                    getM = target.getClass().getMethod(getter);
                } catch (NoSuchMethodException e) {
                    return 0;
                }
                Object current;
                try {
                    current = getM.invoke(target);
                } catch (Throwable e) {
                    return 0;
                }
                if (current instanceof String) {
                    String s = ((String) current).trim();
                    if (!s.isEmpty()) {
                        return 0;
                    }
                } else if (current != null) {
                    return 0;
                }
                for (Method sm : target.getClass().getMethods()) {
                    if (!setter.equals(sm.getName()) || sm.getParameterCount() != 1) {
                        continue;
                    }
                    Class<?> p = sm.getParameterTypes()[0];
                    if (String.class.equals(p)) {
                        try {
                            sm.invoke(target, value);
                            return 1;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return 0;
        }

        private BOrd preferredComponentOrd(Object componentObj, String fallbackOrdText) {
            try {
                for (Method m : componentObj.getClass().getMethods()) {
                    if (!"getHandleOrd".equals(m.getName()) || m.getParameterCount() != 0) {
                        continue;
                    }
                    Object ord = m.invoke(componentObj);
                    if (ord instanceof BOrd) {
                        return (BOrd) ord;
                    }
                }
            } catch (Throwable ignored) {
            }
            return BOrd.make(localOrd(fallbackOrdText));
        }

        private List<Object> collectLinkOrdState(Object component) {
            List<Object> out = new ArrayList<>();
            try {
                for (Method m : component.getClass().getMethods()) {
                    if (!"getLinks".equals(m.getName()) || m.getParameterCount() != 0) {
                        continue;
                    }
                    Object linksObj = m.invoke(component);
                    if (linksObj == null) {
                        return out;
                    }
                    if (linksObj.getClass().isArray()) {
                        int len = java.lang.reflect.Array.getLength(linksObj);
                        for (int i = 0; i < len; i++) {
                            out.add(singleLinkOrdState(java.lang.reflect.Array.get(linksObj, i)));
                        }
                        return out;
                    }
                    if (linksObj instanceof Iterable) {
                        for (Object link : (Iterable<?>) linksObj) {
                            out.add(singleLinkOrdState(link));
                        }
                        return out;
                    }
                }
            } catch (Throwable ignored) {
            }
            return out;
        }

        private Map<String, Object> singleLinkOrdState(Object link) {
            Map<String, Object> state = new LinkedHashMap<>();
            if (link == null) {
                state.put("link", "null");
                return state;
            }
            state.put("class", link.getClass().getSimpleName());
            state.put("endpointOrd", invokeNoArgToString(link, "getEndpointOrd"));
            state.put("sourceOrd", invokeNoArgToString(link, "getSourceOrd"));
            state.put("targetOrd", invokeNoArgToString(link, "getTargetOrd"));
            state.put("sourceSlotName", invokeNoArgToString(link, "getSourceSlotName"));
            state.put("targetSlotName", invokeNoArgToString(link, "getTargetSlotName"));
            return state;
        }

        private String invokeNoArgToString(Object target, String methodName) {
            try {
                Method m = target.getClass().getMethod(methodName);
                Object v = m.invoke(target);
                return v == null ? null : String.valueOf(v);
            } catch (Throwable ignored) {
                return null;
            }
        }

    private List<Object> collectLinks(Object component, Object slotObj) {
        List<Object> links = new ArrayList<>();
        if (component == null) {
            return links;
        }
        for (Method m : component.getClass().getMethods()) {
            if (!"getLinks".equals(m.getName())) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            try {
                Object result;
                if (params.length == 0) {
                    result = m.invoke(component);
                } else if (params.length == 1 && slotObj != null) {
                    Object[] args = new Object[1];
                    if (!adaptValueForType(slotObj, params[0], args, 0)) {
                        continue;
                    }
                    result = m.invoke(component, args);
                } else {
                    continue;
                }
                addLinkResults(links, result);
            } catch (Throwable ignored) {
            }
        }
        return links;
    }

    private void addLinkResults(List<Object> links, Object result) {
        if (result == null) {
            return;
        }
        if (result instanceof Iterable) {
            for (Object o : (Iterable<?>) result) {
                links.add(String.valueOf(o));
            }
            return;
        }
        if (result.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(result);
            for (int i = 0; i < len; i++) {
                links.add(String.valueOf(java.lang.reflect.Array.get(result, i)));
            }
            return;
        }
        links.add(String.valueOf(result));
    }

    private List<LayoutNode> readLayoutNodes(Object rawComponents, String rootOrd, Context cx) {
        List<LayoutNode> nodes = new ArrayList<>();
        if (rawComponents instanceof List) {
            List<?> list = (List<?>) rawComponents;
            for (Object item : list) {
                if (!(item instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> comp = (Map<String, Object>) item;
                String ord = asString(comp.get("ord"));
                if (ord == null || ord.trim().isEmpty()) {
                    continue;
                }
                String name = asString(comp.get("name"));
                if (name == null || name.trim().isEmpty()) {
                    int cut = ord.lastIndexOf('/');
                    name = cut > 0 && cut < ord.length() - 1 ? ord.substring(cut + 1) : ord;
                }
                String type = asString(comp.get("type"));
                String ws = asString(comp.get("wsAnnotation"));
                int[] wsParts = parseWsAnnotationParts(ws);
                int currentWidth = wsParts == null ? 0 : wsParts[2];
                int currentHeight = wsParts == null ? 0 : wsParts[3];
                int visibleSlotCount = intOrDefault(comp.get("visibleSlotCount"), -1);
                boolean comment = comp.get("isComment") instanceof Boolean
                        ? ((Boolean) comp.get("isComment")).booleanValue()
                        : looksLikeComment(type, name);
                nodes.add(new LayoutNode(ord, name, type, ws, comment, currentWidth, currentHeight, visibleSlotCount));
            }
            return nodes;
        }

        Object rootObj = BOrd.make(localOrd(rootOrd)).get(null, cx);
        if (!(rootObj instanceof BComponent)) {
            return nodes;
        }
        BComponent root = (BComponent) rootObj;
        BComponent[] children = root.getChildComponents();
        if (children == null) {
            return nodes;
        }
        for (BComponent child : children) {
            String childName = safeComponentName(child);
            String ord = rootOrd + "/" + childName;
            String type = safeComponentType(child);
            String ws = readWsAnnotation(child);
            int[] wsParts = parseWsAnnotationParts(ws);
            int currentWidth = wsParts == null ? 0 : wsParts[2];
            int currentHeight = wsParts == null ? 0 : wsParts[3];
            int visibleSlotCount = countVisiblePropertySlots(child);
            boolean comment = looksLikeComment(type, childName);
            nodes.add(new LayoutNode(ord, childName, type, ws, comment, currentWidth, currentHeight, visibleSlotCount));
        }
        return nodes;
    }

    private List<LayoutEdge> readLayoutEdges(Object rawLinks, Map<String, LayoutNode> byOrd) {
        List<LayoutEdge> edges = new ArrayList<>();
        if (!(rawLinks instanceof List)) {
            return edges;
        }
        List<?> links = (List<?>) rawLinks;
        for (Object item : links) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> link = (Map<String, Object>) item;
            String from = normalizeEndpoint(asString(link.get("from")), "out");
            String to = normalizeEndpoint(asString(link.get("to")), "in10");
            Endpoint src = parseEndpoint(from, "out");
            Endpoint dst = parseEndpoint(to, "in10");
            if (src == null || dst == null) {
                continue;
            }
            if (!byOrd.containsKey(src.componentOrd) || !byOrd.containsKey(dst.componentOrd)) {
                continue;
            }
            edges.add(new LayoutEdge(src.componentOrd, dst.componentOrd));
        }
        return edges;
    }

    private void assignLayers(List<LayoutNode> nodes,
                              List<LayoutEdge> edges,
                              Map<String, LayoutNode> byOrd) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (LayoutNode node : nodes) {
            indegree.put(node.ord, Integer.valueOf(0));
            outgoing.put(node.ord, new ArrayList<String>());
            node.layer = 0;
        }
        for (LayoutEdge edge : edges) {
            List<String> outs = outgoing.get(edge.fromOrd);
            if (outs != null) {
                outs.add(edge.toOrd);
            }
            Integer d = indegree.get(edge.toOrd);
            if (d != null) {
                indegree.put(edge.toOrd, Integer.valueOf(d.intValue() + 1));
            }
        }

        List<LayoutNode> queue = new ArrayList<>();
        for (LayoutNode node : nodes) {
            Integer d = indegree.get(node.ord);
            if (d != null && d.intValue() == 0) {
                queue.add(node);
            }
        }
        sortNodesByName(queue);

        int idx = 0;
        while (idx < queue.size()) {
            LayoutNode node = queue.get(idx++);
            List<String> outs = outgoing.get(node.ord);
            if (outs == null) {
                continue;
            }
            for (String toOrd : outs) {
                LayoutNode target = byOrd.get(toOrd);
                if (target == null) {
                    continue;
                }
                if (target.layer < node.layer + 1) {
                    target.layer = node.layer + 1;
                }
                Integer d = indegree.get(toOrd);
                if (d == null) {
                    continue;
                }
                int next = d.intValue() - 1;
                indegree.put(toOrd, Integer.valueOf(next));
                if (next == 0) {
                    queue.add(target);
                    sortNodesByName(queue.subList(idx, queue.size()));
                }
            }
        }
    }

    private void assignGridPositions(List<LayoutNode> nodes,
                                     List<LayoutEdge> edges,
                                     int originX,
                                     int originY,
                                     int spacingX,
                                     int spacingY,
                                     int width,
                                     int height) {
        List<LayoutNode> nonComments = new ArrayList<>();
        List<LayoutNode> comments = new ArrayList<>();
        for (LayoutNode node : nodes) {
            if (node.comment) {
                comments.add(node);
            } else {
                nonComments.add(node);
            }
        }

        sortNodesByLayerThenName(nonComments);
        Map<Integer, Integer> layerNextY = new HashMap<>();
        List<LayoutBox> occupied = new ArrayList<>();

        for (LayoutNode node : nonComments) {
            int x = originX + (node.layer * spacingX);
            int nodeWidth = deriveLayoutWidth(node, width);
            int nodeHeight = deriveLayoutHeight(node, height);
            int y = layerNextY.containsKey(Integer.valueOf(node.layer))
                    ? layerNextY.get(Integer.valueOf(node.layer)).intValue()
                    : originY;
            while (hasOverlap(occupied, x, y, nodeWidth, nodeHeight)) {
                y += Math.max(1, spacingY);
            }
            occupied.add(new LayoutBox(x, y, nodeWidth, nodeHeight));
            layerNextY.put(Integer.valueOf(node.layer), Integer.valueOf(y + nodeHeight + spacingY));
            node.targetWsAnnotation = formatWsAnnotation(x, y, nodeWidth, nodeHeight);
            node.gridX = x;
            node.gridY = y;
        }

        sortNodesByName(comments);
        for (LayoutNode comment : comments) {
            LayoutNode anchor = findCommentAnchor(comment, nonComments, edges);
            int layer = anchor == null ? 0 : anchor.layer;
            int x = originX + (layer * spacingX);
            int nodeWidth = deriveLayoutWidth(comment, width);
            int nodeHeight = deriveLayoutHeight(comment, height);
            int y = layerNextY.containsKey(Integer.valueOf(layer))
                    ? layerNextY.get(Integer.valueOf(layer)).intValue()
                    : originY;
            while (hasOverlap(occupied, x, y, nodeWidth, nodeHeight)) {
                y += Math.max(1, spacingY);
            }
            occupied.add(new LayoutBox(x, y, nodeWidth, nodeHeight));
            layerNextY.put(Integer.valueOf(layer), Integer.valueOf(y + nodeHeight + spacingY));
            comment.layer = layer;
            comment.gridX = x;
            comment.gridY = y;
            comment.targetWsAnnotation = formatWsAnnotation(x, y, nodeWidth, nodeHeight);
        }
    }

    private int deriveLayoutWidth(LayoutNode node, int defaultWidth) {
        int base = Math.max(1, defaultWidth);
        return Math.max(base, node.currentWidth);
    }

    private int deriveLayoutHeight(LayoutNode node, int defaultHeight) {
        int base = Math.max(1, defaultHeight);
        if (node.comment) {
            return Math.max(base, node.currentHeight);
        }
        int estimatedFromSlots = Math.max(base, 3);
        if (node.visibleSlotCount > 0) {
            // Tune slot-to-height scaling to keep large blocks readable without over-stretching.
            int rows = (node.visibleSlotCount + 3) / 4;
            estimatedFromSlots = 2 + rows;
            if (estimatedFromSlots > 10) {
                estimatedFromSlots = 10;
            }
        }
        int desired = Math.max(base, estimatedFromSlots);
        if (node.currentHeight <= 0) {
            return desired;
        }
        // Keep small manual tweaks, but normalize oversized persisted auto-heights.
        if (node.currentHeight <= desired + 2) {
            return Math.max(node.currentHeight, desired);
        }
        return desired;
    }

    private boolean hasOverlap(List<LayoutBox> occupied, int x, int y, int width, int height) {
        for (LayoutBox box : occupied) {
            if (rectanglesOverlap(x, y, width, height, box.x, box.y, box.width, box.height)) {
                return true;
            }
        }
        return false;
    }

    private boolean rectanglesOverlap(int x1, int y1, int w1, int h1,
                                      int x2, int y2, int w2, int h2) {
        return x1 < (x2 + w2)
                && (x1 + w1) > x2
                && y1 < (y2 + h2)
                && (y1 + h1) > y2;
    }

    private LayoutNode findCommentAnchor(LayoutNode comment,
                                         List<LayoutNode> nonComments,
                                         List<LayoutEdge> edges) {
        for (LayoutEdge edge : edges) {
            if (comment.ord.equals(edge.fromOrd)) {
                for (LayoutNode node : nonComments) {
                    if (edge.toOrd.equals(node.ord)) {
                        return node;
                    }
                }
            }
            if (comment.ord.equals(edge.toOrd)) {
                for (LayoutNode node : nonComments) {
                    if (edge.fromOrd.equals(node.ord)) {
                        return node;
                    }
                }
            }
        }
        return nonComments.isEmpty() ? null : nonComments.get(0);
    }

    private void sortNodesByName(List<LayoutNode> nodes) {
        Collections.sort(nodes, (a, b) -> a.name.compareToIgnoreCase(b.name));
    }

    private void sortNodesByLayerThenName(List<LayoutNode> nodes) {
        Collections.sort(nodes, (a, b) -> {
            if (a.layer != b.layer) {
                return a.layer < b.layer ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    private String readWsAnnotation(Object component) {
        String getterValue = invokeNoArgToString(component, "getWsAnnotation");
        if (getterValue != null && !getterValue.trim().isEmpty()) {
            return getterValue;
        }

        Object wsSlot = resolveSlotObject(component, "wsAnnotation");
        if (wsSlot == null) {
            return null;
        }
        for (Method method : component.getClass().getMethods()) {
            if (!"get".equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            Object[] args = new Object[1];
            if (!adaptValueForType(wsSlot, method.getParameterTypes()[0], args, 0)) {
                continue;
            }
            try {
                Object value = method.invoke(component, args);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private int[] parseWsAnnotationParts(String ws) {
        if (ws == null || ws.trim().isEmpty()) {
            return null;
        }
        String[] parts = ws.split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int width = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 20;
            int height = parts.length > 3 ? Integer.parseInt(parts[3].trim()) : 2;
            return new int[]{x, y, width, height};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int countVisiblePropertySlots(BComponent component) {
        if (component == null) {
            return 0;
        }
        try {
            Method getProperties = component.getClass().getMethod("getProperties");
            Object cursor = getProperties.invoke(component);
            if (cursor == null) {
                return 0;
            }

            Method nextMethod = findCursorNextMethod(cursor.getClass());
            Method slotMethod = findCursorSlotMethod(cursor.getClass());
            if (nextMethod == null || slotMethod == null) {
                return 0;
            }

            int visible = 0;
            while (Boolean.TRUE.equals(nextMethod.invoke(cursor))) {
                Object slot = slotMethod.invoke(cursor);
                if (slot == null) {
                    continue;
                }
                String slotName = invokeNoArgToString(slot, "getName");
                if ("wsAnnotation".equals(slotName)) {
                    continue;
                }
                if (isHiddenSlot(component, slot)) {
                    continue;
                }
                visible++;
            }
            if (visible > 0) {
                return visible;
            }
            return countVisibleSlotsFromFields(component);
        } catch (Throwable ignored) {
            return countVisibleSlotsFromFields(component);
        }
    }

    private int countVisibleSlotsFromFields(BComponent component) {
        try {
            Class<?> slotClass = Class.forName("javax.baja.sys.Slot");
            Set<String> names = new HashSet<>();
            for (java.lang.reflect.Field field : component.getClass().getFields()) {
                if (!slotClass.isAssignableFrom(field.getType())) {
                    continue;
                }
                if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) {
                    continue;
                }
                Object slot = field.get(null);
                if (slot == null) {
                    continue;
                }
                String slotName = invokeNoArgToString(slot, "getName");
                if (slotName == null || slotName.trim().isEmpty()) {
                    slotName = field.getName();
                }
                if ("wsAnnotation".equals(slotName)) {
                    continue;
                }
                if (isHiddenSlot(component, slot)) {
                    continue;
                }
                names.add(slotName);
            }
            return names.size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private Method findCursorNextMethod(Class<?> cursorClass) {
        for (String name : Arrays.asList("next", "nextProperty", "nextComponent")) {
            try {
                Method m = cursorClass.getMethod(name);
                if (boolean.class.equals(m.getReturnType()) || Boolean.class.equals(m.getReturnType())) {
                    return m;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Method findCursorSlotMethod(Class<?> cursorClass) {
        for (String name : Arrays.asList("property", "slot", "get")) {
            try {
                Method m = cursorClass.getMethod(name);
                if (m.getParameterCount() == 0) {
                    return m;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean isHiddenSlot(BComponent component, Object slot) {
        try {
            Class<?> flagsClass = Class.forName("javax.baja.sys.Flags");
            Class<?> bComplexClass = Class.forName("javax.baja.sys.BComplex");
            Class<?> slotClass = Class.forName("javax.baja.sys.Slot");
            Method isHidden = flagsClass.getMethod("isHidden", bComplexClass, slotClass);
            Object result = isHidden.invoke(null, component, slot);
            return result instanceof Boolean && ((Boolean) result).booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String safeComponentName(BComponent component) {
        try {
            String name = component.getName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        } catch (Throwable ignored) {
        }
        return component.getClass().getSimpleName();
    }

    private String safeComponentType(BComponent component) {
        try {
            Method m = component.getClass().getMethod("getType");
            Object type = m.invoke(component);
            if (type != null) {
                for (String typeMethod : new String[]{"getTypeSpec", "getTypeName", "toString"}) {
                    try {
                        Method tm = type.getClass().getMethod(typeMethod);
                        Object v = tm.invoke(type);
                        if (v != null) {
                            return String.valueOf(v);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return component.getClass().getSimpleName();
    }

    private boolean looksLikeComment(String type, String name) {
        String t = type == null ? "" : type.toLowerCase();
        String n = name == null ? "" : name.toLowerCase();
        return t.contains("textblock") || n.contains("text") || n.contains("comment") || n.contains("note");
    }

    private String formatWsAnnotation(int x, int y, int width, int height) {
        return x + "," + y + "," + width + "," + height;
    }

    private int intOrDefault(Object raw, int fallback) {
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw instanceof String) {
            try {
                return Integer.parseInt((String) raw);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String baseInputSchema() {
        return "{\"type\":\"object\","
                + "\"properties\":{"
                + "  \"rootOrd\":{\"type\":\"string\",\"description\":\"Allowlisted root ORD for validation scope\"},"
                + "  \"operations\":{\"type\":\"array\",\"description\":\"Declarative operation list. "
                + "createComponent operations accept an optional 'facets' object with keys: "
                + "units, precision, min, max, trueText, falseText.\"},"
                + "  \"strict\":{\"type\":\"boolean\",\"description\":\"Enable strict type checks (default true). "
                + "When false, kitControl:/baja:/nre: prefix types are accepted.\"}"
                + "},"
                + "\"required\":[\"rootOrd\",\"operations\"]}";
    }

    private ValidationResult validate(Map<String, Object> arguments) {
        List<ValidationIssue> errors = new ArrayList<>();
        List<Object> warnings = new ArrayList<>();
        List<Map<String, Object>> normalized = new ArrayList<>();

        if (arguments == null) {
            errors.add(invalidShape(
                    "arguments",
                    "tools/call arguments must be an object",
                    "Send an object with rootOrd and operations."));
            return new ValidationResult(null, true, normalized, errors, warnings);
        }

        String rootOrd = asString(arguments.get("rootOrd"));
        if (isBlank(rootOrd)) {
            errors.add(missingField("arguments.rootOrd", "rootOrd", null,
                    "Include an allowlisted rootOrd such as station:|slot:/Drivers."));
            return new ValidationResult(rootOrd, true, normalized, errors, warnings);
        }
        try {
            security.checkAllowlist(rootOrd);
        } catch (NiagaraSecurity.McpSecurityException e) {
            errors.add(securityIssue(
                    e,
                    "arguments.rootOrd",
                    "Use a rootOrd under one of the allowlisted roots."));
            return new ValidationResult(rootOrd, true, normalized, errors, warnings);
        }

        Object rawStrict = arguments.get("strict");
        boolean strict = rawStrict == null ? true : asBoolean(rawStrict);

        Object rawOps = arguments.get("operations");
        if (!(rawOps instanceof List)) {
            if (rawOps == null) {
                errors.add(missingField("arguments.operations", "operations", null,
                        "Provide an array of operation objects."));
            } else {
                errors.add(invalidShape(
                        "arguments.operations",
                        "operations must be an array",
                        "Wrap operations in a JSON array."));
            }
            return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
        }

        List<?> ops = (List<?>) rawOps;
        for (int i = 0; i < ops.size(); i++) {
            Object raw = ops.get(i);
            if (!(raw instanceof Map)) {
                errors.add(invalidShape(
                        operationPath(i),
                        "Operation at index " + i + " must be an object",
                        "Each operations entry must be a JSON object."));
                return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> op = (Map<String, Object>) raw;
            Map<String, Object> no = new LinkedHashMap<>();
            no.put("index", Integer.valueOf(i));
            no.put("id", asString(op.get("id")));

            String type = asString(op.get("type"));
            no.put("type", type);

            if (isBlank(type)) {
                errors.add(missingField(
                        operationFieldPath(i, "type"),
                        "type",
                        ALLOWED_OPERATION_TYPES,
                        "Set type to one of the supported operation kinds."));
                return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
            }

            if (!ALLOWED_OPERATION_TYPES.contains(type)) {
                errors.add(invalidEnum(
                        operationFieldPath(i, "type"),
                        "Operation at index " + i + " has unsupported type: " + type,
                        ALLOWED_OPERATION_TYPES,
                        "Use one of the allowed operation type values."));
                return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
            }

            if ("createComponent".equals(type)) {
                String parentOrd = asString(op.get("parentOrd"));
                String name = asString(op.get("name"));
                String componentType = asString(op.get("componentType"));
                no.put("parentOrd", parentOrd);
                no.put("name", name);
                no.put("componentType", componentType);
                Object facets = op.get("facets");
                if (facets != null) {
                    no.put("facets", facets);
                }
                if (!requireStringField(i, "parentOrd", parentOrd, errors)) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (!requireStringField(i, "name", name, errors)) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (!requireStringField(i, "componentType", componentType, errors)) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (!allowlistedOrError(parentOrd, operationFieldPath(i, "parentOrd"), errors,
                        "Use a parentOrd under the allowlisted roots.")) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                boolean isKnownPrefix = componentType.startsWith("control:")
                        || componentType.startsWith("kitControl:")
                        || componentType.startsWith("baja:")
                        || componentType.startsWith("nre:");
                if (!isKnownPrefix) {
                    if (strict) {
                        errors.add(invalidEnum(
                                operationFieldPath(i, "componentType"),
                                "Operation at index " + i
                                        + " uses unsupported componentType for strict mode: " + componentType,
                                Arrays.<Object>asList("control:*"),
                                "Use control:* type, or pass strict:false to allow wider type prefixes."));
                        return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                    }
                    warnings.add("Operation at index " + i
                            + " uses non-standard componentType: " + componentType);
                } else if (strict && !componentType.startsWith("control:")) {
                    errors.add(invalidEnum(
                            operationFieldPath(i, "componentType"),
                            "Operation at index " + i
                                    + " uses unsupported componentType for strict mode: " + componentType,
                            Arrays.<Object>asList("control:*"),
                            "Set strict:false to permit kitControl:/baja:/nre: types."));
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
            }

            if ("setSlot".equals(type)) {
                String componentOrd = asString(op.get("componentOrd"));
                Integer priority = asPriority(op.get("priority"));
                String rawSlot = asString(op.get("slot"));
                String slot = normalizeSetSlot(rawSlot, priority);
                Object value = op.get("value");
                no.put("componentOrd", componentOrd);
                no.put("slot", slot);
                if (priority != null) {
                    no.put("priority", priority);
                }
                no.put("value", value);
                if (!requireStringField(i, "componentOrd", componentOrd, errors)) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (rawSlot == null && op.containsKey("priority") && priority == null) {
                    errors.add(invalidEnum(
                            operationFieldPath(i, "priority"),
                            "Operation at index " + i + " has invalid priority; expected integer 1-16",
                            Arrays.<Object>asList("1", "2", "3", "4", "5", "6", "7", "8",
                                    "9", "10", "11", "12", "13", "14", "15", "16"),
                            "Set priority to an integer between 1 and 16."));
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (value == null) {
                    errors.add(missingField(
                            operationFieldPath(i, "value"),
                            "value",
                            null,
                            "Provide a concrete value for setSlot operations."));
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (!allowlistedOrError(componentOrd, operationFieldPath(i, "componentOrd"), errors,
                        "Use a componentOrd under the allowlisted roots.")) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
            }

            if ("link".equals(type)) {
                String from = normalizeEndpoint(asString(op.get("from")), "out");
                String to = normalizeEndpoint(asString(op.get("to")), "in10");
                no.put("from", from);
                no.put("to", to);
                if (!requireStringField(i, "from", from, errors)) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (!requireStringField(i, "to", to, errors)) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (!allowlistedOrError(from, operationFieldPath(i, "from"), errors,
                        "Use a source endpoint under the allowlisted roots.")) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (!allowlistedOrError(to, operationFieldPath(i, "to"), errors,
                        "Use a destination endpoint under the allowlisted roots.")) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
            }

            if ("addCompositePin".equals(type)) {
                String folderOrd = asString(op.get("folderOrd"));
                String pinName = asString(op.get("pinName"));
                String targetComponentOrd = asString(op.get("targetComponentOrd"));
                String targetSlot = asString(op.get("targetSlot"));
                String direction = asString(op.get("direction"));
                no.put("folderOrd", folderOrd);
                no.put("pinName", pinName);
                no.put("targetComponentOrd", targetComponentOrd);
                no.put("targetSlot", targetSlot);
                no.put("direction", direction);
                if (!requireStringField(i, "folderOrd", folderOrd, errors)
                        || !requireStringField(i, "pinName", pinName, errors)
                        || !requireStringField(i, "targetComponentOrd", targetComponentOrd, errors)
                        || !requireStringField(i, "targetSlot", targetSlot, errors)) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (!"in".equals(direction) && !"out".equals(direction)) {
                    errors.add(invalidEnum(
                            operationFieldPath(i, "direction"),
                            "Operation at index " + i + " addCompositePin requires direction: 'in' or 'out'",
                            Arrays.<Object>asList("in", "out"),
                            "Set direction to 'in' or 'out'."));
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (!allowlistedOrError(folderOrd, operationFieldPath(i, "folderOrd"), errors,
                        "Use a folderOrd under the allowlisted roots.")) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
                if (!allowlistedOrError(targetComponentOrd, operationFieldPath(i, "targetComponentOrd"), errors,
                        "Use a targetComponentOrd under the allowlisted roots.")) {
                    return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
                }
            }

            normalized.add(no);
        }

        return new ValidationResult(rootOrd, strict, normalized, errors, warnings);
    }

    private boolean allowlistedOrError(String ord,
                                       String path,
                                       List<ValidationIssue> errors,
                                       String hint) {
        try {
            security.checkAllowlist(ord);
            return true;
        } catch (NiagaraSecurity.McpSecurityException e) {
            errors.add(securityIssue(e, path, hint));
            return false;
        }
    }

    private boolean requireStringField(int index,
                                       String field,
                                       String value,
                                       List<ValidationIssue> errors) {
        if (isBlank(value)) {
            errors.add(missingField(
                    operationFieldPath(index, field),
                    field,
                    null,
                    "Populate operations[" + index + "]." + field + " with a non-empty value."));
            return false;
        }
        return true;
    }

    private String normalizeEndpoint(String endpoint, String defaultSlot) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return endpoint;
        }
        if (hasExplicitLinkSlot(endpoint)) {
            return endpoint;
        }
        return endpoint + "/" + defaultSlot;
    }

    private String normalizeSetSlot(String slot, Integer priority) {
        if (slot != null && !slot.trim().isEmpty()) {
            return slot;
        }
        if (priority != null) {
            return "in" + priority.intValue();
        }
        // Writable points treat fallback/set as lowest-priority value.
        return "fallback";
    }

    private Integer asPriority(Object value) {
        if (value == null) {
            return null;
        }
        int p;
        if (value instanceof Number) {
            p = ((Number) value).intValue();
        } else {
            try {
                p = Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (p < 1 || p > 16) {
            return null;
        }
        return Integer.valueOf(p);
    }

    private static final String CODE_VALIDATION_MISSING_FIELD = "NMCP_VALIDATION_MISSING_FIELD";
    private static final String CODE_VALIDATION_INVALID_ENUM = "NMCP_VALIDATION_INVALID_ENUM";
    private static final String CODE_VALIDATION_INVALID_SHAPE = "NMCP_VALIDATION_INVALID_SHAPE";
    private static final String CODE_PATH_NOT_ALLOWLISTED = "NMCP_PATH_NOT_ALLOWLISTED";
    private static final String CODE_WRITE_DISABLED = "NMCP_WRITE_DISABLED";

    private McpToolResult validationError(ValidationIssue issue) {
        return McpToolResult.error(
                issue.message,
                issue.code,
                issue.path,
                issue.hint,
                issue.allowedValues);
    }

    private ValidationIssue missingField(String path,
                                         String field,
                                         List<Object> allowedValues,
                                         String hint) {
        return new ValidationIssue(
                CODE_VALIDATION_MISSING_FIELD,
                "Missing required field: " + field,
                path,
                hint,
                allowedValues);
    }

    private ValidationIssue invalidEnum(String path,
                                        String message,
                                        List<Object> allowedValues,
                                        String hint) {
        return new ValidationIssue(
                CODE_VALIDATION_INVALID_ENUM,
                message,
                path,
                hint,
                allowedValues);
    }

    private ValidationIssue invalidShape(String path,
                                         String message,
                                         String hint) {
        return new ValidationIssue(
                CODE_VALIDATION_INVALID_SHAPE,
                message,
                path,
                hint,
                null);
    }

    private ValidationIssue securityIssue(NiagaraSecurity.McpSecurityException e,
                                          String path,
                                          String hint) {
        return new ValidationIssue(
                mapSecurityCode(e),
                e.getMessage(),
                path,
                hint,
                null);
    }

    private McpToolResult securityError(NiagaraSecurity.McpSecurityException e,
                                        String path,
                                        String hint) {
        ValidationIssue issue = securityIssue(e, path, hint);
        return validationError(issue);
    }

    private String mapSecurityCode(NiagaraSecurity.McpSecurityException e) {
        if (e == null) {
            return "NMCP_UNKNOWN_ERROR";
        }
        if (e.getCode() == McpErrors.PATH_NOT_ALLOWLISTED) {
            return CODE_PATH_NOT_ALLOWLISTED;
        }
        if (e.getCode() == McpErrors.READONLY_VIOLATION) {
            return CODE_WRITE_DISABLED;
        }
        if (e.getCode() == McpErrors.INVALID_PARAMS) {
            return CODE_VALIDATION_MISSING_FIELD;
        }
        return "NMCP_SECURITY_ERROR";
    }

    private String operationPath(int index) {
        return "arguments.operations[" + index + "]";
    }

    private String operationFieldPath(int index, String field) {
        return operationPath(index) + "." + field;
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private List<Map<String, Object>> sortForExecution(List<Map<String, Object>> operations) {
        List<Map<String, Object>> creates = new ArrayList<>();
        List<Map<String, Object>> updates = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        List<Map<String, Object>> other = new ArrayList<>();

        for (Map<String, Object> op : operations) {
            String type = asString(op.get("type"));
            if ("createComponent".equals(type)) {
                creates.add(op);
            } else if ("setSlot".equals(type)) {
                updates.add(op);
            } else if ("link".equals(type)) {
                links.add(op);
            } else {
                other.add(op);
            }
        }

        List<Map<String, Object>> sorted = new ArrayList<>();
        sorted.addAll(creates);
        sorted.addAll(updates);
        sorted.addAll(links);
        sorted.addAll(other);
        return sorted;
    }

    private int countType(List<Map<String, Object>> operations, String type) {
        int count = 0;
        for (Map<String, Object> op : operations) {
            if (type.equals(asString(op.get("type")))) {
                count++;
            }
        }
        return count;
    }

    private String operationKey(Map<String, Object> op) {
        String type = asString(op.get("type"));
        if ("createComponent".equals(type)) {
            return "create|" + asString(op.get("parentOrd")) + "|"
                    + asString(op.get("name")) + "|" + asString(op.get("componentType"));
        }
        if ("setSlot".equals(type)) {
            return "set|" + asString(op.get("componentOrd")) + "|"
                    + asString(op.get("slot")) + "|" + asString(op.get("value"));
        }
        if ("link".equals(type)) {
            return "link|" + asString(op.get("from")) + "|" + asString(op.get("to"));
        }
        return "other|" + op.toString();
    }

    private int asInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return false;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Strips any leading remote-transport scheme segments (e.g. "local:|foxwss:|") so
     * that the ORD can be resolved against the local station context via a plain
     * {@code station:|} path.  If no {@code station:|} anchor is present the original
     * ORD is returned unchanged.
     */
    private static String localOrd(String ord) {
        if (ord == null) {
            return null;
        }
        int idx = ord.indexOf("station:|");
        if (idx > 0) {
            return ord.substring(idx);
        }
        return ord;
    }

    private static Map<String, List<Object>> buildRequiredFieldsByType() {
        Map<String, List<Object>> map = new LinkedHashMap<>();
        map.put("createComponent", Arrays.<Object>asList("parentOrd", "name", "componentType"));
        map.put("setSlot", Arrays.<Object>asList("componentOrd", "value"));
        map.put("link", Arrays.<Object>asList("from", "to"));
        map.put("addCompositePin", Arrays.<Object>asList(
                "folderOrd", "pinName", "targetComponentOrd", "targetSlot", "direction"));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<Object>> buildOptionalFieldsByType() {
        Map<String, List<Object>> map = new LinkedHashMap<>();
        map.put("createComponent", Arrays.<Object>asList("id", "facets"));
        map.put("setSlot", Arrays.<Object>asList("id", "slot", "priority"));
        map.put("link", Arrays.<Object>asList("id"));
        map.put("addCompositePin", Arrays.<Object>asList("id"));
        return Collections.unmodifiableMap(map);
    }

    private static final class ValidationIssue {
        private final String code;
        private final String message;
        private final String path;
        private final String hint;
        private final List<Object> allowedValues;

        private ValidationIssue(String code,
                                String message,
                                String path,
                                String hint,
                                List<Object> allowedValues) {
            this.code = code;
            this.message = message;
            this.path = path;
            this.hint = hint;
            this.allowedValues = allowedValues == null
                    ? new ArrayList<Object>()
                    : new ArrayList<>(allowedValues);
        }
    }

    private static final class ValidationResult {
        private final String rootOrd;
        private final boolean strict;
        private final List<Map<String, Object>> normalizedOperations;
        private final List<ValidationIssue> errors;
        private final List<Object> warnings;

        private ValidationResult(String rootOrd,
                                 boolean strict,
                                 List<Map<String, Object>> normalizedOperations,
                                 List<ValidationIssue> errors,
                                 List<Object> warnings) {
            this.rootOrd = rootOrd;
            this.strict = strict;
            this.normalizedOperations = normalizedOperations;
            this.errors = errors;
            this.warnings = warnings;
        }
    }

    private static final class Endpoint {
        private final String componentOrd;
        private final String slot;

        private Endpoint(String componentOrd, String slot) {
            this.componentOrd = componentOrd;
            this.slot = slot;
        }
    }

    private static final class LayoutNode {
        private final String ord;
        private final String name;
        private final String type;
        private final String currentWsAnnotation;
        private final boolean comment;
        private final int currentWidth;
        private final int currentHeight;
        private final int visibleSlotCount;
        private int layer;
        private int gridX;
        private int gridY;
        private String targetWsAnnotation;

        private LayoutNode(String ord,
                           String name,
                           String type,
                           String currentWsAnnotation,
                           boolean comment,
                           int currentWidth,
                           int currentHeight,
                           int visibleSlotCount) {
            this.ord = ord;
            this.name = name;
            this.type = type;
            this.currentWsAnnotation = currentWsAnnotation;
            this.comment = comment;
            this.currentWidth = currentWidth;
            this.currentHeight = currentHeight;
            this.visibleSlotCount = visibleSlotCount;
            this.layer = 0;
            this.gridX = 0;
            this.gridY = 0;
            this.targetWsAnnotation = currentWsAnnotation;
        }
    }

    private static final class LayoutBox {
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private LayoutBox(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private static final class LayoutEdge {
        private final String fromOrd;
        private final String toOrd;

        private LayoutEdge(String fromOrd, String toOrd) {
            this.fromOrd = fromOrd;
            this.toOrd = toOrd;
        }
    }
}