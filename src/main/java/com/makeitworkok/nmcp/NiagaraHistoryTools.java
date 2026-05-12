// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import com.tridium.history.BHistory;
import javax.baja.history.BHistoryRecord;
import javax.baja.history.BHistoryId;
import javax.baja.history.BNumericTrendRecord;
import javax.baja.history.BHistoryService;
import javax.baja.history.BIHistory;
import javax.baja.history.db.BHistoryDatabase;
import javax.baja.naming.BOrd;
import javax.baja.sys.BAbsTime;
import javax.baja.sys.Cursor;
import javax.baja.sys.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * MCP tools for Niagara history (trend) data.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code nmcp.history.list} – list available history identifiers</li>
 *   <li>{@code nmcp.history.read} – read time-series records for a history</li>
 * </ul>
 *
 * <p>History data retrieval is read-only. Writing to history is explicitly out of scope.
 */
public final class NiagaraHistoryTools {

    private static final Logger LOG = Logger.getLogger(NiagaraHistoryTools.class.getName());
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?");

    private final NiagaraSecurity security;

    public NiagaraHistoryTools(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(historyList());
        list.add(historyRead());
        list.add(trendSummary());
        return list;
    }

    // -------------------------------------------------------------------------
    // nmcp.history.list
    // -------------------------------------------------------------------------

