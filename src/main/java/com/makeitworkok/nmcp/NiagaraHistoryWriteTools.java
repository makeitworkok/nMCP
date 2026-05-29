// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import com.tridium.history.BHistory;
import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MCP write tool for creating/configuring history and attaching it to a point.
 */
public final class NiagaraHistoryWriteTools {

    private static final Logger LOG = Logger.getLogger(NiagaraHistoryWriteTools.class.getName());

    private final NiagaraSecurity security;

    public NiagaraHistoryWriteTools(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(historyProvisionOnPoint());
        return list;
    }

    private McpTool historyProvisionOnPoint() {
        return new McpTool() {
            @Override public String name() { return "nmcp.history.provisionOnPoint"; }

            @Override public String description() {
                return "Creates/configures a Niagara history and attaches it to a point in one call. "
                        + "Requires readOnly=false and pointOrd within allowlisted roots.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"pointOrd\":{\"type\":\"string\",\"description\":\"Point ORD to attach history to\"},"
                        + "  \"historyId\":{\"type\":\"string\",\"description\":\"History ID/name\"},"
                        + "  \"enabled\":{\"type\":\"boolean\",\"description\":\"Enable history on the point (default true)\"},"
                        + "  \"sampleIntervalMs\":{\"type\":\"integer\",\"description\":\"Optional sample interval in ms\"},"
                        + "  \"retentionCount\":{\"type\":\"integer\",\"description\":\"Optional retention/capacity count\"},"
                        + "  \"typeOptions\":{\"type\":\"object\",\"description\":\"Type-specific option bag\"}"
                        + "},"
                        + "\"required\":[\"pointOrd\",\"historyId\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String pointOrd = asString(arguments.get("pointOrd"));
                String historyId = asString(arguments.get("historyId"));
                if (isBlank(pointOrd)) {
                    return McpToolResult.error("Missing required argument: pointOrd");
                }
                if (isBlank(historyId)) {
                    return McpToolResult.error("Missing required argument: historyId");
                }

                boolean enabled = asBoolean(arguments.get("enabled"), true);
                Integer sampleIntervalMs = asInt(arguments.get("sampleIntervalMs"));
                Integer retentionCount = asInt(arguments.get("retentionCount"));
                Map<String, Object> typeOptions = asObject(arguments.get("typeOptions"));
                if (typeOptions == null) {
                    typeOptions = new LinkedHashMap<>();
                }

                try {
                    security.checkReadOnly();
                    String normalizedPointOrd = localOrd(pointOrd);
                    security.checkAllowlist(normalizedPointOrd);

                    Object resolved = BOrd.make(normalizedPointOrd).get(null, cx);
                    if (!(resolved instanceof BComponent)) {
                        return McpToolResult.error("pointOrd did not resolve to a component: " + normalizedPointOrd);
                    }
                    BComponent point = (BComponent) resolved;

                    BHistory history = NiagaraHistoryTools.findHistory(historyId, cx);
                    boolean created = false;
                    if (history == null) {
                        history = tryCreateHistory(historyId, cx);
                        created = history != null;
                    }
                    if (history == null) {
                        return McpToolResult.error("History not found and could not be created: " + historyId);
                    }

                    boolean alreadyConfigured = isAlreadyConfigured(point, historyId);
                    List<Object> applyDetails = new ArrayList<>();
                    boolean attached = attachHistoryToPoint(point, history, historyId, enabled, applyDetails, cx);
                    boolean configured = applyHistoryConfiguration(point, sampleIntervalMs, retentionCount, typeOptions, applyDetails, cx);

                    String status;
                    if (alreadyConfigured) {
                        status = "alreadyConfigured";
                    } else if (created || attached || configured) {
                        status = "configured";
                    } else {
                        status = "partial";
                    }

                    return McpToolResult.success(NiagaraJson.obj(
                            "status", status,
                            "createdHistory", created,
                            "pointOrd", normalizedPointOrd,
                            "historyId", historyId,
                            "enabled", enabled,
                            "sampleIntervalMs", sampleIntervalMs,
                            "retentionCount", retentionCount,
                            "details", applyDetails
                    ));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.history.provisionOnPoint error: " + e);
                    return McpToolResult.error("History provision error: " + e.getMessage());
                }
            }
        };
    }

