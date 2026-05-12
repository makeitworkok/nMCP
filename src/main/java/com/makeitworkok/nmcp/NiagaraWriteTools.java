// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
                return "Writes a value to a writable point at operator priority (in10). "
                        + "Requires readOnly=false and ORD within allowlisted roots. "
                        + "value must be a number (for NumericWritable) or boolean (for BooleanWritable). "
                        + "To release the override pass null as value.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Writable point ORD\"},"
                        + "  \"value\":{\"description\":\"Value to write (number, boolean, string, or null to release)\"}"
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
                return "Writes an operator override (in8 priority) to a writable point. "
                        + "Higher priority than normal write. "
                        + "Requires readOnly=false and ORD within allowlisted roots. "
                        + "Pass null as value to release the override.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Writable point ORD\"},"
                        + "  \"value\":{\"description\":\"Override value (number, boolean, or null to release)\"}"
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
                return "Invokes a named action on a component. "
                        + "Common actions: 'active', 'inactive', 'enable', 'disable', 'reset'. "
                        + "Requires readOnly=false and ORD within allowlisted roots. "
                        + "Optional 'arg' is passed to the action if it accepts a parameter.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Component ORD\"},"
                        + "  \"action\":{\"type\":\"string\",\"description\":\"Action name (e.g. 'active', 'disable', 'reset')\"},"
                        + "  \"arg\":{\"description\":\"Optional argument value for the action\"}"
                        + "},"
                        + "\"required\":[\"ord\",\"action\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String ord    = asString(arguments.get("ord"));
                String action = asString(arguments.get("action"));
                if (ord == null)    return McpToolResult.error("Missing required argument: ord");
                if (action == null) return McpToolResult.error("Missing required argument: action");
                Object arg = arguments.get("arg");
                try {
                    security.checkReadOnly();
                    security.checkAllowlist(ord);
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
                return "Requests a controlled restart of the Niagara station. "
                        + "Requires readOnly=false. "
                        + "This is a destructive, irreversible operation — the station will be unavailable during restart. "
                        + "Optional 'reason' string is logged before the restart is issued.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"reason\":{\"type\":\"string\",\"description\":\"Reason for restart (logged)\"}"
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
                            Method m = station.getClass().getMethod("restart", Context.class);
                            m.invoke(station, cx);
                            restarted = true;
                            detail = "restart invoked";
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
                return "Triggers network discovery on a driver network component and adds discovered devices. "
                        + "Requires readOnly=false and networkOrd within allowlisted roots. "
                        + "networkOrd must point to a BIpNetwork or BacnetNetwork component. "
                        + "Discovery is asynchronous — results appear in the network's device list after completion.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"networkOrd\":{\"type\":\"string\",\"description\":\"Driver network component ORD\"}"
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
                    }
                } catch (Throwable e) {
                    LOG.fine(actionName + "() invocation failed: " + e);
                }
            }
        }

        return "no suitable invoke method found for action '" + actionName + "'";
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
