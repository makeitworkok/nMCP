// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.sys.BComponent;
import javax.baja.sys.BStation;
import javax.baja.sys.BValue;
import javax.baja.sys.Context;
import javax.baja.sys.Property;
import javax.baja.sys.SlotCursor;
import javax.baja.sys.Sys;
import javax.baja.naming.BOrd;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MCP tools for Niagara component introspection.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code nmcp.station.info}      – basic station metadata</li>
 *   <li>{@code nmcp.component.read}    – read a component by ORD</li>
 *   <li>{@code nmcp.component.children} – list child components</li>
 *   <li>{@code nmcp.component.slots}   – list slots on a component</li>
 * </ul>
 */
public final class NiagaraComponentTools {

    private static final Logger LOG = Logger.getLogger(NiagaraComponentTools.class.getName());

    private final NiagaraSecurity security;
    private final String moduleVersion;

    public NiagaraComponentTools(NiagaraSecurity security, String moduleVersion) {
        this.security = security;
        this.moduleVersion = moduleVersion;
    }

    /** Returns all tools provided by this class. */
    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(stationInfo());
        list.add(componentRead());
        list.add(componentChildren());
        list.add(componentSlots());
        list.add(componentSearch());
        return list;
    }

    // -------------------------------------------------------------------------
    // nmcp.station.info
    // -------------------------------------------------------------------------

    private McpTool stationInfo() {
        return new McpTool() {
            @Override public String name() { return "nmcp.station.info"; }

            @Override public String description() {
                return "Returns basic information about the Niagara station: name, host ID, "
                        + "Niagara platform version, module version, host/system details, and read-only status.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                try {
                    BStation station = Sys.getStation();
                    String stationName = "unknown";
                    String hostId      = "unknown";
                    String niagaraVer  = "unknown";
                    if (station != null) {
                        try { stationName = station.getStationName(); } catch (Throwable ignored) {}
                        try { hostId      = station.getHostId();      } catch (Throwable ignored) {}
                        try { niagaraVer  = station.getNiagaraVersion(); } catch (Throwable ignored) {}
                    }
                    // Fallback: use platform service for version and hostId if station failed
                    String platformVer = detectPlatformVersionString(cx);
                    if (!isNonEmpty(niagaraVer) || "unknown".equals(niagaraVer)) {
                        niagaraVer = platformVer;
                    }
                    if ("unknown".equals(hostId)) {
                        String platformHostId = detectHostIdString(cx);
                        if (isNonEmpty(platformHostId)) {
                            hostId = platformHostId;
                        }
                    }

                    String platformStationName = detectStringSlot(cx, "stationName");
                    if ("unknown".equals(stationName) && isNonEmpty(platformStationName)) {
                        stationName = platformStationName;
                    }

                    Integer currentCpuUsage = detectIntSlot(cx, "currentCpuUsage");
                    String model = detectStringSlot(cx, "model");
                    String modelVersion = detectStringSlot(cx, "modelVersion");
                    Integer numCpus = detectIntSlot(cx, "numCpus");
                    String osName = detectStringSlot(cx, "osName");
                    String osVersion = detectStringSlot(cx, "osVersion");
                    Integer overallCpuUsage = detectIntSlot(cx, "overallCpuUsage");
                    Integer totalPhysicalMemory = detectIntSlot(cx, "totalPhysicalMemory");

                    Map<String, Object> info = NiagaraJson.obj(
                            "stationName",     stationName,
                            "hostId",          hostId,
                            "platformVersion", niagaraVer,
                            "niagaraVersion",  niagaraVer,
                            "currentCpuUsage", currentCpuUsage,
                            "model",           model,
                            "modelVersion",    modelVersion,
                            "numCpus",         numCpus,
                            "osName",          osName,
                            "osVersion",       osVersion,
                            "overallCpuUsage", overallCpuUsage,
                            "totalPhysicalMemory", totalPhysicalMemory,
                            "moduleVersion",   moduleVersion,
                            "readOnly",        security.isReadOnly()
                    );
                    return McpToolResult.success(info);
                } catch (Throwable e) {
                    LOG.warning("nmcp.station.info error: " + e.getMessage());
                    return McpToolResult.error("Could not read station info: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.component.read
    // -------------------------------------------------------------------------

    private McpTool componentRead() {
        return new McpTool() {
            @Override public String name() { return "nmcp.component.read"; }

            @Override public String description() {
                return "Reads a Niagara component by ORD and returns its type, display name, "
                        + "and a summary of its properties. ORD must be within allowlisted roots.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Niagara ORD, e.g. station:|slot:/Drivers\"}"
                        + "},"
                        + "\"required\":[\"ord\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String ord = getStringArg(arguments, "ord");
                if (ord == null) return McpToolResult.error("Missing required argument: ord");
                try {
                    security.checkAllowlist(ord);
                    // TODO: Replace with actual ORD resolution
                    BComponent comp = resolveComponent(ord, cx);
                    if (comp == null) {
                        return McpToolResult.error("ORD not found: " + ord);
                    }
                    Map<String, Object> summary = buildComponentSummary(comp, cx);
                    Map<String, Object> result = NiagaraJson.obj(
                            "ord",         ord,
                            "type",        comp.getType() != null ? comp.getType().getTypeName() : "unknown",
                            "displayName", comp.getDisplayName(cx),
                            "summary",     summary
                    );
                    return McpToolResult.success(result);
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.component.read error for " + ord + ": " + e.getMessage());
                    return McpToolResult.error("Error reading component: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.component.children
    // -------------------------------------------------------------------------

    private McpTool componentChildren() {
        return new McpTool() {
            @Override public String name() { return "nmcp.component.children"; }

            @Override public String description() {
                return "Lists immediate child components under a Niagara ORD. "
                        + "ORD must be within allowlisted roots.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Parent ORD\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max results (default 100)\"}"
                        + "},"
                        + "\"required\":[\"ord\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String ord = getStringArg(arguments, "ord");
                if (ord == null) return McpToolResult.error("Missing required argument: ord");
                Integer limit = getIntArg(arguments, "limit");
                try {
                    security.checkAllowlist(ord);
                    int effectiveLimit = security.effectiveLimit(limit);
                    // TODO: Replace with actual ORD resolution
                    BComponent parent = resolveComponent(ord, cx);
                    if (parent == null) {
                        return McpToolResult.error("ORD not found: " + ord);
                    }
                    List<Object> children = new ArrayList<>();
                    BComponent[] childArray = parent.getChildComponents();
                    if (childArray != null) {
                        int count = 0;
                        for (BComponent child : childArray) {
                            if (count >= effectiveLimit) break;
                            String childName = child.getName();
                            String childOrd  = ord.endsWith("/")
                                    ? ord + childName
                                    : ord + "/" + childName;
                            Map<String, Object> childEntry = NiagaraJson.obj(
                                    "name", childName,
                                    "ord",  childOrd,
                                    "type", child.getType() != null ? child.getType().getTypeName() : "unknown"
                            );
                            children.add(childEntry);
                            count++;
                        }
                    }
                    Map<String, Object> result = NiagaraJson.obj(
                            "ord",      ord,
                            "children", children
                    );
                    return McpToolResult.success(result);
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.component.children error for " + ord + ": " + e.getMessage());
                    return McpToolResult.error("Error listing children: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.component.slots
    // -------------------------------------------------------------------------

    private McpTool componentSlots() {
        return new McpTool() {
            @Override public String name() { return "nmcp.component.slots"; }

            @Override public String description() {
                return "Lists all slots (properties) on a Niagara component identified by ORD. "
                        + "Sensitive slot values (passwords, keys, tokens) are masked. "
                        + "ORD must be within allowlisted roots.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Component ORD\"}"
                        + "},"
                        + "\"required\":[\"ord\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String ord = getStringArg(arguments, "ord");
                if (ord == null) return McpToolResult.error("Missing required argument: ord");
                try {
                    security.checkAllowlist(ord);
                    // TODO: Replace with actual ORD resolution
                    BComponent comp = resolveComponent(ord, cx);
                    if (comp == null) {
                        return McpToolResult.error("ORD not found: " + ord);
                    }
                    List<Object> slots = buildSlotList(comp, cx);
                    Map<String, Object> result = NiagaraJson.obj(
                            "ord",   ord,
                            "slots", slots
                    );
                    return McpToolResult.success(result);
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.component.slots error for " + ord + ": " + e.getMessage());
                    return McpToolResult.error("Error listing slots: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.component.search
    // -------------------------------------------------------------------------

    private McpTool componentSearch() {
        return new McpTool() {
            @Override public String name() { return "nmcp.component.search"; }

            @Override public String description() {
                return "Searches all components under a root ORD by display name substring and/or type substring. "
                        + "Returns navigation details only: ORD, name, display name, and type.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"nameFilter\":{\"type\":\"string\",\"description\":\"Case-insensitive substring on display name\"},"
                        + "  \"typeFilter\":{\"type\":\"string\",\"description\":\"Case-insensitive substring on type name\"},"
                        + "  \"root\":{\"type\":\"string\",\"description\":\"Root ORD to search (default station:|slot:/Drivers)\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max results (default 50)\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String nameFilter = getStringArg(arguments, "nameFilter");
                String typeFilter = getStringArg(arguments, "typeFilter");
                String root = getStringArg(arguments, "root");
                if (root == null || root.isEmpty()) {
                    root = "station:|slot:/Drivers";
                }
                Integer limit = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit != null ? limit : Integer.valueOf(50));
                try {
                    security.checkAllowlist(root);
                    BComponent comp = resolveComponent(root, cx);
                    if (comp == null) {
                        return McpToolResult.error("ORD not found: " + root);
                    }
                    List<Object> matches = new ArrayList<>();
                    searchComponents(comp, root, nameFilter, typeFilter, matches, effectiveLimit, cx);
                    return McpToolResult.success(NiagaraJson.obj(
                            "root", root,
                            "count", matches.size(),
                            "components", matches));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.component.search error for " + root + ": " + e.getMessage());
                    return McpToolResult.error("Error searching components: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // Niagara helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves an ORD string to a BComponent.
     * TODO: Replace with the correct Niagara ORD resolution API for the target version.
     *       Example (Niagara 4.x):
     *         BObject obj = BOrd.make(ord).get(null, cx);
     *         if (obj instanceof BComponent) return (BComponent) obj;
     */
    private BComponent resolveComponent(String ord, Context cx) {
        try {
            Object resolved = BOrd.make(ord).get(null, cx);
            return (resolved instanceof BComponent) ? (BComponent) resolved : null;
        } catch (Throwable e) {
            LOG.warning("Failed to resolve ORD " + ord + ": " + e.getMessage());
            return null;
        }
    }

    private void searchComponents(BComponent comp, String compOrd,
                                   String nameFilter, String typeFilter,
                                   List<Object> out, int limit, Context cx) {
        if (out.size() >= limit) {
            return;
        }

        try {
            String typeName = "";
            try { typeName = comp.getType() != null ? comp.getType().getTypeName() : ""; } catch (Throwable ignored) {}
            String displayName = "";
            try { displayName = comp.getDisplayName(cx); } catch (Throwable ignored) {}
            String name = "";
            try { name = comp.getName(); } catch (Throwable ignored) {}

            boolean nameMatch = nameFilter == null || nameFilter.isEmpty()
                    || displayName.toLowerCase().contains(nameFilter.toLowerCase())
                    || name.toLowerCase().contains(nameFilter.toLowerCase());
            boolean typeMatch = typeFilter == null || typeFilter.isEmpty()
                    || typeName.toLowerCase().contains(typeFilter.toLowerCase());

            if (nameMatch && typeMatch) {
                out.add(NiagaraJson.obj(
                        "ord", compOrd,
                        "name", name,
                        "displayName", displayName,
                        "type", typeName));
            }

            BComponent[] children = comp.getChildComponents();
            if (children == null) {
                return;
            }
            for (BComponent child : children) {
                if (out.size() >= limit) {
                    break;
                }
                String childName = "";
                try { childName = child.getName(); } catch (Throwable ignored) {}
                String childOrd = compOrd.endsWith("/") ? compOrd + childName : compOrd + "/" + childName;
                searchComponents(child, childOrd, nameFilter, typeFilter, out, limit, cx);
            }
        } catch (Throwable e) {
            LOG.warning("searchComponents error at " + compOrd + ": " + e.getMessage());
        }
    }

    private Map<String, Object> buildComponentSummary(BComponent comp, Context cx) {
        Map<String, Object> summary = NiagaraJson.obj();
        try {
            Property[] props = comp.getPropertiesArray();
            if (props != null) {
                for (Property prop : props) {
                    String name = prop.getName();
                    BValue val = (BValue) comp.get(name);
                    String displayValue = valueToString(name, val);
                    summary.put(name, displayValue);
                }
            }
        } catch (Throwable e) {
            summary.put("_error", "Could not read properties: " + e.getMessage());
        }
        return summary;
    }

    private List<Object> buildSlotList(BComponent comp, Context cx) {
        List<Object> result = new ArrayList<>();
        try {
            Property[] props = comp.getPropertiesArray();
            if (props != null) {
                for (Property prop : props) {
                    String name = prop.getName();
                    BValue val = (BValue) comp.get(name);
                    String displayValue = valueToString(name, val);
                    Map<String, Object> slotEntry = NiagaraJson.obj(
                            "name",  name,
                            "type",  prop.getType() != null ? prop.getType().getTypeName() : "unknown",
                            "value", displayValue
                    );
                    result.add(slotEntry);
                }
            }
        } catch (Throwable e) {
            result.add(NiagaraJson.obj("_error", "Could not read slots: " + e.getMessage()));
        }
        return result;
    }

    private String valueToString(String slotName, BValue val) {
        if (security.isSensitiveSlot(slotName)) return NiagaraSecurity.maskedValue();
        if (val == null) return "null";
        return val.toString();
    }

    // -------------------------------------------------------------------------
    // Argument helpers
    // -------------------------------------------------------------------------

    private String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString() : null;
    }

    private String detectPlatformVersionString(Context cx) {
        String fromPlatformService = detectFromSystemPlatformService(cx, "niagaraVersion");
        if (isNonEmpty(fromPlatformService)) {
            return fromPlatformService;
        }

        String fromSysVersion = tryInvokeToString(Sys.class, "getVersion");
        if (isNonEmpty(fromSysVersion)) {
            return fromSysVersion;
        }

        String fromSysVersionString = tryInvokeToString(Sys.class, "getVersionString");
        if (isNonEmpty(fromSysVersionString)) {
            return fromSysVersionString;
        }

        // Final fallback: scan /Services tree for platform service
        String fromServicesScan = detectFromServicesTree(cx, "niagaraVersion");
        if (isNonEmpty(fromServicesScan)) {
            return fromServicesScan;
        }

        return null;
    }

    private String detectHostIdString(Context cx) {
        String fromPlatformService = detectFromSystemPlatformService(cx, "hostId");
        if (isNonEmpty(fromPlatformService)) {
            return fromPlatformService;
        }

        // Final fallback: scan /Services tree for platform service
        String fromServicesScan = detectFromServicesTree(cx, "hostId");
        if (isNonEmpty(fromServicesScan)) {
            return fromServicesScan;
        }

        return null;
    }

    private String detectStringSlot(Context cx, String slotName) {
        String fromPlatformService = detectFromSystemPlatformService(cx, slotName);
        if (isNonEmpty(fromPlatformService) && !"null".equalsIgnoreCase(fromPlatformService)) {
            return fromPlatformService;
        }

        String fromServicesScan = detectFromServicesTree(cx, slotName);
        if (isNonEmpty(fromServicesScan) && !"null".equalsIgnoreCase(fromServicesScan)) {
            return fromServicesScan;
        }

        return null;
    }

    private Integer detectIntSlot(Context cx, String slotName) {
        String value = detectStringSlot(cx, slotName);
        if (!isNonEmpty(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String detectFromSystemPlatformService(Context cx, String slotName) {
        try {
            Object platformService = resolveFirstServiceCandidate(cx);
            if (platformService == null) {
                return null;
            }

            String fromProperty = tryInvokeToString(platformService, "get", slotName);
            if (isNonEmpty(fromProperty) && !fromProperty.equals("null")) {
                return fromProperty;
            }

            String fromGetter = tryInvokeToString(platformService, "get" + capitalize(slotName));
            if (isNonEmpty(fromGetter) && !fromGetter.equals("null")) {
                return fromGetter;
            }
        } catch (Throwable ignored) {
            // Non-fatal: fallback chain continues.
        }
        return null;
    }

    private String detectFromServicesTree(Context cx, String slotName) {
        try {
            Object resolved = BOrd.make("station:|slot:/Services").get(null, cx);
            if (!(resolved instanceof BComponent)) {
                return null;
            }

            BComponent services = (BComponent) resolved;
            BComponent[] children = services.getChildComponents();
            if (children == null || children.length == 0) {
                return null;
            }

            for (BComponent child : children) {
                String candidate = scanComponentTreeForSlot(child, cx, slotName, 3);
                if (isNonEmpty(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // Tree scan is optional fallback; non-fatal if it fails.
        }
        return null;
    }

    private String scanComponentTreeForSlot(BComponent component, Context cx, String slotName, int remainingDepth) {
        if (component == null || remainingDepth <= 0) {
            return null;
        }

        // Probe this component for the slot if it looks platform-related
        boolean isPlatform = isPlatformish(component);
        if (isPlatform) {
            String candidate = probeSlotFromComponent(component, slotName);
            if (isNonEmpty(candidate)) {
                return candidate;
            }
        }

        // Recurse to children
        try {
            BComponent[] children = component.getChildComponents();
            if (children != null && children.length > 0) {
                for (BComponent child : children) {
                    String descendant = scanComponentTreeForSlot(child, cx, slotName, remainingDepth - 1);
                    if (isNonEmpty(descendant)) {
                        return descendant;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Non-fatal: continue with other branches.
        }

        return null;
    }

    private String probeSlotFromComponent(BComponent component, String slotName) {
        if (component == null) {
            return null;
        }
        try {
            String value = tryInvokeToString(component, "get", slotName);
            if (isNonEmpty(value) && !value.equals("null")) {
                return value;
            }

            String getter = tryInvokeToString(component, "get" + capitalize(slotName));
            if (isNonEmpty(getter) && !getter.equals("null")) {
                return getter;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isPlatformish(BComponent component) {
        if (component == null) {
            return false;
        }
        try {
            String name = component.getName();
            String className = component.getClass().getName();
            String typeName = component.getType() != null ? component.getType().getTypeName() : "";

            String lower = (name + "|" + className + "|" + typeName).toLowerCase();
            return lower.contains("platform") || lower.contains("systemplatformservice") || lower.contains("bplatformservice");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object resolveFirstServiceCandidate(Context cx) {
        String[] candidates = {
            "station:|slot:/Services/SystemPlatformService",
            "station:|slot:/Services/PlatformService",
            "station:|slot:/Services/PlatformServices/BSystemPlatformService",
            "station:|slot:/Services/PlatformServices/SystemPlatformService",
            "station:|slot:/Services/PlatformServices/PlatformService"
        };
        for (String ord : candidates) {
            try {
                Object resolved = BOrd.make(ord).get(null, cx);
                if (resolved != null) {
                    return resolved;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String capitalize(String str) {
        if (str == null || str.length() == 0) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private static boolean isNonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String tryInvokeToString(Object target, String methodName) {
        return tryInvokeToString(target, methodName, new Object[0]);
    }

    private String tryInvokeToString(Object target, String methodName, Object... args) {
        Object value = tryInvoke(target, methodName, args);
        return value != null ? String.valueOf(value) : null;
    }

    private Object tryInvoke(Object target, String methodName, Object... args) {
        try {
            Class<?> targetClass = (target instanceof Class<?>) ? (Class<?>) target : target.getClass();
            for (java.lang.reflect.Method m : targetClass.getMethods()) {
                if (!methodName.equals(m.getName())) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != args.length) {
                    continue;
                }

                boolean compatible = true;
                for (int i = 0; i < params.length; i++) {
                    Object arg = args[i];
                    if (arg == null) {
                        continue;
                    }
                    if (!params[i].isAssignableFrom(arg.getClass())) {
                        compatible = false;
                        break;
                    }
                }
                if (!compatible) {
                    continue;
                }

                try {
                    return m.invoke((target instanceof Class<?>) ? null : target, args);
                } catch (Throwable ignored) {
                    // Keep searching compatible overloads.
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
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