    private BHistory tryCreateHistory(String historyId, Context cx) {
        try {
            Object svcObj = BOrd.make("station:|slot:/Services/HistoryService").get(null, cx);
            if (svcObj == null) {
                return null;
            }
            Object db = invokeNoArg(svcObj, "getDatabase");
            if (db == null) {
                return null;
            }

            String[] methodNames = new String[] {
                    "createHistory", "addHistory", "makeHistory", "create"
            };
            for (String methodName : methodNames) {
                Object created = invokeWithStringArg(db, methodName, historyId);
                if (created instanceof BHistory) {
                    return (BHistory) created;
                }
            }

            // Some platforms create history via service-level APIs rather than database APIs.
            for (String methodName : methodNames) {
                Object created = invokeWithStringArg(svcObj, methodName, historyId);
                if (created instanceof BHistory) {
                    return (BHistory) created;
                }
            }

            return NiagaraHistoryTools.findHistory(historyId, cx);
        } catch (Throwable e) {
            LOG.fine("History creation fallback failed: " + e.getMessage());
            return null;
        }
    }

    private boolean attachHistoryToPoint(BComponent point, BHistory history, String historyId,
                                         boolean enabled, List<Object> details, Context cx) {
        boolean applied = false;

        if (invokeNamedSetter(point, "history", history, cx)) {
            details.add("setHistory applied");
            applied = true;
        }

        if (invokeNamedSetter(point, "historyId", historyId, cx)) {
            details.add("setHistoryId applied");
            applied = true;
        }

        String historyOrd = null;
        try {
            historyOrd = history.getHandleOrd() != null ? history.getHandleOrd().toString() : null;
        } catch (Throwable ignored) {
        }
        if (!isBlank(historyOrd) && invokeNamedSetter(point, "historyOrd", historyOrd, cx)) {
            details.add("setHistoryOrd applied");
            applied = true;
        }

        if (invokeNamedSetter(point, "historyEnabled", Boolean.valueOf(enabled), cx)
                || invokeNamedSetter(point, "enableHistory", Boolean.valueOf(enabled), cx)
                || invokeNamedSetter(point, "recordHistory", Boolean.valueOf(enabled), cx)) {
            details.add("history enabled flag applied");
            applied = true;
        }

        // Final fallback through generic point.set(slotName, value)
        if (!applied) {
            applied = applySlotFallback(point, "historyId", historyId)
                    || applySlotFallback(point, "historyName", historyId)
                    || (!isBlank(historyOrd) && applySlotFallback(point, "historyOrd", historyOrd));
            if (applied) {
                details.add("history attachment applied via slot fallback");
            }
        }

        return applied;
    }

    private boolean applyHistoryConfiguration(BComponent point,
                                              Integer sampleIntervalMs,
                                              Integer retentionCount,
                                              Map<String, Object> typeOptions,
                                              List<Object> details,
                                              Context cx) {
        boolean configured = false;

        if (sampleIntervalMs != null) {
            if (invokeNamedSetter(point, "sampleIntervalMs", sampleIntervalMs, cx)
                    || invokeNamedSetter(point, "sampleInterval", sampleIntervalMs, cx)
                    || invokeNamedSetter(point, "interval", sampleIntervalMs, cx)) {
                details.add("sample interval applied");
                configured = true;
            }
        }

        if (retentionCount != null) {
            if (invokeNamedSetter(point, "retentionCount", retentionCount, cx)
                    || invokeNamedSetter(point, "capacity", retentionCount, cx)
                    || invokeNamedSetter(point, "maxRecords", retentionCount, cx)) {
                details.add("retention/capacity applied");
                configured = true;
            }
        }

        for (Map.Entry<String, Object> entry : typeOptions.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isBlank(key)) {
                continue;
            }
            if (invokeNamedSetter(point, key, value, cx) || applySlotFallback(point, key, value)) {
                details.add("type option applied: " + key);
                configured = true;
            } else {
                details.add("type option not applied: " + key);
            }
        }

