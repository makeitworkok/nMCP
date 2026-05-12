// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.sys.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

                    // TODO: Replace with actual BQL execution using the Niagara API.
                    //       Example (Niagara 4.x):
                    //         BQlQuery bql = BQlQuery.make(query);
                    //         Cursor cursor = bql.execute(cx);
                    //         while (cursor.next() && rows.size() < effectiveLimit) {
                    //             rows.add(rowToMap(cursor.get()));
                    //         }
                    List<Object> rows = new ArrayList<>();
                    // Stub: return an empty result set with a TODO note
                    rows.add(NiagaraJson.obj(
                            "_note", "TODO: BQL execution requires the Niagara SDK. "
                                    + "The query '" + query + "' would execute here.",
                            "_limit", effectiveLimit
                    ));

                    Map<String, Object> result = NiagaraJson.obj(
                            "query",   query,
                            "limit",   effectiveLimit,
                            "count",   0,
                            "rows",    rows
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
}
