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
 * MCP tools for reading live point values from Niagara proxy points.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code nmcp.point.read}   – read the current value of a single point by ORD</li>
 *   <li>{@code nmcp.point.search} – find proxy points by display name or type substring</li>
 * </ul>
 *
 * <p>All point access is read-only.
 */
public final class NiagaraPointTools {

    private static final Logger LOG = Logger.getLogger(NiagaraPointTools.class.getName());
    private final NiagaraSecurity security;

    public NiagaraPointTools(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(pointRead());
        list.add(pointSearch());
        return list;
    }

    // -------------------------------------------------------------------------
    // nmcp.point.read
    // -------------------------------------------------------------------------

    private McpTool pointRead() {
        return new McpTool() {
            @Override public String name() { return "nmcp.point.read"; }

            @Override public String description() {
                return "Reads the current value and status of a single proxy point by ORD. "
                        + "Returns the out/value slot content. ORD must be within allowlisted roots.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\",\"description\":\"Proxy point ORD\"}"
                        + "},"
                        + "\"required\":[\"ord\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String ord = getStringArg(arguments, "ord");
                if (ord == null) return McpToolResult.error("Missing required argument: ord");
                try {
                    security.checkAllowlist(ord);
                    Object obj = BOrd.make(ord).get(null, cx);
                    if (!(obj instanceof BComponent)) {
                        return McpToolResult.error("ORD is not a component: " + ord);
                    }
                    BComponent point = (BComponent) obj;
                    String typeName = "";
                    try { typeName = point.getType() != null ? point.getType().getTypeName() : ""; } catch (Throwable ignored) {}
                    String displayName = "";
                    try { displayName = point.getDisplayName(cx); } catch (Throwable ignored) {}
                    Map<String, Object> parsed = readPointValue(point);
                    Map<String, Object> result = NiagaraJson.obj(
                            "ord", ord, "displayName", displayName, "type", typeName);
                    result.putAll(parsed);
                    return McpToolResult.success(result);
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.point.read error for " + ord + ": " + e);
                    return McpToolResult.error("Point read error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.point.search
    // -------------------------------------------------------------------------

    private McpTool pointSearch() {
        return new McpTool() {
            @Override public String name() { return "nmcp.point.search"; }

            @Override public String description() {
                return "Finds proxy points under the Drivers tree filtered by display name substring "
                        + "and/or type substring (e.g. 'NumericPoint', 'BooleanPoint'). "
                        + "Returns ORD, display name, type, and current value for each match.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"nameFilter\":{\"type\":\"string\","
                        + "    \"description\":\"Substring match on display name (case-insensitive)\"},"
                        + "  \"typeFilter\":{\"type\":\"string\","
                        + "    \"description\":\"Substring match on type spec (e.g. NumericPoint)\"},"
                        + "  \"root\":{\"type\":\"string\","
                        + "    \"description\":\"Root ORD to search (default station:|slot:/Drivers)\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max results\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String nameFilter = getStringArg(arguments, "nameFilter");
                String typeFilter = getStringArg(arguments, "typeFilter");
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
                    List<Object> matches = new ArrayList<>();
                    searchPoints((BComponent) obj, root, nameFilter, typeFilter, matches, effectiveLimit, cx);
                    return McpToolResult.success(NiagaraJson.obj(
                            "root", root, "count", matches.size(), "points", matches));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.point.search error: " + e);
                    return McpToolResult.error("Point search error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void searchPoints(BComponent comp, String compOrd,
                               String nameFilter, String typeFilter,
                               List<Object> out, int limit, Context cx) {
        if (out.size() >= limit) return;
        try {
            String typeName = "";
            try { typeName = comp.getType() != null ? comp.getType().getTypeName() : ""; } catch (Throwable ignored) {}
            String displayName = "";
            try { displayName = comp.getDisplayName(cx); } catch (Throwable ignored) {}

            boolean typeMatch = typeFilter == null || typeFilter.isEmpty()
                    || typeName.toLowerCase().contains(typeFilter.toLowerCase());
            boolean nameMatch = nameFilter == null || nameFilter.isEmpty()
                    || displayName.toLowerCase().contains(nameFilter.toLowerCase());
            boolean isPoint = typeName.contains("NumericPoint") || typeName.contains("BooleanPoint")
                    || typeName.contains("StringPoint") || typeName.contains("EnumPoint")
                    || typeName.contains("ProxyPoint") || typeName.contains("Point");

            if (isPoint && typeMatch && nameMatch) {
                Map<String, Object> entry = NiagaraJson.obj(
                        "ord", compOrd, "displayName", displayName, "type", typeName);
                entry.putAll(readPointValue(comp));
                out.add(entry);
            }

            BComponent[] children = comp.getChildComponents();
            if (children != null) {
                for (BComponent child : children) {
                    if (out.size() >= limit) break;
                    String childName = "";
                    try { childName = child.getName(); } catch (Throwable ignored) {}
                    String childOrd = compOrd.endsWith("/")
                            ? compOrd + childName : compOrd + "/" + childName;
                    searchPoints(child, childOrd, nameFilter, typeFilter, out, limit, cx);
                }
            }
        } catch (Throwable e) {
            LOG.warning("searchPoints error at " + compOrd + ": " + e.getMessage());
        }
    }

    static Map<String, Object> readPointValue(BComponent point) {
        String raw = "unavailable";
        for (String slotName : new String[]{"out", "value", "presentValue", "rawValue"}) {
            try {
                Object val = point.get(slotName);
                if (val != null) { raw = val.toString(); break; }
            } catch (Throwable ignored) {}
        }
        return parseValueAndStatus(raw);
    }

    static Map<String, Object> parseValueAndStatus(String raw) {
        // Niagara status values look like "83.20 {ok}" or "true {stale}" or "0.00 {fault|overridden}"
        String value = raw;
        String status = "";
        int braceOpen = raw.lastIndexOf('{');
        int braceClose = raw.lastIndexOf('}');
        if (braceOpen >= 0 && braceClose > braceOpen) {
            status = raw.substring(braceOpen + 1, braceClose).trim();
            value  = raw.substring(0, braceOpen).trim();
        }
        Map<String, Object> m = NiagaraJson.obj("value", value, "status", status, "raw", raw);
        return m;
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
