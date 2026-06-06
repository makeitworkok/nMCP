// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MCP tool for device onboarding and capability profiling.
 */
public final class NiagaraDeviceProfileTool {

    private static final Logger LOG = Logger.getLogger(NiagaraDeviceProfileTool.class.getName());

    private final NiagaraSecurity security;

    public NiagaraDeviceProfileTool(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(deviceProfile());
        return list;
    }

    private McpTool deviceProfile() {
        return new McpTool() {
            @Override public String name() { return "nmcp.device.profile"; }

            @Override public String description() {
                return "Use for newly onboarded device triage. Profiles one allowlisted device ORD and returns "
                        + "point inventory, writable/action candidates, status health counts, and recommended next "
                        + "tool calls for control, history, alarms, and haystack workflows.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"deviceOrd\":{\"type\":\"string\",\"description\":\"Allowlisted device ORD to profile, e.g. station:|slot:/Drivers/BacnetNetwork/DeviceA\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Maximum entries returned per category list, capped by BMcpService maxResults\"},"
                        + "  \"includeDescendants\":{\"type\":\"boolean\",\"description\":\"If true (default), profile nested points/components recursively under the device\"}"
                        + "},"
                        + "\"required\":[\"deviceOrd\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String deviceOrd = getStringArg(arguments, "deviceOrd");
                if (deviceOrd == null || deviceOrd.trim().isEmpty()) {
                    return McpToolResult.error("Missing required argument: deviceOrd");
                }

                Integer limit = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit);
                boolean includeDescendants = getBooleanArg(arguments, "includeDescendants", true);
                String normalizedDeviceOrd = normalizeOrd(deviceOrd);

                try {
                    security.checkAllowlist(normalizedDeviceOrd);
                    Object obj = BOrd.make(normalizedDeviceOrd).get(null, cx);
                    if (!(obj instanceof BComponent)) {
                        return McpToolResult.error("deviceOrd did not resolve to a component: " + deviceOrd);
                    }

                    BComponent device = (BComponent) obj;
                    ProfileAccumulator acc = new ProfileAccumulator(effectiveLimit);
                    profileDevice(device, normalizedDeviceOrd, includeDescendants, cx, acc);

                    Map<String, Object> result = NiagaraJson.obj(
                            "deviceOrd", deviceOrd,
                            "normalizedDeviceOrd", normalizedDeviceOrd,
                            "includeDescendants", includeDescendants,
                            "limit", effectiveLimit,
                            "device", NiagaraJson.obj(
                                    "name", safeName(device),
                                    "displayName", safeDisplayName(device, cx),
                                    "type", safeTypeName(device)),
                            "summary", NiagaraJson.obj(
                                    "componentsScanned", acc.componentsScanned,
                                    "pointsScanned", acc.pointsScanned,
                                    "writablePointCount", acc.writablePointCount,
                                    "faultPointCount", acc.faultPointCount,
                                    "stalePointCount", acc.stalePointCount,
                                    "overriddenPointCount", acc.overriddenPointCount,
                                    "historyEnabledPointCount", acc.historyEnabledPointCount),
                            "pointTypeCounts", acc.pointTypeCounts,
                            "actionCandidates", acc.actionCandidates,
                            "writableCandidates", acc.writableCandidates,
                            "faultedPoints", acc.faultedPoints,
                            "historyCandidates", acc.historyCandidates,
                            "capabilities", NiagaraJson.obj(
                                    "canReadPoints", acc.pointsScanned > 0,
                                    "canWritePoints", acc.writablePointCount > 0,
                                    "canInvokeActions", acc.actionCandidates.size() > 0,
                                    "canProvisionHistory", acc.pointsScanned > 0,
                                    "canScanHaystack", acc.pointsScanned > 0),
                            "nextSteps", buildNextSteps(acc));

                    return McpToolResult.success(result);
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.device.profile error for " + deviceOrd + ": " + e);
                    return McpToolResult.error("Device profile error: " + e.getMessage());
                }
            }
        };
    }

    private void profileDevice(BComponent component, String ord, boolean recurse, Context cx, ProfileAccumulator acc) {
        if (component == null || ord == null) {
            return;
        }
        acc.componentsScanned++;

        collectActions(component, ord, acc);

        String typeName = safeTypeName(component);
        boolean isPoint = isPointType(typeName);
        if (isPoint) {
            acc.pointsScanned++;
            classifyPoint(component, ord, typeName, cx, acc);
        }

        if (!recurse) {
            return;
        }

        BComponent[] children = component.getChildComponents();
        if (children == null) {
            return;
        }
        for (BComponent child : children) {
            String childName = safeName(child);
            String childOrd = ord.endsWith("/") ? ord + childName : ord + "/" + childName;
            profileDevice(child, childOrd, true, cx, acc);
        }
    }

    private void classifyPoint(BComponent point, String ord, String typeName, Context cx, ProfileAccumulator acc) {
        Map<String, Object> parsed = NiagaraPointTools.readPointValue(point);
        String status = parsed.get("status") != null ? String.valueOf(parsed.get("status")) : "";
        String statusLower = status.toLowerCase();

        String bucket = pointTypeBucket(typeName);
        Integer existing = (Integer) acc.pointTypeCounts.get(bucket);
        acc.pointTypeCounts.put(bucket, Integer.valueOf(existing.intValue() + 1));

        boolean writable = looksWritable(typeName);
        boolean historyEnabled = hasHistoryExtension(point);
        if (writable) {
            acc.writablePointCount++;
            if (acc.writableCandidates.size() < acc.limitPerList) {
                acc.writableCandidates.add(pointEntry(point, ord, typeName, status, historyEnabled, cx));
            }
        }
        if (!historyEnabled && acc.historyCandidates.size() < acc.limitPerList) {
            acc.historyCandidates.add(pointEntry(point, ord, typeName, status, false, cx));
        }
        if (historyEnabled) {
            acc.historyEnabledPointCount++;
        }

        if (statusLower.contains("fault")) {
            acc.faultPointCount++;
            if (acc.faultedPoints.size() < acc.limitPerList) {
                acc.faultedPoints.add(pointEntry(point, ord, typeName, status, historyEnabled, cx));
            }
        }
        if (statusLower.contains("stale")) {
            acc.stalePointCount++;
        }
        if (statusLower.contains("overridden")) {
            acc.overriddenPointCount++;
        }
    }

    private Map<String, Object> pointEntry(BComponent point, String ord, String typeName,
                                           String status, boolean historyEnabled, Context cx) {
        return NiagaraJson.obj(
                "ord", ord,
                "name", safeName(point),
                "displayName", safeDisplayName(point, cx),
                "type", typeName,
                "status", status,
                "historyEnabled", historyEnabled);
    }

    private void collectActions(BComponent component, String ord, ProfileAccumulator acc) {
        try {
            javax.baja.sys.Property[] props = component.getPropertiesArray();
            if (props == null) {
                return;
            }
            for (javax.baja.sys.Property property : props) {
                if (property == null || !property.isAction()) {
                    continue;
                }
                if (acc.actionCandidates.size() >= acc.limitPerList) {
                    return;
                }
                acc.actionCandidates.add(NiagaraJson.obj(
                        "ord", ord,
                        "action", property.getName()));
            }
        } catch (Throwable ignored) {
        }
    }

    private List<Object> buildNextSteps(ProfileAccumulator acc) {
        List<Object> steps = new ArrayList<>();
        steps.add("Use nmcp.component.children or nmcp.component.slots to inspect the device component graph.");
        if (acc.writablePointCount > 0) {
            steps.add("Use nmcp.point.write for routine commands and nmcp.point.override for operator-level overrides.");
        }
        if (acc.actionCandidates.size() > 0) {
            steps.add("Use nmcp.component.invokeAction with debug=true to validate action resolution before live mutation.");
        }
        if (acc.historyCandidates.size() > 0) {
            steps.add("Use nmcp.history.provisionOnPoint to enable trending on points missing history extensions.");
        }
        if (acc.faultPointCount > 0 || acc.stalePointCount > 0 || acc.overriddenPointCount > 0) {
            steps.add("Use nmcp.fault.scan and nmcp.alarm.active to investigate current health and alarm impact.");
        }
        steps.add("Use nmcp.haystack.scanPoints and nmcp.haystack.suggestTags to improve semantic discoverability.");
        return steps;
    }

    private boolean hasHistoryExtension(BComponent point) {
        try {
            Object ext = point.get("historyExt");
            if (ext == null) {
                return false;
            }
            String value = String.valueOf(ext).trim();
            return !(value.isEmpty() || "null".equalsIgnoreCase(value) || "n:".equals(value));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isPointType(String typeName) {
        if (typeName == null) {
            return false;
        }
        return typeName.contains("NumericPoint")
                || typeName.contains("BooleanPoint")
                || typeName.contains("StringPoint")
                || typeName.contains("EnumPoint")
                || typeName.contains("ProxyPoint")
                || typeName.contains("Point");
    }

    private boolean looksWritable(String typeName) {
        if (typeName == null) {
            return false;
        }
        return typeName.contains("Writable") || typeName.contains("Override");
    }

    private String pointTypeBucket(String typeName) {
        if (typeName == null) {
            return "other";
        }
        String lower = typeName.toLowerCase();
        if (lower.contains("numeric")) return "numeric";
        if (lower.contains("boolean")) return "boolean";
        if (lower.contains("enum")) return "enum";
        if (lower.contains("string")) return "string";
        return "other";
    }

    private String safeName(BComponent component) {
        try {
            return component != null && component.getName() != null ? component.getName() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String safeDisplayName(BComponent component, Context cx) {
        try {
            return component != null ? component.getDisplayName(cx) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String safeTypeName(BComponent component) {
        try {
            return component != null && component.getType() != null ? component.getType().getTypeName() : "";
        } catch (Throwable ignored) {
            return "";
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

    private boolean getBooleanArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object v = args.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    private String normalizeOrd(String ord) {
        String s = ord;
        if (s.startsWith("local:|foxwss:|")) s = s.substring("local:|foxwss:|".length());
        else if (s.startsWith("foxwss:|")) s = s.substring("foxwss:|".length());
        else if (s.startsWith("local:|")) s = s.substring("local:|".length());
        return s;
    }

    private static final class ProfileAccumulator {
        final int limitPerList;
        int componentsScanned;
        int pointsScanned;
        int writablePointCount;
        int faultPointCount;
        int stalePointCount;
        int overriddenPointCount;
        int historyEnabledPointCount;
        final Map<String, Object> pointTypeCounts;
        final List<Object> actionCandidates;
        final List<Object> writableCandidates;
        final List<Object> faultedPoints;
        final List<Object> historyCandidates;

        ProfileAccumulator(int limitPerList) {
            this.limitPerList = limitPerList;
            this.pointTypeCounts = new LinkedHashMap<String, Object>();
            this.pointTypeCounts.put("numeric", Integer.valueOf(0));
            this.pointTypeCounts.put("boolean", Integer.valueOf(0));
            this.pointTypeCounts.put("enum", Integer.valueOf(0));
            this.pointTypeCounts.put("string", Integer.valueOf(0));
            this.pointTypeCounts.put("other", Integer.valueOf(0));
            this.actionCandidates = new ArrayList<Object>();
            this.writableCandidates = new ArrayList<Object>();
            this.faultedPoints = new ArrayList<Object>();
            this.historyCandidates = new ArrayList<Object>();
        }
    }
}
