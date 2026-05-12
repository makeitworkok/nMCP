// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON parser and builder used throughout the MCP module.
 * No external dependencies – pure Java.
 *
 * <p>Capabilities:
 * <ul>
 *   <li>Parse JSON objects from strings (sufficient for JSON-RPC requests)</li>
 *   <li>Build JSON strings from {@link Map} / {@link List} / primitives</li>
 * </ul>
 */
public final class NiagaraJson {

    private NiagaraJson() {}

    // -------------------------------------------------------------------------
    // Building
    // -------------------------------------------------------------------------

    /**
     * Serialises a {@code Map<String, Object>} to a JSON object string.
     * Supported value types: String, Number, Boolean, null, Map, List.
     */
    @SuppressWarnings("unchecked")
    public static String buildObject(Map<String, Object> obj) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : obj.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeString(entry.getKey())).append("\":");
            appendValue(sb, entry.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    /** Serialises a {@code List<Object>} to a JSON array string. */
    @SuppressWarnings("unchecked")
    public static String buildArray(List<Object> arr) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object v : arr) {
            if (!first) sb.append(',');
            first = false;
            appendValue(sb, v);
        }
        sb.append(']');
        return sb.toString();
    }

    /** Convenience factory: build a {@code Map<String,Object>} from alternating key/value pairs. */
    public static Map<String, Object> obj(Object... kvPairs) {
        if (kvPairs.length % 2 != 0) {
            throw new IllegalArgumentException("obj() requires an even number of arguments");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            map.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return map;
    }

    /** Convenience factory: build a {@code List<Object>} from items. */
    public static List<Object> arr(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    @SuppressWarnings("unchecked")
    static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof String) {
            sb.append('"').append(escapeString((String) value)).append('"');
        } else if (value instanceof Map) {
            sb.append(buildObject((Map<String, Object>) value));
        } else if (value instanceof List) {
            sb.append(buildArray((List<Object>) value));
        } else {
            sb.append('"').append(escapeString(value.toString())).append('"');
        }
    }

    static String escapeString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    /**
     * Parses a JSON object string into a {@code Map<String, Object>}.
     * Returns an empty map on null/empty input.
     *
     * @throws IllegalArgumentException if the JSON is not a valid object
     */
    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        Parser p = new Parser(json.trim());
        Object result = p.parseValue();
        if (!(result instanceof Map)) {
            throw new IllegalArgumentException("Expected JSON object but got: " + json);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        return map;
    }

    // -------------------------------------------------------------------------
    // Internal parser
    // -------------------------------------------------------------------------

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
            this.pos = 0;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) return null;
            char c = src.charAt(pos);
            if (c == '{')  return parseObject();
            if (c == '[')  return parseArray();
            if (c == '"')  return parseString();
            if (c == 't')  return parseLiteral("true",  Boolean.TRUE);
            if (c == 'f')  return parseLiteral("false", Boolean.FALSE);
            if (c == 'n')  return parseLiteral("null",  null);
            if (c == '-' || Character.isDigit(c)) return parseNumber();
            throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char sep = peek();
                if (sep == '}') { pos++; break; }
                if (sep == ',') { pos++; continue; }
                throw new IllegalArgumentException("Expected ',' or '}' at position " + pos);
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char sep = peek();
                if (sep == ']') { pos++; break; }
                if (sep == ',') { pos++; continue; }
                throw new IllegalArgumentException("Expected ',' or ']' at position " + pos);
            }
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length()) break;
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 <= src.length()) {
                                String hex = src.substring(pos, pos + 4);
                                sb.append((char) Integer.parseInt(hex, 16));
                                pos += 4;
                            }
                            break;
                        default:   sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string at position " + pos);
        }

        Number parseNumber() {
            int start = pos;
            if (pos < src.length() && src.charAt(pos) == '-') pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            boolean isDouble = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isDouble = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            String numStr = src.substring(start, pos);
            if (isDouble) return Double.parseDouble(numStr);
            long lv = Long.parseLong(numStr);
            if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) return (int) lv;
            return lv;
        }

        Object parseLiteral(String expected, Object value) {
            if (src.startsWith(expected, pos)) {
                pos += expected.length();
                return value;
            }
            throw new IllegalArgumentException("Expected '" + expected + "' at position " + pos);
        }

        void expect(char c) {
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at position " + pos
                    + " but got '" + (pos < src.length() ? src.charAt(pos) : "EOF") + "'");
            }
            pos++;
        }

        char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
    }
}
