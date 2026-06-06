// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import javax.baja.naming.BOrd;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
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

    private static final String[] BACNET_DEVICE_CLASS_CANDIDATES = new String[] {
        "javax.baja.bacnet.BBacnetDevice",
        "com.tridium.bacnet.BBacnetDevice"
    };

    private static final String[] BACNET_NETWORK_CLASS_CANDIDATES = new String[] {
        "javax.baja.bacnet.BBacnetNetwork",
        "com.tridium.bacnet.BBacnetNetwork"
    };

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
                return "Use to list provisioned BACnet device components under an allowlisted BACnet network ORD. "
                    + "Read-only; returns device name, ORD, instance number, address, and network number.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"networkOrd\":{\"type\":\"string\","
                        + "    \"description\":\"Allowlisted BACnet network ORD, e.g. station:|slot:/Drivers/BacnetNetwork\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Maximum provisioned BACnet devices to return, capped by BMcpService maxResults\"}"
                        + "},"
                        + "\"required\":[\"networkOrd\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String networkOrd = getStringArg(arguments, "networkOrd");
                if (networkOrd == null || networkOrd.trim().isEmpty()) {
                    return McpToolResult.error("Missing required argument: networkOrd");
                }
                Integer limit = getIntArg(arguments, "limit");
                String normalizedOrd = normalizeOrd(networkOrd);

                try {
                    security.checkAllowlist(normalizedOrd);
                    int effectiveLimit = security.effectiveLimit(limit);

                    Object resolved = BOrd.make(normalizedOrd).get(null, cx);
                    if (!(resolved instanceof BComponent)) {
                        return McpToolResult.error("ORD did not resolve to a component: " + networkOrd);
                    }
                    BComponent network = (BComponent) resolved;

                    BacnetRuntime runtime = detectBacnetRuntime();
                    if (!runtime.deviceClassAvailable) {
                        return unsupportedBacnetRuntime("nmcp.bacnet.devices", networkOrd, runtime);
                    }

                    List<Object> deviceList = new ArrayList<>();
                    BComponent[] children = network.getChildComponents();
                    if (children == null) {
                        children = new BComponent[0];
                    }
                    for (BComponent child : children) {
                        if (deviceList.size() >= effectiveLimit) break;
                        if (!isBacnetDevice(child, runtime)) continue;
                        deviceList.add(deviceToMap(child, normalizedOrd, cx));
                    }

                    return McpToolResult.success(NiagaraJson.obj(
                            "networkOrd", networkOrd,
                            "limit",      effectiveLimit,
                            "count",      deviceList.size(),
                            "devices",    deviceList));

                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    return bacnetRuntimeError("nmcp.bacnet.devices", networkOrd, normalizedOrd, e);
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
                return "Use for read-only BACnet discovery insight before provisioning. Returns the network stack's "
                        + "WhoIs/IAm-heard device registry, including devices not yet added as station components.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"networkOrd\":{\"type\":\"string\","
                        + "    \"description\":\"Allowlisted BACnet network ORD to inspect, e.g. station:|slot:/Drivers/BacnetNetwork\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Maximum heard BACnet devices to return, capped by BMcpService maxResults\"}"
                        + "},"
                        + "\"required\":[\"networkOrd\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String networkOrd = getStringArg(arguments, "networkOrd");
                if (networkOrd == null || networkOrd.trim().isEmpty()) {
                    return McpToolResult.error("Missing required argument: networkOrd");
                }
                Integer limit = getIntArg(arguments, "limit");
                String normalizedOrd = normalizeOrd(networkOrd);

                try {
                    security.checkAllowlist(normalizedOrd);
                    int effectiveLimit = security.effectiveLimit(limit);

                    Object resolved = BOrd.make(normalizedOrd).get(null, cx);
                    if (!(resolved instanceof BComponent)) {
                        return McpToolResult.error("ORD did not resolve to a component: " + networkOrd);
                    }

                    BacnetRuntime runtime = detectBacnetRuntime();
                    if (!runtime.networkClassAvailable) {
                        return unsupportedBacnetRuntime("nmcp.bacnet.discover", networkOrd, runtime);
                    }

                    Method getDeviceListMethod = findMethod(resolved.getClass(), "getDeviceList");
                    if (getDeviceListMethod == null) {
                        return McpToolResult.error("BACnet runtime unsupported: network component does not expose getDeviceList(); "
                                + "use nmcp.bacnet.devices if provisioned components are present");
                    }

                    Object allDevices = getDeviceListMethod.invoke(resolved);
                    if (allDevices == null) {
                        allDevices = new Object[0];
                    }
                    List<Object> deviceList = new ArrayList<>();
                    for (Object dev : toList(allDevices)) {
                        if (deviceList.size() >= effectiveLimit) break;
                        if (dev == null) continue;
                        if (!isBacnetDevice(dev, runtime)) continue;
                        deviceList.add(deviceToMap(dev, normalizedOrd, cx));
                    }

                    return McpToolResult.success(NiagaraJson.obj(
                            "networkOrd",    networkOrd,
                            "limit",         effectiveLimit,
                            "count",         deviceList.size(),
                            "devices",       deviceList));

                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    return bacnetRuntimeError("nmcp.bacnet.discover", networkOrd, normalizedOrd, e);
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> deviceToMap(Object dev, String networkOrd, Context cx) {
        String name = "";
        String devOrd = "";
        int instanceNumber = -1;
        String address = "";
        int networkNumber = 0;

        try {
            Method getName = findMethod(dev.getClass(), "getName");
            if (getName != null) {
                Object value = getName.invoke(dev);
                if (value != null) {
                    name = String.valueOf(value);
                }
            }
        } catch (Throwable ignored) {}
        try {
            devOrd = networkOrd + "/slot:" + name;
        } catch (Throwable ignored) {}
        try {
            Method getObjectId = findMethod(dev.getClass(), "getObjectId");
            Object oid = getObjectId != null ? getObjectId.invoke(dev) : null;
            if (oid != null) {
                Method getInstanceNumber = findMethod(oid.getClass(), "getInstanceNumber");
                if (getInstanceNumber != null) {
                    Object value = getInstanceNumber.invoke(oid);
                    if (value instanceof Number) {
                        instanceNumber = ((Number) value).intValue();
                    }
                }
            }
        } catch (Throwable ignored) {}
        try {
            Method getAddress = findMethod(dev.getClass(), "getAddress");
            Object addr = getAddress != null ? getAddress.invoke(dev) : null;
            if (addr != null) {
                Method toStringWithContext = findMethod(addr.getClass(), "toString", Context.class);
                if (toStringWithContext != null) {
                    Object value = toStringWithContext.invoke(addr, cx);
                    address = value != null ? String.valueOf(value) : "";
                } else {
                    address = String.valueOf(addr);
                }
                Method getNetworkNumber = findMethod(addr.getClass(), "getNetworkNumber");
                if (getNetworkNumber != null) {
                    Object value = getNetworkNumber.invoke(addr);
                    if (value instanceof Number) {
                        networkNumber = ((Number) value).intValue();
                    }
                }
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

    private BacnetRuntime detectBacnetRuntime() {
        Class<?> deviceClass = loadFirstAvailableClass(BACNET_DEVICE_CLASS_CANDIDATES);
        Class<?> networkClass = loadFirstAvailableClass(BACNET_NETWORK_CLASS_CANDIDATES);
        return new BacnetRuntime(deviceClass, networkClass);
    }

    private Class<?> loadFirstAvailableClass(String[] names) {
        if (names == null) return null;
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean isBacnetDevice(Object value, BacnetRuntime runtime) {
        if (value == null) return false;
        if (runtime.deviceClass != null && runtime.deviceClass.isInstance(value)) {
            return true;
        }
        String name = value.getClass().getName().toLowerCase();
        return name.contains("bacnet") && name.contains("device");
    }

    private Method findMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
        try {
            return type.getMethod(methodName, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<Object> toList(Object value) {
        List<Object> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) {
                out.add(Array.get(value, i));
            }
            return out;
        }
        if (value instanceof Iterable) {
            for (Object v : (Iterable<?>) value) {
                out.add(v);
            }
            return out;
        }
        out.add(value);
        return out;
    }

    private McpToolResult unsupportedBacnetRuntime(String toolName, String networkOrd, BacnetRuntime runtime) {
        String detail = "BACnet runtime unsupported on this station profile; "
                + "deviceClassAvailable=" + runtime.deviceClassAvailable
                + ", networkClassAvailable=" + runtime.networkClassAvailable
                + ". Try validating Niagara BACnet modules/classes for this platform version.";
        LOG.warning(toolName + " unsupported for " + networkOrd + ": " + detail);
        return McpToolResult.error(detail);
    }

    private McpToolResult bacnetRuntimeError(String toolName, String rawOrd, String normalizedOrd, Throwable e) {
        String type = e.getClass().getSimpleName();
        String msg = e.getMessage() != null ? e.getMessage() : "(no message)";
        LOG.warning(toolName + " error for " + rawOrd + " [normalized=" + normalizedOrd + "] "
                + type + ": " + msg);
        return McpToolResult.error("BACnet runtime error [" + type + "]: " + msg);
    }

    private static final class BacnetRuntime {
        final Class<?> deviceClass;
        final Class<?> networkClass;
        final boolean deviceClassAvailable;
        final boolean networkClassAvailable;

        BacnetRuntime(Class<?> deviceClass, Class<?> networkClass) {
            this.deviceClass = deviceClass;
            this.networkClass = networkClass;
            this.deviceClassAvailable = deviceClass != null;
            this.networkClassAvailable = networkClass != null;
        }
    }
}
