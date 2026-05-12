// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.sys.Sys;
import javax.baja.sys.Type;
import javax.baja.sys.BBoolean;
import javax.baja.sys.BComponent;
import javax.baja.sys.BStation;
import javax.baja.sys.BString;
import javax.baja.sys.BValue;
import javax.baja.sys.Context;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.naming.BOrd;
import javax.baja.web.BWebServlet;
import javax.baja.web.WebOp;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Method;
import java.util.logging.Logger;

public class BMcpService extends BWebServlet {

    private static final Logger LOG = Logger.getLogger(BMcpService.class.getName());
    private static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final String MCP_TOKEN_HEADER = "X-MCP-Token";
    private static final String RUNTIME_PROFILE_EXPECTED = "R@wD0g!t";
    private static final String RUNTIME_PROFILE_SYSTEM_PROPERTY = "nmcp.runtime.profile";

    // Hidden property to keep UI clean; configure via Workbench Config or programmatically
    public static final Property readOnly = newProperty(Flags.HIDDEN, BBoolean.TRUE, null);
    public static final Property runtimeProfile = newProperty(Flags.HIDDEN, BString.make(""), null);

    public static final Type TYPE = Sys.loadType(BMcpService.class);
    public static final String MODULE_VERSION = "0.8.0";

    private boolean enabled = true;
    private String endpointPath = "/nmcp";
    private boolean allowBql = true;
    private String haystackRulesetPath = System.getProperty("java.io.tmpdir") + "/nmcp-haystack-rules.json";
    private int maxResults = 500;
    private String allowlistedRoots =
            "station:|slot:/Drivers,station:|slot:/Services,station:|slot:/Config";
    private boolean requireToken = true;
    private String mcpToken = "";
        private String runtimeProfileValue = "";
    private boolean eulaBlocked = false;
    private String eulaBlockedReason = "";
    private String detectedPlatformVersion = "unknown";
    private boolean eulaOverrideUsed = false;

    private McpToolRegistry registry;
    private McpJsonRpcHandler handler;

    @Override
    public Type getType() {
        return TYPE;
    }

    public Type[] getServiceTypes() {
        return new Type[] { TYPE };
    }

    @Override
    public void started() throws Exception {
        evaluateEulaCompliance();

        if (!enabled) {
            LOG.info("BMcpService: disabled - not starting MCP endpoint");
            return;
        }

        String servletName = toServletName(endpointPath);
        setServletName(servletName);
        super.started();

        if (eulaBlocked) {
            LOG.warning("BMcpService: EULA blocked mode active; endpoint /" + servletName + " is returning HTTP 503");
            return;
        }

        String requireTokenProperty = System.getProperty("nmcp.mcp.requireToken");
        if (requireTokenProperty != null) {
            requireToken = Boolean.parseBoolean(requireTokenProperty);
        }
        String tokenProperty = System.getProperty("nmcp.mcp.token");
        if (tokenProperty != null) {
            mcpToken = tokenProperty.trim();
        }

        if (requireToken && (mcpToken == null || mcpToken.trim().isEmpty())) {
            mcpToken = generateToken();
            LOG.info("BMcpService: generated MCP token for this run: " + mcpToken);
        }

        List<String> roots = parseRoots(allowlistedRoots);
        NiagaraSecurity security = new NiagaraSecurity(isReadOnly(), allowBql, maxResults, roots);

        registry = new McpToolRegistry();
        registerTools(security);
        handler = new McpJsonRpcHandler(registry, security, MODULE_VERSION);
        LOG.info("BMcpService: registered MCP endpoint at /" + servletName);
    }

    @Override
    public void stopped() throws Exception {
        try {
            super.stopped();
        } catch (Throwable e) {
            LOG.warning("BMcpService: unregister failed: " + e.getMessage());
        }

        if (registry != null) {
            registry.clear();
        }
        registry = null;
        handler = null;
    }

