package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exact UTF-8 canonical encodings used by the administrator device-proof contract. */
public final class AdminCanonicalEncoding {
    public static final class QueryParameter {
        private final String name;
        private final String value;

        public QueryParameter(String name, String value) {
            if (name == null || value == null) {
                throw new IllegalArgumentException("query names and values must be present");
            }
            requireWellFormedUnicode(name, "query name");
            requireWellFormedUnicode(value, "query value");
            this.name = name;
            this.value = value;
        }

        public String name() { return name; }
        public String value() { return value; }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final char[] UPPER_HEX = "0123456789ABCDEF".toCharArray();

    private AdminCanonicalEncoding() {}

    public static byte[] canonicalJsonBytes(Map<String, ?> values) {
        return canonicalJson(values).getBytes(StandardCharsets.UTF_8);
    }

    public static String canonicalJson(Map<String, ?> values) {
        if (values == null) throw new IllegalArgumentException("JSON object is required");
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || sorted.put(key, entry.getValue()) != null) {
                throw new IllegalArgumentException("JSON keys must be unique and present");
            }
            requireWellFormedUnicode(key, "JSON key");
        }
        StringBuilder encoded = new StringBuilder();
        encoded.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) encoded.append(',');
            first = false;
            appendJsonString(encoded, entry.getKey());
            encoded.append(':');
            appendJsonScalar(encoded, entry.getValue());
        }
        return encoded.append('}').toString();
    }

    public static String canonicalQuery(List<QueryParameter> parameters) {
        if (parameters == null) throw new IllegalArgumentException("query parameters are required");
        List<EncodedQueryParameter> encoded = new ArrayList<>(parameters.size());
        for (QueryParameter parameter : parameters) {
            if (parameter == null) throw new IllegalArgumentException("query parameter is required");
            encoded.add(new EncodedQueryParameter(
                percentEncode(parameter.name),
                percentEncode(parameter.value)
            ));
        }
        encoded.sort(Comparator.comparing((EncodedQueryParameter item) -> item.name)
            .thenComparing(item -> item.value));
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < encoded.size(); index++) {
            if (index > 0) result.append('&');
            EncodedQueryParameter item = encoded.get(index);
            result.append(item.name).append('=').append(item.value);
        }
        return result.toString();
    }

    /** Parses a raw query without treating '+' as a space and rejects invalid percent UTF-8. */
    public static List<QueryParameter> parseRawQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return AdminJava8Collections.list();
        List<QueryParameter> result = new ArrayList<>();
        for (String pair : rawQuery.split("&", -1)) {
            int delimiter = pair.indexOf('=');
            String name = delimiter < 0 ? pair : pair.substring(0, delimiter);
            String value = delimiter < 0 ? "" : pair.substring(delimiter + 1);
            result.add(new QueryParameter(percentDecode(name), percentDecode(value)));
        }
        return AdminJava8Collections.copyList(result);
    }

    public static String sha256Hex(byte[] value) {
        if (value == null) throw new IllegalArgumentException("hash input is required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            char[] output = new char[digest.length * 2];
            for (int index = 0; index < digest.length; index++) {
                int current = digest[index] & 0xff;
                output[index * 2] = HEX[current >>> 4];
                output[index * 2 + 1] = HEX[current & 0x0f];
            }
            return new String(output);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String percentEncode(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(utf8.length);
        for (byte currentByte : utf8) {
            int current = currentByte & 0xff;
            if ((current >= 'A' && current <= 'Z')
                || (current >= 'a' && current <= 'z')
                || (current >= '0' && current <= '9')
                || current == '-' || current == '.' || current == '_' || current == '~') {
                result.append((char) current);
            } else {
                result.append('%')
                    .append(UPPER_HEX[current >>> 4])
                    .append(UPPER_HEX[current & 0x0f]);
            }
        }
        return result.toString();
    }

    private static String percentDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()) throw new IllegalArgumentException("malformed percent encoding");
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("malformed percent encoding");
                bytes.write((high << 4) | low);
                index += 3;
                continue;
            }
            int codePoint = value.codePointAt(index);
            if (Character.isSurrogate(current) && Character.charCount(codePoint) == 1) {
                throw new IllegalArgumentException("query contains invalid Unicode");
            }
            byte[] literal = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
            bytes.write(literal, 0, literal.length);
            index += Character.charCount(codePoint);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("query is not valid UTF-8", error);
        }
    }

    private static void appendJsonScalar(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            requireWellFormedUnicode(text, "JSON string");
            appendJsonString(output, text);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            output.append(value);
        } else {
            throw new IllegalArgumentException("canonical JSON supports only string, null, boolean, and integer scalars");
        }
    }

    private static void appendJsonString(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        output.append("\\u00")
                            .append(HEX[(codePoint >>> 4) & 0x0f])
                            .append(HEX[codePoint & 0x0f]);
                    } else {
                        output.appendCodePoint(codePoint);
                    }
                }
            }
        }
        output.append('"');
    }

    private static void requireWellFormedUnicode(String value, String label) {
        try {
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(label + " contains invalid Unicode", error);
        }
    }

    private static final class EncodedQueryParameter {
        private final String name;
        private final String value;

        private EncodedQueryParameter(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
