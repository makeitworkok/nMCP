// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link McpToolRegistry}.
 */
class McpToolRegistryTest {

    private McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new McpToolRegistry();
    }

    private McpTool simpleTool(String name) {
        return new McpTool() {
            @Override public String name()        { return name; }
            @Override public String description() { return "A test tool: " + name; }
            @Override public String inputSchema()  { return "{}"; }
            @Override public McpToolResult call(java.util.Map<String, Object> args,
                                                 javax.baja.sys.Context cx) {
                return McpToolResult.success("{\"ok\":true}");
            }
        };
    }

    @Test
    void register_andGet() {
        McpTool tool = simpleTool("test.tool");
        registry.register(tool);
        assertSame(tool, registry.get("test.tool"));
    }

    @Test
    void get_unknownName_returnsNull() {
        assertNull(registry.get("does.not.exist"));
    }

    @Test
    void register_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    @Test
    void listTools_returnsAllInOrder() {
        registry.register(simpleTool("alpha"));
        registry.register(simpleTool("beta"));
        registry.register(simpleTool("gamma"));

        List<McpTool> list = registry.listTools();
        assertEquals(3, list.size());
        assertEquals("alpha", list.get(0).name());
        assertEquals("beta",  list.get(1).name());
        assertEquals("gamma", list.get(2).name());
    }

    @Test
    void register_duplicateName_replacesExisting() {
        McpTool first  = simpleTool("my.tool");
        McpTool second = simpleTool("my.tool");
        registry.register(first);
        registry.register(second);
        assertSame(second, registry.get("my.tool"));
        assertEquals(1, registry.size());
    }

    @Test
    void size_reflectsRegistrations() {
        assertEquals(0, registry.size());
        registry.register(simpleTool("t1"));
        assertEquals(1, registry.size());
        registry.register(simpleTool("t2"));
        assertEquals(2, registry.size());
    }

    @Test
    void clear_removesAll() {
        registry.register(simpleTool("t1"));
        registry.register(simpleTool("t2"));
        registry.clear();
        assertEquals(0, registry.size());
        assertNull(registry.get("t1"));
    }
}
