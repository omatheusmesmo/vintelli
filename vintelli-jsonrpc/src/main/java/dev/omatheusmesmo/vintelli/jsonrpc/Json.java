package dev.omatheusmesmo.vintelli.jsonrpc;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

/**
 * JSON codec Join around the Jackson 3 streaming API. Package private on purpose: the
 * consumer modules only see the message records and the framings, never the JSON
 * implementation used underneath.
 *
 * <p>Values are plain JDK types: objects as {@link Map}, arrays as {@link List}
 * ({@link ArrayList} when parsed), strings as {@link String}, integral numbers as
 * {@link Long} when they fit and {@link BigInteger} otherwise, floats as
 * {@link Double}, booleans as {@link Boolean}, and null as {@code null}. Big
 * integers keep full precision in the tree so id values can be rejected precisely
 * by {@link JsonRpcId#fromTree(Object)}.</p>
 *
 * <p>Duplicate object keys are allowed and keep the last value, which RFC 8259
 * permits; the decision is covered by a regression test. Unescaped control
 * characters inside strings, leading zeros, leading signs and other grammar
 * deviations are rejected because Jackson is spec strict by default.</p>
 */
final class Json {

    static final int MAX_NESTING_DEPTH = 128;

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(256)
                    .maxNumberLength(64)
                    .maxStringLength(16_777_216)
                    .maxNameLength(16_777_216)
                    .build())
            .build();

    private Json() {
    }

    /**
     * Parses one complete JSON document into the plain type tree. Trailing content
     * and depth beyond {@link #MAX_NESTING_DEPTH} are surfaced as
     * {@link JsonRpcProtocolException}.
     */
    static Object parse(String text) {
        try (JsonParser parser = FACTORY.createParser(ObjectReadContext.empty(), new StringReader(text))) {
            if (parser.nextToken() == null) {
                throw new JsonRpcProtocolException("empty JSON input");
            }
            Object value = readValue(parser, 0);
            if (parser.nextToken() != null) {
                throw new JsonRpcProtocolException("invalid JSON: trailing content after the value");
            }
            return value;
        } catch (JacksonException invalidJson) {
            throw new JsonRpcProtocolException("invalid JSON", invalidJson);
        }
    }

    private static Object readValue(JsonParser parser, int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new JsonRpcProtocolException("invalid JSON: nesting deeper than " + MAX_NESTING_DEPTH);
        }
        return switch (parser.currentToken()) {
            case START_OBJECT -> readObject(parser, depth);
            case START_ARRAY -> readArray(parser, depth);
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> readIntegral(parser);
            case VALUE_NUMBER_FLOAT -> readFloat(parser);
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new JsonRpcProtocolException(
                    "invalid JSON: unexpected token " + parser.currentToken());
        };
    }

    private static Map<String, Object> readObject(JsonParser parser, int depth) {
        Map<String, Object> object = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                throw new JsonRpcProtocolException("invalid JSON: expected an object member");
            }
            String key = parser.currentName();
            parser.nextToken();
            object.put(key, readValue(parser, depth + 1));
        }
        return object;
    }

    private static List<Object> readArray(JsonParser parser, int depth) {
        List<Object> array = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            array.add(readValue(parser, depth + 1));
        }
        return array;
    }

    /**
     * Integral numbers stay exact: fitting magnitudes as {@link Long}, larger ones as
     * {@link BigInteger} so ids keep full precision.
     */
    private static Number readIntegral(JsonParser parser) {
        return switch (parser.getNumberType()) {
            case INT, LONG -> Long.valueOf(parser.getLongValue());
            case BIG_INTEGER -> parser.getBigIntegerValue();
            default -> throw new JsonRpcProtocolException("invalid JSON: unexpected integer token");
        };
    }

    private static Double readFloat(JsonParser parser) {
        double value = parser.getDoubleValue();
        if (Double.isInfinite(value)) {
            throw new JsonRpcProtocolException("invalid JSON: number out of double range");
        }
        return Double.valueOf(value);
    }

    /**
     * Writes any plain type tree to compact JSON. Unserializable tree values, lone
     * surrogates inside strings and non-finite numbers are rejected.
     */
    static String write(Object value) {
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = FACTORY.createGenerator(ObjectWriteContext.empty(), out)) {
            writeValue(generator, value, 0);
        } catch (JacksonException unwritableValue) {
            throw new IllegalArgumentException(
                    "value cannot be written as JSON: " + unwritableValue.getMessage(), unwritableValue);
        }
        return out.toString();
    }

    private static void writeValue(JsonGenerator generator, Object value, int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException("value nesting deeper than " + MAX_NESTING_DEPTH);
        }
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof String text) {
            writeValidString(generator, text);
        } else if (value instanceof Boolean flag) {
            generator.writeBoolean(flag.booleanValue());
        } else if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            generator.writeNumber(((Number) value).longValue());
        } else if (value instanceof BigInteger magnitude) {
            generator.writeNumber(magnitude);
        } else if (value instanceof BigDecimal decimal) {
            generator.writeNumber(decimal);
        } else if (value instanceof Double number) {
            writeDouble(generator, number);
        } else if (value instanceof Float number) {
            if (number.isNaN() || number.isInfinite()) {
                throw new IllegalArgumentException("non-finite number cannot be written as JSON");
            }
            writeDouble(generator, number.doubleValue());
        } else if (value instanceof Map<?, ?> object) {
            writeObject(generator, object, depth);
        } else if (value instanceof List<?> array) {
            writeArray(generator, array, depth);
        } else {
            throw new IllegalArgumentException(
                    "cannot write type as JSON: " + value.getClass().getName());
        }
    }

    private static void writeDouble(JsonGenerator generator, double number) {
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new IllegalArgumentException("non-finite number cannot be written as JSON");
        }
        generator.writeNumber(number);
    }

    private static void writeObject(JsonGenerator generator, Map<?, ?> object, int depth) {
        generator.writeStartObject();
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "object key must be a string, got " + entry.getKey());
            }
            generator.writeName(key);
            writeValue(generator, entry.getValue(), depth + 1);
        }
        generator.writeEndObject();
    }

    private static void writeArray(JsonGenerator generator, List<?> array, int depth) {
        generator.writeStartArray();
        for (Object element : array) {
            writeValue(generator, element, depth + 1);
        }
        generator.writeEndArray();
    }

    /**
     * Writes a string value and verifies it is encodable as UTF-8 first: an unpaired
     * surrogate inside the value would silently become a replacement byte rather
     * than fail, so it is rejected here instead.
     */
    private static void writeValidString(JsonGenerator generator, String text) {
        checkEncodableChars(text);
        generator.writeString(text);
    }

    private static void checkEncodableChars(String text) {
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current >= '\ud800' && current <= '\udfff') {
                char next = index + 1 < text.length() ? text.charAt(index + 1) : '?';
                boolean paired = Character.isHighSurrogate(current)
                        && Character.isLowSurrogate(next);
                if (!paired) {
                    throw new IllegalArgumentException(
                            "string contains a lone surrogate, cannot be UTF-8 encoded");
                }
                index++;
            }
            index++;
        }
    }
}
