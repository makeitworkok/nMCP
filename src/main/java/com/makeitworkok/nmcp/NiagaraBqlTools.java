// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.naming.BOrd;
import javax.baja.sys.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * MCP tool for executing read-only BQL (Baja Query Language) queries.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code nmcp.bql.query} – run a SELECT BQL query and return results as JSON</li>
 * </ul>
 *
 * <p>Only SELECT-style queries are permitted.  Any mutation keywords cause an
 * immediate rejection.  Result counts are capped at the configured maximum.
 */
public final class NiagaraBqlTools {

    private static final Logger LOG = Logger.getLogger(NiagaraBqlTools.class.getName());

    private final NiagaraSecurity security;

    private static final int MAX_SERIALIZE_DEPTH = 3;

    public NiagaraBqlTools(NiagaraSecurity security) {
        this.security = security;
    }

    /** Returns all tools provided by this class. */
    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(bqlQuery());
        return list;
    }

    // -------------------------------------------------------------------------
    // nmcp.bql.query
    // -------------------------------------------------------------------------

    private McpTool bqlQuery() {
        return new McpTool() {
            @Override public String name() { return "nmcp.bql.query"; }

            @Override public String description() {
                return "Executes a read-only BQL SELECT query against the station and returns "
                        + "results as a JSON array of row objects. Only SELECT queries are allowed. "
                        + "Results are capped at the configured maximum.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"query\":{\"type\":\"string\",\"description\":\"BQL SELECT query\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max result rows\"}"
                        + "},"
                        + "\"required\":[\"query\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String query = getStringArg(arguments, "query");
                if (query == null || query.trim().isEmpty()) {
                    return McpToolResult.error("Missing required argument: query");
                }
                Integer limit = getIntArg(arguments, "limit");

                try {
                    security.checkBqlQuery(query);
                    int effectiveLimit = security.effectiveLimit(limit);

                    QueryExecution execution = executeBql(query, effectiveLimit, cx);

                    Map<String, Object> result = NiagaraJson.obj(
                            "query",   query,
                            "limit",   effectiveLimit,
                        "count",   execution.rows.size(),
                        "rows",    execution.rows,
                        "truncated", execution.truncated,
                        "engine", execution.engineClass
                    );
                    return McpToolResult.success(result);

                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Exception e) {
                    LOG.warning("nmcp.bql.query error: " + e.getMessage());
                    return McpToolResult.error("BQL query error: " + e.getMessage());
                }
            }
        };
    }

    private QueryExecution executeBql(String query, int effectiveLimit, Context cx) throws Exception {
        Class<?> bqlClass = findBqlClass();
        if (bqlClass == null) {
            throw new IllegalStateException("BQL runtime class not found (tried javax.baja.bql and javax.baja.query variants)");
        }

        Object bqlQuery = createBqlQuery(bqlClass, query);
        CursorExecution cursorExecution = executeQueryToCursor(bqlQuery, query, cx);
        Object cursor = cursorExecution.cursor;
        if (cursor == null) {
            return new QueryExecution(new ArrayList<Object>(), false, cursorExecution.engineClass);
        }

        List<Object> rows = new ArrayList<>();
        boolean truncated = false;
        Method nextMethod = findMethod(cursor.getClass(), "next");
        Method getMethod = findMethod(cursor.getClass(), "get");
        Method closeMethod = findMethod(cursor.getClass(), "close");
        if (nextMethod == null || getMethod == null) {
            throw new IllegalStateException("BQL cursor does not expose next()/get() methods");
        }

        try {
            while (Boolean.TRUE.equals(nextMethod.invoke(cursor))) {
                if (rows.size() >= effectiveLimit) {
                    truncated = true;
                    break;
                }
                Object row = getMethod.invoke(cursor);
                rows.add(serializeValue(row, 0));
            }
        } finally {
            if (closeMethod != null) {
                try {
                    closeMethod.invoke(cursor);
                } catch (Throwable ignored) {
                }
            }
        }

        return new QueryExecution(rows, truncated, cursorExecution.engineClass);
    }

    private Class<?> findBqlClass() {
        String[] classNames = new String[] {
            "javax.baja.bql.BqlQuery",
                "javax.baja.bql.BBqlQuery",
                "javax.baja.bql.BQlQuery",
                "javax.baja.query.BBqlQuery",
                "javax.baja.query.BQlQuery"
        };
        for (String className : classNames) {
            try {
                return Class.forName(className);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object createBqlQuery(Class<?> bqlClass, String query) throws Exception {
        Method makeMethod = findMethod(bqlClass, "make", String.class);
        if (makeMethod == null) {
            throw new IllegalStateException("BQL class does not expose static make(String)");
        }
        return makeMethod.invoke(null, query);
    }

    private CursorExecution executeQueryToCursor(Object bqlQuery, String query, Context cx) throws Exception {
        Method executeWithCx = findMethod(bqlQuery.getClass(), "execute", Context.class);
        if (executeWithCx != null) {
            return toCursorExecution(executeWithCx.invoke(bqlQuery, cx), bqlQuery.getClass().getName());
        }
        Method executeNoArgs = findMethod(bqlQuery.getClass(), "execute");
        if (executeNoArgs != null) {
            return toCursorExecution(executeNoArgs.invoke(bqlQuery), bqlQuery.getClass().getName());
        }
        Method cursorWithCx = findMethod(bqlQuery.getClass(), "cursor", Context.class);
        if (cursorWithCx != null) {
            return toCursorExecution(cursorWithCx.invoke(bqlQuery, cx), bqlQuery.getClass().getName());
        }
        Method cursorNoArgs = findMethod(bqlQuery.getClass(), "cursor");
        if (cursorNoArgs != null) {
            return toCursorExecution(cursorNoArgs.invoke(bqlQuery), bqlQuery.getClass().getName());
        }

        CursorExecution engineCursor = executeViaBqlEngine(query, cx);
        if (engineCursor != null) {
            return engineCursor;
        }

        CursorExecution ordCursor = executeViaOrd(query, cx);
        if (ordCursor != null) {
            return ordCursor;
        }
        throw new IllegalStateException("BQL query object does not expose execute()/cursor() methods");
    }

    private CursorExecution executeViaBqlEngine(String query, Context cx) {
        try {
            Class<?> engineClass = Class.forName("com.tridium.bql.query.BBqlEngine");
            Object queryOrd = BOrd.make("station:|slot:/|bql:" + query);

            Object engine = null;
            for (java.lang.reflect.Constructor<?> ctor : engineClass.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 1 && params[0].isInstance(queryOrd)) {
                    engine = ctor.newInstance(queryOrd);
                    break;
                }
            }
            if (engine == null) {
                return null;
            }

            Method executeWithCx = findMethod(engineClass, "execute", Context.class);
            if (executeWithCx != null) {
                return toCursorExecution(executeWithCx.invoke(engine, cx), engineClass.getName());
            }
            Method executeNoArgs = findMethod(engineClass, "execute");
            if (executeNoArgs != null) {
                return toCursorExecution(executeNoArgs.invoke(engine), engineClass.getName());
            }
            return null;
        } catch (Throwable e) {
            LOG.fine("BQL engine fallback failed: " + e.getMessage());
            return null;
        }
    }

    private CursorExecution executeViaOrd(String query, Context cx) {
        String[] ords = new String[] {
                "station:|slot:/|bql:" + query,
                "station:|slot:/|bql:" + encodeForOrdQuery(query)
        };
        for (String ordString : ords) {
            try {
                BOrd ord = BOrd.make(ordString);
                Object direct = ord.get(null, cx);
                CursorExecution c1 = toCursorExecution(direct, "station-ord-bql");
                if (c1.cursor != null) {
                    return c1;
                }

                Object resolved = resolveOrdReflective(ord, cx);
                Object resolvedObj = extractOrdTargetObject(resolved);
                CursorExecution c2 = toCursorExecution(resolvedObj, "station-ord-bql");
                if (c2.cursor != null) {
                    return c2;
                }
            } catch (Throwable e) {
                LOG.fine("BQL ORD fallback failed for '" + ordString + "': " + e.getMessage());
            }
        }
        return null;
    }

    private Object extractOrdTargetObject(Object resolved) {
        if (resolved == null) {
            return null;
        }
        Method getMethod = findMethod(resolved.getClass(), "get");
        if (getMethod != null) {
            try {
                return getMethod.invoke(resolved);
            } catch (Throwable ignored) {
            }
        }
        return resolved;
    }

    private Object resolveOrdReflective(BOrd ord, Context cx) {
        if (ord == null) {
            return null;
        }
        try {
            Method resolveWithCx = ord.getClass().getMethod("resolve", Object.class, Context.class);
            return resolveWithCx.invoke(ord, null, cx);
        } catch (Throwable ignored) {
        }
        try {
            Method resolveNoArgs = ord.getClass().getMethod("resolve");
            return resolveNoArgs.invoke(ord);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String encodeForOrdQuery(String query) {
        if (query == null) {
            return "";
        }
        // ORD query segment is URL-like; keep this conservative to avoid malformed ORDs.
        return query
                .replace("%", "%25")
                .replace(" ", "%20")
                .replace("\"", "%22")
                .replace("#", "%23")
                .replace("?", "%3F");
    }

    private CursorExecution toCursorExecution(Object value, String engineClass) {
        if (value == null) {
            return new CursorExecution(null, engineClass);
        }
        Method nextMethod = findMethod(value.getClass(), "next");
        Method getMethod = findMethod(value.getClass(), "get");
        if (nextMethod != null && getMethod != null) {
            return new CursorExecution(value, engineClass);
        }
        Method cursorMethod = findMethod(value.getClass(), "cursor");
        if (cursorMethod != null) {
            try {
                Object cursor = cursorMethod.invoke(value);
                return new CursorExecution(cursor, engineClass);
            } catch (Throwable e) {
                LOG.fine("cursor() extraction failed: " + e.getMessage());
            }
        }
        return new CursorExecution(null, engineClass);
    }

    private Method findMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
        try {
            return type.getMethod(methodName, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object serializeValue(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth >= MAX_SERIALIZE_DEPTH) {
            return value.toString();
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map) {
            Map<Object, Object> input = (Map<Object, Object>) value;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> e : input.entrySet()) {
                String key = e.getKey() == null ? "null" : e.getKey().toString();
                out.put(key, serializeValue(e.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            List<Object> input = (List<Object>) value;
            for (Object v : input) {
                out.add(serializeValue(v, depth + 1));
            }
            return out;
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < len; i++) {
                out.add(serializeValue(Array.get(value, i), depth + 1));
            }
            return out;
        }

        Map<String, Object> fromSlots = trySerializeSlots(value, depth + 1);
        if (fromSlots != null && !fromSlots.isEmpty()) {
            fromSlots.put("_type", value.getClass().getName());
            return fromSlots;
        }

        return value.toString();
    }

    private Map<String, Object> trySerializeSlots(Object value, int depth) {
        Method getPropertiesArray = findMethod(value.getClass(), "getPropertiesArray");
        Method getByName = findMethod(value.getClass(), "get", String.class);
        if (getPropertiesArray == null || getByName == null) {
            return null;
        }

        try {
            Object propsObj = getPropertiesArray.invoke(value);
            if (propsObj == null || !propsObj.getClass().isArray()) {
                return null;
            }
            int len = Array.getLength(propsObj);
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < len; i++) {
                Object prop = Array.get(propsObj, i);
                if (prop == null) {
                    continue;
                }
                Method getName = findMethod(prop.getClass(), "getName");
                if (getName == null) {
                    continue;
                }
                String slotName = String.valueOf(getName.invoke(prop));
                Object slotValue;
                try {
                    slotValue = getByName.invoke(value, slotName);
                } catch (Throwable ignored) {
                    continue;
                }
                if (security.isSensitiveSlot(slotName)) {
                    map.put(slotName, NiagaraSecurity.maskedValue());
                } else {
                    map.put(slotName, serializeValue(slotValue, depth + 1));
                }
            }
            return map;
        } catch (Throwable e) {
            LOG.fine("BQL row slot serialization failed: " + e.getMessage());
            return null;
        }
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

    private static final class QueryExecution {
        final List<Object> rows;
        final boolean truncated;
        final String engineClass;

        QueryExecution(List<Object> rows, boolean truncated, String engineClass) {
            this.rows = rows;
            this.truncated = truncated;
            this.engineClass = engineClass;
        }
    }

    private static final class CursorExecution {
        final Object cursor;
        final String engineClass;

        CursorExecution(Object cursor, String engineClass) {
            this.cursor = cursor;
            this.engineClass = engineClass;
        }
    }
}
