// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.sys.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON-RPC 2.0 dispatcher for the MCP endpoint.
 *
 * <p>Supported MCP methods:
 * <ul>
 *   <li>{@code initialize}   – MCP handshake</li>
 *   <li>{@code tools/list}   – enumerate available tools</li>
 *   <li>{@code tools/call}   – invoke a named tool</li>
 *   <li>{@code resources/list} – list allowlisted root ORDs as resources</li>
 *   <li>{@code resources/read} – read a resource by URI</li>
 * </ul>
 *
 * <p>All other methods return a {@code -32601 Method not found} error.
 */
public final class McpJsonRpcHandler {

    private static final Logger LOG = Logger.getLogger(McpJsonRpcHandler.class.getName());

    /** MCP protocol version advertised during {@code initialize}. */
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";

    private final McpToolRegistry registry;
    private final NiagaraSecurity security;
    private final String serviceVersion;

    public McpJsonRpcHandler(McpToolRegistry registry,
                             NiagaraSecurity security,
                             String serviceVersion) {
        this.registry = registry;
        this.security = security;
        this.serviceVersion = serviceVersion;
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Handles a raw JSON-RPC request string and returns a JSON-RPC response string.
     * This method never throws; errors are encoded as JSON-RPC error responses.
     *
     * @param requestBody raw JSON string from the HTTP request body
     * @param cx          Niagara context (may be null during testing)
     * @return JSON-RPC response string
     */
    public String handle(String requestBody, Context cx) {
        Object id = null;
        // Parse the request outside the main try so parse errors map to PARSE_ERROR
        Map<String, Object> request;
        try {
            request = NiagaraJson.parseObject(requestBody);
        } catch (Exception e) {
            return error(null, McpErrors.PARSE_ERROR, "Parse error: " + e.getMessage());
        }
        try {
            id = request.get("id");
            validateRequest(request);
            String method = (String) request.get("method");
            Map<String, Object> params = getParams(request);

            LOG.fine("MCP request: method=" + method + " id=" + id);

            switch (method) {
                case "initialize":
                    return success(id, handleInitialize(params));
                case "tools/list":
                    return success(id, handleToolsList());
                case "tools/call":
                    return success(id, handleToolsCall(params, cx));
                case "resources/list":
                    return success(id, handleResourcesList());
                case "resources/read":
                    return success(id, handleResourcesRead(params, cx));
                default:
                    return error(id, McpErrors.METHOD_NOT_FOUND,
                            "Method not found: " + method);
            }
        } catch (InvalidRequestException e) {
            return error(id, McpErrors.INVALID_REQUEST, e.getMessage());
        } catch (NiagaraSecurity.McpSecurityException e) {
            LOG.warning("MCP security check failed: " + e.getMessage());
            return error(id, e.getCode(), e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "MCP internal error handling request", e);
            return error(id, McpErrors.INTERNAL_ERROR, "Internal error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Method handlers
    // -------------------------------------------------------------------------

    private Map<String, Object> handleInitialize(Map<String, Object> params) {
        Map<String, Object> capabilities = NiagaraJson.obj(
                "tools", NiagaraJson.obj("listChanged", false),
                "resources", NiagaraJson.obj("listChanged", false)
        );
        Map<String, Object> serverInfo = NiagaraJson.obj(
                "name", "nMCP",
                "version", serviceVersion
        );
        return NiagaraJson.obj(
                "protocolVersion", MCP_PROTOCOL_VERSION,
                "capabilities", capabilities,
                "serverInfo", serverInfo
        );
    }

    private Map<String, Object> handleToolsList() {
        List<Object> toolList = new ArrayList<>();
        for (McpTool tool : registry.listTools()) {
            Map<String, Object> entry = NiagaraJson.obj(
                    "name", tool.name(),
                    "description", tool.description(),
                    "inputSchema", NiagaraJson.parseObject(tool.inputSchema())
            );
            toolList.add(entry);
        }
        return NiagaraJson.obj("tools", toolList);
    }

    private Map<String, Object> handleToolsCall(Map<String, Object> params, Context cx)
            throws Exception {
        String name = getString(params, "name", true);
        McpTool tool = registry.get(name);
        if (tool == null) {
            return errorResult(McpErrors.METHOD_NOT_FOUND, "Unknown tool: " + name);
        }

        Map<String, Object> arguments = getNestedObject(params, "arguments");
        LOG.info("MCP tools/call: tool=" + name + " user=" + contextUser(cx));

        // Tools handle security and exceptions internally; they return McpToolResult rather than
        // throwing. A RuntimeException would still bubble up to handle()'s outer catch.
        McpToolResult result = tool.call(arguments, cx);

        return NiagaraJson.obj("content", result.toMcpContent(), "isError", result.isError());
    }

    private Map<String, Object> handleResourcesList() {
        List<Object> resources = new ArrayList<>();
        for (String root : security.getAllowlistedRoots()) {
            String uri = ordToUri(root);
            Map<String, Object> resource = NiagaraJson.obj(
                    "uri", uri,
                    "name", uri,
                    "description", "Niagara component at " + root,
                    "mimeType", "application/json"
            );
            resources.add(resource);
        }
        return NiagaraJson.obj("resources", resources);
    }

    private Map<String, Object> handleResourcesRead(Map<String, Object> params, Context cx)
            throws Exception {
        String uri = getString(params, "uri", true);
        String ord = uriToOrd(uri);
        security.checkAllowlist(ord);

        McpTool readTool = resolveComponentReadTool();
        if (readTool != null) {
            Map<String, Object> args = NiagaraJson.obj("ord", ord);
            McpToolResult result = readTool.call(args, cx);
            List<Object> contents = new ArrayList<>();
            contents.add(NiagaraJson.obj("uri", uri, "mimeType", "application/json",
                    "text", result.isError() ? result.getErrorMessage() : result.getContent()));
            return NiagaraJson.obj("contents", contents);
        }
        return NiagaraJson.obj("contents", NiagaraJson.arr());
    }

    private McpTool resolveComponentReadTool() {
        return registry.get("nmcp.component.read");
    }

    // -------------------------------------------------------------------------
    // JSON-RPC response builders
    // -------------------------------------------------------------------------

    private String success(Object id, Map<String, Object> result) {
        Map<String, Object> response = NiagaraJson.obj(
                "jsonrpc", "2.0",
                "id", id,
                "result", result
        );
        return NiagaraJson.buildObject(response);
    }

    private String error(Object id, int code, String message) {
        Map<String, Object> errorObj = NiagaraJson.obj(
                "code", code,
                "message", message
        );
        Map<String, Object> response = NiagaraJson.obj(
                "jsonrpc", "2.0",
                "id", id,
                "error", errorObj
        );
        return NiagaraJson.buildObject(response);
    }

    /** Builds a result map that signals an error via the MCP content/isError convention. */
    private Map<String, Object> errorResult(int code, String message) {
        McpToolResult err = McpToolResult.error("[" + code + "] " + message);
        return NiagaraJson.obj("content", err.toMcpContent(), "isError", true);
    }

    // -------------------------------------------------------------------------
    // URI ↔ ORD conversion helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a Niagara ORD like {@code station:|slot:/Drivers/BacnetNetwork} to
     * the MCP resource URI {@code nmcp://station/slot/Drivers/BacnetNetwork}.
     */
    static String ordToUri(String ord) {
        if (ord == null) return "nmcp://station/";
        // strip "station:|slot:/" prefix → "Drivers/BacnetNetwork"
        String path = ord.replace("station:|slot:/", "").replace("station:|slot:", "");
        return "nmcp://station/slot/" + path;
    }

    /**
     * Inverse of {@link #ordToUri}.
     * Converts {@code nmcp://station/slot/Drivers/BacnetNetwork} back to
     * {@code station:|slot:/Drivers/BacnetNetwork}.
     */
    static String uriToOrd(String uri) {
        if (uri == null) return "";
        String prefix = "nmcp://station/slot/";
        if (uri.startsWith(prefix)) {
            return "station:|slot:/" + uri.substring(prefix.length());
        }
        return uri;
    }

    // -------------------------------------------------------------------------
    // Request validation helpers
    // -------------------------------------------------------------------------

    private void validateRequest(Map<String, Object> req) throws InvalidRequestException {
        if (!"2.0".equals(req.get("jsonrpc"))) {
            throw new InvalidRequestException("jsonrpc must be \"2.0\"");
        }
        if (!req.containsKey("method") || !(req.get("method") instanceof String)) {
            throw new InvalidRequestException("method is required and must be a string");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getParams(Map<String, Object> req) {
        Object params = req.get("params");
        if (params instanceof Map) return (Map<String, Object>) params;
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getNestedObject(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        return new LinkedHashMap<>();
    }

    private String getString(Map<String, Object> map, String key, boolean required)
            throws InvalidRequestException {
        Object v = map.get(key);
        if (v == null) {
            if (required) throw new InvalidRequestException("Missing required param: " + key);
            return null;
        }
        return v.toString();
    }

    private String contextUser(Context cx) {
        if (cx == null) return "unknown";
        try { return cx.getUsername(); } catch (Exception e) { return "unknown"; }
    }

    // -------------------------------------------------------------------------
    // Local exception types
    // -------------------------------------------------------------------------

    private static final class InvalidRequestException extends Exception {
        InvalidRequestException(String msg) { super(msg); }
    }
}
