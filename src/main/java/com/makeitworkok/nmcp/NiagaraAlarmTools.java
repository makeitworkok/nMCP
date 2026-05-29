// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.alarm.AlarmDbConnection;
import javax.baja.alarm.BAckState;
import javax.baja.alarm.BAlarmDatabase;
import javax.baja.alarm.BAlarmRecord;
import javax.baja.alarm.BAlarmService;
import javax.baja.naming.BOrd;
import javax.baja.naming.BOrdList;
import javax.baja.sys.BAbsTime;
import javax.baja.sys.Cursor;
import javax.baja.sys.Context;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * MCP tools for querying Niagara alarms.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code nmcp.alarm.query}  – recent alarm records</li>
 *   <li>{@code nmcp.alarm.active} – currently active/unacknowledged alarms</li>
 * </ul>
 *
 * <p>Read tools are always available. {@code nmcp.alarm.ack} requires {@code readOnly=false}.
 */
public final class NiagaraAlarmTools {

    private static final Logger LOG = Logger.getLogger(NiagaraAlarmTools.class.getName());

    private final NiagaraSecurity security;

    public NiagaraAlarmTools(NiagaraSecurity security) {
        this.security = security;
    }

    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(alarmQuery());
        list.add(alarmActive());
        list.add(alarmAck());
        return list;
    }

    // -------------------------------------------------------------------------
    // nmcp.alarm.query
    // -------------------------------------------------------------------------

    private McpTool alarmQuery() {
        return new McpTool() {
            @Override public String name() { return "nmcp.alarm.query"; }

            @Override public String description() {
                return "Returns recent alarm records from the station alarm service. "
                        + "Results are limited to the configured maximum. "
                        + "Alarm acknowledgement is not supported (read-only).";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max alarms to return\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                Integer limit = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit);
                try {
                    List<Object> alarms = fetchAlarms(effectiveLimit, false, cx);
                    return McpToolResult.success(NiagaraJson.obj(
                            "limit", effectiveLimit, "count", alarms.size(), "alarms", alarms));
                } catch (Throwable e) {
                    LOG.warning("nmcp.alarm.query error: " + e);
                    return McpToolResult.error("Alarm query error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.alarm.active
    // -------------------------------------------------------------------------

    private McpTool alarmActive() {
        return new McpTool() {
            @Override public String name() { return "nmcp.alarm.active"; }

            @Override public String description() {
                return "Lists currently active (unacknowledged or unresolved) alarms with source ORD, "
                        + "alarm class, priority, timestamp, and message.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"limit\":{\"type\":\"integer\",\"description\":\"Max alarms to return\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                Integer limit = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit);
                try {
                    List<Object> alarms = fetchAlarms(effectiveLimit, true, cx);
                    return McpToolResult.success(NiagaraJson.obj(
                            "limit", effectiveLimit, "count", alarms.size(), "alarms", alarms));
                } catch (Throwable e) {
                    LOG.warning("nmcp.alarm.active error: " + e);
                    return McpToolResult.error("Alarm query error: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.alarm.ack
    // -------------------------------------------------------------------------

    private McpTool alarmAck() {
        return new McpTool() {
            @Override public String name() { return "nmcp.alarm.ack"; }

            @Override public String description() {
                return "Acknowledges pending alarms by source ORD. "
                        + "Requires readOnly=false. "
                        + "Pass the ORD of the alarm source (e.g. station:|slot:/Drivers/BacnetNetwork/Device1/NumericPoint). "
                        + "Optional 'note' is recorded in the acknowledgement record. "
                        + "Returns the count of alarms acknowledged.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"sourceOrd\":{\"type\":\"string\",\"description\":\"Source ORD of the alarm to acknowledge\"},"
                        + "  \"note\":{\"type\":\"string\",\"description\":\"Acknowledgement note (optional)\"}"
                        + "},"
                        + "\"required\":[\"sourceOrd\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                Object sourceOrdArg = arguments.get("sourceOrd");
                if (!(sourceOrdArg instanceof String)) {
                    return McpToolResult.error("Missing required argument: sourceOrd");
                }
                String sourceOrd = (String) sourceOrdArg;
                String normalizedSourceOrd = normalizeAlarmSourceOrd(sourceOrd);
                String note = arguments.get("note") instanceof String
                        ? (String) arguments.get("note") : "Acknowledged via MCP";
                try {
                    security.checkReadOnly();
                    security.checkAllowlist(normalizedSourceOrd);

                    Object svc = BOrd.make("station:|slot:/Services/AlarmService").get(null, cx);
                    if (!(svc instanceof BAlarmService)) {
                        return McpToolResult.error("AlarmService not available");
                    }
                    BAlarmDatabase db = ((BAlarmService) svc).getAlarmDb();
                    if (db == null) return McpToolResult.error("Alarm database not available");

                    AlarmDbConnection conn = null;
                    Cursor cursor = null;
                    int acked = 0;
                    Set<String> ackedRecords = new HashSet<>();
                    try {
                        conn = db.getDbConnection(cx);
                        if (conn == null) return McpToolResult.error("Alarm database connection failed");

                        String[] sourceCandidates = buildSourceOrdCandidates(sourceOrd, normalizedSourceOrd);
                        for (int i = 0; i < sourceCandidates.length; i++) {
                            String candidate = sourceCandidates[i];
                            if (candidate == null || candidate.trim().isEmpty()) {
                                continue;
                            }

                            cursor = conn.getAlarmsForSource(BOrdList.make(candidate));
                            if (cursor == null) {
                                continue;
                            }

                            while (cursor.next()) {
                                Object row = cursor.get();
                                if (!(row instanceof BAlarmRecord)) continue;
                                BAlarmRecord record = (BAlarmRecord) row;

                                String dedupeKey;
                                try {
                                    dedupeKey = record.toString();
                                } catch (Throwable ignored) {
                                    dedupeKey = String.valueOf(System.identityHashCode(record));
                                }

                                if (ackedRecords.contains(dedupeKey)) {
                                    continue;
                                }

                                try {
                                    BAckState state = record.getAckState();
                                    if (state != null && "Unacked".equals(state.toString())) {
                                        if (acknowledgeRecord(record, note, cx, svc, conn)) {
                                            acked++;
                                            ackedRecords.add(dedupeKey);
                                        }
                                    }
                                } catch (Throwable e) {
                                    // If we can't read ack state, try to ack anyway.
                                    try {
                                        if (acknowledgeRecord(record, note, cx, svc, conn)) {
                                            acked++;
                                            ackedRecords.add(dedupeKey);
                                        }
                                    } catch (Throwable ignored2) {}
                                }
                            }

                            try { cursor.close(); } catch (Throwable ignored) {}
                            cursor = null;
                            if (acked > 0) {
                                break;
                            }
                        }
                    } finally {
                        if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
                        if (conn != null)   try { conn.close();   } catch (Throwable ignored) {}
                    }
                    return McpToolResult.success(NiagaraJson.obj(
                            "sourceOrd", sourceOrd,
                            "normalizedSourceOrd", normalizedSourceOrd,
                            "acknowledged", acked,
                            "note", note));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Throwable e) {
                    LOG.warning("nmcp.alarm.ack error for " + sourceOrd + ": " + e);
                    return McpToolResult.error("Alarm ack error: " + e.getMessage());
                }
            }
        };
    }

    /**
     * Acknowledges a single alarm record and returns true only when post-ack state is no longer Unacked.
     */
    private static boolean acknowledgeRecord(
            BAlarmRecord record,
            String note,
            Context cx,
            Object alarmService,
            AlarmDbConnection conn) throws Throwable {
        boolean invoked = false;

        // Most deterministic path on Niagara 4.15: doAckAlarm(BAlarmRecord)
        if (alarmService != null) {
            for (java.lang.reflect.Method m : alarmService.getClass().getMethods()) {
                if ("doAckAlarm".equals(m.getName())) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0].isAssignableFrom(record.getClass())) {
                        m.invoke(alarmService, record);
                        persistAlarmRecordUpdate(conn, record);
                        return !isUnacked(record);
                    }
                }
            }
        }

        // Preferred path on Niagara 4.15: invoke BAlarmService.ackAlarm(BAlarmRecord)
        if (alarmService != null) {
            for (java.lang.reflect.Method m : alarmService.getClass().getMethods()) {
                if ("ackAlarm".equals(m.getName())) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0].isAssignableFrom(record.getClass())) {
                        m.invoke(alarmService, record);
                        invoked = true;
                        break;
                    }
                }
            }
            if (invoked) {
                persistAlarmRecordUpdate(conn, record);
                return !isUnacked(record);
            }
        }

        // Niagara 4.15 runtime provides ackAlarm(String)/ackAlarm() on BAlarmRecord.
        for (java.lang.reflect.Method m : record.getClass().getMethods()) {
            if ("ackAlarm".equals(m.getName())) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && String.class.equals(p[0])) {
                    m.invoke(record, note);
                    persistAlarmRecordUpdate(conn, record);
                    return !isUnacked(record);
                }
                if (p.length == 0) {
                    m.invoke(record);
                    persistAlarmRecordUpdate(conn, record);
                    return !isUnacked(record);
                }
            }
        }

        // Legacy fallback for runtimes exposing acknowledge(...) variants.
        for (java.lang.reflect.Method m : record.getClass().getMethods()) {
            if ("acknowledge".equals(m.getName())) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && String.class.equals(p[0]) && Context.class.isAssignableFrom(p[1])) {
                    m.invoke(record, note, cx);
                    persistAlarmRecordUpdate(conn, record);
                    return !isUnacked(record);
                }
                if (p.length == 1 && Context.class.isAssignableFrom(p[0])) {
                    m.invoke(record, cx);
                    persistAlarmRecordUpdate(conn, record);
                    return !isUnacked(record);
                }
            }
        }
        throw new UnsupportedOperationException("No acknowledge method on " + record.getClass().getSimpleName());
    }

    private static boolean isUnacked(BAlarmRecord record) {
        try {
            BAckState state = record.getAckState();
            return state != null && "Unacked".equals(state.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void persistAlarmRecordUpdate(AlarmDbConnection conn, BAlarmRecord record) {
        if (conn == null || record == null) return;
        try {
            java.lang.reflect.Method update = conn.getClass().getMethod("update", record.getClass());
            update.invoke(conn, record);
        } catch (Throwable ignored) {
            try {
                java.lang.reflect.Method update = conn.getClass().getMethod("update", BAlarmRecord.class);
                update.invoke(conn, record);
            } catch (Throwable ignored2) {
                // Some runtimes persist through service-level ack without explicit db update.
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static List<Object> fetchAlarms(int effectiveLimit, boolean ackPendingOnly, Context cx) {
        Object svc = BOrd.make("station:|slot:/Services/AlarmService").get(null, cx);
        if (!(svc instanceof BAlarmService)) {
            throw new IllegalStateException("AlarmService not available");
        }

        BAlarmDatabase db = ((BAlarmService) svc).getAlarmDb();
        if (db == null) {
            throw new IllegalStateException("Alarm database not available");
        }

        AlarmDbConnection conn = null;
        Cursor cursor = null;
        List<Object> alarms = new ArrayList<>();
        try {
            conn = db.getDbConnection(cx);
            if (conn == null) {
                throw new IllegalStateException("Alarm database connection not available");
            }
            cursor = ackPendingOnly ? conn.getAckPendingAlarms() : conn.scan();
            if (cursor == null) {
                return alarms;
            }

            while (cursor.next() && alarms.size() < effectiveLimit) {
                Object row = cursor.get();
                if (row instanceof BAlarmRecord) {
                    alarms.add(alarmToMap((BAlarmRecord) row));
                }
            }
            return alarms;
        } catch (Throwable e) {
            LOG.warning("alarm fetch error: " + e);
            throw e;
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) {}
            }
            if (conn != null) {
                try { conn.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static Map<String, Object> alarmToMap(BAlarmRecord r) {
        String ackState    = "";
        String alarmClass  = "";
        int priority       = 0;
        String timestamp   = "";
        long timestampMs   = 0L;
        String source      = "";
        String data        = "";
        try {
            BAckState state = r.getAckState();
            ackState = state != null ? state.toString() : "";
        } catch (Throwable ignored) {}
        try { alarmClass = r.getAlarmClass(); } catch (Throwable ignored) {}
        try { priority = r.getPriority(); } catch (Throwable ignored) {}
        try {
            BAbsTime ts = r.getTimestamp();
            timestamp = ts != null ? ts.toString() : "";
            timestampMs = ts != null ? ts.getMillis() : 0L;
        } catch (Throwable ignored) {}
        try {
            BOrdList src = r.getSource();
            source = src != null ? src.toString() : "";
        } catch (Throwable ignored) {}
        try {
            Object alarmData = r.getAlarmData();
            data = alarmData != null ? alarmData.toString() : "";
        } catch (Throwable ignored) {}
        return NiagaraJson.obj(
                "ackState", ackState,
                "alarmClass", alarmClass,
                "priority", priority,
                "timestamp", timestamp,
                "timestampMillis", timestampMs,
                "source", source,
                "data", data);
    }

    private Integer getIntArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    static String normalizeAlarmSourceOrd(String ord) {
        if (ord == null) return null;
        String s = ord.trim();
        if (s.startsWith("local:|foxwss:|")) s = s.substring("local:|foxwss:|".length());
        else if (s.startsWith("foxwss:|")) s = s.substring("foxwss:|".length());
        else if (s.startsWith("local:|")) s = s.substring("local:|".length());

        if (s.startsWith("station:|station:|")) {
            s = "station:|" + s.substring("station:|station:|".length());
        }
        return s;
    }

    private static String[] buildSourceOrdCandidates(String rawSourceOrd, String normalizedSourceOrd) {
        List<String> out = new ArrayList<>();
        addUnique(out, normalizedSourceOrd);
        addUnique(out, rawSourceOrd);
        addUnique(out, normalizeAlarmSourceOrd(rawSourceOrd));
        return out.toArray(new String[out.size()]);
    }

    private static void addUnique(List<String> list, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (!list.contains(value)) list.add(value);
    }
}