    @Override
    public void doPost(WebOp op) {
        try {
            if (eulaBlocked) {
                writeServiceBlockedRpc(op.getResponse());
                return;
            }

            HttpServletRequest req = op.getRequest();
            HttpServletResponse resp = op.getResponse();

            if (!isAuthorized(req)) {
                writeUnauthorized(resp);
                return;
            }

            String body = readBody(req);
            if (handler == null) {
                writeServiceUnavailable(resp);
                return;
            }
            String response = handler.handle(body, null);

            resp.setStatus(200);
            resp.setContentType(CONTENT_TYPE_JSON);
            writeBody(resp, response);
        } catch (Throwable e) {
            LOG.warning("BMcpService: HTTP POST failed: " + e.getMessage());
            try {
                HttpServletResponse resp = op.getResponse();
                resp.setStatus(500);
                resp.setContentType(CONTENT_TYPE_JSON);
                writeBody(resp,
                        "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{"
                                + "\"code\":-32603,\"message\":\"Internal server error\"}}");
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void doGet(WebOp op) {
        try {
            HttpServletResponse resp = op.getResponse();

            if (eulaBlocked) {
                writeServiceBlockedGet(resp);
                return;
            }

            String tokenValue = mcpToken == null ? "" : mcpToken;
            resp.setStatus(200);
            resp.setContentType(CONTENT_TYPE_JSON);
            writeBody(
                    resp,
                    "{"
                            + "\"status\":\"ok\","
                            + "\"message\":\"MCP endpoint is running. Use POST for JSON-RPC.\","
                            + "\"tokenHeader\":\"X-MCP-Token\","
                            + "\"token\":\"" + jsonEscape(tokenValue) + "\""
                            + "}");
        } catch (Throwable e) {
            LOG.warning("BMcpService: HTTP GET failed: " + e.getMessage());
        }
    }

    private void registerTools(NiagaraSecurity security) {
        new NiagaraComponentTools(security, MODULE_VERSION).tools().forEach(registry::register);
        new NiagaraBqlTools(security).tools().forEach(registry::register);
        new NiagaraAlarmTools(security).tools().forEach(registry::register);
        new NiagaraHistoryTools(security).tools().forEach(registry::register);
        new NiagaraBacnetTools(security).tools().forEach(registry::register);
        new NiagaraScheduleTools(security).tools().forEach(registry::register);
        new NiagaraPointTools(security).tools().forEach(registry::register);
        new NiagaraEquipmentTools(security).tools().forEach(registry::register);
        new NiagaraFaultScanTool(security).tools().forEach(registry::register);
        new NiagaraBuildingBriefTool(security).tools().forEach(registry::register);
        new NiagaraHaystackTools(security, haystackRulesetPath).tools().forEach(registry::register);
        new NiagaraWiresheetTools(security).tools().forEach(registry::register);
        new NiagaraWriteTools(security).tools().forEach(registry::register);
    }

    private List<String> parseRoots(String csv) {
        List<String> roots = new ArrayList<>();
        if (csv == null) {
            return roots;
        }
        for (String value : csv.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                roots.add(trimmed);
            }
        }
        return roots;
    }

    private String toServletName(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "nmcp";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private boolean isAuthorized(HttpServletRequest req) {
        if (!requireToken) {
            return true;
        }

        if (mcpToken == null || mcpToken.trim().isEmpty()) {
            LOG.warning("BMcpService: token auth enabled but mcpToken is not configured");
            return false;
        }

        String provided = req.getHeader(MCP_TOKEN_HEADER);
        if (provided == null) {
            return false;
        }

        return constantTimeEquals(provided, mcpToken);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        int max = Math.max(a.length(), b.length());
        int diff = a.length() ^ b.length();
        for (int i = 0; i < max; i++) {
            char ca = i < a.length() ? a.charAt(i) : 0;
            char cb = i < b.length() ? b.charAt(i) : 0;
            diff |= ca ^ cb;
        }
        return diff == 0;
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void writeUnauthorized(HttpServletResponse resp) throws IOException {
        resp.setStatus(401);
        resp.setContentType(CONTENT_TYPE_JSON);
        writeBody(resp, "{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid X-MCP-Token\"}");
    }

    private void writeServiceUnavailable(HttpServletResponse resp) throws IOException {
        resp.setStatus(503);
        resp.setContentType(CONTENT_TYPE_JSON);
        writeBody(resp,
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{"
                        + "\"code\":-32003,\"message\":\"MCP service unavailable\"}}"
        );
    }

    private void writeServiceBlockedRpc(HttpServletResponse resp) throws IOException {
        resp.setStatus(503);
        resp.setContentType(CONTENT_TYPE_JSON);
        writeBody(resp,
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{"
                        + "\"code\":-32003,\"message\":\"MCP disabled due to EULA policy\"}}"
        );
    }

    private void writeServiceBlockedGet(HttpServletResponse resp) throws IOException {
        resp.setStatus(503);
        resp.setContentType(CONTENT_TYPE_JSON);
        writeBody(
                resp,
                "{"
                        + "\"status\":\"blocked\","
                        + "\"message\":\"MCP disabled due to EULA policy\","
                        + "\"detectedVersion\":\"" + jsonEscape(detectedPlatformVersion) + "\","
                        + "\"reason\":\"" + jsonEscape(eulaBlockedReason) + "\","
                        + "\"overrideUsed\":" + (eulaOverrideUsed ? "true" : "false")
                        + "}"
        );
    }

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
                LOG.warning("BMcpService: request body exceeds maximum size");
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

    private void evaluateEulaCompliance() {
        eulaBlocked = false;
        eulaBlockedReason = "";
        detectedPlatformVersion = "unknown";
        eulaOverrideUsed = false;

        String versionStr = detectNiagaraVersionString();
        if (versionStr == null || versionStr.trim().isEmpty()) {
            LOG.warning("BMcpService: Niagara platform version could not be detected; EULA check could not be confirmed.");
            return;
        }

        detectedPlatformVersion = versionStr;

        int[] majorMinor = parseMajorMinor(versionStr);
        if (majorMinor == null) {
            LOG.warning("BMcpService: Niagara platform version '" + versionStr + "' is not parseable; EULA check could not be confirmed.");
            return;
        }

        int major = majorMinor[0];
        int minor = majorMinor[1];
        LOG.info("BMcpService: detected Niagara platform version " + versionStr);

        if (major > 4 || (major == 4 && minor >= 13)) {
            LOG.warning("EULA of the version 4.13 and greater forbids use of AI, see Section 3.1(q) for details.");

            if (hasValidRuntimeProfile()) {
                eulaOverrideUsed = true;
                LOG.warning("BMcpService: compliance override accepted; MCP remains enabled.");
                return;
            }

            eulaBlocked = true;
            eulaBlockedReason = "Restricted Niagara platform version without valid runtime profile";
            LOG.warning("BMcpService: " + eulaBlockedReason + ". Service will return HTTP 503.");
        }
    }

    private boolean hasValidRuntimeProfile() {
        String provided = getRuntimeProfile();
        if (!isNonEmpty(provided)) {
            try {
                String fromProperty = System.getProperty(RUNTIME_PROFILE_SYSTEM_PROPERTY);
                if (isNonEmpty(fromProperty)) {
                    provided = fromProperty;
                }
            } catch (SecurityException ignored) {
                // Access to system properties may be restricted by Niagara security manager.
            }
        }
        if (!isNonEmpty(provided)) {
            return false;
        }
        return constantTimeEquals(provided.trim(), RUNTIME_PROFILE_EXPECTED);
    }

    private String detectNiagaraVersionString() {
        String fromPlatformService = detectFromSystemPlatformService(null);
        if (isNonEmpty(fromPlatformService)) {
            return fromPlatformService;
        }

        String fromSysVersion = tryInvokeToString(Sys.class, "getVersion");
        if (isNonEmpty(fromSysVersion)) {
            return fromSysVersion;
        }

        String fromSysVersionString = tryInvokeToString(Sys.class, "getVersionString");
        if (isNonEmpty(fromSysVersionString)) {
            return fromSysVersionString;
        }

        try {
            BStation station = Sys.getStation();
            if (station != null) {
                String fromStation = tryInvokeToString(station, "getNiagaraVersion");
                if (isNonEmpty(fromStation)) {
                    return fromStation;
                }

                String fromStationVersion = tryInvokeToString(station, "getVersion");
                if (isNonEmpty(fromStationVersion)) {
                    return fromStationVersion;
                }
            }
        } catch (Throwable ignored) {
            // Station-level fallbacks are best-effort only.
        }

        // Final fallback: scan /Services tree for platform service
        String fromServicesScan = detectFromServicesTree(null);
        if (isNonEmpty(fromServicesScan)) {
            return fromServicesScan;
        }

        return null;
    }

    private String detectFromSystemPlatformService(Context cx) {
        try {
            Object platformService = resolveFirstServiceCandidate(cx,
                    "SystemPlatformService",
                    "station:|slot:/Services/SystemPlatformService",
                    "PlatformService",
                    "station:|slot:/Services/PlatformService",
                    "BSystemPlatformService",
                    "station:|slot:/Services/PlatformServices/BSystemPlatformService",
                    "SystemPlatformServiceInContainer",
                    "station:|slot:/Services/PlatformServices/SystemPlatformService",
                    "PlatformServiceInContainer",
                    "station:|slot:/Services/PlatformServices/PlatformService");
            if (platformService == null) {
                return null;
            }

            String fromProperty = tryInvokeToString(platformService, "get", "niagaraVersion");
            if (isValidNiagaraVersion(fromProperty)) {
                return fromProperty;
            }

            String fromPropertyWithCx = tryInvokeToString(platformService, "get", "niagaraVersion", cx);
            if (isValidNiagaraVersion(fromPropertyWithCx)) {
                return fromPropertyWithCx;
            }

            String fromGetter = tryInvokeToString(platformService, "getNiagaraVersion");
            if (isValidNiagaraVersion(fromGetter)) {
                return fromGetter;
            }

            String fromVersion = tryInvokeToString(platformService, "getVersion");
            if (isValidNiagaraVersion(fromVersion)) {
                return fromVersion;
            }

            String fromVersionString = tryInvokeToString(platformService, "getVersionString");
            if (isValidNiagaraVersion(fromVersionString)) {
                return fromVersionString;
            }
        } catch (Throwable ignored) {
            // Non-fatal: fallback chain continues.
        }
        return null;
    }



    private Object resolveFirstServiceCandidate(Context cx, String... labelsAndOrds) {
        if (labelsAndOrds == null) {
            return null;
        }
        for (int i = 0; i + 1 < labelsAndOrds.length; i += 2) {
            String ord = labelsAndOrds[i + 1];
            try {
                Object resolved = BOrd.make(ord).get(null, cx);
                if (resolved != null) {
                    return resolved;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String detectFromServicesTree(Context cx) {
        try {
            Object resolved = BOrd.make("station:|slot:/Services").get(null, cx);
            if (!(resolved instanceof BComponent)) {
                return null;
            }

            BComponent services = (BComponent) resolved;
            BComponent[] children = services.getChildComponents();
            if (children == null || children.length == 0) {
                return null;
            }

            for (BComponent child : children) {
                String candidate = scanComponentTreeForVersion(child, cx, 3);
                if (isNonEmpty(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // Tree scan is optional fallback; non-fatal if it fails.
        }
        return null;
    }

    private String scanComponentTreeForVersion(BComponent component, Context cx, int remainingDepth) {
        if (component == null || remainingDepth <= 0) {
            return null;
        }

        // Probe this component for version if it looks platform-related
        boolean isPlatform = isPlatformish(component);
        if (isPlatform) {
            String candidate = probeVersionFromComponent(component, cx);
            if (isNonEmpty(candidate)) {
                return candidate;
            }
        }

        // Recurse to children
        try {
            BComponent[] children = component.getChildComponents();
            if (children != null && children.length > 0) {
                for (BComponent child : children) {
                    String descendant = scanComponentTreeForVersion(child, cx, remainingDepth - 1);
                    if (isNonEmpty(descendant)) {
                        return descendant;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Non-fatal: continue with other branches.
        }

        return null;
    }

    private String probeVersionFromComponent(Object component, Context cx) {
        if (component == null) {
            return null;
        }

        String fromProperty = tryInvokeToString(component, "get", "niagaraVersion");
        if (isValidNiagaraVersion(fromProperty)) {
            return fromProperty;
        }

        String fromGetter = tryInvokeToString(component, "getNiagaraVersion");
        if (isValidNiagaraVersion(fromGetter)) {
            return fromGetter;
        }

        String fromVersion = tryInvokeToString(component, "getVersion");
        if (isValidNiagaraVersion(fromVersion)) {
            return fromVersion;
        }

        String fromVersionString = tryInvokeToString(component, "getVersionString");
        if (isValidNiagaraVersion(fromVersionString)) {
            return fromVersionString;
        }

        return null;
    }

    private static boolean isPlatformish(BComponent component) {
        if (component == null) {
            return false;
        }
        try {
            String name = component.getName();
            String className = component.getClass().getName();
            String typeName = component.getType() != null ? component.getType().getTypeName() : "";
            String display = component.getDisplayName(null);

            String lower = (name + "|" + className + "|" + typeName + "|" + display).toLowerCase();
            return lower.contains("platform") || lower.contains("systemplatformservice") || lower.contains("bplatformservice");
        } catch (Throwable ignored) {
            return false;
        }
    }



    private static boolean isNonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean isValidNiagaraVersion(String value) {
        if (!isNonEmpty(value)) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("mcp:")) {
            return false;
        }
        return trimmed.matches("\\d+(?:\\.\\d+){1,3}(?:[-_A-Za-z0-9].*)?");
    }

    private String tryInvokeToString(Object target, String methodName) {
        return tryInvokeToString(target, methodName, new Object[0]);
    }

    private String tryInvokeToString(Object target, String methodName, Object... args) {
        Object value = tryInvoke(target, methodName, args);
        return value != null ? String.valueOf(value) : null;
    }

    private Object tryInvoke(Object target, String methodName, Object... args) {
        try {
            Class<?> targetClass = (target instanceof Class<?>) ? (Class<?>) target : target.getClass();
            for (Method m : targetClass.getMethods()) {
                if (!methodName.equals(m.getName())) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != args.length) {
                    continue;
                }

                boolean compatible = true;
                for (int i = 0; i < params.length; i++) {
                    Object arg = args[i];
                    if (arg == null) {
                        continue;
                    }
                    if (!params[i].isAssignableFrom(arg.getClass())) {
                        compatible = false;
                        break;
                    }
                }
                if (!compatible) {
                    continue;
                }

                try {
                    return m.invoke((target instanceof Class<?>) ? null : target, args);
                } catch (Throwable ignored) {
                    // Keep searching compatible overloads.
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private int[] parseMajorMinor(String rawVersion) {
        if (rawVersion == null) {
            return null;
        }

        String cleaned = rawVersion.trim();
        String[] tokens = cleaned.split("[^0-9]+");
        int[] nums = new int[2];
        int found = 0;

        for (int i = 0; i < tokens.length && found < 2; i++) {
            if (tokens[i] == null || tokens[i].isEmpty()) {
                continue;
            }
            try {
                nums[found] = Integer.parseInt(tokens[i]);
                found++;
            } catch (NumberFormatException ignored) {
                // Skip malformed chunks.
            }
        }

        if (found < 2) {
            return null;
        }
        return nums;
    }

    public boolean isEnabled()            { return enabled; }
    public void    setEnabled(boolean v)  { enabled = v; }

    public String  getEndpointPath()      { return endpointPath; }
    public void    setEndpointPath(String v) { endpointPath = v; }

    public boolean getReadOnly()          { return getBoolean(readOnly); }
    public void    setReadOnly(boolean v) { setBoolean(readOnly, v, null); }
    public boolean isReadOnly()           { return getReadOnly(); }

    public String  getRuntimeProfile() {
        String fromSlot = readStringSlot("runtimeProfile");
        if (isNonEmpty(fromSlot)) {
            return fromSlot;
        }
        return runtimeProfileValue;
    }

    public void    setRuntimeProfile(String value) {
        runtimeProfileValue = value == null ? "" : value;
        try {
            set("runtimeProfile", BString.make(runtimeProfileValue));
        } catch (Throwable ignored) {
        }
    }

    private String readStringSlot(String slotName) {
        if (!isNonEmpty(slotName)) {
            return null;
        }
        try {
            BValue value = get(slotName);
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value);
            return isNonEmpty(text) ? text : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public boolean isAllowBql()           { return allowBql; }
    public void    setAllowBql(boolean v) { allowBql = v; }

    public int     getMaxResults()        { return maxResults; }

    public void    setMaxResults(int v)   { maxResults = Math.max(1, v); }

    public String  getAllowlistedRoots()  { return allowlistedRoots; }
    public void    setAllowlistedRoots(String v) { allowlistedRoots = v; }

    public boolean isRequireToken() { return requireToken; }
    public void setRequireToken(boolean v) { requireToken = v; }

    public String getMcpToken() { return mcpToken; }
    public void setMcpToken(String v) { mcpToken = v; }

    public String getHaystackRulesetPath() { return haystackRulesetPath; }
    public void   setHaystackRulesetPath(String v) { haystackRulesetPath = v; }

    public McpJsonRpcHandler getHandler() { return handler; }
    public McpToolRegistry getRegistry()  { return registry; }
}