    private McpTool historyList() {
        return new McpTool() {
            @Override public String name() { return "nmcp.history.list"; }

            @Override public String description() {
                return "Lists known history identifiers available in the station. "
                        + "Results are limited to the configured maximum.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max histories to return\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                Integer limit = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit);
                try {
                    BIHistory[] histories = getHistories(cx);
                    List<Object> list = new ArrayList<>();
                    int count = 0;
                    for (BIHistory h : histories) {
                        if (count++ >= effectiveLimit) break;
                        String id = "";
                        try {
                            BHistoryId historyId = h.getId();
                            id = historyId != null ? historyId.toString() : "";
                        } catch (Throwable ignored) {}
                        String dn = displayNameFor(h, cx);
                        list.add(NiagaraJson.obj("id", id, "displayName", dn));
                    }
                    return McpToolResult.success(NiagaraJson.obj(
                            "limit", effectiveLimit, "count", list.size(), "histories", list));
                } catch (Throwable e) {
                    LOG.warning("nmcp.history.list error: " + e);
                    return McpToolResult.error("History list error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.history.read
    // -------------------------------------------------------------------------

    private McpTool historyRead() {
        return new McpTool() {
            @Override public String name() { return "nmcp.history.read"; }

            @Override public String description() {
                return "Reads time-series records for a history by ID over an optional time window. "
                        + "startTime and endTime are epoch milliseconds. limit caps samples returned.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"id\":{\"type\":\"string\",\"description\":\"History ID from history.list\"},"
                        + "  \"startTime\":{\"type\":\"integer\",\"description\":\"Start epoch ms (optional)\"},"
                        + "  \"endTime\":{\"type\":\"integer\",\"description\":\"End epoch ms (optional)\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max samples\"}"
                        + "},"
                        + "\"required\":[\"id\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String id = getStringArg(arguments, "id");
                if (id == null) return McpToolResult.error("Missing required argument: id");
                Long startTime = getLongArg(arguments, "startTime");
                Long endTime   = getLongArg(arguments, "endTime");
                Integer limit  = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit);
                long start = startTime != null ? startTime : 0L;
                long end   = endTime   != null ? endTime   : System.currentTimeMillis();
                Cursor cursor = null;
                try {
                    BHistory history = findHistory(id, cx);
                    if (history == null) {
                        return McpToolResult.error("History not found: " + id);
                    }
                    cursor = history.timeQueryCursor(BAbsTime.make(start), BAbsTime.make(end), false, cx);
                    List<Object> rows = new ArrayList<>();
                    while (cursor != null && cursor.next() && rows.size() < effectiveLimit) {
                        Object row = cursor.get();
                        if (!(row instanceof BHistoryRecord)) {
                            continue;
                        }
                        BHistoryRecord record = (BHistoryRecord) row;
                        long ts = 0L;
                        String value = stringifyRecord(record, cx);
                        try {
                            BAbsTime absTime = record.getTimestamp();
                            ts = absTime != null ? absTime.getMillis() : 0L;
                        } catch (Throwable ignored) {}
                        rows.add(NiagaraJson.obj("timestamp", ts, "value", value));
                    }
                    return McpToolResult.success(NiagaraJson.obj(
                            "id", id, "startTime", start, "endTime", end,
                            "count", rows.size(), "records", rows));
                } catch (Throwable e) {
                    LOG.warning("nmcp.history.read error: " + e);
                    return McpToolResult.error("History read error: " + e.getMessage());
                } finally {
                    if (cursor != null) {
                        try { cursor.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.trend.summary
    // -------------------------------------------------------------------------

    private McpTool trendSummary() {
        return new McpTool() {
            @Override public String name() { return "nmcp.trend.summary"; }

            @Override public String description() {
                return "Summarizes a trend over the last N hours with min, max, first, and last values. "
                        + "Best for operator questions like 'what has space temperature been doing?'";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"id\":{\"type\":\"string\",\"description\":\"History ID from history.list\"},"
                        + "  \"hours\":{\"type\":\"integer\",\"description\":\"Window size in hours (default 4)\"},"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max samples to read (default 200)\"}"
                        + "},"
                        + "\"required\":[\"id\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String id = getStringArg(arguments, "id");
                if (id == null) {
                    return McpToolResult.error("Missing required argument: id");
                }
                Integer hoursArg = getIntArg(arguments, "hours");
                Integer limitArg = getIntArg(arguments, "limit");
                int hours = hoursArg != null ? hoursArg.intValue() : 4;
                int effectiveLimit = security.effectiveLimit(limitArg != null ? limitArg : Integer.valueOf(200));
                long end = System.currentTimeMillis();
                long start = end - (hours * 60L * 60L * 1000L);
                Cursor cursor = null;
                try {
                    BHistory history = findHistory(id, cx);
                    if (history == null) {
                        return McpToolResult.error("History not found: " + id);
                    }
                    cursor = history.timeQueryCursor(BAbsTime.make(start), BAbsTime.make(end), false, cx);

                    int sampleCount = 0;
                    Double min = null;
                    Double max = null;
                    Double first = null;
                    Double last = null;
                    while (cursor != null && cursor.next() && sampleCount < effectiveLimit) {
                        Object row = cursor.get();
                        if (!(row instanceof BHistoryRecord)) {
                            continue;
                        }
                        Double numeric = extractNumericValue((BHistoryRecord) row, cx);
                        if (numeric == null) {
                            continue;
                        }
                        if (first == null) {
                            first = numeric;
                        }
                        last = numeric;
                        min = min == null ? numeric : Double.valueOf(Math.min(min.doubleValue(), numeric.doubleValue()));
                        max = max == null ? numeric : Double.valueOf(Math.max(max.doubleValue(), numeric.doubleValue()));
                        sampleCount++;
                    }
                    return McpToolResult.success(NiagaraJson.obj(
                            "id", id,
                            "windowHours", hours,
                            "sampleCount", sampleCount,
                            "min", formatNumber(min),
                            "max", formatNumber(max),
                            "first", formatNumber(first),
                            "last", formatNumber(last),
                            "startTime", start,
                            "endTime", end));
                } catch (Throwable e) {
                    LOG.warning("nmcp.trend.summary error: " + e);
                    return McpToolResult.error("Trend summary error: " + e.getMessage());
                } finally {
                    if (cursor != null) {
                        try { cursor.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        };
    }

    static BIHistory[] getHistories(Context cx) {
        Object svc = BOrd.make("station:|slot:/Services/HistoryService").get(null, cx);
        if (!(svc instanceof BHistoryService)) {
            throw new IllegalStateException("HistoryService not available");
        }
        BHistoryDatabase db = ((BHistoryService) svc).getDatabase();
        if (db == null) {
            throw new IllegalStateException("History database not available");
        }
        return db.getHistories();
    }

    static BHistory findHistory(String id, Context cx) {
        BIHistory[] histories = getHistories(cx);
        for (BIHistory history : histories) {
            try {
                BHistoryId historyId = history.getId();
                String value = historyId != null ? historyId.toString() : "";
                if (id.equals(value) && history instanceof BHistory) {
                    return (BHistory) history;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static String displayNameFor(BIHistory history, Context cx) {
        if (history instanceof BHistory) {
            try {
                return ((BHistory) history).getDisplayName(cx);
            } catch (Throwable ignored) {}
        }
        try {
            BHistoryId id = history.getId();
            return id != null ? id.toString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String stringifyRecord(BHistoryRecord record, Context cx) {
        try {
            return record.toDataSummary(cx);
        } catch (Throwable ignored) {}
        try {
            return record.toString(cx);
        } catch (Throwable ignored) {}
        return record.toString();
    }

    private static Double extractNumericValue(BHistoryRecord record, Context cx) {
        try {
            if (record instanceof BNumericTrendRecord) {
                return Double.valueOf(((BNumericTrendRecord) record).getValue());
            }
        } catch (Throwable ignored) {}
        String raw = stringifyRecord(record, cx);
        Matcher matcher = NUMERIC_PATTERN.matcher(raw);
        if (matcher.find()) {
            try {
                return Double.valueOf(Double.parseDouble(matcher.group()));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String formatNumber(Double value) {
        if (value == null) {
            return "";
        }
        double numeric = value.doubleValue();
        if (numeric == (long) numeric) {
            return Long.toString((long) numeric);
        }
        String text = Double.toString(numeric);
        if (text.indexOf('E') >= 0 || text.indexOf('e') >= 0) {
            return text;
        }
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
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

    private Long getLongArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
