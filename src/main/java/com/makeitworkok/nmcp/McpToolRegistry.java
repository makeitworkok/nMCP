// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Registry that holds all available {@link McpTool} instances.
 *
 * <p>Tools are registered at service start-up and looked up by name when a
 * {@code tools/call} request arrives.  The registry preserves insertion order
 * for {@code tools/list} responses.
 */
public final class McpToolRegistry {

    private static final Logger LOG = Logger.getLogger(McpToolRegistry.class.getName());

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    /**
     * Registers a tool.  If a tool with the same name is already registered it
     * is silently replaced (allowing hot-swap during development).
     */
    public void register(McpTool tool) {
        if (tool == null) throw new IllegalArgumentException("tool must not be null");
        tools.put(tool.name(), tool);
        LOG.fine("Registered MCP tool: " + tool.name());
    }

    /** Returns an unmodifiable snapshot of all registered tools (in registration order). */
    public List<McpTool> listTools() {
        return Collections.unmodifiableList(new ArrayList<>(tools.values()));
    }

    /**
     * Looks up a tool by name.
     *
     * @return the tool, or {@code null} if no tool with that name is registered
     */
    public McpTool get(String name) {
        return tools.get(name);
    }

    /** Returns the number of registered tools. */
    public int size() {
        return tools.size();
    }

    /** Removes all tools (used on service stop). */
    public void clear() {
        tools.clear();
    }
}
