package gsb.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("Unexpected trailing JSON at " + parser.position);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        return (List<Object>) value;
    }

    public static String string(Map<String, Object> map, String key) {
        Object value = required(map, key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return text;
    }

    public static String optionalString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return text;
    }

    public static boolean optionalBool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return result;
    }

    public static Object required(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value;
    }

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        write(builder, value, false, 0);
        return builder.toString();
    }

    public static String writePretty(Object value) {
        StringBuilder builder = new StringBuilder();
        write(builder, value, true, 0);
        return builder.toString();
    }

    public static String canonical(Object value) {
        StringBuilder builder = new StringBuilder();
        writeCanonical(builder, normalize(value));
        return builder.toString();
    }

    private static void write(StringBuilder out, Object value, boolean pretty, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else if (value instanceof String text) {
            writeString(out, text);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                if (pretty) out.append('\n').append("  ".repeat(depth + 1));
                writeString(out, String.valueOf(entry.getKey()));
                out.append(pretty ? ": " : ":");
                write(out, entry.getValue(), pretty, depth + 1);
                first = false;
            }
            if (pretty) out.append('\n').append("  ".repeat(depth));
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            writeArray(out, items, pretty, depth);
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeArray(StringBuilder out, Iterable<?> items, boolean pretty, int depth) {
        boolean first = true;
        out.append('[');
        for (Object item : items) {
            if (!first) out.append(',');
            if (pretty) out.append('\n').append("  ".repeat(depth + 1));
            write(out, item, pretty, depth + 1);
            first = false;
        }
        if (!first && pretty) out.append('\n').append("  ".repeat(depth));
        out.append(']');
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> list = new ArrayList<>();
            for (Object item : items) list.add(normalize(item));
            return list;
        }
        return value;
    }

    private static void writeCanonical(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else if (value instanceof String text) {
            writeString(out, text);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeCanonical(out, entry.getValue());
                first = false;
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) out.append(',');
                writeCanonical(out, item);
                first = false;
            }
            out.append(']');
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    public static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    public static List<Object> array() {
        return new ArrayList<>();
    }

    private static final class Parser {
        private final String text;
        private int position;

        private Parser(String text) {
            this.text = text;
        }

        private boolean end() {
            return position >= text.length();
        }

        private void skipWhitespace() {
            while (!end() && Character.isWhitespace(text.charAt(position))) position++;
        }

        private Object readValue() {
            skipWhitespace();
            if (end()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = text.charAt(position);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            return readNumber();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (consume('}')) return map;
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue());
                skipWhitespace();
                if (consume('}')) return map;
                expect(',');
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (consume(']')) return list;
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (consume(']')) return list;
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (end()) throw new IllegalArgumentException("Unterminated string");
                char c = text.charAt(position++);
                if (c == '"') return builder.toString();
                if (c == '\\') {
                    if (end()) throw new IllegalArgumentException("Unterminated escape");
                    char escaped = text.charAt(position++);
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            if (position + 4 > text.length()) throw new IllegalArgumentException("Bad unicode escape");
                            builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> throw new IllegalArgumentException("Bad escape: " + escaped);
                    }
                } else {
                    builder.append(c);
                }
            }
        }

        private Boolean readBoolean() {
            if (text.startsWith("true", position)) {
                position += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", position)) {
                position += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid JSON literal at " + position);
        }

        private Object readNull() {
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid JSON literal at " + position);
        }

        private Number readNumber() {
            int start = position;
            if (consume('-')) {}
            while (!end() && Character.isDigit(text.charAt(position))) position++;
            boolean decimal = false;
            if (!end() && text.charAt(position) == '.') {
                decimal = true;
                position++;
                while (!end() && Character.isDigit(text.charAt(position))) position++;
            }
            if (!end() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                decimal = true;
                position++;
                if (!end() && (text.charAt(position) == '+' || text.charAt(position) == '-')) position++;
                while (!end() && Character.isDigit(text.charAt(position))) position++;
            }
            String token = text.substring(start, position);
            if (token.isEmpty() || "-".equals(token)) throw new IllegalArgumentException("Invalid number");
            return decimal ? Double.parseDouble(token) : Long.parseLong(token);
        }

        private boolean consume(char expected) {
            if (!end() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (end() || text.charAt(position) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at " + position);
            }
            position++;
        }
    }
}
