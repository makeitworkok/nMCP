// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Enforces all read-only and allowlist security rules for MCP tool calls.
 *
 * <p>Every tool call MUST pass through this class before any Niagara API is
 * invoked.  The module fails closed: if a check is uncertain, access is denied.
 *
 * <p>Sensitive slot names are masked to prevent credentials from leaking to
 * clients.
 */
public final class NiagaraSecurity {

    private static final Logger LOG = Logger.getLogger(NiagaraSecurity.class.getName());

    /** Slot name fragments that indicate credential-like content. */
    private static final Set<String> SENSITIVE_KEYWORDS = new HashSet<>(Arrays.asList(
            "password", "passwd", "secret", "token", "key", "credential", "auth",
            "apikey", "api_key", "private", "cert", "license", "passphrase"
    ));

    /** BQL keywords that indicate a mutation query. */
    private static final String[] MUTATION_KEYWORDS = {
            "INSERT", "UPDATE", "DELETE", "SET ", "EXEC ", "EXECUTE",
            "DROP", "ALTER", "CREATE", "TRUNCATE"
    };

    private final boolean readOnly;
    private final boolean allowBql;
    private final int maxResults;
    private final List<String> allowlistedRoots;

    public NiagaraSecurity(boolean readOnly,
                           boolean allowBql,
                           int maxResults,
                           List<String> allowlistedRoots) {
        this.readOnly = readOnly;
        this.allowBql = allowBql;
        this.maxResults = maxResults;
        this.allowlistedRoots = allowlistedRoots;
    }

    // -------------------------------------------------------------------------
    // Read-only guard
    // -------------------------------------------------------------------------

    /**
     * Throws a {@link McpSecurityException} if the service is in read-only mode.
     * (All current tools are read-only; this exists for future write tools.)
     */
    public void checkReadOnly() throws McpSecurityException {
        if (readOnly) {
            throw new McpSecurityException(McpErrors.READONLY_VIOLATION,
                    "Operation not permitted in read-only mode");
        }
    }

    // -------------------------------------------------------------------------
    // Allowlist enforcement
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@code ord} starts with at least one of the configured
     * allowlisted root ORDs.
     *
     * @throws McpSecurityException if the path is outside all allowlisted roots
     */
    public void checkAllowlist(String ord) throws McpSecurityException {
        if (ord == null || ord.trim().isEmpty()) {
            throw new McpSecurityException(McpErrors.INVALID_PARAMS, "ORD must not be empty");
        }
        String normalized = ord.trim();
        // Strip optional leading ORD scheme segments (e.g. "local:|foxwss:|") so that
        // "local:|foxwss:|station:|slot:/Drivers/..." matches the allowlisted root
        // "station:|slot:/Drivers".
        String stripped = normalized.contains("station:|")
                ? normalized.substring(normalized.indexOf("station:|"))
                : normalized;
        for (String root : allowlistedRoots) {
            if (normalized.startsWith(root) || stripped.startsWith(root)) {
                return;
            }
        }
        LOG.warning("MCP access denied – ORD not allowlisted: " + normalized);
        throw new McpSecurityException(McpErrors.PATH_NOT_ALLOWLISTED,
                "Path not in allowlisted roots: " + normalized);
    }

    // -------------------------------------------------------------------------
    // BQL guard
    // -------------------------------------------------------------------------

    /**
     * Verifies that a BQL query is a safe, read-only SELECT query.
     * Rejects anything that looks like a mutation (SET, EXEC, INSERT, DELETE, UPDATE).
     *
     * @throws McpSecurityException if the query appears unsafe or BQL is disabled
     */
    public void checkBqlQuery(String query) throws McpSecurityException {
        if (!allowBql) {
            throw new McpSecurityException(McpErrors.BQL_REJECTED, "BQL queries are disabled");
        }
        if (query == null || query.trim().isEmpty()) {
            throw new McpSecurityException(McpErrors.INVALID_PARAMS, "BQL query must not be empty");
        }
        String upper = query.trim().toUpperCase();
        if (!upper.startsWith("SELECT")) {
            throw new McpSecurityException(McpErrors.BQL_REJECTED,
                    "Only SELECT queries are permitted");
        }
        // Reject known mutation keywords appearing anywhere in the query
        for (String kw : MUTATION_KEYWORDS) {
            if (upper.contains(kw)) {
                LOG.warning("MCP BQL rejected – mutation keyword detected: " + kw);
                throw new McpSecurityException(McpErrors.BQL_REJECTED,
                        "Query contains forbidden keyword: " + kw.trim());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Result-count limit
    // -------------------------------------------------------------------------

    /** Returns the effective limit: the lesser of the requested limit and {@code maxResults}. */
    public int effectiveLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) return maxResults;
        return Math.min(requestedLimit, maxResults);
    }

    // -------------------------------------------------------------------------
    // Sensitive-slot masking
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the slot name contains a sensitive keyword and
     * its value should be masked before returning to the client.
     */
    public boolean isSensitiveSlot(String slotName) {
        if (slotName == null) return false;
        String lower = slotName.toLowerCase();
        for (String kw : SENSITIVE_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /** Returns the placeholder string used when masking a sensitive value. */
    public static String maskedValue() {
        return "***";
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public boolean isReadOnly() { return readOnly; }
    public boolean isAllowBql() { return allowBql; }
    public int getMaxResults() { return maxResults; }
    public List<String> getAllowlistedRoots() { return allowlistedRoots; }

    // -------------------------------------------------------------------------
    // Exception type
    // -------------------------------------------------------------------------

    /** Thrown when a security check fails. Carries the JSON-RPC error code. */
    public static final class McpSecurityException extends Exception {
        private final int code;

        public McpSecurityException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() { return code; }
    }
}
