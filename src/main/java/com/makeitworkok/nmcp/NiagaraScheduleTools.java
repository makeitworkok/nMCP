// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.naming.BOrd;
import javax.baja.schedule.BWeeklySchedule;
import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MCP tools for Niagara schedule components.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code nmcp.schedule.read} – current occupancy state and next transition for one schedule</li>
 *   <li>{@code nmcp.schedule.list} – list all schedules in the station with their current state</li>
 * </ul>
 *
 * <p>Read tools are always available. {@code nmcp.schedule.write} requires {@code readOnly=false}.
 */
public final class NiagaraScheduleTools {

    private static final Logger LOG = Logger.getLogger(NiagaraScheduleTools.class.getName());
    private final NiagaraSecurity security;

    public NiagaraScheduleTools(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(scheduleRead());
        list.add(scheduleList());
        list.add(scheduleWrite());
        return list;
    }

    // -------------------------------------------------------------------------
    // nmcp.schedule.read
    // -------------------------------------------------------------------------

    private McpTool scheduleRead() {
        return new McpTool() {
            @Override public String name() { return "nmcp.schedule.read"; }

            @Override public String description() {
                return "Reads a schedule component by ORD: current occupancy state, "
                        + "next transition time (epoch ms), and next state. "
                        + "ORD must be within allowlisted roots.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Schedule component ORD\"}"
                        + "},"
                        + "\"required\":[\"ord\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String ord = getStringArg(arguments, "ord");
                if (ord == null) return McpToolResult.error("Missing required argument: ord");
                try {
                    security.checkAllowlist(ord);
                    Object obj = BOrd.make(ord).get(null, cx);
                    if (!(obj instanceof BWeeklySchedule)) {
                        return McpToolResult.error("ORD is not a BWeeklySchedule: " + ord);
                    }
                    BWeeklySchedule sched = (BWeeklySchedule) obj;
                    String state  = "unknown"; try { state  = sched.getEffectiveState(cx);        } catch (Throwable ignored) {}
                    long nextMs   = 0L;        try { nextMs  = sched.getNextTransitionMillis(cx);  } catch (Throwable ignored) {}
                    String nextSt = "unknown"; try { nextSt  = sched.getNextTransitionState(cx);   } catch (Throwable ignored) {}
                    return McpToolResult.success(NiagaraJson.obj(
                            "ord", ord, "currentState", state,
                            "nextTransitionMs", nextMs, "nextState", nextSt));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.schedule.read error for " + ord + ": " + e);
                    return McpToolResult.error("Schedule read error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.schedule.list
    // -------------------------------------------------------------------------

    private McpTool scheduleList() {
        return new McpTool() {
            @Override public String name() { return "nmcp.schedule.list"; }

            @Override public String description() {
                return "Lists all BWeeklySchedule components under the configured root "
                        + "with their current occupancy state. Quick overview of occupancy across the building.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"root\":{\"type\":\"string\","
                        + "    \"description\":\"Root ORD to search (default station:|slot:/Drivers)\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max results\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String root = getStringArg(arguments, "root");
                if (root == null || root.isEmpty()) root = "station:|slot:/Drivers";
                Integer limit = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit);
                try {
                    security.checkAllowlist(root);
                    Object obj = BOrd.make(root).get(null, cx);
                    if (!(obj instanceof BComponent)) {
                        return McpToolResult.error("Root ORD not a component: " + root);
                    }
                    List<Object> schedules = new ArrayList<>();
                    collectSchedules((BComponent) obj, root, schedules, effectiveLimit, cx);
                    return McpToolResult.success(NiagaraJson.obj(
                            "root", root, "count", schedules.size(), "schedules", schedules));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.schedule.list error: " + e);
                    return McpToolResult.error("Schedule list error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.schedule.write
    // -------------------------------------------------------------------------

    private McpTool scheduleWrite() {
        return new McpTool() {
            @Override public String name() { return "nmcp.schedule.write"; }

            @Override public String description() {
                return "Sets the defaultOutput slot on a BWeeklySchedule — effectively the fallback occupancy state. "
                        + "For finer-grained event editing (weekly program entries) use Workbench. "
                        + "Requires readOnly=false and ORD within allowlisted roots. "
                        + "'state' must be the desired default output string (e.g. 'occupied', 'unoccupied').";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Schedule component ORD\"},"
                        + "  \"state\":{\"type\":\"string\",\"description\":\"New default output state (e.g. occupied, unoccupied)\"}"
                        + "},"
                        + "\"required\":[\"ord\",\"state\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String ord   = getStringArg(arguments, "ord");
                String state = getStringArg(arguments, "state");
                if (ord == null)   return McpToolResult.error("Missing required argument: ord");
                if (state == null) return McpToolResult.error("Missing required argument: state");
                try {
                    security.checkReadOnly();
                    security.checkAllowlist(ord);
                    Object obj = BOrd.make(localOrd(ord)).get(null, cx);
                    if (!(obj instanceof BWeeklySchedule)) {
                        return McpToolResult.error("ORD is not a BWeeklySchedule: " + ord);
                    }
                    BWeeklySchedule sched = (BWeeklySchedule) obj;
                    String detail = writeScheduleDefaultOutput(sched, state, cx);
                    return McpToolResult.success(NiagaraJson.obj(
                            "ord", ord, "state", state, "status", "applied", "detail", detail));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.schedule.write error for " + ord + ": " + e);
                    return McpToolResult.error("Schedule write error: " + e.getMessage());
                }
            }
        };
    }

    /**
     * Attempts to write the defaultOutput slot on the schedule.
     * Tries setDefaultOutput(BValue, Context), then setDefaultOutput(BValue),
     * then BComplex.set("defaultOutput", BValue).
     */
    private static String writeScheduleDefaultOutput(BWeeklySchedule sched, String state, Context cx) {
        javax.baja.sys.BValue bval = javax.baja.sys.BString.make(state);

        // Strategy 1: setDefaultOutput(BValue, Context)
        for (java.lang.reflect.Method m : sched.getClass().getMethods()) {
            if ("setDefaultOutput".equals(m.getName())) {
                Class<?>[] params = m.getParameterTypes();
                try {
                    if (params.length == 2 && Context.class.isAssignableFrom(params[1])) {
                        m.invoke(sched, bval, cx);
                        return "set via setDefaultOutput(BValue, Context)";
                    } else if (params.length == 1) {
                        m.invoke(sched, bval);
                        return "set via setDefaultOutput(BValue)";
                    }
                } catch (Throwable e) {
                    // try next
                }
            }
        }
        // Strategy 2: BComplex.set(String, BValue)
        try {
            sched.set("defaultOutput", bval);
            return "set via BComplex.set(\"defaultOutput\", BValue)";
        } catch (Throwable e) {
            // fall through
        }
        return "no suitable setter found — schedule write not supported on this platform";
    }

    private static String localOrd(String ord) {
        if (ord == null) return null;
        String s = ord;
        if (s.startsWith("local:|foxwss:|")) s = s.substring("local:|foxwss:|".length());
        else if (s.startsWith("foxwss:|"))    s = s.substring("foxwss:|".length());
        else if (s.startsWith("local:|"))     s = s.substring("local:|".length());
        return s;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void collectSchedules(BComponent comp, String parentOrd,
                                   List<Object> out, int limit, Context cx) {
        if (out.size() >= limit) return;
        try {
            BComponent[] children = comp.getChildComponents();
            if (children == null) return;
            for (BComponent child : children) {
                if (out.size() >= limit) break;
                String childName = "";
                try { childName = child.getName(); } catch (Throwable ignored) {}
                String childOrd = parentOrd.endsWith("/")
                        ? parentOrd + childName : parentOrd + "/" + childName;
                if (child instanceof BWeeklySchedule) {
                    BWeeklySchedule s = (BWeeklySchedule) child;
                    String state = "unknown";
                    try { state = s.getEffectiveState(cx); } catch (Throwable ignored) {}
                    out.add(NiagaraJson.obj("ord", childOrd, "name", childName, "currentState", state));
                }
                collectSchedules(child, childOrd, out, limit, cx);
            }
        } catch (Throwable e) {
            LOG.warning("collectSchedules error at " + parentOrd + ": " + e.getMessage());
        }
    }

    private String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString() : null;
    }

    private Integer getIntArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
