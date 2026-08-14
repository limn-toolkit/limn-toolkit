package limn.render3d.gltf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny recursive-descent JSON parser (no dependencies) for reading glTF. Objects
 * become {@code Map<String,Object>}, arrays {@code List<Object>}, numbers
 * {@code Double}, plus {@code String}/{@code Boolean}/{@code null}. Enough for
 * glTF 2.0; not a general-purpose validator.
 */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json json = new Json(text);
        json.ws();
        Object value = json.value();
        json.ws();
        return value;
    }

    private Object value() {
        char c = peek();
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't', 'f' -> bool();
            case 'n' -> nul();
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        expect('{');
        ws();
        if (peek() == '}') {
            i++;
            return map;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            map.put(key, value());
            ws();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw err("',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        expect('[');
        ws();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw err("',' or ']'");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("valid escape");
                }
            } else {
                b.append(c);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return Double.parseDouble(s.substring(start, i));
    }

    private Boolean bool() {
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        throw err("boolean");
    }

    private Object nul() {
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        throw err("null");
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        return s.charAt(i);
    }

    private char next() {
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (s.charAt(i++) != c) {
            throw err("'" + c + "'");
        }
    }

    private IllegalArgumentException err(String expected) {
        return new IllegalArgumentException("JSON: expected " + expected + " at index " + i);
    }
}
