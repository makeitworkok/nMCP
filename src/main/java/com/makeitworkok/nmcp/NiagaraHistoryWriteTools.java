// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import com.tridium.history.BHistory;
import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
                return "Write-mode required. Creates or configures a Niagara history for an allowlisted point ORD and "
                    + "attaches/enables history collection; use debug=true first to inspect runtime support safely.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"pointOrd\":{\"type\":\"string\",\"description\":\"Allowlisted point ORD to attach history to\"},"
                        + "  \"historyId\":{\"type\":\"string\",\"description\":\"Desired Niagara history ID/name; existing histories are reused when found\"},"
                        + "  \"enabled\":{\"type\":\"boolean\",\"description\":\"Whether history collection should be enabled on the point; default true\"},"
                        + "  \"sampleIntervalMs\":{\"type\":\"integer\",\"description\":\"Optional sample interval in milliseconds when supported by the point/history extension\"},"
                        + "  \"retentionCount\":{\"type\":\"integer\",\"description\":\"Optional history retention/capacity count when supported\"},"
                        + "  \"typeOptions\":{\"type\":\"object\",\"description\":\"Optional type-specific configuration bag passed to supported history extension setters\"},"
                        + "  \"debug\":{\"type\":\"boolean\",\"description\":\"If true, return HistoryService reflection details without mutating or requiring write mode\"}"
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
                boolean debug = asBoolean(arguments.get("debug"), false);
                Map<String, Object> typeOptions = asObject(arguments.get("typeOptions"));
                if (typeOptions == null) {
                    typeOptions = new LinkedHashMap<>();
                }

                try {
                    String normalizedPointOrd = localOrd(pointOrd);
                    security.checkAllowlist(normalizedPointOrd);

                    if (debug) {
                        return McpToolResult.success(createHistoryDebugReport(normalizedPointOrd, historyId, cx));
                    }

                    security.checkReadOnly();

                    Object resolved = BOrd.make(normalizedPointOrd).get(null, cx);
                    if (!(resolved instanceof BComponent)) {
                        return McpToolResult.error("pointOrd did not resolve to a component: " + normalizedPointOrd);
                    }
                    BComponent point = (BComponent) resolved;
                    List<Object> applyDetails = new ArrayList<>();

                    BHistory history = findHistoryByCandidates(historyId, cx);
                    boolean created = false;
                    if (history == null) {
                        history = tryCreateHistory(historyId, point, applyDetails, cx);
                        created = history != null;
                    }

                    boolean alreadyConfigured = isAlreadyConfigured(point, historyId);
                    boolean attached = attachHistoryToPoint(point, history, historyId, enabled, applyDetails, cx);
                    boolean configured = applyHistoryConfiguration(point, sampleIntervalMs, retentionCount, typeOptions, applyDetails, cx);

                    if (history == null) {
                        applyDetails.add("history object unresolved after create attempt; continuing with id/ord attachment path");
                    }

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

    private BHistory tryCreateHistory(String historyId, BComponent point, List<Object> details, Context cx) {
        try {
            Object svcObj = BOrd.make("station:|slot:/Services/HistoryService").get(null, cx);
            if (svcObj == null) {
                details.add("history create: HistoryService unavailable");
                return null;
            }
            Object db = invokeNoArg(svcObj, "getDatabase");
            if (db == null) {
                details.add("history create: history database unavailable");
                return null;
            }
            details.add("history create: db class=" + db.getClass().getName());

            String[] methodNames = new String[] {
                    "createHistory", "addHistory", "makeHistory", "create"
            };
            for (String candidateId : buildHistoryIdCandidates(historyId)) {
                for (String methodName : methodNames) {
                    Object created = invokeWithStringArg(db, methodName, candidateId);
                    if (created instanceof BHistory) {
                        details.add("history create: " + methodName + " on db succeeded for " + candidateId);
                        return (BHistory) created;
                    }
                }
            }
            details.add("history create: direct db create methods did not return BHistory");

            // Niagara 4.15 local DB path: build a BHistoryConfig and call setConfig(BHistoryConfig).
            if (createHistoryViaConnection(db, historyId, point, cx, details)
                    || createHistoryViaSetConfig(db, historyId, point, details)) {
                invokeNoArg(db, "createArchive");
                invokeNoArg(db, "flush");
                invokeNoArg(svcObj, "saveDb");
                BHistory created = findHistoryByCandidates(historyId, cx);
                if (created != null) {
                    details.add("history create: resolved after setConfig(BHistoryConfig)");
                    return created;
                }
                details.add("history create: setConfig invoked but history still unresolved");
            }

            // Some platforms create history via service-level APIs rather than database APIs.
            for (String candidateId : buildHistoryIdCandidates(historyId)) {
                for (String methodName : methodNames) {
                    Object created = invokeWithStringArg(svcObj, methodName, candidateId);
                    if (created instanceof BHistory) {
                        details.add("history create: " + methodName + " on service succeeded for " + candidateId);
                        return (BHistory) created;
                    }
                }
            }
            details.add("history create: service create methods did not return BHistory");

            return findHistoryByCandidates(historyId, cx);
        } catch (Throwable e) {
            details.add("history create: exception=" + describeThrowable(e));
            LOG.fine("History creation fallback failed: " + e.getMessage());
            return null;
        }
    }

    private boolean createHistoryViaSetConfig(Object historyDb, String historyId, BComponent point, List<Object> details) {
        if (historyDb == null || isBlank(historyId)) {
            return false;
        }
        try {
            boolean invoked = false;
            for (String candidateId : buildHistoryIdCandidates(historyId)) {
                Object config = makeHistoryConfig(candidateId, point, details);
                if (config == null) {
                    details.add("history create: config build failed for " + candidateId);
                    continue;
                }
                for (Method method : historyDb.getClass().getMethods()) {
                    if (!"setConfig".equals(method.getName())) {
                        continue;
                    }
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length != 1) {
                        continue;
                    }
                    if (!params[0].isInstance(config)) {
                        continue;
                    }
                    method.invoke(historyDb, config);
                    invoked = true;
                    details.add("history create: setConfig invoked for " + candidateId);
                }
            }
            return invoked;
        } catch (Throwable e) {
            details.add("history create: setConfig path exception=" + describeThrowable(e));
            LOG.fine("setConfig(BHistoryConfig) creation path failed: " + e.getMessage());
        }
        return false;
    }

    private boolean createHistoryViaConnection(Object historyDb,
                                               String historyId,
                                               BComponent point,
                                               Context cx,
                                               List<Object> details) {
        if (historyDb == null || isBlank(historyId)) {
            return false;
        }
        try {
            Object connection = null;
            for (Method m : historyDb.getClass().getMethods()) {
                if (!"getConnection".equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && Context.class.isAssignableFrom(p[0])) {
                    connection = m.invoke(historyDb, cx);
                    break;
                }
            }

            if (connection == null) {
                details.add("history create: getConnection(Context) unavailable");
                return false;
            }

            boolean invoked = false;
            for (String candidateId : buildHistoryIdCandidates(historyId)) {
                Object config = makeHistoryConfig(candidateId, point, details);
                Object historyIdObj = makeHistoryId(candidateId);
                if (config == null || historyIdObj == null) {
                    continue;
                }

                if (invokeSingleArg(connection, "createHistory", config)) {
                    invoked = true;
                    details.add("history create: connection.createHistory invoked for " + candidateId);
                }

                Boolean exists = invokeExists(connection, historyIdObj);
                if (Boolean.TRUE.equals(exists)) {
                    details.add("history create: connection.exists true for " + candidateId);
                    return true;
                }
            }

            return invoked;
        } catch (Throwable e) {
            details.add("history create: connection path exception=" + describeThrowable(e));
            return false;
        }
    }

    private Boolean invokeExists(Object connection, Object historyIdObj) {
        if (connection == null || historyIdObj == null) {
            return null;
        }
        for (Method method : connection.getClass().getMethods()) {
            if (!"exists".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) {
                continue;
            }
            if (!params[0].isInstance(historyIdObj)) {
                continue;
            }
            try {
                Object out = method.invoke(connection, historyIdObj);
                if (out instanceof Boolean) {
                    return (Boolean) out;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object makeHistoryConfig(String historyId, BComponent point, List<Object> details) {
        try {
            Class<?> configClass = Class.forName("javax.baja.history.BHistoryConfig");
            Object historyIdObj = makeHistoryId(historyId);
            if (historyIdObj == null) {
                details.add("history create: could not construct BHistoryId for " + historyId);
                return null;
            }

            Object typeSpec = makeHistoryRecordTypeSpec(point);

            Object config = null;
            if (typeSpec != null) {
                for (java.lang.reflect.Constructor<?> ctor : configClass.getConstructors()) {
                    Class<?>[] params = ctor.getParameterTypes();
                    if (params.length == 2 && params[0].isInstance(historyIdObj) && params[1].isInstance(typeSpec)) {
                        config = ctor.newInstance(historyIdObj, typeSpec);
                        break;
                    }
                }
            }

            if (config == null) {
                config = configClass.getConstructor().newInstance();
            }

            // Common names observed across releases.
            if (!invokeSingleArg(config, "setId", historyIdObj)
                    && !invokeSingleArg(config, "setHistoryId", historyIdObj)
                    && !invokeSingleArg(config, "setHistoryName", String.valueOf(historyId))) {
                details.add("history create: no id setter accepted for " + historyId);
                return null;
            }

            if (typeSpec != null && !invokeSingleArg(config, "setRecordType", typeSpec)) {
                details.add("history create: setRecordType not applied for " + historyId);
            }
            return config;
        } catch (Throwable e) {
            details.add("history create: BHistoryConfig construction exception=" + describeThrowable(e));
            LOG.fine("BHistoryConfig construction failed: " + e.getMessage());
            return null;
        }
    }

    private Object makeHistoryId(String historyId) {
        try {
            Class<?> historyIdClass = Class.forName("javax.baja.history.BHistoryId");
            String normalized = normalizeHistoryId(historyId);

            String deviceName = null;
            String historyName = null;
            if (!isBlank(normalized)) {
                String id = normalized.startsWith("/") ? normalized.substring(1) : normalized;
                int slash = id.indexOf('/');
                if (slash > 0 && slash < id.length() - 1) {
                    deviceName = id.substring(0, slash);
                    historyName = id.substring(slash + 1);
                }
            }

            for (Method method : historyIdClass.getMethods()) {
                if (!"make".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2
                        && String.class.equals(params[0])
                        && String.class.equals(params[1])
                        && !isBlank(deviceName)
                        && !isBlank(historyName)) {
                    try {
                        return method.invoke(null, deviceName, historyName);
                    } catch (Throwable ignored) {
                    }
                }
                if (params.length == 1 && String.class.equals(params[0])) {
                    try {
                        return method.invoke(null, normalized);
                    } catch (Throwable ignored) {
                    }
                    return method.invoke(null, historyId);
                }
            }
            for (java.lang.reflect.Constructor<?> ctor : historyIdClass.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 1 && String.class.equals(params[0])) {
                    try {
                        return ctor.newInstance(normalized);
                    } catch (Throwable ignored) {
                    }
                    return ctor.newInstance(historyId);
                }
            }
        } catch (Throwable e) {
            LOG.fine("BHistoryId construction failed: " + e.getMessage());
        }
        return null;
    }

    private BHistory findHistoryByCandidates(String historyId, Context cx) {
        for (String candidateId : buildHistoryIdCandidates(historyId)) {
            BHistory history = NiagaraHistoryTools.findHistory(candidateId, cx);
            if (history != null) {
                return history;
            }
        }
        return null;
    }

    private List<String> buildHistoryIdCandidates(String historyId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String base = normalizeHistoryId(historyId);
        if (!isBlank(base)) {
            ids.add(base);
            ids.add(base.startsWith("/") ? base.substring(1) : "/" + base);
        }
        String raw = asString(historyId);
        if (!isBlank(raw)) {
            ids.add(raw);
            ids.add(raw.startsWith("/") ? raw.substring(1) : "/" + raw);
        }
        return new ArrayList<>(ids);
    }

    private String normalizeHistoryId(String historyId) {
        String id = asString(historyId);
        if (isBlank(id)) {
            return id;
        }
        String trimmed = id.trim();
        if (trimmed.startsWith("local:|") || trimmed.startsWith("foxwss:|") || trimmed.contains("station:|")) {
            return trimmed;
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private Object makeHistoryRecordTypeSpec(BComponent point) {
        try {
            String trendRecordClassName = pickTrendRecordClassName(point);
            Class<?> trendRecordClass = Class.forName(trendRecordClassName);
            java.lang.reflect.Field typeField = trendRecordClass.getField("TYPE");
            Object typeObj = typeField.get(null);
            if (typeObj == null) {
                return null;
            }

            Class<?> typeSpecClass = Class.forName("javax.baja.util.BTypeSpec");
            for (Method method : typeSpecClass.getMethods()) {
                if (!"make".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0].isInstance(typeObj)) {
                    return method.invoke(null, typeObj);
                }
            }
        } catch (Throwable e) {
            LOG.fine("Unable to build history record type spec: " + e.getMessage());
        }
        return null;
    }

    private String pickTrendRecordClassName(BComponent point) {
        String className = point != null ? point.getClass().getName() : "";
        String lower = className != null ? className.toLowerCase() : "";

        if (lower.contains("boolean")) {
            return "javax.baja.history.BBooleanTrendRecord";
        }
        if (lower.contains("string")) {
            return "javax.baja.history.BStringTrendRecord";
        }
        if (lower.contains("enum")) {
            return "javax.baja.history.BEnumTrendRecord";
        }
        return "javax.baja.history.BNumericTrendRecord";
    }

    private boolean invokeSingleArg(Object target, String methodName, Object arg) {
        if (target == null || methodName == null) {
            return false;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) {
                continue;
            }
            if (arg != null && !params[0].isAssignableFrom(arg.getClass())) {
                continue;
            }
            try {
                method.invoke(target, arg);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private Map<String, Object> createHistoryDebugReport(String normalizedPointOrd, String historyId, Context cx) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pointOrd", normalizedPointOrd);
        report.put("historyId", historyId);
        report.put("historyService", describeClass(null));
        report.put("historyDatabase", describeClass(null));
        report.put("existingHistory", describeClass(null));
        report.put("existingHistoryFound", Boolean.FALSE);

        try {
            Object svcObj = BOrd.make("station:|slot:/Services/HistoryService").get(null, cx);
            report.put("historyService", describeClass(svcObj != null ? svcObj.getClass() : null));

            Object db = svcObj != null ? invokeNoArg(svcObj, "getDatabase") : null;
            report.put("historyDatabase", describeClass(db != null ? db.getClass() : null));

            BHistory history = NiagaraHistoryTools.findHistory(historyId, cx);
            report.put("existingHistory", describeClass(history != null ? history.getClass() : null));
            report.put("existingHistoryFound", Boolean.valueOf(history != null));
        } catch (Throwable e) {
            report.put("debugError", describeThrowable(e));
        }

        return report;
    }

    private boolean attachHistoryToPoint(BComponent point, BHistory history, String historyId,
                                         boolean enabled, List<Object> details, Context cx) {
        boolean applied = false;

        if (history != null) {
            if (invokeNamedSetter(point, "history", history, cx)) {
                details.add("setHistory applied");
                applied = true;
            }
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

    private Map<String, Object> describeClass(Class<?> type) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (type == null) {
            out.put("className", "<unknown>");
            out.put("superclasses", new ArrayList<Object>());
            out.put("interfaces", new ArrayList<Object>());
            out.put("publicMethods", new ArrayList<Object>());
            return out;
        }
        out.put("className", type.getName());
        out.put("superclasses", describeSuperclasses(type));
        out.put("interfaces", describeInterfaces(type));
        out.put("publicMethods", describePublicMethods(type));
        return out;
    }

    private List<Object> describeSuperclasses(Class<?> type) {
        List<Object> names = new ArrayList<>();
        Class<?> current = type != null ? type.getSuperclass() : null;
        while (current != null) {
            names.add(current.getName());
            current = current.getSuperclass();
        }
        return names;
    }

    private List<Object> describeInterfaces(Class<?> type) {
        List<String> names = new ArrayList<>();
        collectInterfaceNames(type, names);
        java.util.Collections.sort(names);
        List<Object> out = new ArrayList<>();
        out.addAll(names);
        return out;
    }

    private void collectInterfaceNames(Class<?> type, List<String> names) {
        if (type == null) {
            return;
        }
        Class<?>[] interfaces = type.getInterfaces();
        for (int i = 0; i < interfaces.length; i++) {
            String name = interfaces[i].getName();
            if (!names.contains(name)) {
                names.add(name);
            }
            collectInterfaceNames(interfaces[i], names);
        }
        collectInterfaceNames(type.getSuperclass(), names);
    }

    private List<Object> describePublicMethods(Class<?> type) {
        List<Object> signatures = new ArrayList<>();
        if (type == null) {
            return signatures;
        }
        Method[] methods = type.getMethods();
        List<String> methodStrings = new ArrayList<>();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            StringBuilder sb = new StringBuilder();
            sb.append(m.getReturnType().getName()).append(" ");
            sb.append(m.getDeclaringClass().getName()).append(".");
            sb.append(m.getName()).append("(");
            Class<?>[] params = m.getParameterTypes();
            for (int p = 0; p < params.length; p++) {
                if (p > 0) {
                    sb.append(", ");
                }
                sb.append(params[p].getName());
            }
            sb.append(")");
            methodStrings.add(sb.toString());
        }
        java.util.Collections.sort(methodStrings);
        signatures.addAll(methodStrings);
        return signatures;
    }

    private String describeThrowable(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "<no message>";
        }
        return root.getClass().getName() + ": " + message;
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
