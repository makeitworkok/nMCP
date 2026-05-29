// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.history.BHistoryId;
import javax.baja.history.BIHistory;
import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * MCP tool for exporting a station memory schema for agent-side local stores.
 */
public final class NiagaraStationTools {

    private static final Logger LOG = Logger.getLogger(NiagaraStationTools.class.getName());

    private final NiagaraSecurity security;

    public NiagaraStationTools(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(exportSchema());
        return list;
    }

    private McpTool exportSchema() {
        return new McpTool() {
            @Override public String name() { return "nmcp.station.exportSchema"; }

            @Override public String description() {
                return "Exports station topology JSON for agent memory stores. "
                        + "Supports selective inclusion of points, devices, schedules, histories, and direct links.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"rootOrd\":{\"type\":\"string\",\"description\":\"Root ORD scope (default station:|slot:/)\"},"
                        + "  \"includePoints\":{\"type\":\"boolean\",\"description\":\"Include point definitions (default true)\"},"
                        + "  \"includeDevices\":{\"type\":\"boolean\",\"description\":\"Include device definitions (default true)\"},"
                        + "  \"includeSchedules\":{\"type\":\"boolean\",\"description\":\"Include schedule definitions (default true)\"},"
                        + "  \"includeHistories\":{\"type\":\"boolean\",\"description\":\"Include history definitions (default true)\"},"
                        + "  \"includeLinks\":{\"type\":\"boolean\",\"description\":\"Include direct wiresheet links (default true)\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String rootOrd = getStringArg(arguments, "rootOrd");
                if (rootOrd == null || rootOrd.trim().isEmpty()) {
                    rootOrd = "station:|slot:/";
                }
                String normalizedRoot = localOrd(rootOrd);

                boolean includePoints = getBooleanArg(arguments, "includePoints", true);
                boolean includeDevices = getBooleanArg(arguments, "includeDevices", true);
                boolean includeSchedules = getBooleanArg(arguments, "includeSchedules", true);
                boolean includeHistories = getBooleanArg(arguments, "includeHistories", true);
                boolean includeLinks = getBooleanArg(arguments, "includeLinks", true);

                int maxPerSection = security.effectiveLimit(null);

                try {
                    security.checkAllowlist(normalizedRoot);
                    Object rootObj = BOrd.make(normalizedRoot).get(null, cx);
                    if (!(rootObj instanceof BComponent)) {
                        return McpToolResult.error("rootOrd is not a component: " + normalizedRoot);
                    }

                    ExportState state = new ExportState(maxPerSection, includePoints, includeDevices,
                            includeSchedules, includeLinks);
                    walkComponentTree((BComponent) rootObj, normalizedRoot, state, cx);

                    if (includeHistories) {
                        collectHistories(state, cx);
                    }

                    Map<String, Object> metadata = NiagaraJson.obj(
                            "schemaVersion", "1.0",
                            "exportedAtMs", System.currentTimeMillis(),
                            "rootOrd", normalizedRoot,
                            "truncated", state.truncated,
                            "include", NiagaraJson.obj(
                                    "points", includePoints,
                                    "devices", includeDevices,
                                    "schedules", includeSchedules,
                                    "histories", includeHistories,
                                    "links", includeLinks
                            )
                    );

                    Map<String, Object> summary = NiagaraJson.obj(
                            "deviceCount", state.devices.size(),
                            "pointCount", state.points.size(),
                            "scheduleCount", state.schedules.size(),
                            "historyCount", state.histories.size(),
                            "linkCount", state.links.size()
                    );

                    Map<String, Object> station = NiagaraJson.obj(
                            "metadata", metadata,
                            "summary", summary,
                            "devices", state.devices,
                            "points", state.points,
                            "schedules", state.schedules,
                            "histories", state.histories,
                            "links", state.links
                    );

                    return McpToolResult.success(NiagaraJson.obj("station", station));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.station.exportSchema error: " + e);
                    return McpToolResult.error("Station schema export error: " + e.getMessage());
                }
            }
        };
    }

    private void walkComponentTree(BComponent component, String ord, ExportState state, Context cx) {
        if (component == null) {
            return;
        }
        try {
            String typeName = safeTypeName(component);
            String name = safeName(component);
            String displayName = safeDisplayName(component, cx);

            if (state.includeDevices && isDeviceType(typeName)) {
                addEntity(state.devices, NiagaraJson.obj(
                        "ord", ord,
                        "name", name,
                        "displayName", displayName,
                        "type", typeName,
                        "status", safeSlotString(component, "status")
                ), state);
            }

            if (state.includePoints && isPointType(typeName)) {
                Map<String, Object> point = NiagaraJson.obj(
                        "ord", ord,
                        "name", name,
                        "displayName", displayName,
                        "type", typeName
                );
                point.putAll(NiagaraPointTools.readPointValue(component));
                addEntity(state.points, point, state);
            }

            if (state.includeSchedules && isScheduleType(typeName)) {
                addEntity(state.schedules, NiagaraJson.obj(
                        "ord", ord,
                        "name", name,
                        "displayName", displayName,
                        "type", typeName,
                        "currentState", safeInvokeString(component, "getEffectiveState", cx)
                ), state);
            }

            if (state.includeLinks) {
                collectDirectLinks(component, state);
            }

            BComponent[] children = component.getChildComponents();
            if (children == null) {
                return;
            }
            for (BComponent child : children) {
                String childName = safeName(child);
                String childOrd = ord.endsWith("/") ? ord + childName : ord + "/" + childName;
                walkComponentTree(child, childOrd, state, cx);
            }
        } catch (Throwable e) {
            LOG.fine("walkComponentTree error at " + ord + ": " + e.getMessage());
        }
    }

