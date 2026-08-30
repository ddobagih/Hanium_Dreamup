package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict RFC 8259 subset parser for bounded, scalar administrator contracts. */
final class AdminStrictJson {
    private AdminStrictJson() {}

    static Map<String, Object> parseObject(String input) throws IOException {
        Object parsed = new Parser(input).parseDocument();
        if (!(parsed instanceof Map<?, ?> raw)) throw new IOException("JSON root must be an object");
        return castObject(raw);
    }

    static List<Map<String, Object>> parseObjectArray(String input) throws IOException {
        Object parsed = new Parser(input).parseDocument();
        if (!(parsed instanceof List<?> raw)) throw new IOException("JSON root must be an array");
        List<Map<String, Object>> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> object)) throw new IOException("JSON array item must be an object");
            result.add(castObject(object));
        }
        return AdminJava8Collections.copyList(result);
    }

    static String decodeUtf8(byte[] value) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("administrator API response is not valid UTF-8", error);
        }
    }

    private static Map<String, Object> castObject(Map<?, ?> raw) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IOException("JSON object key is invalid");
            result.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 8;
        private final String input;
        private int offset;

        private Parser(String input) throws IOException {
            if (input == null) throw new IOException("JSON input is required");
            this.input = input;
            requireWellFormedUnicode(input);
        }

        private Object parseDocument() throws IOException {
            skipWhitespace();
            Object value = parseValue(0);
            skipWhitespace();
            if (offset != input.length()) throw error("trailing data is forbidden");
            return value;
        }

        private Object parseValue(int depth) throws IOException {
            if (depth > MAX_DEPTH || offset >= input.length()) throw error("JSON value is incomplete");
            return switch (input.charAt(offset)) {
                case '{' -> parseObject(depth + 1);
                case '[' -> parseArray(depth + 1);
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject(int depth) throws IOException {
            expect('{');
            skipWhitespace();
            Map<String, Object> result = new LinkedHashMap<>();
            if (consume('}')) return result;
            while (true) {
                if (offset >= input.length() || input.charAt(offset) != '"') {
                    throw error("JSON object key must be double-quoted");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue(depth);
                if (result.containsKey(key)) throw error("duplicate JSON object key is forbidden");
                result.put(key, value);
                skipWhitespace();
                if (consume('}')) return result;
                expect(',');
                skipWhitespace();
            }
        }

        private List<Object> parseArray(int depth) throws IOException {
            expect('[');
            skipWhitespace();
            List<Object> result = new ArrayList<>();
            if (consume(']')) return result;
            while (true) {
                result.add(parseValue(depth));
                skipWhitespace();
                if (consume(']')) return result;
                expect(',');
                skipWhitespace();
            }
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (offset < input.length()) {
                char current = input.charAt(offset++);
                if (current == '"') {
                    requireWellFormedUnicode(result.toString());
                    return result.toString();
                }
                if (current < 0x20) throw error("unescaped JSON control character is forbidden");
                if (current != '\\') {
                    result.append(current);
                    continue;
                }
                if (offset >= input.length()) throw error("JSON escape is incomplete");
                char escaped = input.charAt(offset++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(parseUnicodeEscape());
                    default -> throw error("JSON escape is invalid");
                }
            }
            throw error("JSON string is unterminated");
        }

        private char parseUnicodeEscape() throws IOException {
            if (offset + 4 > input.length()) throw error("JSON Unicode escape is incomplete");
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(input.charAt(offset++), 16);
                if (digit < 0) throw error("JSON Unicode escape is invalid");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Object parseLiteral(String literal, Object value) throws IOException {
            if (!input.startsWith(literal, offset)) throw error("JSON literal is invalid");
            offset += literal.length();
            return value;
        }

        private Number parseNumber() throws IOException {
            int start = offset;
            if (consume('-') && offset >= input.length()) throw error("JSON number is incomplete");
            if (consume('0')) {
                if (offset < input.length() && isAsciiDigit(input.charAt(offset))) {
                    throw error("JSON number has a leading zero");
                }
            } else {
                if (offset >= input.length() || input.charAt(offset) < '1' || input.charAt(offset) > '9') {
                    throw error("JSON value is invalid");
                }
                while (offset < input.length() && isAsciiDigit(input.charAt(offset))) offset += 1;
            }
            boolean decimal = false;
            if (consume('.')) {
                decimal = true;
                if (offset >= input.length() || !isAsciiDigit(input.charAt(offset))) {
                    throw error("JSON fraction is incomplete");
                }
                while (offset < input.length() && isAsciiDigit(input.charAt(offset))) offset += 1;
            }
            if (offset < input.length() && (input.charAt(offset) == 'e' || input.charAt(offset) == 'E')) {
                throw error("administrator contract numbers must not use an exponent");
            }
            try {
                String encoded = input.substring(start, offset);
                return decimal ? new BigDecimal(encoded) : Long.parseLong(encoded);
            } catch (NumberFormatException error) {
                throw error("JSON number is invalid");
            }
        }

        private static boolean isAsciiDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private void skipWhitespace() {
            while (offset < input.length()) {
                char current = input.charAt(offset);
                if (current != ' ' && current != '\t' && current != '\n' && current != '\r') return;
                offset += 1;
            }
        }

        private boolean consume(char expected) {
            if (offset < input.length() && input.charAt(offset) == expected) {
                offset += 1;
                return true;
            }
            return false;
        }

        private void expect(char expected) throws IOException {
            if (!consume(expected)) throw error("expected '" + expected + "'");
        }

        private IOException error(String message) {
            return new IOException(message + " at offset " + offset);
        }
    }

    private static void requireWellFormedUnicode(String value) throws IOException {
        try {
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException error) {
            throw new IOException("JSON text contains invalid Unicode", error);
        }
    }
}
