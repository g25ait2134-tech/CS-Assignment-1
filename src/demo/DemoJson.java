package demo;

import java.util.LinkedHashMap;
import java.util.Map;

final class DemoJson {
    private DemoJson() {
    }

    static Map<String, String> parseFlat(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.trim().isEmpty()) {
            return out;
        }
        String s = body.trim();
        if (s.startsWith("{")) {
            s = s.substring(1);
        }
        if (s.endsWith("}")) {
            s = s.substring(0, s.length() - 1);
        }
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',')) {
                i++;
            }
            if (i >= s.length()) {
                break;
            }
            ParseResult key = readValue(s, i);
            i = key.next;
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ':')) {
                i++;
            }
            ParseResult value = readValue(s, i);
            i = value.next;
            out.put(key.value, value.value);
            while (i < s.length() && s.charAt(i) != ',') {
                i++;
            }
        }
        return out;
    }

    static String object(Object... pairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(String.valueOf(pairs[i]))).append('"').append(':');
            Object value = pairs[i + 1];
            if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static ParseResult readValue(String s, int start) {
        int i = start;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        if (i < s.length() && s.charAt(i) == '"') {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length()) {
                    char next = s.charAt(i++);
                    sb.append(next == 'n' ? '\n' : next == 'r' ? '\r' : next);
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return new ParseResult(sb.toString(), i);
        }
        int end = i;
        while (end < s.length() && s.charAt(end) != ',' && s.charAt(end) != '}') {
            end++;
        }
        return new ParseResult(s.substring(i, end).trim(), end);
    }

    private record ParseResult(String value, int next) {
    }
}