    private void collectHistories(ExportState state, Context cx) {
        try {
            BIHistory[] histories = NiagaraHistoryTools.getHistories(cx);
            for (BIHistory history : histories) {
                if (state.histories.size() >= state.maxPerSection) {
                    state.truncated = true;
                    break;
                }
                String id = "";
                try {
                    BHistoryId hid = history.getId();
                    id = hid != null ? hid.toString() : "";
                } catch (Throwable ignored) {
                }
                String displayName = NiagaraHistoryTools.displayNameFor(history, cx);
                addEntity(state.histories, NiagaraJson.obj(
                        "id", id,
                        "displayName", displayName
                ), state);
            }
        } catch (Throwable e) {
            addEntity(state.histories, NiagaraJson.obj("_error", "History export error: " + e.getMessage()), state);
        }
    }

    private void collectDirectLinks(BComponent component, ExportState state) {
        Method[] methods = component.getClass().getMethods();
        for (Method method : methods) {
            if (!"getLinks".equals(method.getName()) || method.getParameterTypes().length != 0) {
                continue;
            }
            try {
                Object linksObj = method.invoke(component);
                addLinksResult(linksObj, state);
            } catch (Throwable ignored) {
            }
        }
    }

    private void addLinksResult(Object linksObj, ExportState state) {
        if (linksObj == null) {
            return;
        }
        if (linksObj.getClass().isArray()) {
            int len = Array.getLength(linksObj);
            for (int i = 0; i < len; i++) {
                addSingleLink(Array.get(linksObj, i), state);
            }
            return;
        }
        if (linksObj instanceof Iterable) {
            for (Object link : (Iterable<?>) linksObj) {
                addSingleLink(link, state);
            }
            return;
        }
        addSingleLink(linksObj, state);
    }

    private void addSingleLink(Object linkObj, ExportState state) {
        if (linkObj == null || state.links.size() >= state.maxPerSection) {
            if (state.links.size() >= state.maxPerSection) {
                state.truncated = true;
            }
            return;
        }
        String sourceOrd = invokeNoArgToString(linkObj, "getSourceOrd");
        String targetOrd = invokeNoArgToString(linkObj, "getTargetOrd");
        String sourceSlot = invokeNoArgToString(linkObj, "getSourceSlotName");
        String targetSlot = invokeNoArgToString(linkObj, "getTargetSlotName");

        String source = appendSlot(sourceOrd, sourceSlot);
        String target = appendSlot(targetOrd, targetSlot);

        if (isBlank(source) && isBlank(target)) {
            return;
        }

        String key = String.valueOf(source) + "->" + String.valueOf(target);
        if (!state.linkKeys.add(key)) {
            return;
        }

        addEntity(state.links, NiagaraJson.obj(
                "source", source,
                "target", target
        ), state);
    }

    private static String appendSlot(String ord, String slot) {
        if (isBlank(ord)) {
            return ord;
        }
        if (isBlank(slot)) {
            return ord;
        }
        return ord + "/" + slot;
    }

    private static boolean isPointType(String typeName) {
        if (typeName == null) {
            return false;
        }
        return typeName.contains("Point") || typeName.contains("ProxyPoint");
    }

    private static boolean isDeviceType(String typeName) {
        if (typeName == null) {
            return false;
        }
        String lower = typeName.toLowerCase();
        return lower.contains("device") && !lower.contains("point");
    }

    private static boolean isScheduleType(String typeName) {
        if (typeName == null) {
            return false;
        }
        return typeName.contains("WeeklySchedule") || typeName.contains("Schedule");
    }

    private void addEntity(List<Object> list, Object value, ExportState state) {
        if (list.size() >= state.maxPerSection) {
            state.truncated = true;
            return;
        }
        list.add(value);
    }

    private static String safeTypeName(BComponent c) {
        try {
            return c.getType() != null ? c.getType().getTypeName() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeName(BComponent c) {
        try {
            return c.getName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeDisplayName(BComponent c, Context cx) {
        try {
            return c.getDisplayName(cx);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeSlotString(BComponent c, String slotName) {
        try {
            Object v = c.get(slotName);
            return v != null ? v.toString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeInvokeString(Object target, String methodName, Context cx) {
        try {
            Method m = target.getClass().getMethod(methodName, Context.class);
            Object v = m.invoke(target, cx);
            return v != null ? v.toString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String invokeNoArgToString(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v != null ? String.valueOf(v) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String localOrd(String ord) {
        if (ord == null) return null;
        String s = ord;
        if (s.startsWith("local:|foxwss:|")) s = s.substring("local:|foxwss:|".length());
        else if (s.startsWith("foxwss:|")) s = s.substring("foxwss:|".length());
        else if (s.startsWith("local:|")) s = s.substring("local:|".length());
        return s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString() : null;
    }

    private boolean getBooleanArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static final class ExportState {
        final int maxPerSection;
        final boolean includePoints;
        final boolean includeDevices;
        final boolean includeSchedules;
        final boolean includeLinks;

        final List<Object> devices = new ArrayList<>();
        final List<Object> points = new ArrayList<>();
        final List<Object> schedules = new ArrayList<>();
        final List<Object> histories = new ArrayList<>();
        final List<Object> links = new ArrayList<>();
        final Set<String> linkKeys = new LinkedHashSet<>();

        boolean truncated;

        ExportState(int maxPerSection, boolean includePoints, boolean includeDevices,
                    boolean includeSchedules, boolean includeLinks) {
            this.maxPerSection = maxPerSection;
            this.includePoints = includePoints;
            this.includeDevices = includeDevices;
            this.includeSchedules = includeSchedules;
            this.includeLinks = includeLinks;
            this.truncated = false;
        }
    }
}
