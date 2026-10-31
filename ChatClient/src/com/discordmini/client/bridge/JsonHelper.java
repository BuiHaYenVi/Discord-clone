package com.discordmini.client.bridge;

import java.util.*;

/**
 * Tiện ích tuần tự hóa và giải tuần tự hóa JSON chuẩn mực, an toàn và độc lập,
 * không sử dụng regex string split đơn giản, hỗ trợ đầy đủ tiếng Việt, emoji,
 * ký tự thoát đặc biệt và cấu trúc lồng nhau.
 */
public final class JsonHelper {

    private JsonHelper() {}

    public static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) {
            return "\"" + escapeString(s) + "\"";
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        if (obj instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeString(String.valueOf(entry.getKey()))).append("\":");
                sb.append(toJson(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof Collection<?> coll) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : coll) {
                if (!first) sb.append(",");
                sb.append(toJson(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        if (obj instanceof Object[] arr) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : arr) {
                if (!first) sb.append(",");
                sb.append(toJson(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeString(obj.toString()) + "\"";
    }

    public static String escapeString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object result = parse(json);
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    public static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        Parser parser = new Parser(json.trim());
        return parser.parseValue();
    }

    private static class Parser {
        private final String text;
        private int pos = 0;

        Parser(String text) {
            this.text = text;
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) return null;

            char c = text.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            if (c == '-' || Character.isDigit(c)) return parseNumber();

            throw new IllegalArgumentException("Ký tự không hợp lệ tại vị trí " + pos + ": " + c);
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // bỏ '{'
            skipWhitespace();

            if (pos < text.length() && text.charAt(pos) == '}') {
                pos++;
                return map;
            }

            while (pos < text.length()) {
                skipWhitespace();
                if (pos >= text.length() || text.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Khóa của object phải là chuỗi tại vị trí " + pos);
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= text.length() || text.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Kỳ vọng dấu ':' tại vị trí " + pos);
                }
                pos++; // bỏ ':'
                Object value = parseValue();
                map.put(key, value);

                skipWhitespace();
                if (pos >= text.length()) break;
                char next = text.charAt(pos);
                if (next == '}') {
                    pos++;
                    return map;
                } else if (next == ',') {
                    pos++;
                } else {
                    throw new IllegalArgumentException("Kỳ vọng dấu ',' hoặc '}' tại vị trí " + pos);
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // bỏ '['
            skipWhitespace();

            if (pos < text.length() && text.charAt(pos) == ']') {
                pos++;
                return list;
            }

            while (pos < text.length()) {
                list.add(parseValue());
                skipWhitespace();
                if (pos >= text.length()) break;
                char next = text.charAt(pos);
                if (next == ']') {
                    pos++;
                    return list;
                } else if (next == ',') {
                    pos++;
                } else {
                    throw new IllegalArgumentException("Kỳ vọng dấu ',' hoặc ']' tại vị trí " + pos);
                }
            }
            return list;
        }

        private String parseString() {
            pos++; // bỏ dấu mở '"'
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                } else if (c == '\\') {
                    if (pos >= text.length()) break;
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 <= text.length()) {
                                String hex = text.substring(pos, pos + 4);
                                sb.append((char) Integer.parseInt(hex, 16));
                                pos += 4;
                            }
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Boolean parseBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            } else if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Boolean không hợp lệ tại vị trí " + pos);
        }

        private Object parseNull() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Null không hợp lệ tại vị trí " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            if (text.charAt(pos) == '-') pos++;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.' || text.charAt(pos) == 'e' || text.charAt(pos) == 'E' || text.charAt(pos) == '+')) {
                pos++;
            }
            String numStr = text.substring(start, pos);
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                return Double.parseDouble(numStr);
            }
            try {
                return Integer.parseInt(numStr);
            } catch (NumberFormatException e) {
                return Long.parseLong(numStr);
            }
        }
    }
}
