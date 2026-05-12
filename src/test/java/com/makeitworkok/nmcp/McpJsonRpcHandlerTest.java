// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.baja.sys.Context;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link McpJsonRpcHandler}.
 */
class McpJsonRpcHandlerTest {

    private McpToolRegistry registry;
    private NiagaraSecurity security;
    private McpJsonRpcHandler handler;
    private Context cx;

    @BeforeEach
    void setUp() {
        List<String> roots = Arrays.asList(
                "station:|slot:/Drivers",
                "station:|slot:/Services",
                "station:|slot:/Config"
        );
        security = new NiagaraSecurity(true, true, 500, roots);
        registry = new McpToolRegistry();
        handler  = new McpJsonRpcHandler(registry, security, "0.1.0");
        cx       = new Context("testuser");
    }

    // -------------------------------------------------------------------------
    // initialize
    // -------------------------------------------------------------------------

    @Test
    void initialize_returnsProtocolVersionAndCapabilities() {
        String resp = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        assertEquals("2.0", parsed.get("jsonrpc"));
        Map<String, Object> result = assertIsMap(parsed.get("result"));
        assertTrue(result.containsKey("protocolVersion"));
        assertTrue(result.containsKey("capabilities"));
        assertTrue(result.containsKey("serverInfo"));
        Map<String, Object> serverInfo = assertIsMap(result.get("serverInfo"));
        assertEquals("nMCP", serverInfo.get("name"));
        assertEquals("0.1.0",      serverInfo.get("version"));
    }

    // -------------------------------------------------------------------------
    // tools/list
    // -------------------------------------------------------------------------

    @Test
    void toolsList_emptyRegistry() {
        String resp = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        Map<String, Object> result = assertIsMap(parsed.get("result"));
        List<?> tools = (List<?>) result.get("tools");
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void toolsList_returnsRegisteredTool() {
        registry.register(echoTool("my.tool", "Does something useful"));
        String resp = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{}}",
                cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        Map<String, Object> result = assertIsMap(parsed.get("result"));
        @SuppressWarnings("unchecked")
        List<Object> tools = (List<Object>) result.get("tools");
        assertEquals(1, tools.size());
        Map<String, Object> entry = assertIsMap(tools.get(0));
        assertEquals("my.tool", entry.get("name"));
        assertEquals("Does something useful", entry.get("description"));
    }

    // -------------------------------------------------------------------------
    // tools/call
    // -------------------------------------------------------------------------

    @Test
    void toolsCall_knownTool_returnsContent() {
        registry.register(echoTool("echo", "Echoes args"));
        String resp = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"msg\":\"hello\"}}}",
                cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        assertNull(parsed.get("error"), "Expected no error but got: " + parsed.get("error"));
        Map<String, Object> result = assertIsMap(parsed.get("result"));
        List<?> content = (List<?>) result.get("content");
        assertNotNull(content);
        assertFalse(content.isEmpty());
    }

    @Test
    void toolsCall_unknownTool_returnsIsError() {
        String resp = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"no.such.tool\",\"arguments\":{}}}",
                cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        Map<String, Object> result = assertIsMap(parsed.get("result"));
        assertEquals(Boolean.TRUE, result.get("isError"));
    }

    @Test
    void toolsCall_nmcpAlias_returnsContent() {
        registry.register(echoTool("nmcp.point.read", "Reads a point"));
        String resp = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"nmcp.point.read\",\"arguments\":{\"ord\":\"station:|slot:/Drivers\"}}}",
                cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        Map<String, Object> result = assertIsMap(parsed.get("result"));
        assertEquals(Boolean.FALSE, result.get("isError"));
    }

    @Test
    void toolsCall_missingNameParam_returnsError() {
        String resp = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                + "\"params\":{\"arguments\":{}}}",
                cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        // Should return a JSON-RPC error or isError result
        assertTrue(parsed.containsKey("error") || isErrorResult(parsed));
    }

    // -------------------------------------------------------------------------
    // resources/list
    // -------------------------------------------------------------------------

