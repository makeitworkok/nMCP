// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NiagaraJson}.
 */
class NiagaraJsonTest {

    // -------------------------------------------------------------------------
    // buildObject / obj
    // -------------------------------------------------------------------------

    @Test
    void buildObject_emptyMap() {
        assertEquals("{}", NiagaraJson.buildObject(NiagaraJson.obj()));
    }

    @Test
    void buildObject_simpleStringValues() {
        String json = NiagaraJson.buildObject(NiagaraJson.obj("name", "Alice", "city", "NYC"));
        assertTrue(json.contains("\"name\":\"Alice\""));
        assertTrue(json.contains("\"city\":\"NYC\""));
    }

    @Test
    void buildObject_intAndBooleanValues() {
        String json = NiagaraJson.buildObject(NiagaraJson.obj("count", 42, "active", true));
        assertTrue(json.contains("\"count\":42"));
        assertTrue(json.contains("\"active\":true"));
    }

    @Test
    void buildObject_nullValue() {
        String json = NiagaraJson.buildObject(NiagaraJson.obj("key", (Object) null));
        assertTrue(json.contains("\"key\":null"));
    }

    @Test
    void buildObject_nestedObject() {
        Map<String, Object> inner = NiagaraJson.obj("x", 1);
        String json = NiagaraJson.buildObject(NiagaraJson.obj("nested", inner));
        assertTrue(json.contains("\"nested\":{\"x\":1}"));
    }

    @Test
    void buildArray_primitives() {
        List<Object> arr = NiagaraJson.arr("a", "b", 3);
        String json = NiagaraJson.buildArray(arr);
        assertEquals("[\"a\",\"b\",3]", json);
    }

