// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.sys.Context;
import javax.baja.web.BWebServlet;
import javax.baja.web.WebOp;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Niagara web servlet that exposes the MCP JSON-RPC endpoint.
 *
 * <p>Accepts HTTP {@code POST} requests with a {@code Content-Type: application/json}
 * body containing a JSON-RPC 2.0 payload.  Delegates to {@link McpJsonRpcHandler}
 * and writes the response back as JSON.
 *
 * <p>Only {@code POST} is supported.  {@code GET} returns a 405 Method Not Allowed.
 * An empty {@code POST} body triggers a parse-error JSON-RPC response.
 *
 * <p>TODO: In a real Niagara station, register this servlet with the platform
 * WebService during {@link BMcpService#started()}.
 */
public final class McpHttpServlet extends BWebServlet {

    private static final Logger LOG = Logger.getLogger(McpHttpServlet.class.getName());

    /** Maximum accepted request body size (1 MB). */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";

    private final McpJsonRpcHandler handler;

    public McpHttpServlet(McpJsonRpcHandler handler) {
        if (handler == null) throw new IllegalArgumentException("handler must not be null");
        this.handler = handler;
    }

    @Override
    public void doPost(WebOp op) {
        try {
            HttpServletRequest req = op.getRequest();
            HttpServletResponse resp = op.getResponse();
            String body = readBody(req);
            String response = handler.handle(body, (Context) null);

            resp.setStatus(200);
            resp.setContentType(CONTENT_TYPE_JSON);
            writeBody(resp, response);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Unexpected error in McpHttpServlet", e);
            try {
                HttpServletResponse resp = op.getResponse();
                resp.setStatus(500);
                resp.setContentType(CONTENT_TYPE_JSON);
                writeBody(resp, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":"
                        + "{\"code\":-32603,\"message\":\"Internal server error\"}}");
            } catch (Exception ignored) {
                // Nothing more we can do if the response is already committed
            }
        }
    }

    @Override
    public void doGet(WebOp op) {
        try {
            HttpServletResponse resp = op.getResponse();
            resp.setStatus(405);
            resp.setContentType(CONTENT_TYPE_JSON);
            writeBody(resp, "{\"error\":\"Method Not Allowed - use POST\"}");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error handling GET in McpHttpServlet", e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String readBody(HttpServletRequest req) throws IOException {
        InputStream in = req.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                LOG.warning("MCP request body exceeds maximum size of " + MAX_BODY_BYTES + " bytes");
                return "";
            }
            baos.write(buf, 0, n);
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    private void writeBody(HttpServletResponse resp, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = resp.getOutputStream();
        out.write(bytes);
        out.flush();
    }
}