    @Test
    void resourcesList_returnsAllowlistedRoots() {
        String resp = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\",\"params\":{}}",
                cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        Map<String, Object> result = assertIsMap(parsed.get("result"));
        @SuppressWarnings("unchecked")
        List<Object> resources = (List<Object>) result.get("resources");
        assertNotNull(resources);
        assertEquals(3, resources.size());
    }

    @Test
    void resourcesRead_usesNmcpAliasWhenLegacyToolMissing() {
        registry.register(echoTool("nmcp.component.read", "Reads a component"));
        String resp = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":71,\"method\":\"resources/read\","
                        + "\"params\":{\"uri\":\"nmcp://station/slot/Drivers\"}}",
                cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        Map<String, Object> result = assertIsMap(parsed.get("result"));
        List<?> contents = (List<?>) result.get("contents");
        assertNotNull(contents);
        assertFalse(contents.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    void invalidJson_returnsParseError() {
        String resp = handler.handle("not valid json", cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        assertTrue(parsed.containsKey("error"));
        Map<String, Object> error = assertIsMap(parsed.get("error"));
        assertEquals(McpErrors.PARSE_ERROR, ((Number) error.get("code")).intValue());
    }

    @Test
    void missingMethod_returnsInvalidRequest() {
        String resp = handler.handle("{\"jsonrpc\":\"2.0\",\"id\":9}", cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        assertTrue(parsed.containsKey("error"));
        Map<String, Object> error = assertIsMap(parsed.get("error"));
        assertEquals(McpErrors.INVALID_REQUEST, ((Number) error.get("code")).intValue());
    }

    @Test
    void unknownMethod_returnsMethodNotFound() {
        String resp = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"no/such/method\",\"params\":{}}",
                cx);
        Map<String, Object> parsed = NiagaraJson.parseObject(resp);
        assertTrue(parsed.containsKey("error"));
        Map<String, Object> error = assertIsMap(parsed.get("error"));
        assertEquals(McpErrors.METHOD_NOT_FOUND, ((Number) error.get("code")).intValue());
    }

    // -------------------------------------------------------------------------
    // URI ↔ ORD conversion
    // -------------------------------------------------------------------------

    @Test
    void ordToUri_convertsCorrectly() {
        assertEquals("nmcp://station/slot/Drivers",
                McpJsonRpcHandler.ordToUri("station:|slot:/Drivers"));
        assertEquals("nmcp://station/slot/Drivers/BacnetNetwork",
                McpJsonRpcHandler.ordToUri("station:|slot:/Drivers/BacnetNetwork"));
    }

    @Test
    void uriToOrd_convertsCorrectly() {
        assertEquals("station:|slot:/Drivers",
                McpJsonRpcHandler.uriToOrd("nmcp://station/slot/Drivers"));
        assertEquals("station:|slot:/Drivers/BacnetNetwork",
                McpJsonRpcHandler.uriToOrd("nmcp://station/slot/Drivers/BacnetNetwork"));
    }

    @Test
    void ordUriRoundTrip() {
        String orig = "station:|slot:/Services/AlarmService";
        assertEquals(orig, McpJsonRpcHandler.uriToOrd(McpJsonRpcHandler.ordToUri(orig)));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> assertIsMap(Object value) {
        assertNotNull(value, "Expected a Map but got null");
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    private boolean isErrorResult(Map<String, Object> parsed) {
        Object result = parsed.get("result");
        if (result instanceof Map) {
            return Boolean.TRUE.equals(((Map<?, ?>) result).get("isError"));
        }
        return false;
    }

    private McpTool echoTool(String name, String description) {
        return new McpTool() {
            @Override public String name()        { return name; }
            @Override public String description() { return description; }
            @Override public String inputSchema()  { return "{}"; }
            @Override public McpToolResult call(Map<String, Object> args, Context ctx) {
                return McpToolResult.success(NiagaraJson.obj("args", args.toString()));
            }
        };
    }
}
