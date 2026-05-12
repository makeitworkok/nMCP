// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.bacnet.BBacnetDevice;
import javax.baja.bacnet.BBacnetNetwork;
import javax.baja.bacnet.datatypes.BBacnetAddress;
import javax.baja.bacnet.datatypes.BBacnetObjectIdentifier;
import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import javax.baja.naming.BOrd;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MCP tool for listing BACnet devices visible in the station.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code nmcp.bacnet.devices} – list BACnet devices under a network ORD</li>
 * </ul>
 *
 * <p>All BACnet access is read-only.  BACnet writes and device provisioning are
 * explicitly out of scope for v0.1.
 */
public final class NiagaraBacnetTools {

    private static final Logger LOG = Logger.getLogger(NiagaraBacnetTools.class.getName());

    private final NiagaraSecurity security;

    public NiagaraBacnetTools(NiagaraSecurity security) {
        this.security = security;
    }

    /** Returns all tools provided by this class. */
    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(bacnetDevices());
        list.add(bacnetDiscover());
        return list;
    }

    // -------------------------------------------------------------------------
    // nmcp.bacnet.devices
    // -------------------------------------------------------------------------

    private McpTool bacnetDevices() {
        return new McpTool() {
            @Override public String name() { return "nmcp.bacnet.devices"; }

            @Override public String description() {
                return "Lists BACnet devices visible under a given BACnet network ORD. "
                        + "The networkOrd must be within allowlisted roots. "
                        + "Results include device name, ORD, device ID, address, and status.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"networkOrd\":{\"type\":\"string\","
                        + "    \"description\":\"ORD of the BACnet network, "
                        + "e.g. station:|slot:/Drivers/BacnetNetwork\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max devices to return\"}"
                        + "},"
                        + "\"required\":[\"networkOrd\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String networkOrd = getStringArg(arguments, "networkOrd");
                if (networkOrd == null || networkOrd.trim().isEmpty()) {
                    return McpToolResult.error("Missing required argument: networkOrd");
                }
                Integer limit = getIntArg(arguments, "limit");

                try {
                    security.checkAllowlist(networkOrd);
                    int effectiveLimit = security.effectiveLimit(limit);
                    String normalizedOrd = normalizeOrd(networkOrd);

                    Object resolved = BOrd.make(normalizedOrd).get(null, cx);
                    if (!(resolved instanceof BComponent)) {
                        return McpToolResult.error("ORD did not resolve to a component: " + networkOrd);
                    }
                    BComponent network = (BComponent) resolved;

                    List<Object> deviceList = new ArrayList<>();
                    BComponent[] children = network.getChildComponents();
                    for (BComponent child : children) {
                        if (deviceList.size() >= effectiveLimit) break;
                        if (!(child instanceof BBacnetDevice)) continue;
                        BBacnetDevice dev = (BBacnetDevice) child;
                        deviceList.add(deviceToMap(dev, normalizedOrd, cx));
                    }

                    return McpToolResult.success(NiagaraJson.obj(
                            "networkOrd", networkOrd,
                            "limit",      effectiveLimit,
                            "count",      deviceList.size(),
                            "devices",    deviceList));

                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Exception e) {
                    LOG.warning("nmcp.bacnet.devices error for " + networkOrd + ": " + e.getMessage());
                    return McpToolResult.error("BACnet device query error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.bacnet.discover
    // -------------------------------------------------------------------------

    private McpTool bacnetDiscover() {
        return new McpTool() {
            @Override public String name() { return "nmcp.bacnet.discover"; }

            @Override public String description() {
                return "Returns the BACnet stack's in-memory device registry for a network — "
                        + "all devices heard via WhoIs/IAm, including those not yet provisioned "
                        + "as station components. Read-only; does not add devices to the station.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"networkOrd\":{\"type\":\"string\","
                        + "    \"description\":\"ORD of the BACnet network, "
                        + "e.g. station:|slot:/Drivers/BacnetNetwork\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max devices to return\"}"
                        + "},"
                        + "\"required\":[\"networkOrd\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String networkOrd = getStringArg(arguments, "networkOrd");
                if (networkOrd == null || networkOrd.trim().isEmpty()) {
                    return McpToolResult.error("Missing required argument: networkOrd");
                }
                Integer limit = getIntArg(arguments, "limit");

                try {
                    security.checkAllowlist(networkOrd);
                    int effectiveLimit = security.effectiveLimit(limit);
                    String normalizedOrd = normalizeOrd(networkOrd);

                    Object resolved = BOrd.make(normalizedOrd).get(null, cx);
                    if (!(resolved instanceof BBacnetNetwork)) {
                        return McpToolResult.error(
                                "ORD did not resolve to a BBacnetNetwork: " + networkOrd);
                    }
                    BBacnetNetwork network = (BBacnetNetwork) resolved;

                    BBacnetDevice[] allDevices = network.getDeviceList();
                    List<Object> deviceList = new ArrayList<>();
                    for (BBacnetDevice dev : allDevices) {
                        if (deviceList.size() >= effectiveLimit) break;
                        deviceList.add(deviceToMap(dev, normalizedOrd, cx));
                    }

                    return McpToolResult.success(NiagaraJson.obj(
                            "networkOrd",    networkOrd,
                            "limit",         effectiveLimit,
                            "count",         deviceList.size(),
                            "devices",       deviceList));

                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Exception e) {
                    LOG.warning("nmcp.bacnet.discover error for " + networkOrd + ": " + e.getMessage());
                    return McpToolResult.error("BACnet discover error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> deviceToMap(BBacnetDevice dev, String networkOrd, Context cx) {
        String name = "";
        String devOrd = "";
        int instanceNumber = -1;
        String address = "";
        int networkNumber = 0;

        try { name = dev.getName(); } catch (Throwable ignored) {}
        try {
            devOrd = networkOrd + "/slot:" + name;
        } catch (Throwable ignored) {}
        try {
            BBacnetObjectIdentifier oid = dev.getObjectId();
            if (oid != null) instanceNumber = oid.getInstanceNumber();
        } catch (Throwable ignored) {}
        try {
            BBacnetAddress addr = dev.getAddress();
            if (addr != null) {
                address = addr.toString(cx);
                networkNumber = addr.getNetworkNumber();
            }
        } catch (Throwable ignored) {}

        return NiagaraJson.obj(
                "name",          name,
                "ord",           devOrd,
                "instanceNumber", instanceNumber,
                "address",       address,
                "networkNumber", networkNumber);
    }

    // -------------------------------------------------------------------------
    // Argument helpers
    // -------------------------------------------------------------------------

    private static String normalizeOrd(String s) {
        if (s.startsWith("local:|foxwss:|")) s = s.substring("local:|foxwss:|".length());
        else if (s.startsWith("foxwss:|"))   s = s.substring("foxwss:|".length());
        else if (s.startsWith("local:|"))    s = s.substring("local:|".length());
        return s;
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
