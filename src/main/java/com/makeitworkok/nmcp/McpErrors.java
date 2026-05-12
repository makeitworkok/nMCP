// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

/**
 * JSON-RPC 2.0 and MCP-specific error codes and utility methods.
 *
 * <p>Standard JSON-RPC codes (negative integers):
 * <ul>
 *   <li>-32700 Parse error – Invalid JSON was received</li>
 *   <li>-32600 Invalid Request – The request object is not valid JSON-RPC</li>
 *   <li>-32601 Method not found</li>
 *   <li>-32602 Invalid params</li>
 *   <li>-32603 Internal error</li>
 *   <li>-32000 to -32099 Server errors (application defined)</li>
 * </ul>
 */
public final class McpErrors {

    private McpErrors() {}

    // Standard JSON-RPC error codes
    public static final int PARSE_ERROR       = -32700;
    public static final int INVALID_REQUEST   = -32600;
    public static final int METHOD_NOT_FOUND  = -32601;
    public static final int INVALID_PARAMS    = -32602;
    public static final int INTERNAL_ERROR    = -32603;

    // MCP / Niagara application error codes
    public static final int ACCESS_DENIED         = -32000;
    public static final int PATH_NOT_ALLOWLISTED  = -32001;
    public static final int READONLY_VIOLATION    = -32002;
    public static final int ORD_NOT_FOUND         = -32003;
    public static final int BQL_REJECTED          = -32004;
    public static final int RESULTS_EXCEEDED      = -32005;
    public static final int SERVICE_DISABLED      = -32006;

    /** Human-readable message for a given error code. */
    public static String messageFor(int code) {
        switch (code) {
            case PARSE_ERROR:       return "Parse error";
            case INVALID_REQUEST:   return "Invalid request";
            case METHOD_NOT_FOUND:  return "Method not found";
            case INVALID_PARAMS:    return "Invalid params";
            case INTERNAL_ERROR:    return "Internal error";
            case ACCESS_DENIED:     return "Access denied";
            case PATH_NOT_ALLOWLISTED: return "Path not in allowlisted roots";
            case READONLY_VIOLATION: return "Operation not permitted in read-only mode";
            case ORD_NOT_FOUND:     return "ORD could not be resolved";
            case BQL_REJECTED:      return "BQL query rejected: only SELECT queries are permitted";
            case RESULTS_EXCEEDED:  return "Result count exceeds configured maximum";
            case SERVICE_DISABLED:  return "MCP service is disabled";
            default:                return "Unknown error";
        }
    }
}
