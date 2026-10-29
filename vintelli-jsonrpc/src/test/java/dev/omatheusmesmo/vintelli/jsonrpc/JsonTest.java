package dev.omatheusmesmo.vintelli.jsonrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesAndWritesPlainTypes() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("number", Long.valueOf(50));
        value.put("fraction", Double.valueOf(2.75));
        value.put("text", "hello world");
        value.put("flag", Boolean.TRUE);
        value.put("nothing", null);
        value.put("list", List.of("a", "b"));
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("deep", Long.valueOf(1));
        value.put("object", inner);
        assertEquals(value, Json.parse(Json.write(value)));
    }

    @Test
    void roundTripsEscapesAndUnicode() {
        String original = "quote \" backslash \\ newline \n tab \t high \u00e9 emoji \ud83d\ude00";
        assertEquals(original,
                Json.parse("\"quote \\\" backslash \\\\ newline \\n tab \\t high \\u00e9 emoji \\ud83d\\ude00\""));
        assertEquals("\"quote \\\" backslash \\\\ newline \\n tab \\t high \u00e9 emoji \ud83d\ude00\"",
                Json.write(original));
    }

    @Test
    void integralNumbersParseAsLongAndHugeOnesKeepPrecision() {
        Map<String, Object> parsed = asObject(Json.parse(
                "{\"i\": 9007199254740993, \"f\": 1.5, \"big\": 90071992547409930000}"));
        assertEquals(Long.valueOf(9007199254740993L), parsed.get("i"));
        assertEquals(Double.valueOf(1.5), parsed.get("f"));
        assertEquals(BigInteger.valueOf(9007199254740993L).multiply(BigInteger.valueOf(10_000L)),
                parsed.get("big"));
    }

    @Test
    void writesAndParsesTheWholeUtf8Range() {
        String value = "quoted \" backslash \\ control \u0001 raw unicode caf\u00e9 \u4f60\u597d \ud83d\ude00";
        assertEquals(value, Json.parse(Json.write(value)));
    }

    @Test
    void writesCompactStructure() {
        Map<String, Object> value = TestSupport.params("a", TestSupport.listOf(Long.valueOf(2), Boolean.FALSE));
        assertEquals("{\"a\":[2,false]}", Json.write(value));
    }

    @Test
    void writesEmptyContainers() {
        assertEquals("[]", Json.write(TestSupport.listOf()));
        assertEquals("{}", Json.write(TestSupport.params()));
    }

    @Test
    void rejectsBrokenInput() {
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("{"));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse(""));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("{\"a\":1} trailing"));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("[1,]"));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("tru"));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("\"unterminated"));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("{\"a\" 1}"));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("\"\\u00zz\""));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("\"\\u+041\""));
    }

    @Test
    void rejectsNumbersOutsideTheRfc8259Grammar() {
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("+1"));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse(".5"));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("1."));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("01"));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("1e"));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("1e999999999999"));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("{\"a\": 1.5e999999999999999999999}"));
    }

    @Test
    void rejectsUnescapedControlCharactersInStrings() {
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("\"a\u0004b\""));
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse("\"line\nbreak\""));
    }

    @Test
    void duplicateObjectKeysKeepTheLastValue() {
        Map<String, Object> parsed = asObject(Json.parse("{\"a\": 1, \"a\": 2}"));
        assertEquals(Long.valueOf(2), parsed.get("a"));
    }

    @Test
    void rejectsNestingDeeperThanTheLimit() {
        StringBuilder deep = new StringBuilder();
        for (int index = 0; index < Json.MAX_NESTING_DEPTH + 2; index++) {
            deep.append('[');
        }
        for (int index = 0; index < Json.MAX_NESTING_DEPTH + 2; index++) {
            deep.append(']');
        }
        assertThrows(JsonRpcProtocolException.class, () -> Json.parse(deep.toString()));
    }

    @Test
    void rejectsNonWritableTypes() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.valueOf(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.valueOf(Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Float.valueOf(Float.NaN)));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Float.valueOf(Float.POSITIVE_INFINITY)));
    }

    @Test
    void rejectsLoneSurrogatesAndAcceptsValidPairs() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(TestSupport.params("odd", "\ud800")));
        assertThrows(IllegalArgumentException.class, () -> Json.write(TestSupport.params("low", "\udc00")));
        String valid = "surrogate pair \ud83d\ude00 tail";
        assertEquals(valid, Json.parse(Json.write(valid)));
    }

    @Test
    void nullValueIsTransparent() {
        assertNull(Json.parse("null"));
        assertEquals("null", Json.write(null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        return (Map<String, Object>) value;
    }
}
