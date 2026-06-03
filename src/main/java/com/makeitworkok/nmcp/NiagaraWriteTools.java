// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MCP write tools — all require {@code readOnly=false}.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code nmcp.point.write}             – write a value to a point's in10 priority slot</li>
 *   <li>{@code nmcp.point.override}           – override (operator) a point and optionally set expiry</li>
 *   <li>{@code nmcp.component.invokeAction}   – invoke a named action on a component</li>
 *   <li>{@code nmcp.station.restart}          – request a controlled station restart</li>
 *   <li>{@code nmcp.driver.discoverAndAdd}    – trigger network discovery on a driver network</li>
 * </ul>
 *
 * <p>Every method calls {@code security.checkReadOnly()} before any mutation.
 */
public final class NiagaraWriteTools {

    private static final Logger LOG = Logger.getLogger(NiagaraWriteTools.class.getName());
    private final NiagaraSecurity security;

    public NiagaraWriteTools(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(pointWrite());
        list.add(pointOverride());
        list.add(invokeAction());
        list.add(stationRestart());
        list.add(driverDiscoverAndAdd());
        return list;
    }

    // -------------------------------------------------------------------------
    // nmcp.point.write
    // -------------------------------------------------------------------------

    private McpTool pointWrite() {
        return new McpTool() {
            @Override public String name() { return "nmcp.point.write"; }

            @Override public String description() {
                return "Write-mode required (BMcpService readOnly=false). Writes a value to an allowlisted writable point at operator priority in10; "
                    + "pass null to release that priority slot and let lower priorities flow through.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Allowlisted writable point ORD, usually control:NumericWritable or control:BooleanWritable\"},"
                        + "  \"value\":{\"description\":\"Value for in10: number, boolean, string, or null to release the operator priority slot\"}"
                        + "},"
                        + "\"required\":[\"ord\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String ord = asString(arguments.get("ord"));
                if (ord == null) return McpToolResult.error("Missing required argument: ord");
                Object value = arguments.get("value");
                try {
                    security.checkReadOnly();
                    security.checkAllowlist(ord);
                    Object obj = BOrd.make(localOrd(ord)).get(null, cx);
                    if (!(obj instanceof BComponent)) {
                        return McpToolResult.error("ORD did not resolve to a component: " + ord);
                    }
                    BComponent point = (BComponent) obj;
                    String writeResult = invokePointWrite(point, "in10", value, cx);
                    return McpToolResult.success(NiagaraJson.obj(
                            "ord", ord,
                            "slot", "in10",
                            "value", value,
                            "status", "applied",
                            "detail", writeResult));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.point.write error for " + ord + ": " + e);
                    return McpToolResult.error("Point write error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.point.override
    // -------------------------------------------------------------------------

    private McpTool pointOverride() {
        return new McpTool() {
            @Override public String name() { return "nmcp.point.override"; }

            @Override public String description() {
                return "Write-mode required (BMcpService readOnly=false). Writes an operator override at priority in8 on an allowlisted writable point; "
                    + "higher priority than point.write, and null releases the override.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Allowlisted writable point ORD to override at priority in8\"},"
                        + "  \"value\":{\"description\":\"Override value for in8: number, boolean, string, or null to release the override\"}"
                        + "},"
                        + "\"required\":[\"ord\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String ord = asString(arguments.get("ord"));
                if (ord == null) return McpToolResult.error("Missing required argument: ord");
                Object value = arguments.get("value");
                try {
                    security.checkReadOnly();
                    security.checkAllowlist(ord);
                    Object obj = BOrd.make(localOrd(ord)).get(null, cx);
                    if (!(obj instanceof BComponent)) {
                        return McpToolResult.error("ORD did not resolve to a component: " + ord);
                    }
                    BComponent point = (BComponent) obj;
                    String writeResult = invokePointWrite(point, "in8", value, cx);
                    return McpToolResult.success(NiagaraJson.obj(
                            "ord", ord,
                            "slot", "in8",
                            "value", value,
                            "status", "applied",
                            "detail", writeResult));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.point.override error for " + ord + ": " + e);
                    return McpToolResult.error("Point override error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.component.invokeAction
    // -------------------------------------------------------------------------

    private McpTool invokeAction() {
        return new McpTool() {
            @Override public String name() { return "nmcp.component.invokeAction"; }

            @Override public String description() {
                return "Invokes a named action on an allowlisted component. Use debug=true first to inspect action "
                    + "resolution without mutation; actual invocation requires write mode and may change live state.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Allowlisted component ORD that owns the action\"},"
                        + "  \"action\":{\"type\":\"string\",\"description\":\"Action name, e.g. active, inactive, enable, disable, reset, emergencyAuto\"},"
                        + "  \"arg\":{\"description\":\"Optional argument value passed only if the action accepts a parameter\"},"
                        + "  \"debug\":{\"type\":\"boolean\",\"description\":\"If true, return action-resolution diagnostics without invoking or requiring write mode\"}"
                        + "},"
                        + "\"required\":[\"ord\",\"action\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String ord    = asString(arguments.get("ord"));
                String action = asString(arguments.get("action"));
                if (ord == null)    return McpToolResult.error("Missing required argument: ord");
                if (action == null) return McpToolResult.error("Missing required argument: action");
                Object arg = arguments.get("arg");
                boolean debug = asBoolean(arguments.get("debug"), false);
                try {
                    security.checkAllowlist(ord);
                    if (debug) {
                        return McpToolResult.success(createActionDebugReport(ord, action, arg, cx));
                    }

                    security.checkReadOnly();
                    Object obj = BOrd.make(localOrd(ord)).get(null, cx);
                    if (!(obj instanceof BComponent)) {
                        return McpToolResult.error("ORD did not resolve to a component: " + ord);
                    }
                    BComponent comp = (BComponent) obj;
                    String result = invokeComponentAction(comp, action, arg, cx);
                    return McpToolResult.success(NiagaraJson.obj(
                            "ord", ord,
                            "action", action,
                            "status", "applied",
                            "detail", result));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.component.invokeAction error for " + ord + "/" + action + ": " + e);
                    return McpToolResult.error("Action invocation error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.station.restart
    // -------------------------------------------------------------------------

    private McpTool stationRestart() {
        return new McpTool() {
            @Override public String name() { return "nmcp.station.restart"; }

            @Override public String description() {
                return "Write-mode required and disruptive. Requests a controlled Niagara station restart; the station "
                    + "will be unavailable during restart and the optional reason is logged for audit context.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"reason\":{\"type\":\"string\",\"description\":\"Optional human-readable reason logged before restart; default MCP-initiated restart\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String reason = asString(arguments.get("reason"));
                if (reason == null) reason = "MCP-initiated restart";
                try {
                    security.checkReadOnly();
                    LOG.warning("nmcp.station.restart requested via MCP. Reason: " + reason);
                    // Invoke BStation.restart(Context) reflectively — avoid direct station stub dependency
                    boolean restarted = false;
                    String detail = "no restart method found";
                    try {
                        Class<?> sysClass = Class.forName("javax.baja.sys.Sys");
                        Object station = sysClass.getMethod("getStation").invoke(null);
                        if (station != null) {
                            Method[] methods = station.getClass().getMethods();
                            for (Method method : methods) {
                                if (!"restart".equals(method.getName())) {
                                    continue;
                                }
                                Class<?>[] params = method.getParameterTypes();
                                try {
                                    if (params.length == 0) {
                                        method.invoke(station);
                                        restarted = true;
                                        detail = "restart invoked via restart()";
                                        break;
                                    }
                                    if (params.length == 1 && Context.class.isAssignableFrom(params[0])) {
                                        method.invoke(station, cx);
                                        restarted = true;
                                        detail = "restart invoked via restart(Context)";
                                        break;
                                    }
                                    if (params.length == 1) {
                                        method.invoke(station, new Object[] { null });
                                        restarted = true;
                                        detail = "restart invoked via restart(Object)";
                                        break;
                                    }
                                } catch (Throwable e) {
                                    detail = "restart invocation failed: " + e.getMessage();
                                }
                            }
                        }
                    } catch (Throwable e) {
                        detail = "restart invocation failed: " + e.getMessage();
                    }
                    return McpToolResult.success(NiagaraJson.obj(
                            "status", restarted ? "requested" : "failed",
                            "reason", reason,
                            "detail", detail));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    return McpToolResult.error("Station restart error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.driver.discoverAndAdd
    // -------------------------------------------------------------------------

    private McpTool driverDiscoverAndAdd() {
        return new McpTool() {
            @Override public String name() { return "nmcp.driver.discoverAndAdd"; }

            @Override public String description() {
                return "Write-mode required. Triggers asynchronous driver-network discovery/add on an allowlisted network; "
                    + "use bacnet.discover or bacnet.devices afterward to inspect results when discovery completes.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"networkOrd\":{\"type\":\"string\",\"description\":\"Allowlisted driver network ORD, e.g. station:|slot:/Drivers/BacnetNetwork\"}"
                        + "},"
                        + "\"required\":[\"networkOrd\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String networkOrd = asString(arguments.get("networkOrd"));
                if (networkOrd == null) return McpToolResult.error("Missing required argument: networkOrd");
                try {
                    security.checkReadOnly();
                    security.checkAllowlist(networkOrd);
                    Object obj = BOrd.make(localOrd(networkOrd)).get(null, cx);
                    if (!(obj instanceof BComponent)) {
                        return McpToolResult.error("networkOrd did not resolve to a component: " + networkOrd);
                    }
                    BComponent network = (BComponent) obj;
                    String result = invokeDiscovery(network, cx);
                    return McpToolResult.success(NiagaraJson.obj(
                            "networkOrd", networkOrd,
                            "status", "requested",
                            "detail", result));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.driver.discoverAndAdd error for " + networkOrd + ": " + e);
                    return McpToolResult.error("Discovery error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // Private write helpers
    // -------------------------------------------------------------------------

    /**
     * Writes a value to the named priority slot (e.g. "in10", "in8") on a
     * BNumericWritable / BBooleanWritable / BStringWritable.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Try {@code set(slotName, BValue)} via setSlot reflective pattern.</li>
     *   <li>Try {@code write(slotName, value, cx)} if available.</li>
     *   <li>Try setting the slot directly via {@code BComplex.set(String, BValue)}.</li>
     * </ol>
     *
     * <p>If value is null, attempts to call {@code setNull} or pass a null-equivalent BStatusValue.
     */
    private String invokePointWrite(BComponent point, String slotName, Object value, Context cx) {
        // Build a BValue from the raw argument (null → null-status BStatusNumeric to release the slot)
        Object bval = (value == null) ? tryMakeNullStatusNumeric() : toBValue(value);

        // Strategy 1: set<SlotName>(BValue, Context) named setter
        String capitalSlot = Character.toUpperCase(slotName.charAt(0)) + slotName.substring(1);
        for (Method m : point.getClass().getMethods()) {
            if (("set" + capitalSlot).equals(m.getName())) {
                Class<?>[] params = m.getParameterTypes();
                try {
                    if (params.length == 1) {
                        m.invoke(point, bval);
                        return "set via set" + capitalSlot + "(BValue)";
                    } else if (params.length == 2 && Context.class.isAssignableFrom(params[1])) {
                        m.invoke(point, bval, cx);
                        return "set via set" + capitalSlot + "(BValue, Context)";
                    }
                } catch (Throwable e) {
                    LOG.fine("set" + capitalSlot + " failed: " + e);
                }
            }
        }

        // Strategy 2: write(String slotName, Object value, Context) method
        for (Method m : point.getClass().getMethods()) {
            if ("write".equals(m.getName()) && m.getParameterTypes().length == 3) {
                try {
                    m.invoke(point, slotName, bval, cx);
                    return "set via write(String, BValue, Context)";
                } catch (Throwable e) {
                    LOG.fine("write(3) failed: " + e);
                }
            }
        }

        // Strategy 3: BComplex.set(String, BValue)
        try {
            point.set(slotName, bval != null ? (javax.baja.sys.BValue) bval : null);
            return "set via BComplex.set(String, BValue)";
        } catch (Throwable e) {
            LOG.fine("BComplex.set failed: " + e);
        }

        return "no suitable write method found on " + point.getClass().getSimpleName();
    }

    /**
     * Invokes a named action on a component.
     * Tries {@code invoke(String, BValue, Context)} and {@code invoke(Slot, BValue, Context)}.
     */
    private String invokeComponentAction(BComponent comp, String actionName, Object arg, Context cx) {
        Object barg = arg != null ? toBValue(arg) : null;

        if (isReleaseAction(actionName)) {
            String releaseResult = invokePointWrite(comp, "in8", null, cx);
            if (!releaseResult.startsWith("no suitable write method found")) {
                return "released override via in8 (" + releaseResult + ")";
            }
        }

        Object actionSlot = findActionSlot(comp, actionName);
        if (actionSlot != null) {
            String slotResult = invokeActionSlot(comp, actionSlot, barg, cx);
            if (slotResult != null) {
                return slotResult;
            }
        }

        // Try invoke(String, BValue, Context)
        for (Method m : comp.getClass().getMethods()) {
            if ("invoke".equals(m.getName())) {
                Class<?>[] params = m.getParameterTypes();
                try {
                    if (params.length == 3 && String.class.equals(params[0])) {
                        m.invoke(comp, actionName, barg, cx);
                        return "invoked via invoke(String, BValue, Context)";
                    }
                } catch (Throwable e) {
                    LOG.fine("invoke(String,...) failed: " + e);
                }
            }
        }

        // Try resolving the Slot then invoke(Slot, BValue, Context)
        try {
            javax.baja.sys.Slot slot = null;
            for (Method m : comp.getClass().getMethods()) {
                if ("getSlot".equals(m.getName()) && m.getParameterTypes().length == 1
                        && String.class.equals(m.getParameterTypes()[0])) {
                    slot = (javax.baja.sys.Slot) m.invoke(comp, actionName);
                    break;
                }
            }
            if (slot != null) {
                for (Method m : comp.getClass().getMethods()) {
                    if ("invoke".equals(m.getName()) && m.getParameterTypes().length == 3
                            && javax.baja.sys.Slot.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        m.invoke(comp, slot, barg, cx);
                        return "invoked via invoke(Slot, BValue, Context)";
                    }
                }
            }
        } catch (Throwable e) {
            LOG.fine("invoke(Slot,...) failed: " + e);
        }

        // Try direct named method (e.g. active(), disable())
        for (Method m : comp.getClass().getMethods()) {
            if (actionName.equals(m.getName())) {
                try {
                    if (m.getParameterTypes().length == 0) {
                        m.invoke(comp);
                        return "invoked via " + actionName + "()";
                    } else if (m.getParameterTypes().length == 1
                            && Context.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        m.invoke(comp, cx);
                        return "invoked via " + actionName + "(Context)";
                    } else if (m.getParameterTypes().length == 1
                            && !Context.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        Object converted = convertValueForType(barg, m.getParameterTypes()[0]);
                        if (converted == UNSET) {
                            continue;
                        }
                        if (barg == null && converted == null) {
                            converted = createDefaultActionArg(m.getParameterTypes()[0], comp, actionSlot, cx);
                        }
                        if (converted == UNSET) {
                            continue;
                        }
                        m.invoke(comp, converted);
                        return "invoked via " + actionName + "(Value)";
                    } else if (m.getParameterTypes().length == 2
                            && !Context.class.isAssignableFrom(m.getParameterTypes()[0])
                            && Context.class.isAssignableFrom(m.getParameterTypes()[1])) {
                        Object converted = convertValueForType(barg, m.getParameterTypes()[0]);
                        if (converted == UNSET) {
                            continue;
                        }
                        if (barg == null && converted == null) {
                            converted = createDefaultActionArg(m.getParameterTypes()[0], comp, actionSlot, cx);
                        }
                        if (converted == UNSET) {
                            continue;
                        }
                        m.invoke(comp, converted, cx);
                        return "invoked via " + actionName + "(Value, Context)";
                    }
                } catch (Throwable e) {
                    LOG.fine(actionName + "() invocation failed: " + e);
                }
            }
        }

        return "no suitable invoke method found for action '" + actionName + "'";
    }

    private boolean isReleaseAction(String actionName) {
        if (actionName == null) {
            return false;
        }
        String normalized = actionName.trim().toLowerCase();
        return "release".equals(normalized)
                || "clear".equals(normalized)
                || "reset".equals(normalized)
                || "deactivate".equals(normalized)
                || "unoverride".equals(normalized);
    }

    private Map<String, Object> createActionDebugReport(String ord, String actionName, Object arg, Context cx) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ord", ord);
        report.put("action", actionName);
        report.put("argType", arg == null ? "null" : arg.getClass().getName());

        try {
            Object obj = BOrd.make(localOrd(ord)).get(null, cx);
            report.put("componentClass", obj == null ? "<null>" : obj.getClass().getName());
            report.put("componentMethods", describePublicMethods(obj != null ? obj.getClass() : null, actionName));

            Object actionSlot = obj instanceof BComponent ? findActionSlot((BComponent) obj, actionName) : null;
            if (actionSlot != null) {
                report.put("actionSlotClass", actionSlot.getClass().getName());
                report.put("actionSlotMethods", describePublicMethods(actionSlot.getClass(), actionName));
            } else {
                report.put("actionSlotClass", "<not found>");
                report.put("actionSlotMethods", new ArrayList<Object>());
            }
        } catch (Throwable e) {
            report.put("debugError", describeThrowable(e));
        }

        return report;
    }

    private Object findActionSlot(BComponent comp, String actionName) {
        if (comp == null || actionName == null) {
            return null;
        }

        Object slot = invokeNoArgStringGetter(comp, "getSlot", actionName);
        if (slot == null) {
            slot = invokeNoArgStringGetter(comp, "getProperty", actionName);
        }
        if (slot == null) {
            slot = invokeNoArgStringGetter(comp, "getAction", actionName);
        }

        if (slot instanceof javax.baja.sys.Slot) {
            javax.baja.sys.Slot typedSlot = (javax.baja.sys.Slot) slot;
            if (typedSlot.isAction()) {
                return typedSlot;
            }
        }
        return slot;
    }

    private Object invokeNoArgStringGetter(Object target, String methodName, String value) {
        if (target == null || methodName == null) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !String.class.equals(params[0])) {
                continue;
            }
            try {
                return method.invoke(target, value);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String invokeActionSlot(BComponent comp, Object actionSlot, Object barg, Context cx) {
        if (actionSlot == null) {
            return null;
        }

        String[] methodNames = new String[] {"invoke", "doAction", "run", "call"};
        for (String methodName : methodNames) {
            for (Method m : actionSlot.getClass().getMethods()) {
                if (!methodName.equals(m.getName())) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                try {
                    if (params.length == 0) {
                        m.invoke(actionSlot);
                        return "invoked action slot via " + methodName + "()";
                    }
                    if (params.length == 1 && Context.class.isAssignableFrom(params[0])) {
                        m.invoke(actionSlot, cx);
                        return "invoked action slot via " + methodName + "(Context)";
                    }
                    if (params.length == 1 && isValueType(params[0])) {
                        Object converted = convertValueForType(barg, params[0]);
                        if (converted == UNSET) {
                            continue;
                        }
                        if (barg == null && converted == null) {
                            converted = createDefaultActionArg(params[0], comp, actionSlot, cx);
                        }
                        if (converted == UNSET) {
                            continue;
                        }
                        m.invoke(actionSlot, converted);
                        return "invoked action slot via " + methodName + "(Value)";
                    }
                    if (params.length == 2 && isValueType(params[0]) && Context.class.isAssignableFrom(params[1])) {
                        Object converted = convertValueForType(barg, params[0]);
                        if (converted == UNSET) {
                            continue;
                        }
                        if (barg == null && converted == null) {
                            converted = createDefaultActionArg(params[0], comp, actionSlot, cx);
                        }
                        if (converted == UNSET) {
                            continue;
                        }
                        m.invoke(actionSlot, converted, cx);
                        return "invoked action slot via " + methodName + "(Value, Context)";
                    }
                } catch (Throwable e) {
                    LOG.fine(methodName + " action-slot invocation failed: " + e);
                }
            }
        }

        return null;
    }

    private Object createDefaultActionArg(Class<?> targetType, BComponent comp, Object actionSlot, Context cx) {
        if (targetType == null || targetType.isPrimitive()) {
            return UNSET;
        }

        // Prefer Niagara action default parameter when available.
        if (comp != null && actionSlot != null) {
            for (Method m : comp.getClass().getMethods()) {
                if (!"getActionParameterDefault".equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1) {
                    continue;
                }
                if (!p[0].isInstance(actionSlot)) {
                    continue;
                }
                try {
                    Object candidate = m.invoke(comp, actionSlot);
                    if (candidate != null && targetType.isInstance(candidate)) {
                        return candidate;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // Try public DEFAULT constant.
        try {
            java.lang.reflect.Field f = targetType.getField("DEFAULT");
            Object candidate = f.get(null);
            if (candidate != null && targetType.isInstance(candidate)) {
                return candidate;
            }
        } catch (Throwable ignored) {
        }

        // Try static make() factory.
        for (Method m : targetType.getMethods()) {
            if (!"make".equals(m.getName())) {
                continue;
            }
            if (m.getParameterTypes().length != 0) {
                continue;
            }
            try {
                Object candidate = m.invoke(null);
                if (candidate != null && targetType.isInstance(candidate)) {
                    return candidate;
                }
            } catch (Throwable ignored) {
            }
        }

        // Final fallback: no-arg constructor.
        try {
            return targetType.getConstructor().newInstance();
        } catch (Throwable ignored) {
        }

        return UNSET;
    }

    private boolean isValueType(Class<?> type) {
        if (type == null) {
            return false;
        }
        return !type.isPrimitive() && !Context.class.isAssignableFrom(type)
                && !javax.baja.sys.Slot.class.isAssignableFrom(type)
                && !BComponent.class.isAssignableFrom(type);
    }

    private List<Object> describePublicMethods(Class<?> type, String filter) {
        List<Object> out = new ArrayList<>();
        if (type == null) {
            return out;
        }
        String normalizedFilter = filter == null ? null : filter.trim().toLowerCase();
        for (Method method : type.getMethods()) {
            String methodName = method.getName().toLowerCase();
            if (normalizedFilter != null && !normalizedFilter.isEmpty() && !methodName.contains(normalizedFilter)) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(method.getReturnType().getName()).append(" ");
            sb.append(method.getDeclaringClass().getName()).append(".");
            sb.append(method.getName()).append("(");
            Class<?>[] params = method.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(params[i].getName());
            }
            sb.append(")");
            out.add(sb.toString());
        }
        return out;
    }

    private String describeThrowable(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "<no message>";
        }
        return root.getClass().getName() + ": " + message;
    }

    /**
     * Triggers discovery on a driver network component.
     * Tries common Niagara discovery method names: discoverAndAdd, discover, startDiscover.
     */
    private String invokeDiscovery(BComponent network, Context cx) {
        String[] discoveryMethods = {"discoverAndAdd", "discover", "startDiscover", "doDiscover"};
        for (String methodName : discoveryMethods) {
            for (Method m : network.getClass().getMethods()) {
                if (methodName.equals(m.getName())) {
                    try {
                        if (m.getParameterTypes().length == 0) {
                            m.invoke(network);
                            return "triggered via " + methodName + "()";
                        } else if (m.getParameterTypes().length == 1
                                && Context.class.isAssignableFrom(m.getParameterTypes()[0])) {
                            m.invoke(network, cx);
                            return "triggered via " + methodName + "(Context)";
                        }
                    } catch (Throwable e) {
                        LOG.fine(methodName + " failed: " + e);
                    }
                }
            }
        }
        return "no discovery method found on " + network.getClass().getSimpleName()
                + " — discovery may need to be triggered from Workbench";
    }

    /**
     * Converts a raw JSON argument (Number, Boolean, String, null) to a Niagara BValue.
     * Uses reflection to avoid hard dependencies on control BStatusNumeric/BStatusBoolean.
     */
    private Object toBValue(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Double || raw instanceof Float) {
            double d = ((Number) raw).doubleValue();
            return tryMakeBStatusNumeric(d);
        }
        if (raw instanceof Number) {
            double d = ((Number) raw).doubleValue();
            return tryMakeBStatusNumeric(d);
        }
        if (raw instanceof Boolean) {
            return tryMakeBStatusBoolean((Boolean) raw);
        }
        if (raw instanceof String) {
            try {
                return javax.baja.sys.BString.make((String) raw);
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    private Object tryMakeBStatusNumeric(double value) {
        // BStatusNumeric(double) constructor — in javax.baja.status (baja.jar)
        try {
            Class<?> cls = Class.forName("javax.baja.status.BStatusNumeric");
            return cls.getConstructor(double.class).newInstance(value);
        } catch (Throwable ignored) {}
        // Fallback: BDouble.make(double)
        try {
            Class<?> cls = Class.forName("javax.baja.sys.BDouble");
            Method m = cls.getMethod("make", double.class);
            return m.invoke(null, value);
        } catch (Throwable ignored) {}
        return null;
    }

    private Object tryMakeBStatusBoolean(boolean value) {
        // BStatusBoolean(boolean) constructor — in javax.baja.status (baja.jar)
        try {
            Class<?> cls = Class.forName("javax.baja.status.BStatusBoolean");
            return cls.getConstructor(boolean.class).newInstance(value);
        } catch (Throwable ignored) {}
        try {
            return javax.baja.sys.BBoolean.make(value);
        } catch (Throwable ignored) {}
        return null;
    }

    private Object tryMakeNullStatusBoolean() {
        try {
            Class<?> cls = Class.forName("javax.baja.status.BStatusBoolean");
            Object obj = cls.getConstructor().newInstance();
            cls.getMethod("setStatusNull", boolean.class).invoke(obj, true);
            return obj;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Creates a BStatusNumeric with the null-status bit set (used to release a priority input). */
    private Object tryMakeNullStatusNumeric() {
        try {
            Class<?> cls = Class.forName("javax.baja.status.BStatusNumeric");
            Object obj = cls.getConstructor().newInstance();
            // setStatusNull is declared on BStatusValue
            cls.getMethod("setStatusNull", boolean.class).invoke(obj, true);
            return obj;
        } catch (Throwable ignored) {}
        return null;
    }

    private Object convertValueForType(Object value, Class<?> targetType) {
        if (targetType == null) {
            return UNSET;
        }
        if (value == null) {
            if (!targetType.isPrimitive()) {
                return null;
            }
            return UNSET;
        }
        if (targetType.isAssignableFrom(value.getClass())) {
            return value;
        }
        if (String.class.equals(targetType)) {
            return String.valueOf(value);
        }
        if (Integer.class.equals(targetType) || Integer.TYPE.equals(targetType)) {
            Integer i = asInt(value);
            return i != null ? i : UNSET;
        }
        if (Long.class.equals(targetType) || Long.TYPE.equals(targetType)) {
            Long l = asLong(value);
            return l != null ? l : UNSET;
        }
        if (Double.class.equals(targetType) || Double.TYPE.equals(targetType)) {
            Double d = asDouble(value);
            return d != null ? d : UNSET;
        }
        if (Float.class.equals(targetType) || Float.TYPE.equals(targetType)) {
            Double d = asDouble(value);
            return d != null ? Float.valueOf(d.floatValue()) : UNSET;
        }
        if (Boolean.class.equals(targetType) || Boolean.TYPE.equals(targetType)) {
            return Boolean.valueOf(asBoolean(value, false));
        }
        return UNSET;
    }

    private static boolean asBoolean(Object v, boolean defaultValue) {
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number) return Integer.valueOf(((Number) v).intValue());
        if (v instanceof String) {
            try {
                return Integer.valueOf(Integer.parseInt((String) v));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Long asLong(Object v) {
        if (v instanceof Number) return Long.valueOf(((Number) v).longValue());
        if (v instanceof String) {
            try {
                return Long.valueOf(Long.parseLong((String) v));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number) return Double.valueOf(((Number) v).doubleValue());
        if (v instanceof String) {
            try {
                return Double.valueOf(Double.parseDouble((String) v));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static final Object UNSET = new Object();

    private static String localOrd(String ord) {
        if (ord == null) return null;
        String s = ord;
        if (s.startsWith("local:|foxwss:|")) s = s.substring("local:|foxwss:|".length());
        else if (s.startsWith("foxwss:|"))    s = s.substring("foxwss:|".length());
        else if (s.startsWith("local:|"))     s = s.substring("local:|".length());
        return s;
    }

    private static String asString(Object v) {
        return v instanceof String ? (String) v : null;
    }
}
