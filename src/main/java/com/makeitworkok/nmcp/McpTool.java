// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.sys.Context;
import java.util.Map;

/**
 * Interface that every MCP tool must implement.
 *
 * <p>Tools are registered with the {@link McpToolRegistry} and invoked by
 * {@link McpJsonRpcHandler} in response to {@code tools/call} requests.
 *
 * <p>Implementations MUST be thread-safe (they may be called concurrently).
 */
public interface McpTool {

    /** Unique tool name as it appears in MCP {@code tools/list} responses (e.g. {@code nmcp.station.info}). */
    String name();

    /** Human-readable description shown to clients. */
    String description();

    /**
     * JSON Schema string describing the accepted {@code arguments} object.
     * Return a minimal schema or {@code "{}"} if no arguments are required.
     */
    String inputSchema();

    /**
     * Executes the tool.
     *
     * @param arguments parsed JSON arguments map (may be empty, never null)
     * @param cx        Niagara execution context (carries auth info)
     * @return a {@link McpToolResult} containing either the JSON result or an error
     */
    McpToolResult call(Map<String, Object> arguments, Context cx);
}
