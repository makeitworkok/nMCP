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
                return "Summarizes all devices across all driver networks: name, type, ORD, "
                        + "child point count, and status slot value. "
                        + "Answers 'what devices are in the station?' and 'what is offline?'";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max devices to return\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                Integer limit = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit);
                try {
                    List<Object> devices = collectDevices(effectiveLimit, cx);
                    return McpToolResult.success(NiagaraJson.obj(
                            "limit", effectiveLimit, "count", devices.size(), "devices", devices));
                } catch (Throwable e) {
                    LOG.warning("nmcp.equipment.status error: " + e);
                    return McpToolResult.error("Equipment status error: " + e.getMessage());
                }
            }
        };
    }

    static List<Object> collectDevices(int effectiveLimit, Context cx) {
        Object driversObj = BOrd.make("station:|slot:/Drivers").get(null, cx);
        if (!(driversObj instanceof BComponent)) {
            throw new IllegalStateException("Drivers tree not accessible");
        }
        BComponent drivers = (BComponent) driversObj;
        List<Object> devices = new ArrayList<>();
        BComponent[] networks = drivers.getChildComponents();
        if (networks == null) {
            return devices;
        }
        for (BComponent network : networks) {
            if (devices.size() >= effectiveLimit) {
                break;
            }
            String netName = "";
            try { netName = network.getName(); } catch (Throwable ignored) {}
            String netOrd = "station:|slot:/Drivers/" + netName;
            BComponent[] devChildren = network.getChildComponents();
            if (devChildren == null) {
                continue;
            }
            for (BComponent dev : devChildren) {
                if (devices.size() >= effectiveLimit) {
                    break;
                }
                String devName = "";
                try { devName = dev.getName(); } catch (Throwable ignored) {}
                String devType = "";
                try { devType = dev.getType() != null ? dev.getType().getTypeName() : ""; } catch (Throwable ignored) {}
                String devDn = "";
                try { devDn = dev.getDisplayName(cx); } catch (Throwable ignored) {}
                String devOrd = netOrd + "/" + devName;
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
                devices.add(NiagaraJson.obj(
                        "name", devName, "displayName", devDn,
                        "type", devType, "ord", devOrd,
                        "network", netName, "pointCount", pointCount,
                        "status", status));
            }
        }
        return devices;
    }

    // -------------------------------------------------------------------------
    // Argument helpers
    // -------------------------------------------------------------------------

    private Integer getIntArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
