// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable result returned by an {@link McpTool} invocation.
 *
 * <p>On success: {@link #isError()} returns {@code false} and
 * {@link #getContent()} returns a non-null JSON string.
 *
 * <p>On failure: {@link #isError()} returns {@code true} and
 * {@link #getErrorMessage()} describes the problem.
 */
public final class McpToolResult {

    private final boolean error;
    private final String content;
    private final String errorMessage;
    private final Map<String, Object> errorPayload;

    private McpToolResult(boolean error, String content, String errorMessage, Map<String, Object> errorPayload) {
        this.error = error;
        this.content = content;
        this.errorMessage = errorMessage;
        this.errorPayload = errorPayload;
    }

    /** Creates a successful result wrapping the given JSON content string. */
    public static McpToolResult success(String jsonContent) {
        if (jsonContent == null) {
            throw new IllegalArgumentException("jsonContent must not be null");
        }
        return new McpToolResult(false, jsonContent, null, null);
    }

    /** Creates a successful result from a Map that will be serialised to JSON. */
    public static McpToolResult success(Map<String, Object> data) {
        return success(NiagaraJson.buildObject(data));
    }

    /** Creates an error result. */
    public static McpToolResult error(String message) {
        String msg = message != null ? message : "Unknown error";
        return error(msg, null, null, null, null);
    }

    /**
     * Creates an error result with deterministic machine-readable fields.
     * The top-level {@code error} string is always preserved for compatibility.
     */
    public static McpToolResult error(String message,
                                      String code,
                                      String path,
                                      String hint,
                                      List<Object> allowedValues) {
        String msg = message != null ? message : "Unknown error";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", msg);
        payload.put("code", code != null ? code : "NMCP_UNKNOWN_ERROR");
        payload.put("message", msg);
        payload.put("path", path != null ? path : "");
        payload.put("hint", hint != null ? hint : "");
        payload.put("allowedValues", allowedValues != null ? allowedValues : new ArrayList<Object>());
        return new McpToolResult(true, null, msg, payload);
    }

    /** Creates an error result from a fully formed payload map. */
    public static McpToolResult error(Map<String, Object> payload) {
        String msg = "Unknown error";
        if (payload != null) {
            Object error = payload.get("error");
            if (error != null) {
                msg = String.valueOf(error);
            } else {
                Object message = payload.get("message");
                if (message != null) {
                    msg = String.valueOf(message);
                }
            }
        }
        return new McpToolResult(true, null, msg, payload);
    }

    public boolean isError() {
        return error;
    }

    /** Returns the JSON content string (valid when {@link #isError()} is {@code false}). */
    public String getContent() {
        return content;
    }

    /** Returns the error message (valid when {@link #isError()} is {@code true}). */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Formats this result as the MCP {@code content} array expected by the
     * JSON-RPC {@code tools/call} result structure:
     * <pre>
     * [{"type":"text","text":"..."}]
     * </pre>
     */
    public List<Object> toMcpContent() {
        String text;
        if (error) {
            Map<String, Object> payload = errorPayload != null
                    ? errorPayload
                    : NiagaraJson.obj("error", errorMessage);
            text = NiagaraJson.buildObject(payload);
        } else {
            text = content;
        }
        Map<String, Object> item = NiagaraJson.obj("type", "text", "text", text);
        List<Object> list = new ArrayList<>();
        list.add(item);
        return Collections.unmodifiableList(list);
    }
}
