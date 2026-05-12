// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.naming.BOrd;
import javax.baja.sys.BComponent;
import javax.baja.sys.BMarker;
import javax.baja.sys.BString;
import javax.baja.sys.BValue;
import javax.baja.sys.Context;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.sys.SlotCursor;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MCP tools for managing and applying Project Haystack tag rules to Niagara components.
 *
 * <p>Provides three tools:
 * <ul>
 *   <li>{@code nmcp.haystack.getRuleset}  – read the JSON tagging ruleset from disk</li>
 *   <li>{@code nmcp.haystack.setRuleset}  – write/overwrite the JSON tagging ruleset (write mode only)</li>
 *   <li>{@code nmcp.haystack.applyRules}  – walk a component subtree and apply matching haystack tags (write mode only; supports dryRun)</li>
 * </ul>
 *
 * <h2>Ruleset file format</h2>
 * <pre>
 * {
 *   "rules": [
 *     {
 *       "id": "ahu-equipment",
 *       "description": "Tag AHU air handling units",
 *       "conditions": [
 *         { "field": "displayName", "op": "contains", "value": "AHU" }
 *       ],
 *       "tags": { "equip": "m:", "ahu": "m:", "hvac": "m:" }
 *     },
 *     {
 *       "id": "temp-sensor",
 *       "description": "Tag temperature sensor points",
 *       "conditions": [
 *         { "field": "displayName", "op": "contains", "value": "Temp" },
 *         { "field": "typeName",    "op": "contains", "value": "NumericPoint" }
 *       ],
 *       "tags": { "point": "m:", "sensor": "m:", "temp": "m:" }
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h2>Condition fields</h2>
 * <ul>
 *   <li>{@code displayName} – component display name</li>
 *   <li>{@code typeName}    – type spec (e.g. {@code NumericPoint})</li>
 *   <li>{@code name}        – slot name within the parent component</li>
 * </ul>
 *
 * <h2>Condition operators</h2>
 * {@code contains}, {@code equals}, {@code startsWith}, {@code endsWith} (all case-insensitive)
 *
 * <h2>Tag values</h2>
 * Use {@code "m:"} for a Project Haystack marker tag, or any other string for a string tag.
 * At runtime on a real Niagara station the actual tag-writing call must be replaced with
 * the {@code BTagDictionary} API from the nhaystack or native Niagara haystack module.
 */
public final class NiagaraHaystackTools {

    private static final Logger LOG = Logger.getLogger(NiagaraHaystackTools.class.getName());

    /** Prefix used when writing haystack tags as dynamic component slots.
     *  Niagara encodes ':' as '$3a', so "h4:temp" is stored internally as "h4$3atemp".
     *  Using the encoded prefix ensures slot names are valid Niagara identifiers and
     *  match what Workbench displays as "h4:&lt;tagName&gt;" with type baja:Marker.
     */
    private static final String HS_SLOT_PREFIX = "h4$3a";

    private final NiagaraSecurity security;
    private final String rulesetPath;

    public NiagaraHaystackTools(NiagaraSecurity security, String rulesetPath) {
        this.security = security;
        this.rulesetPath = rulesetPath;
    }

    /** Returns all tools provided by this class. */
    public List<McpTool> tools() {
        List<McpTool> list = new ArrayList<>();
        list.add(getRuleset());
        list.add(setRuleset());
        list.add(applyRules());
        list.add(scanPoints());
        list.add(suggestTags());
        return list;
    }

    // -------------------------------------------------------------------------
    // nmcp.haystack.getRuleset
    // -------------------------------------------------------------------------

    private McpTool getRuleset() {
        return new McpTool() {
            @Override public String name() { return "nmcp.haystack.getRuleset"; }

            @Override public String description() {
                return "Reads the current haystack tagging ruleset JSON file from the station "
                        + "filesystem. Returns the raw JSON content and the configured file path. "
                        + "Returns an empty ruleset template if the file does not yet exist.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                try {
                    File file = resolveFile(rulesetPath);
                    if (!file.exists()) {
                        String template = buildEmptyRulesetTemplate();
                        return McpToolResult.success(NiagaraJson.obj(
                                "path",    rulesetPath,
                                "exists",  false,
                                "content", template
                        ));
                    }
                    String content = readFile(file);
                    return McpToolResult.success(NiagaraJson.obj(
                            "path",    rulesetPath,
                            "exists",  true,
                            "content", content
                    ));
                } catch (Exception e) {
                    LOG.warning("nmcp.haystack.getRuleset error: " + e.getMessage());
                    return McpToolResult.error("Failed to read ruleset: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.haystack.setRuleset
    // -------------------------------------------------------------------------

    private McpTool setRuleset() {
        return new McpTool() {
            @Override public String name() { return "nmcp.haystack.setRuleset"; }

            @Override public String description() {
                return "Writes or replaces the haystack tagging ruleset JSON file on the station "
                        + "filesystem. Requires write mode to be enabled on the MCP service. "
                        + "The content must be a valid JSON object with a 'rules' array. "
                        + "Each rule specifies 'conditions' (field/op/value objects) and "
                        + "'tags' (a map of Haystack tag names to values, use \"m:\" for markers).";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"content\":{\"type\":\"string\","
                        + "    \"description\":\"Full JSON content of the ruleset file\"}"
                        + "},"
                        + "\"required\":[\"content\"]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                try {
                    security.checkReadOnly();
                    String content = getStringArg(arguments, "content");
                    if (content == null || content.trim().isEmpty()) {
                        return McpToolResult.error("Missing required argument: content");
                    }
                    // Validate it is parseable JSON before writing
                    try {
                        NiagaraJson.parseObject(content);
                    } catch (Exception e) {
                        return McpToolResult.error("Invalid JSON content: " + e.getMessage());
                    }
                    File file = resolveFile(rulesetPath);
                    if (file.getParentFile() != null) {
                        file.getParentFile().mkdirs();
                    }
                    writeFile(file, content);
                    LOG.info("nmcp.haystack.setRuleset: wrote ruleset to " + file.getAbsolutePath());
                    return McpToolResult.success(NiagaraJson.obj(
                            "path",    rulesetPath,
                            "written", true,
                            "bytes",   content.getBytes(StandardCharsets.UTF_8).length
                    ));
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                } catch (Exception e) {
                    LOG.warning("nmcp.haystack.setRuleset error: " + e.getMessage());
                    return McpToolResult.error("Failed to write ruleset: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.haystack.applyRules
    // -------------------------------------------------------------------------

    private McpTool applyRules() {
        return new McpTool() {
            @Override public String name() { return "nmcp.haystack.applyRules"; }

            @Override public String description() {
                return "Walks the component tree under the given ORD and applies Project Haystack "
                        + "tags from the configured ruleset to every matching component. "
                        + "Requires write mode to be enabled on the MCP service. "
                        + "Use dryRun=true to preview matches without modifying any components. "
                        + "Returns a summary of matched components and the tags that were (or would be) applied.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"ord\":{\"type\":\"string\","
                        + "    \"description\":\"Root ORD to scan (default station:|slot:/Config)\"},"
                        + "  \"dryRun\":{\"type\":\"boolean\","
                        + "    \"description\":\"If true, report matches without writing tags (default false)\"},"
                        + "  \"limit\":{\"type\":\"integer\","
                        + "    \"description\":\"Max components to inspect\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                boolean dryRun = getBooleanArg(arguments, "dryRun", false);

                // Write gate: only bypass if dryRun is explicitly requested
                if (!dryRun) {
                    try {
                        security.checkReadOnly();
                    } catch (NiagaraSecurity.McpSecurityException e) {
                        return McpToolResult.error(e.getMessage());
                    }
                }

                String ord = getStringArg(arguments, "ord");
                if (ord == null || ord.isEmpty()) ord = "station:|slot:/Config";
                Integer limit = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit);

                try {
                    security.checkAllowlist(ord);
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                }

                // Load ruleset from file
                List<Map<String, Object>> rules;
                try {
                    rules = loadRules();
                } catch (Exception e) {
                    return McpToolResult.error("Failed to load ruleset: " + e.getMessage());
                }

                if (rules.isEmpty()) {
                    return McpToolResult.success(NiagaraJson.obj(
                            "ord", ord,
                            "dryRun", dryRun,
                            "rulesLoaded", 0,
                            "componentsMatched", 0,
                            "matches", NiagaraJson.arr()
                    ));
                }

                // Walk the component tree
                List<Object> matches = new ArrayList<>();
                try {
                    Object obj = BOrd.make(ord).get(null, cx);
                    if (!(obj instanceof BComponent)) {
                        return McpToolResult.error("ORD is not a component: " + ord);
                    }
                    walkAndTag((BComponent) obj, ord, rules, matches, effectiveLimit, dryRun, cx);
                } catch (Throwable e) {
                    LOG.warning("nmcp.haystack.applyRules error: " + e);
                    return McpToolResult.error("Error walking component tree: " + e.getMessage());
                }

                return McpToolResult.success(NiagaraJson.obj(
                        "ord",                ord,
                        "dryRun",             dryRun,
                        "rulesLoaded",        rules.size(),
                        "componentsMatched",  matches.size(),
                        "matches",            matches
                ));
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.haystack.scanPoints
    // -------------------------------------------------------------------------

    private McpTool scanPoints() {
        return new McpTool() {
            @Override public String name() { return "nmcp.haystack.scanPoints"; }

            @Override public String description() {
                return "Walks the component tree under the given ORD and finds all points, "
                        + "reporting which Project Haystack tags each already carries. "
                        + "Tags are read from dynamic slots prefixed with 'hs_' (written by "
                        + "nmcp.haystack.applyRules). Returns totalPoints, taggedPoints, "
                        + "untaggedPoints, and a per-point breakdown of existing tags.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"root\":{\"type\":\"string\","
                        + "    \"description\":\"Root ORD to scan (default station:|slot:/Drivers)\"},"
                        + "  \"limit\":{\"type\":\"integer\","
                        + "    \"description\":\"Max points to inspect\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String root = getStringArg(arguments, "root");
                if (root == null || root.isEmpty()) root = "station:|slot:/Drivers";
                Integer limit = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit);

                try {
                    security.checkAllowlist(root);
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                }

                try {
                    Object obj = BOrd.make(root).get(null, cx);
                    if (!(obj instanceof BComponent)) {
                        return McpToolResult.error("Root ORD is not a component: " + root);
                    }
                    List<Object> points = new ArrayList<>();
                    walkAndScan((BComponent) obj, root, points, effectiveLimit, cx);

                    int tagged = 0;
                    for (Object p : points) {
                        if (p instanceof Map) {
                            Object tagCount = ((Map<?, ?>) p).get("tagCount");
                            if (tagCount instanceof Number && ((Number) tagCount).intValue() > 0) {
                                tagged++;
                            }
                        }
                    }

                    return McpToolResult.success(NiagaraJson.obj(
                            "root",           root,
                            "totalPoints",    points.size(),
                            "taggedPoints",   tagged,
                            "untaggedPoints", points.size() - tagged,
                            "points",         points
                    ));
                } catch (Throwable e) {
                    LOG.warning("nmcp.haystack.scanPoints error: " + e);
                    return McpToolResult.error("Error scanning points: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // nmcp.haystack.suggestTags
    // -------------------------------------------------------------------------

    private McpTool suggestTags() {
        return new McpTool() {
            @Override public String name() { return "nmcp.haystack.suggestTags"; }

            @Override public String description() {
                return "Discovers all points under the given ORD and suggests Project Haystack tags "
                        + "for each one based on component type, display name patterns, and parent context. "
                        + "Returns per-point tag suggestions with reasoning, plus a ready-to-use "
                        + "ruleset JSON that can be passed directly to nmcp.haystack.setRuleset "
                        + "and then applied with nmcp.haystack.applyRules.";
            }

            @Override public String inputSchema() {
                return "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "  \"root\":{\"type\":\"string\","
                        + "    \"description\":\"Root ORD to scan (default station:|slot:/Drivers)\"},"
                        + "  \"limit\":{\"type\":\"integer\","
                        + "    \"description\":\"Max points to inspect\"}"
                        + "},"
                        + "\"required\":[]}";
            }

            @Override public McpToolResult call(Map<String, Object> arguments, Context cx) {
                String root = getStringArg(arguments, "root");
                if (root == null || root.isEmpty()) root = "station:|slot:/Drivers";
                Integer limit = getIntArg(arguments, "limit");
                int effectiveLimit = security.effectiveLimit(limit);

                try {
                    security.checkAllowlist(root);
                } catch (NiagaraSecurity.McpSecurityException e) {
                    return McpToolResult.error(e.getMessage());
                }

                try {
                    Object obj = BOrd.make(root).get(null, cx);
                    if (!(obj instanceof BComponent)) {
                        return McpToolResult.error("Root ORD is not a component: " + root);
                    }
                    List<Object> suggestions = new ArrayList<>();
                    walkAndSuggest((BComponent) obj, root, null, suggestions, effectiveLimit, cx);

                    Map<String, Object> suggestedRuleset = buildSuggestedRuleset(suggestions);

                    return McpToolResult.success(NiagaraJson.obj(
                            "root",             root,
                            "totalPoints",      suggestions.size(),
                            "suggestions",      suggestions,
                            "suggestedRuleset", suggestedRuleset
                    ));
                } catch (Throwable e) {
                    LOG.warning("nmcp.haystack.suggestTags error: " + e);
                    return McpToolResult.error("Error suggesting tags: " + e.getMessage());
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // Component tree walker
    // -------------------------------------------------------------------------

    private void walkAndTag(BComponent comp, String compOrd,
                             List<Map<String, Object>> rules,
                             List<Object> matches,
                             int limit, boolean dryRun, Context cx) {
        if (matches.size() >= limit) return;

        String typeName    = "";
        String displayName = "";
        String slotName    = "";
        try { typeName    = comp.getType() != null ? comp.getType().getTypeName() : ""; } catch (Throwable ignored) {}
        try { displayName = comp.getDisplayName(cx); }                                    catch (Throwable ignored) {}
        try { slotName    = comp.getName(); }                                              catch (Throwable ignored) {}

        List<Object> appliedTags = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            if (ruleMatches(rule, displayName, typeName, slotName)) {
                Map<String, Object> tagsMap = getRuleTags(rule);
                if (!tagsMap.isEmpty()) {
                    if (!dryRun) {
                        applyTagsToComponent(comp, tagsMap);
                    }
                    appliedTags.add(NiagaraJson.obj(
                            "ruleId", getRuleId(rule),
                            "tags",   tagsMap
                    ));
                }
            }
        }

        if (!appliedTags.isEmpty()) {
            matches.add(NiagaraJson.obj(
                    "ord",         compOrd,
                    "displayName", displayName,
                    "typeName",    typeName,
                    "appliedTags", appliedTags
            ));
        }

        try {
            BComponent[] children = comp.getChildComponents();
            if (children != null) {
                for (BComponent child : children) {
                    if (matches.size() >= limit) break;
                    String childName = "";
                    try { childName = child.getName(); } catch (Throwable ignored) {}
                    String childOrd = compOrd.endsWith("/")
                            ? compOrd + childName : compOrd + "/" + childName;
                    walkAndTag(child, childOrd, rules, matches, limit, dryRun, cx);
                }
            }
        } catch (Throwable e) {
            LOG.warning("walkAndTag: error traversing children of " + compOrd + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Scan helpers
    // -------------------------------------------------------------------------

    private void walkAndScan(BComponent comp, String compOrd,
                              List<Object> out, int limit, Context cx) {
        if (out.size() >= limit) return;

        String typeName    = "";
        String displayName = "";
        try { typeName    = comp.getType() != null ? comp.getType().getTypeName() : ""; } catch (Throwable ignored) {}
        try { displayName = comp.getDisplayName(cx); }                                    catch (Throwable ignored) {}

        if (isPointType(typeName)) {
            Map<String, String> existingTags = readExistingTags(comp);
            out.add(NiagaraJson.obj(
                    "ord",          compOrd,
                    "displayName",  displayName,
                    "typeName",     typeName,
                    "existingTags", existingTags,
                    "tagCount",     existingTags.size()
            ));
            if (out.size() >= limit) return;
        }

        try {
            BComponent[] children = comp.getChildComponents();
            if (children != null) {
                for (BComponent child : children) {
                    if (out.size() >= limit) break;
                    String childName = "";
                    try { childName = child.getName(); } catch (Throwable ignored) {}
                    String childOrd = compOrd.endsWith("/")
                            ? compOrd + childName : compOrd + "/" + childName;
                    walkAndScan(child, childOrd, out, limit, cx);
                }
            }
        } catch (Throwable e) {
            LOG.warning("walkAndScan: error traversing children of " + compOrd + ": " + e.getMessage());
        }
    }

    private void walkAndSuggest(BComponent comp, String compOrd, String parentName,
                                 List<Object> out, int limit, Context cx) {
        if (out.size() >= limit) return;

        String typeName    = "";
        String displayName = "";
        String slotName    = "";
        try { typeName    = comp.getType() != null ? comp.getType().getTypeName() : ""; } catch (Throwable ignored) {}
        try { displayName = comp.getDisplayName(cx); }                                    catch (Throwable ignored) {}
        try { slotName    = comp.getName(); }                                              catch (Throwable ignored) {}

        if (isPointType(typeName)) {
            Map<String, Object> suggestion = suggestTagsForPoint(displayName, slotName, typeName,
                    parentName != null ? parentName : "");
            Map<?, ?> suggestedTags = (Map<?, ?>) suggestion.get("tags");
            List<?> reasoning       = (List<?>) suggestion.get("reasoning");
            out.add(NiagaraJson.obj(
                    "ord",           compOrd,
                    "displayName",   displayName,
                    "typeName",      typeName,
                    "suggestedTags", suggestedTags,
                    "reasoning",     reasoning
            ));
            if (out.size() >= limit) return;
        }

        try {
            BComponent[] children = comp.getChildComponents();
            if (children != null) {
                String nextParent = displayName != null && !displayName.isEmpty() ? displayName : slotName;
                for (BComponent child : children) {
                    if (out.size() >= limit) break;
                    String childName = "";
                    try { childName = child.getName(); } catch (Throwable ignored) {}
                    String childOrd = compOrd.endsWith("/")
                            ? compOrd + childName : compOrd + "/" + childName;
                    walkAndSuggest(child, childOrd, nextParent, out, limit, cx);
                }
            }
        } catch (Throwable e) {
            LOG.warning("walkAndSuggest: error traversing children of " + compOrd + ": " + e.getMessage());
        }
    }

    /**
     * Reads haystack tags from a component's dynamic {@value #HS_SLOT_PREFIX} slots.
     * Returns a map of {@code {tagName → value}} for every slot whose name starts with the prefix.
     */
    private Map<String, String> readExistingTags(BComponent comp) {
        Map<String, String> tags = new LinkedHashMap<>();
        try {
            Property[] dynamic = comp.getDynamicPropertiesArray();
            if (dynamic == null) return tags;
            for (Property slot : dynamic) {
                if (slot == null) continue;
                String name = slot.getName();
                if (name == null || !name.startsWith(HS_SLOT_PREFIX)) continue;
                String tagName = name.substring(HS_SLOT_PREFIX.length());
                try {
                    BValue val = comp.get(name);
                    tags.put(tagName, val != null ? val.toString() : "m:");
                } catch (Throwable ignored) {
                    tags.put(tagName, "m:");
                }
            }
        } catch (Throwable e) {
            LOG.warning("readExistingTags error: " + e.getMessage());
        }
        return tags;
    }

    /** Returns true if the given Niagara type name indicates this component is a data point. */
    private boolean isPointType(String typeName) {
        return typeName.contains("NumericPoint") || typeName.contains("BooleanPoint")
                || typeName.contains("StringPoint") || typeName.contains("EnumPoint")
                || typeName.contains("ProxyPoint")  || typeName.contains("Point");
    }

    /**
     * Applies heuristic analysis to suggest Project Haystack tags for a single point.
     * Returns {@code {tags: Map<String,String>, reasoning: List<String>}}.
     */
    private Map<String, Object> suggestTagsForPoint(String displayName, String slotName,
                                                     String typeName, String parentName) {
        Map<String, String> tags     = new LinkedHashMap<>();
        List<String>        reasons  = new ArrayList<>();
        String name = (displayName != null && !displayName.isEmpty() ? displayName : slotName)
                .toLowerCase();
        String type = typeName != null ? typeName : "";
        String parent = parentName != null ? parentName.toLowerCase() : "";

        // Step 1 — base role from type
        boolean writable = type.contains("Writable");
        tags.put("point", "m:");
        if (!writable) {
            tags.put("sensor", "m:");
            reasons.add(type.contains("Boolean") ? "BooleanPoint→sensor" : "NumericPoint→sensor");
        } else {
            boolean isSp = name.contains("setpoint") || name.contains(" sp") || name.endsWith("sp")
                    || name.contains("stpt") || name.contains("spt");
            if (isSp) {
                tags.put("sp", "m:");
                reasons.add("Writable+name→sp");
            } else {
                tags.put("cmd", "m:");
                reasons.add("Writable→cmd");
            }
        }

        // Step 2 — quantity from name
        if (containsAny(name, "temp", "temperature", "sat", "rat", "mat", "oat", "lat", "dat")) {
            tags.put("temp", "m:");
            reasons.add("name→temp");
        }
        if (containsAny(name, "humid", " rh", "_rh")) {
            tags.put("humidity", "m:");
            reasons.add("name→humidity");
        }
        if (name.contains("co2")) {
            tags.put("co2", "m:");
            reasons.add("name→co2");
        }
        if (containsAny(name, "press", "pres", " dp", "_dp", " dps", "_dps")) {
            tags.put("pressure", "m:");
            reasons.add("name→pressure");
        }
        if (containsAny(name, "flow", "cfm", "gpm")) {
            tags.put("flow", "m:");
            reasons.add("name→flow");
        }
        if (containsAny(name, "speed", " spd", "_spd", " rpm", "_rpm")) {
            tags.put("speed", "m:");
            reasons.add("name→speed");
        }
        if (containsAny(name, " kw", "_kw", "power")) {
            tags.put("power", "m:");
            reasons.add("name→power");
        }
        if (containsAny(name, "kwh", "energy")) {
            tags.put("energy", "m:");
            reasons.add("name→energy");
        }
        if (containsAny(name, "current", "amps", " amp")) {
            tags.put("current", "m:");
            reasons.add("name→current");
        }
        if (name.contains("voltage") || name.contains("volt")) {
            tags.put("volt", "m:");
            reasons.add("name→volt");
        }
        if (containsAny(name, "occ", "occupan")) {
            tags.put("occupied", "m:");
            reasons.add("name→occupied");
        }

        // Step 3 — medium from name
        if (containsAny(name, "air", " sa", "_sa", " ra", "_ra", " ma", "_ma", " oa", "_oa")) {
            tags.put("air", "m:");
            reasons.add("name→air");
        }
        if (containsAny(name, "water", " hw", "_hw", " cw", "_cw", "chw", "chilled")) {
            tags.put("water", "m:");
            reasons.add("name→water");
        }
        if (name.contains("steam")) {
            tags.put("steam", "m:");
            reasons.add("name→steam");
        }
        if (containsAny(name, "elec", " kw", "_kw", " amp", "circuit")) {
            tags.put("elec", "m:");
            reasons.add("name→elec");
        }

        // Step 4 — directionality from name
        if (containsAny(name, "supply", " sa", "_sa", " sat")) {
            tags.put("supply", "m:");
            reasons.add("name→supply");
        }
        if (containsAny(name, "return", " ra", "_ra", " rat")) {
            tags.put("return", "m:");
            reasons.add("name→return");
        }
        if (containsAny(name, "mixed", " ma", "_ma", " mat")) {
            tags.put("mixed", "m:");
            reasons.add("name→mixed");
        }
        if (containsAny(name, "exhaust", " ea", "_ea")) {
            tags.put("exhaust", "m:");
            reasons.add("name→exhaust");
        }
        if (containsAny(name, "discharge", " da", "_da")) {
            tags.put("discharge", "m:");
            reasons.add("name→discharge");
        }
        if (containsAny(name, "outside", " oa", "_oa", " oat")) {
            tags.put("outside", "m:");
            reasons.add("name→outside");
        }

        // Step 5 — parent equip hint (informational only — equip tags go on the equip component)
        if (containsAny(parent, "ahu", "air handling")) {
            reasons.add("parent→ahu equip (tag the equip component, not this point)");
        } else if (containsAny(parent, "vav")) {
            reasons.add("parent→vav equip (tag the equip component, not this point)");
        } else if (containsAny(parent, "fcu", "fan coil")) {
            reasons.add("parent→fcu equip (tag the equip component, not this point)");
        } else if (containsAny(parent, "rtu", "rooftop")) {
            reasons.add("parent→rtu equip (tag the equip component, not this point)");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tags",      tags);
        result.put("reasoning", reasons);
        return result;
    }

    private boolean containsAny(String subject, String... keywords) {
        for (String kw : keywords) {
            if (subject.contains(kw)) return true;
        }
        return false;
    }

    /**
     * Builds a suggested ruleset from scan results.
     * Produces one rule per canonical heuristic pattern that matched at least one discovered point.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSuggestedRuleset(List<Object> suggestions) {
        // Canonical rule templates: {id, description, typeContains, nameContains, tags[]}
        // nameContains may be null (type-only condition)
        Object[][] templates = {
            {"numeric-sensor",   "Read-only numeric points",     "NumericPoint",   null,       new String[]{"point","sensor"}},
            {"boolean-sensor",   "Read-only boolean points",     "BooleanPoint",   null,       new String[]{"point","sensor"}},
            {"numeric-cmd",      "Numeric command/output points","NumericWritable", null,      new String[]{"point","cmd"}},
            {"boolean-cmd",      "Boolean command/output points","BooleanWritable", null,      new String[]{"point","cmd"}},
            {"temp-quantity",    "Temperature measurement points",null,            "temp",     new String[]{"temp"}},
            {"humidity-quantity","Humidity measurement points",   null,            "humid",    new String[]{"humidity"}},
            {"co2-quantity",     "CO\u2082 measurement points",  null,            "co2",      new String[]{"co2"}},
            {"pressure-quantity","Pressure measurement points",  null,            "press",    new String[]{"pressure"}},
            {"flow-quantity",    "Flow measurement points",      null,            "flow",     new String[]{"flow"}},
            {"power-quantity",   "Electrical power points",      null,            "kw",       new String[]{"power","elec"}},
            {"supply-air",       "Supply air points",            null,            "supply",   new String[]{"air","supply"}},
            {"return-air",       "Return air points",            null,            "return",   new String[]{"air","return"}},
            {"occ-points",       "Occupancy points",             null,            "occ",      new String[]{"occupied"}},
            {"setpoints",        "Setpoint (sp) points",         "Writable",      "setpoint", new String[]{"sp"}},
        };

        // Collect all display names and type names from suggestions for matching
        List<String> allNames = new ArrayList<>();
        List<String> allTypes = new ArrayList<>();
        for (Object s : suggestions) {
            if (!(s instanceof Map)) continue;
            Map<?, ?> sm = (Map<?, ?>) s;
            String dn = sm.get("displayName") != null ? sm.get("displayName").toString().toLowerCase() : "";
            String tn = sm.get("typeName")    != null ? sm.get("typeName").toString() : "";
            allNames.add(dn);
            allTypes.add(tn);
        }

        List<Object> rules = new ArrayList<>();
        for (Object[] t : templates) {
            String id          = (String) t[0];
            String description = (String) t[1];
            String typeContains= (String) t[2];
            String nameContains= (String) t[3];
            String[] tagNames  = (String[]) t[4];

            // Check if this template matches at least one discovered point
            boolean anyMatch = false;
            for (int i = 0; i < allNames.size(); i++) {
                boolean typeOk = typeContains == null || allTypes.get(i).contains(typeContains);
                boolean nameOk = nameContains == null || allNames.get(i).contains(nameContains);
                if (typeOk && nameOk) { anyMatch = true; break; }
            }
            if (!anyMatch) continue;

            // Build conditions list
            List<Object> conditions = new ArrayList<>();
            if (typeContains != null) {
                conditions.add(NiagaraJson.obj("field", "typeName", "op", "contains", "value", typeContains));
            }
            if (nameContains != null) {
                conditions.add(NiagaraJson.obj("field", "displayName", "op", "contains", "value", nameContains));
            }

            // Build tags map
            Map<String, Object> tagsMap = new LinkedHashMap<>();
            for (String tag : tagNames) {
                tagsMap.put(tag, "m:");
            }

            rules.add(NiagaraJson.obj(
                    "id",          id,
                    "description", description,
                    "conditions",  conditions,
                    "tags",        tagsMap
            ));
        }

        return NiagaraJson.obj("rules", rules);
    }

    /**
     * Writes the haystack tags to the given component as dynamic slots prefixed with {@value #HS_SLOT_PREFIX}.     *
     * <p>TODO: Replace with the project-specific Niagara haystack tag API when available.
     *   On a station with the nhaystack module or native Niagara 4.8+ haystack support, use:
     *   <pre>
     *     BTagDictionary dict = BTagDictionary.get(comp);
     *     dict.set(tagName, BMarker.DEFAULT);   // for marker tags
     *     dict.set(tagName, BString.make(val)); // for string tags
     *   </pre>
     */
    private void applyTagsToComponent(BComponent comp, Map<String, Object> tags) {
        for (Map.Entry<String, Object> entry : tags.entrySet()) {
            String tagName  = entry.getKey();
            String tagValue = entry.getValue() != null ? entry.getValue().toString() : "m:";
            String slotName = HS_SLOT_PREFIX + tagName;
            BValue value = "m:".equals(tagValue) ? BMarker.DEFAULT : BString.make(tagValue);
            try {
                Property prop = comp.getProperty(slotName);
                if (prop != null) {
                    comp.set(slotName, value);
                } else {
                    prop = comp.add(slotName, value);
                }
                if (prop != null) {
                    comp.setFlags(prop, Flags.METADATA);
                }
            } catch (Throwable e) {
                LOG.warning("applyTagsToComponent: could not set tag '" + tagName + "': " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rule matching
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if all conditions in the rule match the given component attributes.
     * All conditions must match (logical AND).
     */
    @SuppressWarnings("unchecked")
    private boolean ruleMatches(Map<String, Object> rule,
                                 String displayName, String typeName, String slotName) {
        Object condObj = rule.get("conditions");
        if (!(condObj instanceof List)) return false;
        List<Object> conditions = (List<Object>) condObj;
        if (conditions.isEmpty()) return false;

        for (Object condItem : conditions) {
            if (!(condItem instanceof Map)) return false;
            Map<String, Object> cond = (Map<String, Object>) condItem;
            String field = cond.containsKey("field") ? cond.get("field").toString() : "";
            String op    = cond.containsKey("op")    ? cond.get("op").toString()    : "";
            String value = cond.containsKey("value") ? cond.get("value").toString() : "";

            String subject;
            switch (field) {
                case "displayName": subject = displayName; break;
                case "typeName":    subject = typeName;    break;
                case "name":        subject = slotName;    break;
                default:            return false;
            }

            if (!operatorMatches(op, subject, value)) return false;
        }
        return true;
    }

    private boolean operatorMatches(String op, String subject, String value) {
        if (subject == null) return false;
        String s = subject.toLowerCase();
        String v = value.toLowerCase();
        switch (op) {
            case "contains":   return s.contains(v);
            case "equals":     return s.equals(v);
            case "startsWith": return s.startsWith(v);
            case "endsWith":   return s.endsWith(v);
            default:           return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getRuleTags(Map<String, Object> rule) {
        Object tagsObj = rule.get("tags");
        if (tagsObj instanceof Map) return (Map<String, Object>) tagsObj;
        return NiagaraJson.obj();
    }

    private String getRuleId(Map<String, Object> rule) {
        Object id = rule.get("id");
        return id != null ? id.toString() : "unnamed";
    }

    // -------------------------------------------------------------------------
    // Ruleset file I/O
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadRules() throws Exception {
        File file = resolveFile(rulesetPath);
        if (!file.exists()) {
            return new ArrayList<>();
        }
        String content = readFile(file);
        Map<String, Object> root = NiagaraJson.parseObject(content);
        Object rulesObj = root.get("rules");
        if (!(rulesObj instanceof List)) {
            return new ArrayList<>();
        }
        List<Object> rawList = (List<Object>) rulesObj;
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map) {
                rules.add((Map<String, Object>) item);
            }
        }
        return rules;
    }

    /** Resolves the ruleset path relative to the JVM working directory if not absolute. */
    static File resolveFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return new File("mcp-haystack-rules.json");
        }
        return new File(path);
    }

    private String readFile(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void writeFile(File file, String content) throws IOException {
        Files.write(file.toPath(),
                content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private String buildEmptyRulesetTemplate() {
        return "{\n"
                + "  \"rules\": [\n"
                + "    {\n"
                + "      \"id\": \"example-rule\",\n"
                + "      \"description\": \"Tag AHU air handling units\",\n"
                + "      \"conditions\": [\n"
                + "        {\"field\": \"displayName\", \"op\": \"contains\", \"value\": \"AHU\"}\n"
                + "      ],\n"
                + "      \"tags\": {\"equip\": \"m:\", \"ahu\": \"m:\", \"hvac\": \"m:\"}\n"
                + "    }\n"
                + "  ]\n"
                + "}";
    }

    // -------------------------------------------------------------------------
    // Argument helpers
    // -------------------------------------------------------------------------

    private String getStringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString() : null;
    }

    private Integer getIntArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private boolean getBooleanArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object v = args.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return defaultValue;
    }
}
