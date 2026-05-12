// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.sys.BStation;
import javax.baja.sys.Context;
import javax.baja.sys.Sys;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class NiagaraBuildingBriefTool {

    private static final Logger LOG = Logger.getLogger(NiagaraBuildingBriefTool.class.getName());
    private final NiagaraSecurity security;

    public NiagaraBuildingBriefTool(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(buildingBrief());
        return list;
    }

    private McpTool buildingBrief() {
        return new McpTool() {
            @Override public String name() { return "nmcp.building.brief"; }

            @Override public String description() {
                return "Builds an operator-focused morning briefing with alarm, fault, and equipment summaries.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"alarmLimit\":{\"type\":\"integer\",\"description\":\"Max alarms to query (default 20)\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                Integer alarmLimitArg = getIntArg(arguments, "alarmLimit");
                int alarmLimit = security.effectiveLimit(alarmLimitArg != null ? alarmLimitArg : Integer.valueOf(20));
                try {
                    List<Object> unackedAlarms = NiagaraAlarmTools.fetchAlarms(alarmLimit, true, cx);
                    Map<String, Object> faultSummary = NiagaraFaultScanTool.scanFaults(
                            security, "station:|slot:/Drivers", security.effectiveLimit(Integer.valueOf(10)), cx);
                    List<Object> devices = NiagaraEquipmentTools.collectDevices(security.effectiveLimit(null), cx);

                    Set<String> networks = new LinkedHashSet<>();
                    for (Object obj : devices) {
                        if (obj instanceof Map) {
                            Object network = ((Map<?, ?>) obj).get("network");
                            if (network != null) {
                                networks.add(network.toString());
                            }
                        }
                    }

                    String stationName = "unknown";
                    try {
                        BStation station = Sys.getStation();
                        if (station != null) {
                            stationName = station.getStationName();
                        }
                    } catch (Throwable ignored) {}

                    return McpToolResult.success(NiagaraJson.obj(
                            "stationName", stationName,
                            "moduleVersion", BMcpService.MODULE_VERSION,
                            "timestamp", System.currentTimeMillis(),
                            "alarmSummary", NiagaraJson.obj(
                                    "totalQueried", alarmLimit,
                                    "unackedCount", unackedAlarms.size(),
                                    "recentAlarms", slice(unackedAlarms, 5)),
                            "faultSummary", NiagaraJson.obj(
                                    "faultCount", faultSummary.get("faultCount"),
                                    "staleCount", faultSummary.get("staleCount"),
                                    "overriddenCount", faultSummary.get("overriddenCount"),
                                    "faultPoints", slice(castList(faultSummary.get("faultPoints")), 10)),
                            "equipmentSummary", NiagaraJson.obj(
                                    "networkCount", networks.size(),
                                    "deviceCount", devices.size())));
                } catch (Throwable e) {
                    LOG.warning("nmcp.building.brief error: " + e);
                    return McpToolResult.error("Building brief error: " + e.getMessage());
                }
            }
        };
    }

    private static List<Object> slice(List<Object> values, int size) {
        List<Object> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (int i = 0; i < values.size() && i < size; i++) {
            out.add(values.get(i));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return new ArrayList<Object>();
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