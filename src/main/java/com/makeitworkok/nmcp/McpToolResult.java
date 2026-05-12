// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import java.util.ArrayList;
import java.util.Collections;
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

    private McpToolResult(boolean error, String content, String errorMessage) {
        this.error = error;
        this.content = content;
        this.errorMessage = errorMessage;
    }

    /** Creates a successful result wrapping the given JSON content string. */
    public static McpToolResult success(String jsonContent) {
        if (jsonContent == null) {
            throw new IllegalArgumentException("jsonContent must not be null");
        }
        return new McpToolResult(false, jsonContent, null);
    }

    /** Creates a successful result from a Map that will be serialised to JSON. */
    public static McpToolResult success(Map<String, Object> data) {
        return success(NiagaraJson.buildObject(data));
    }

    /** Creates an error result. */
    public static McpToolResult error(String message) {
        return new McpToolResult(true, null, message != null ? message : "Unknown error");
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
            text = NiagaraJson.buildObject(NiagaraJson.obj("error", errorMessage));
        } else {
            text = content;
        }
        Map<String, Object> item = NiagaraJson.obj("type", "text", "text", text);
        List<Object> list = new ArrayList<>();
        list.add(item);
        return Collections.unmodifiableList(list);
    }
}
