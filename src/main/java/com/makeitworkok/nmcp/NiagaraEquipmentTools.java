// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MCP tool for summarizing equipment (devices) across all driver networks.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code nmcp.equipment.status} – all devices with name, type, ORD, point count, status</li>
 * </ul>
 *
 * <p>Synthesized by walking the Drivers tree — no new Niagara API surface required.
 * All access is read-only.
 */
public final class NiagaraEquipmentTools {

    private static final Logger LOG = Logger.getLogger(NiagaraEquipmentTools.class.getName());
    private final NiagaraSecurity security;

    public NiagaraEquipmentTools(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(equipmentStatus());
        return list;
    }

    // -------------------------------------------------------------------------
    // nmcp.equipment.status
    // -------------------------------------------------------------------------

    private McpTool equipmentStatus() {
        return new McpTool() {
            @Override public String name() { return "nmcp.equipment.status"; }

            @Override public String description() {
                return "Use for read-only device inventory and health context. Walks the Drivers tree and returns "
                    + "device name, network, ORD, type, child point count, and status slot value.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"networkOrd\":{\"type\":\"string\",\"description\":\"Optional exact network ORD to scope results, e.g. station:|slot:/Drivers/BacnetNetwork\"},"
                        + "  \"includeDescendants\":{\"type\":\"boolean\",\"description\":\"When networkOrd is provided, recurse below direct device children\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Maximum devices to return, capped by BMcpService maxResults\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                Integer limit = getIntArg(arguments, "limit");
                String networkOrd = getStringArg(arguments, "networkOrd");
                boolean includeDescendants = getBooleanArg(arguments, "includeDescendants", false);
                int effectiveLimit = security.effectiveLimit(limit);
                try {
                    BComponent networkRoot = null;
                    String normalizedNetworkOrd = null;
                    if (networkOrd != null && !networkOrd.trim().isEmpty()) {
                        normalizedNetworkOrd = normalizeOrd(networkOrd);
                        security.checkAllowlist(normalizedNetworkOrd);
                        Object resolved = BOrd.make(normalizedNetworkOrd).get(null, cx);
                        if (!(resolved instanceof BComponent)) {
                            return McpToolResult.error("networkOrd did not resolve to a component: " + networkOrd);
                        }
                        networkRoot = (BComponent) resolved;
                    }

                    List<Object> devices = collectDevices(effectiveLimit, cx, networkRoot,
                            normalizedNetworkOrd, includeDescendants);
                    return McpToolResult.success(NiagaraJson.obj(
                            "networkOrd", networkOrd,
                            "includeDescendants", includeDescendants,
                            "limit", effectiveLimit,
                            "count", devices.size(),
                            "devices", devices));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.equipment.status error: " + e);
                    return McpToolResult.error("Equipment status error: " + e.getMessage());
                }
            }
        };
    }

    static List<Object> collectDevices(int effectiveLimit, Context cx, BComponent networkRoot,
                                       String networkOrd, boolean includeDescendants) {
        List<Object> devices = new ArrayList<>();

        if (networkRoot != null) {
            String netName = safeName(networkRoot);
            String resolvedOrd = networkOrd != null ? networkOrd : ("station:|slot:/Drivers/" + netName);
            collectNetworkDevices(networkRoot, resolvedOrd, netName, includeDescendants,
                    effectiveLimit, cx, devices);
            return devices;
        }

        Object driversObj = BOrd.make("station:|slot:/Drivers").get(null, cx);
        if (!(driversObj instanceof BComponent)) {
            throw new IllegalStateException("Drivers tree not accessible");
        }
        BComponent drivers = (BComponent) driversObj;
        BComponent[] networks = drivers.getChildComponents();
        if (networks == null) {
            return devices;
        }
        for (BComponent network : networks) {
            if (devices.size() >= effectiveLimit) {
                break;
            }
            String netName = safeName(network);
            String netOrd = "station:|slot:/Drivers/" + netName;
            collectNetworkDevices(network, netOrd, netName, false, effectiveLimit, cx, devices);
        }
        return devices;
    }

    private static void collectNetworkDevices(BComponent network, String networkOrd, String networkName,
                                              boolean includeDescendants, int effectiveLimit,
                                              Context cx, List<Object> devices) {
        BComponent[] children = network.getChildComponents();
        if (children == null) {
            return;
        }
        for (BComponent child : children) {
            if (devices.size() >= effectiveLimit) {
                return;
            }
            String childName = safeName(child);
            String childOrd = networkOrd + "/" + childName;
            devices.add(deviceToMap(child, childOrd, networkName, cx));
            if (includeDescendants) {
                collectDescendantDevices(child, childOrd, networkName, effectiveLimit, cx, devices);
            }
        }
    }

    private static void collectDescendantDevices(BComponent parent, String parentOrd, String networkName,
                                                 int effectiveLimit, Context cx, List<Object> devices) {
        BComponent[] children = parent.getChildComponents();
        if (children == null) {
            return;
        }
        for (BComponent child : children) {
            if (devices.size() >= effectiveLimit) {
                return;
            }
            String childName = safeName(child);
            String childOrd = parentOrd + "/" + childName;
            devices.add(deviceToMap(child, childOrd, networkName, cx));
            collectDescendantDevices(child, childOrd, networkName, effectiveLimit, cx, devices);
        }
    }

    private static Map<String, Object> deviceToMap(BComponent dev, String devOrd, String networkName, Context cx) {
        String devName = safeName(dev);
        String devType = "";
        try { devType = dev.getType() != null ? dev.getType().getTypeName() : ""; } catch (Throwable ignored) {}
        String devDn = "";
        try { devDn = dev.getDisplayName(cx); } catch (Throwable ignored) {}
        int pointCount = 0;
        try {
            BComponent[] pts = dev.getChildComponents();
            pointCount = pts != null ? pts.length : 0;
        } catch (Throwable ignored) {}
        String status = "";
        try {
            Object sv = dev.get("status");
            status = sv != null ? sv.toString() : "";
        } catch (Throwable ignored) {}
        return NiagaraJson.obj(
                "name", devName,
                "displayName", devDn,
                "type", devType,
                "ord", devOrd,
                "network", networkName,
                "pointCount", pointCount,
                "status", status);
    }

    private static String safeName(BComponent comp) {
        try {
            return comp != null && comp.getName() != null ? comp.getName() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String normalizeOrd(String s) {
        if (s == null) return null;
        if (s.startsWith("local:|foxwss:|")) s = s.substring("local:|foxwss:|".length());
        else if (s.startsWith("foxwss:|"))   s = s.substring("foxwss:|".length());
        else if (s.startsWith("local:|"))    s = s.substring("local:|".length());
        return s;
    }

    // -------------------------------------------------------------------------
    // Argument helpers
    // -------------------------------------------------------------------------

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
}
