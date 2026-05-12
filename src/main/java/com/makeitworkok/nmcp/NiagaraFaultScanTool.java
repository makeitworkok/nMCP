// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class NiagaraFaultScanTool {

    private static final Logger LOG = Logger.getLogger(NiagaraFaultScanTool.class.getName());
    private final NiagaraSecurity security;

    public NiagaraFaultScanTool(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(faultScan());
        return list;
    }

    private McpTool faultScan() {
        return new McpTool() {
            @Override public String name() { return "nmcp.fault.scan"; }

            @Override public String description() {
                return "Scans points under a root ORD and summarizes points in fault, stale, or overridden state.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"root\":{\"type\":\"string\",\"description\":\"Root ORD to scan (default station:|slot:/Drivers)\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max point matches to return per category\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String root = getStringArg(arguments, "root");
                if (root == null || root.isEmpty()) {
                    root = "station:|slot:/Drivers";
                }
                Integer limit = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit);
                try {
                    Map<String, Object> result = scanFaults(security, root, effectiveLimit, cx);
                    return McpToolResult.success(result);
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.fault.scan error: " + e);
                    return McpToolResult.error("Fault scan error: " + e.getMessage());
                }
            }
        };
    }

    static Map<String, Object> scanFaults(NiagaraSecurity security, String root, int effectiveLimit, Context cx)
            throws NiagaraSecurity.McpSecurityException {
        security.checkAllowlist(root);
        Object obj = BOrd.make(root).get(null, cx);
        if (!(obj instanceof BComponent)) {
            throw new IllegalStateException("Root ORD not a component: " + root);
        }

        List<Object> faultPoints = new ArrayList<>();
        List<Object> stalePoints = new ArrayList<>();
        List<Object> overriddenPoints = new ArrayList<>();
        int[] scanned = new int[] {0};

        collectFaults((BComponent) obj, root, effectiveLimit, faultPoints, stalePoints, overriddenPoints, scanned, cx);

        return NiagaraJson.obj(
                "root", root,
                "scanned", scanned[0],
                "faultCount", faultPoints.size(),
                "staleCount", stalePoints.size(),
                "overriddenCount", overriddenPoints.size(),
                "faultPoints", faultPoints,
                "stalePoints", stalePoints,
                "overriddenPoints", overriddenPoints);
    }

    private static void collectFaults(BComponent comp, String compOrd, int limit,
                                      List<Object> faultPoints, List<Object> stalePoints,
                                      List<Object> overriddenPoints, int[] scanned, Context cx) {
        try {
            String typeName = "";
            try { typeName = comp.getType() != null ? comp.getType().getTypeName() : ""; } catch (Throwable ignored) {}
            boolean isPoint = typeName.contains("NumericPoint") || typeName.contains("BooleanPoint")
                    || typeName.contains("StringPoint") || typeName.contains("EnumPoint")
                    || typeName.contains("ProxyPoint") || typeName.contains("Point");

            if (isPoint) {
                scanned[0]++;
                String displayName = "";
                try { displayName = comp.getDisplayName(cx); } catch (Throwable ignored) {}
                Map<String, Object> parsed = NiagaraPointTools.readPointValue(comp);
                String status = parsed.get("status") != null ? parsed.get("status").toString().toLowerCase() : "";
                String raw = parsed.get("raw") != null ? parsed.get("raw").toString() : "";
                Map<String, Object> entry = NiagaraJson.obj(
                        "ord", compOrd,
                        "displayName", displayName,
                        "status", status,
                        "raw", raw);
                if (status.contains("fault") && faultPoints.size() < limit) {
                    faultPoints.add(entry);
                }
                if (status.contains("stale") && stalePoints.size() < limit) {
                    stalePoints.add(entry);
                }
                if (status.contains("overridden") && overriddenPoints.size() < limit) {
                    overriddenPoints.add(entry);
                }
            }

            BComponent[] children = comp.getChildComponents();
            if (children == null) {
                return;
            }
            for (BComponent child : children) {
                String childName = "";
                try { childName = child.getName(); } catch (Throwable ignored) {}
                String childOrd = compOrd.endsWith("/") ? compOrd + childName : compOrd + "/" + childName;
                collectFaults(child, childOrd, limit, faultPoints, stalePoints, overriddenPoints, scanned, cx);
            }
        } catch (Throwable e) {
            LOG.warning("collectFaults error at " + compOrd + ": " + e.getMessage());
        }
    }

    private Integer getIntArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString() : null;
    }
}