    @Test
    void buildObject_rejectsInvalidDoubleValues() {
        assertThrows(IllegalArgumentException.class,
                () -> NiagaraJson.buildObject(NiagaraJson.obj("bad", Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> NiagaraJson.buildObject(NiagaraJson.obj("bad", Double.POSITIVE_INFINITY)));
    }

    @Test
    void buildArray_rejectsInvalidFloatValues() {
        assertThrows(IllegalArgumentException.class,
                () -> NiagaraJson.buildArray(NiagaraJson.arr(Float.NEGATIVE_INFINITY)));
    }

    @Test
    void escapeString_specialChars() {
        String result = NiagaraJson.escapeString("line1\nline2\ttab\"quote");
        assertEquals("line1\\nline2\\ttab\\\"quote", result);
    }

    // -------------------------------------------------------------------------
    // parseObject
    // -------------------------------------------------------------------------

    @Test
    void parseObject_emptyObject() {
        Map<String, Object> result = NiagaraJson.parseObject("{}");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseObject_simpleString() {
        Map<String, Object> result = NiagaraJson.parseObject("{\"greeting\":\"hello\"}");
        assertEquals("hello", result.get("greeting"));
    }

    @Test
    void parseObject_integerValue() {
        Map<String, Object> result = NiagaraJson.parseObject("{\"count\":42}");
        assertEquals(42, result.get("count"));
    }

    @Test
    void parseObject_booleanValues() {
        Map<String, Object> result = NiagaraJson.parseObject("{\"yes\":true,\"no\":false}");
        assertEquals(Boolean.TRUE,  result.get("yes"));
        assertEquals(Boolean.FALSE, result.get("no"));
    }

    @Test
    void parseObject_nullValue() {
        Map<String, Object> result = NiagaraJson.parseObject("{\"x\":null}");
        assertNull(result.get("x"));
    }

    @Test
    void parseObject_nestedObject() {
        Map<String, Object> result = NiagaraJson.parseObject("{\"inner\":{\"k\":\"v\"}}");
        assertTrue(result.get("inner") instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) result.get("inner");
        assertEquals("v", inner.get("k"));
    }

    @Test
    void parseObject_arrayValue() {
        Map<String, Object> result = NiagaraJson.parseObject("{\"items\":[1,2,3]}");
        assertTrue(result.get("items") instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) result.get("items");
        assertEquals(3, items.size());
        assertEquals(1, items.get(0));
    }

    @Test
    void parseObject_escapedString() {
        Map<String, Object> result = NiagaraJson.parseObject("{\"msg\":\"line1\\nline2\"}");
        assertEquals("line1\nline2", result.get("msg"));
    }

    @Test
    void parseObject_nullOrEmptyInput() {
        assertTrue(NiagaraJson.parseObject(null).isEmpty());
        assertTrue(NiagaraJson.parseObject("").isEmpty());
        assertTrue(NiagaraJson.parseObject("   ").isEmpty());
    }

    @Test
    void parseObject_rejectsNonObject() {
        assertThrows(IllegalArgumentException.class,
                () -> NiagaraJson.parseObject("[1,2,3]"));
    }

    @Test
    void parseObject_rejectsTrailingContent() {
        assertThrows(IllegalArgumentException.class,
                () -> NiagaraJson.parseObject("{\"x\":1} true"));
    }

    @Test
    void parseObject_rejectsMalformedNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> NiagaraJson.parseObject("{\"x\":1.}"));
        assertThrows(IllegalArgumentException.class,
                () -> NiagaraJson.parseObject("{\"x\":1e}"));
        assertThrows(IllegalArgumentException.class,
            () -> NiagaraJson.parseObject("{\"x\":01}"));
        assertThrows(IllegalArgumentException.class,
            () -> NiagaraJson.parseObject("{\"x\":1e309}"));
    }

    // -------------------------------------------------------------------------
    // parseArray / parseValue
    // -------------------------------------------------------------------------

    @Test
    void parseArray_topLevelArray() {
        List<Object> result = NiagaraJson.parseArray("[1,\"two\",true,null,{\"x\":3}]");
        assertEquals(5, result.size());
        assertEquals(1, result.get(0));
        assertEquals("two", result.get(1));
        assertEquals(Boolean.TRUE, result.get(2));
        assertNull(result.get(3));
        assertTrue(result.get(4) instanceof Map);
    }

    @Test
    void parseArray_nullOrEmptyInput() {
        assertTrue(NiagaraJson.parseArray(null).isEmpty());
        assertTrue(NiagaraJson.parseArray("").isEmpty());
        assertTrue(NiagaraJson.parseArray("   ").isEmpty());
    }

    @Test
    void parseArray_rejectsNonArrayAndTrailingContent() {
        assertThrows(IllegalArgumentException.class,
                () -> NiagaraJson.parseArray("{\"x\":1}"));
        assertThrows(IllegalArgumentException.class,
                () -> NiagaraJson.parseArray("[1] {}"));
    }

    @Test
    void parseValue_topLevelScalars() {
        assertEquals("hello", NiagaraJson.parseValue("\"hello\""));
        assertEquals(42, NiagaraJson.parseValue("42"));
        assertEquals(3.5, ((Number) NiagaraJson.parseValue("3.5")).doubleValue(), 0.0001);
        assertEquals(Boolean.FALSE, NiagaraJson.parseValue("false"));
        assertNull(NiagaraJson.parseValue("null"));
    }

    @Test
    void parseValue_rejectsTrailingContent() {
        assertThrows(IllegalArgumentException.class,
                () -> NiagaraJson.parseValue("true false"));
    }

    // -------------------------------------------------------------------------
    // Typed getters
    // -------------------------------------------------------------------------

    @Test
    void typedGetters_returnExpectedValues() {
        Map<String, Object> map = NiagaraJson.parseObject("{"
                + "\"name\":\"nMCP\","
                + "\"count\":40,"
                + "\"ratio\":2.5,"
                + "\"active\":true,"
                + "\"obj\":{\"x\":1},"
                + "\"list\":[1,2]"
                + "}");

        assertEquals("nMCP", NiagaraJson.getString(map, "name"));
        assertEquals(Integer.valueOf(40), NiagaraJson.getInteger(map, "count"));
        assertEquals(Double.valueOf(2.5), NiagaraJson.getDouble(map, "ratio"));
        assertEquals(Boolean.TRUE, NiagaraJson.getBoolean(map, "active"));
        assertEquals(1, NiagaraJson.getObject(map, "obj").get("x"));
        assertEquals(2, NiagaraJson.getList(map, "list").size());
    }

    @Test
    void typedGetters_returnDefaultsForMissingOrNull() {
        Map<String, Object> map = NiagaraJson.parseObject("{\"name\":null}");
        assertEquals("fallback", NiagaraJson.getString(map, "name", "fallback"));
        assertEquals("fallback", NiagaraJson.getString(map, "missing", "fallback"));
        assertEquals(Integer.valueOf(7), NiagaraJson.getInteger(map, "missing", Integer.valueOf(7)));
        assertEquals(Double.valueOf(1.25), NiagaraJson.getDouble(map, "missing", Double.valueOf(1.25)));
        assertEquals(Boolean.FALSE, NiagaraJson.getBoolean(map, "missing", Boolean.FALSE));
        assertNull(NiagaraJson.getObject(map, "missing"));
        assertNull(NiagaraJson.getList(map, "missing"));
    }

    @Test
    void typedGetters_rejectWrongTypes() {
        Map<String, Object> map = NiagaraJson.parseObject("{\"s\":\"text\",\"n\":5,\"d\":1.5,\"b\":true}");
        assertThrows(IllegalArgumentException.class, () -> NiagaraJson.getString(map, "n"));
        assertThrows(IllegalArgumentException.class, () -> NiagaraJson.getInteger(map, "s"));
        assertThrows(IllegalArgumentException.class, () -> NiagaraJson.getInteger(map, "d"));
        assertThrows(IllegalArgumentException.class, () -> NiagaraJson.getDouble(map, "s"));
        assertThrows(IllegalArgumentException.class, () -> NiagaraJson.getBoolean(map, "s"));
        assertThrows(IllegalArgumentException.class, () -> NiagaraJson.getObject(map, "b"));
        assertThrows(IllegalArgumentException.class, () -> NiagaraJson.getList(map, "b"));
    }

    @Test
    void getInteger_rejectsOutOfRangeLong() {
        Map<String, Object> map = NiagaraJson.parseObject("{\"big\":2147483648}");
        assertThrows(IllegalArgumentException.class, () -> NiagaraJson.getInteger(map, "big"));
    }

    @Test
    void roundTrip() {
        Map<String, Object> original = NiagaraJson.obj(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", NiagaraJson.obj()
        );
        String json = NiagaraJson.buildObject(original);
        Map<String, Object> parsed = NiagaraJson.parseObject(json);
        assertEquals("2.0",        parsed.get("jsonrpc"));
        assertEquals(1,            parsed.get("id"));
        assertEquals("tools/list", parsed.get("method"));
        assertTrue(parsed.get("params") instanceof Map);
    }
}
