package local.gsb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() {
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(out, text);
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeValue(out, entry.getValue());
                first = false;
            }
            out.append('}');
        } else if (value instanceof Iterable<?> values) {
            out.append('[');
            boolean first = true;
            for (Object item : values) {
                if (!first) out.append(',');
                writeValue(out, item);
                first = false;
            }
            out.append(']');
        } else {
            writeString(out, value.toString());
        }
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        out.append('"');
    }

    static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.end()) throw parser.error("trailing characters");
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("expected object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("expected array");
        return (List<Object>) value;
    }

    static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (!(value instanceof String)) throw new IllegalArgumentException(key + " must be a string");
        return (String) value;
    }

    static String requireStr(Map<String, Object> map, String key) {
        String value = str(map, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    static Long integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    static Long requireInteger(Map<String, Object> map, String key) {
        Long value = integer(map, key);
        if (value == null) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    static Boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Boolean result) return result;
        throw new IllegalArgumentException(key + " must be a boolean");
    }

    private static final class Parser {
        private final String text;
        private int pos;

        private Parser(String text) {
            this.text = text;
        }

        private Object readValue() {
            skipWhitespace();
            if (end()) throw error("unexpected end");
            char ch = text.charAt(pos);
            if (ch == '{') return readObject();
            if (ch == '[') return readArray();
            if (ch == '"') return readString();
            if (ch == 't' || ch == 'f') return readBoolean();
            if (ch == 'n') return readNull();
            return readNumber();
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (take('}')) return result;
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (take('}')) return result;
                expect(',');
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (take(']')) return result;
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!end()) {
                char ch = text.charAt(pos++);
                if (ch == '"') return result.toString();
                if (ch != '\\') {
                    result.append(ch);
                    continue;
                }
                if (end()) throw error("bad escape");
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw error("bad unicode escape");
                        result.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("bad escape");
                }
            }
            throw error("unterminated string");
        }

        private Boolean readBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw error("bad literal");
        }

        private Object readNull() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw error("bad literal");
        }

        private Number readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            readDigits();
            boolean floating = false;
            if (!end() && peek() == '.') {
                floating = true;
                pos++;
                readDigits();
            }
            if (!end() && (peek() == 'e' || peek() == 'E')) {
                floating = true;
                pos++;
                if (!end() && (peek() == '+' || peek() == '-')) pos++;
                readDigits();
            }
            String token = text.substring(start, pos);
            if (floating) return Double.parseDouble(token);
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException ex) {
                return new java.math.BigInteger(token);
            }
        }

        private void readDigits() {
            int start = pos;
            while (!end() && Character.isDigit(peek())) pos++;
            if (start == pos) throw error("expected digits");
        }

        private char peek() {
            return text.charAt(pos);
        }

        private boolean end() {
            return pos >= text.length();
        }

        private void skipWhitespace() {
            while (!end() && Character.isWhitespace(peek())) pos++;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (end() || text.charAt(pos) != expected) throw error("expected " + expected);
            pos++;
        }

        private boolean take(char expected) {
            if (!end() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid JSON at " + pos + ": " + message);
        }
    }
}
