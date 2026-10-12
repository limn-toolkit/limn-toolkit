package limn.render3d.gltf;

import limn.io.MalformedContentException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny recursive-descent JSON parser (no dependencies) for reading glTF. Objects
 * become {@code Map<String,Object>}, arrays {@code List<Object>}, numbers
 * {@code Double}, plus {@code String}/{@code Boolean}/{@code null}. Enough for
 * glTF 2.0; not a general-purpose validator.
 *
 * <p>Text that is not JSON is refused with a {@link MalformedContentException} saying what was
 * expected and at which index, and that includes text that ends in the middle of a value: every
 * read of the next character checks for the end first, because the document may have come from
 * anywhere and a file cut short is the commonest way for one to be broken.
 */
final class Json {

    /**
     * Deepest nesting accepted. A glTF document is a handful of levels deep; the parser recurses
     * per level, so without a limit a file of nothing but {@code [} ends the thread it is parsed
     * on with a StackOverflowError, which on the synchronous load path is the UI thread.
     */
    static final int MAX_DEPTH = 64;

    private final String s;
    private int i;
    private int depth;

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
        enter();
        ws();
        if (peek() == '}') {
            i++;
            depth--;
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
                depth--;
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
        enter();
        ws();
        if (peek() == ']') {
            i++;
            depth--;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = next();
            if (c == ']') {
                depth--;
                return list;
            }
            if (c != ',') {
                throw err("',' or ']'");
            }
        }
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new MalformedContentException("glTF", 
                    "JSON nested deeper than " + MAX_DEPTH + " levels at " + i);
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return b.toString();
            }
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> b.append(hexEscape());
                    default -> throw err("valid escape");
                }
            } else {
                b.append(c);
            }
        }
    }

    /** The four hex digits of a string's {@code u} escape, as the character they name. */
    private char hexEscape() {
        int start = i;
        int code = 0;
        for (int k = 0; k < 4; k++) {
            int digit = Character.digit(next(), 16);
            if (digit < 0) {
                i = start;
                throw err("four hex digits");
            }
            code = code * 16 + digit;
        }
        return (char) code;
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw err("a value");
        }
        String text = s.substring(start, i);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException notANumber) {
            // Refused like everything else here, saying where: what Double.parseDouble throws is
            // already an IllegalArgumentException, but it says only "For input string", and not
            // which of a document's numbers it was.
            throw new MalformedContentException("glTF", "JSON: malformed number \"" + text + "\" at index "
                    + start);
        }
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
        if (i >= s.length()) {
            throw endOfText();
        }
        return s.charAt(i);
    }

    private char next() {
        if (i >= s.length()) {
            throw endOfText();
        }
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw err("'" + c + "'");
        }
    }

    private MalformedContentException endOfText() {
        return new MalformedContentException("glTF", "JSON: the text ends at index " + i
                + " in the middle of a value");
    }

    private MalformedContentException err(String expected) {
        return new MalformedContentException("glTF", "JSON: expected " + expected + " at index " + i);
    }
}