        return configured;
    }

    private boolean isAlreadyConfigured(BComponent point, String historyId) {
        String[] slots = new String[] {"historyId", "historyName", "historyOrd"};
        for (String slot : slots) {
            try {
                Object value = point.get(slot);
                if (value != null && String.valueOf(value).contains(historyId)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean invokeNamedSetter(BComponent target, String key, Object value, Context cx) {
        if (target == null || isBlank(key)) {
            return false;
        }

        String setterName = "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        Method[] methods = target.getClass().getMethods();
        for (Method method : methods) {
            if (!setterName.equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            try {
                if (params.length == 1) {
                    Object converted = convertValueForType(value, params[0]);
                    if (converted == UNSET) {
                        continue;
                    }
                    method.invoke(target, converted);
                    return true;
                }
                if (params.length == 2 && Context.class.isAssignableFrom(params[1])) {
                    Object converted = convertValueForType(value, params[0]);
                    if (converted == UNSET) {
                        continue;
                    }
                    method.invoke(target, converted, cx);
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean applySlotFallback(BComponent point, String slotName, Object value) {
        for (Method method : point.getClass().getMethods()) {
            if (!"set".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || !String.class.equals(params[0])) {
                continue;
            }
            try {
                Object converted = convertValueForType(value, params[1]);
                if (converted == UNSET) {
                    continue;
                }
                method.invoke(point, slotName, converted);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object invokeWithStringArg(Object target, String methodName, String value) {
        Method[] methods = target.getClass().getMethods();
        for (Method method : methods) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            try {
                if (params.length == 1 && String.class.equals(params[0])) {
                    return method.invoke(target, value);
                }
                if (params.length == 2 && String.class.equals(params[0])
                        && Context.class.isAssignableFrom(params[1])) {
                    return method.invoke(target, value, null);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object convertValueForType(Object value, Class<?> targetType) {
        if (targetType == null) {
            return UNSET;
        }
        if (value == null) {
            if (!targetType.isPrimitive()) {
                return null;
            }
            return UNSET;
        }
        if (targetType.isAssignableFrom(value.getClass())) {
            return value;
        }
        if (String.class.equals(targetType)) {
            return String.valueOf(value);
        }
        if (Integer.class.equals(targetType) || Integer.TYPE.equals(targetType)) {
            Integer i = asInt(value);
            return i != null ? i : UNSET;
        }
        if (Long.class.equals(targetType) || Long.TYPE.equals(targetType)) {
            Long l = asLong(value);
            return l != null ? l : UNSET;
        }
        if (Double.class.equals(targetType) || Double.TYPE.equals(targetType)) {
            Double d = asDouble(value);
            return d != null ? d : UNSET;
        }
        if (Float.class.equals(targetType) || Float.TYPE.equals(targetType)) {
            Double d = asDouble(value);
            return d != null ? Float.valueOf(d.floatValue()) : UNSET;
        }
        if (Boolean.class.equals(targetType) || Boolean.TYPE.equals(targetType)) {
            return Boolean.valueOf(asBoolean(value, false));
        }
        return UNSET;
    }

    private static String localOrd(String ord) {
        if (ord == null) return null;
        String s = ord;
        if (s.startsWith("local:|foxwss:|")) s = s.substring("local:|foxwss:|".length());
        else if (s.startsWith("foxwss:|")) s = s.substring("foxwss:|".length());
        else if (s.startsWith("local:|")) s = s.substring("local:|".length());
        return s;
    }

    private static String asString(Object v) {
        return v != null ? v.toString() : null;
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number) return Integer.valueOf(((Number) v).intValue());
        if (v instanceof String) {
            try {
                return Integer.valueOf(Integer.parseInt((String) v));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Long asLong(Object v) {
        if (v instanceof Number) return Long.valueOf(((Number) v).longValue());
        if (v instanceof String) {
            try {
                return Long.valueOf(Long.parseLong((String) v));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number) return Double.valueOf(((Number) v).doubleValue());
        if (v instanceof String) {
            try {
                return Double.valueOf(Double.parseDouble((String) v));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static boolean asBoolean(Object v, boolean defaultValue) {
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object v) {
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static final Object UNSET = new Object();
}
