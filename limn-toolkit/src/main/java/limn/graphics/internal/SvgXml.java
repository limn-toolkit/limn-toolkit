package limn.graphics.internal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import limn.io.MalformedContentException;
import org.jspecify.annotations.Nullable;

/**
 * The XML of an SVG, read by the toolkit before any renderer sees it: a small parser written for
 * this, so that nothing in the JDK's XML stack, with its DTDs, external entities and resolvers, is
 * between a file and the screen, and so the toolkit needs no module beyond {@code java.base}.
 *
 * <p>What it reads: elements, attributes, text, CDATA sections; comments and processing
 * instructions, which it drops; character references and the five predefined entities; and the
 * one kind of DTD real files carry, an internal subset declaring <b>internal general entities</b>,
 * which illustration programs write to name their namespaces. Those are expanded once: an entity's
 * value may not refer to another, so no expansion can grow by more than its own text, and every
 * expansion together is held under a ceiling. An external DTD is never read, a parameter entity or
 * an external entity is refused, and so is everything else a DTD may say.
 *
 * <p>Text in UTF-8, UTF-16 with a byte-order mark, ISO-8859-1, US-ASCII or windows-1252, as its
 * declaration says. Everything refused leaves as {@link MalformedContentException}.
 */
public final class SvgXml {

    /** The largest SVG read: 16 MiB, larger than any icon or illustration by far. */
    public static final int MAX_BYTES = 16 << 20;
    /** The most elements one file may hold. */
    public static final int MAX_ELEMENTS = 250_000;
    /** The deepest nesting. */
    public static final int MAX_DEPTH = 256;
    /** The most attributes one element may carry. */
    public static final int MAX_ATTRIBUTES = 1024;
    /** The most entities a DTD may declare. */
    public static final int MAX_ENTITIES = 256;
    /** The most characters every entity expansion together may add. */
    public static final int MAX_EXPANSION = 1 << 20;

    /** An element: its name as written, prefix and all, its attributes in order and its children. */
    public static final class Element {
        private final String name;
        private final List<String> names = new ArrayList<>(4);
        private final List<String> values = new ArrayList<>(4);
        private final List<Object> children = new ArrayList<>(2);
        private @Nullable Element parent;

        /** @param name the element's name as written */
        public Element(String name) {
            this.name = name;
        }

        /** @return the name as written, such as {@code svg} or {@code svg:rect} */
        public String name() {
            return name;
        }

        /** @return the name without its prefix */
        public String localName() {
            int colon = name.indexOf(':');
            return colon < 0 ? name : name.substring(colon + 1);
        }

        /** @return the element this one is a child of, or null for the root */
        public @Nullable Element parent() {
            return parent;
        }

        /** @return how many attributes */
        public int attributeCount() {
            return names.size();
        }

        /** @return the name of attribute {@code index} */
        public String attributeName(int index) {
            return names.get(index);
        }

        /** @return the value of attribute {@code index} */
        public String attributeValue(int index) {
            return values.get(index);
        }

        /** @return the value of the attribute of that name, or null */
        public @Nullable String attribute(String attribute) {
            int at = names.indexOf(attribute);
            return at < 0 ? null : values.get(at);
        }

        /** Sets an attribute, in place when it is there and last when it is not. */
        public void setAttribute(String attribute, String value) {
            int at = names.indexOf(attribute);
            if (at < 0) {
                names.add(attribute);
                values.add(value);
            } else {
                values.set(at, value);
            }
        }

        /** Takes an attribute away; nothing when it is not there. */
        public void removeAttribute(String attribute) {
            int at = names.indexOf(attribute);
            if (at >= 0) {
                names.remove(at);
                values.remove(at);
            }
        }

        /** Takes away attribute {@code index}. */
        public void removeAttribute(int index) {
            names.remove(index);
            values.remove(index);
        }

        /** @return the children, each an {@link Element} or a {@link String} of text; mutable */
        public List<Object> children() {
            return children;
        }

        /** @return the children that are elements */
        public List<Element> elements() {
            List<Element> out = new ArrayList<>(children.size());
            for (Object child : children) {
                if (child instanceof Element element) {
                    out.add(element);
                }
            }
            return out;
        }

        /** @return the text the element holds directly, its text children joined */
        public String text() {
            StringBuilder text = new StringBuilder();
            for (Object child : children) {
                if (child instanceof String s) {
                    text.append(s);
                }
            }
            return text.toString();
        }

        /** Adds a child element last. */
        public void add(Element child) {
            child.parent = this;
            children.add(child);
        }

        /** Writes this element and what it holds as XML. */
        public void write(StringBuilder out) {
            out.append('<').append(name);
            for (int i = 0; i < names.size(); i++) {
                out.append(' ').append(names.get(i)).append("=\"");
                escape(values.get(i), out, true);
                out.append('"');
            }
            if (children.isEmpty()) {
                out.append("/>");
                return;
            }
            out.append('>');
            boolean style = localName().equals("style");
            for (Object child : children) {
                if (child instanceof Element element) {
                    element.write(out);
                } else if (style && !((String) child).contains("]]>")) {
                    // A style sheet as it was written: CSS says '>' and '&' of its own.
                    out.append("<![CDATA[").append((String) child).append("]]>");
                } else {
                    escape((String) child, out, false);
                }
            }
            out.append("</").append(name).append('>');
        }
    }

    private SvgXml() {
    }

    /**
     * Reads a document.
     *
     * @param bytes the file
     * @return its root element
     * @throws MalformedContentException when it is no well-formed XML this reads, or passes a ceiling
     */
    public static Element parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw refused("an SVG of " + bytes.length + " bytes is above the " + MAX_BYTES + " read");
        }
        return new Parser(decode(bytes)).document();
    }

    /** @return the document written out as XML, UTF-8 */
    public static byte[] write(Element root) {
        StringBuilder out = new StringBuilder(1024);
        root.write(out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static void escape(String text, StringBuilder out, boolean attribute) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append(attribute ? "&gt;" : ">");
                case '"' -> out.append(attribute ? "&quot;" : "\"");
                default -> out.append(c);
            }
        }
    }

    static MalformedContentException refused(String why) {
        return new MalformedContentException("SVG", why);
    }

    // ----------------------------------------------------------------------------- text

    private static String decode(byte[] bytes) {
        int start = 0;
        Charset charset = StandardCharsets.UTF_8;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            start = 3;
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            start = 2;
            charset = StandardCharsets.UTF_16BE;
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            start = 2;
            charset = StandardCharsets.UTF_16LE;
        } else {
            charset = declared(bytes);
        }
        try {
            CharBuffer text = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start));
            return text.toString();
        } catch (CharacterCodingException e) {
            throw new MalformedContentException("SVG", "the SVG is not " + charset.name() + " text", e);
        }
    }

    /** The encoding the XML declaration names, read in ASCII; UTF-8 when there is none. */
    private static Charset declared(byte[] bytes) {
        int end = Math.min(bytes.length, 200);
        String head = new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
        if (!head.startsWith("<?xml")) {
            return StandardCharsets.UTF_8;
        }
        int close = head.indexOf("?>");
        int at = head.indexOf("encoding");
        if (at < 0 || close >= 0 && at > close) {
            return StandardCharsets.UTF_8;
        }
        int quote = at + 8;
        while (quote < head.length() && head.charAt(quote) != '"' && head.charAt(quote) != '\'') {
            quote++;
        }
        int endQuote = quote + 1 < head.length() ? head.indexOf(head.charAt(quote), quote + 1) : -1;
        if (endQuote < 0) {
            throw refused("the XML declaration's encoding is not closed");
        }
        String name = head.substring(quote + 1, endQuote).trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "utf-8", "utf8" -> StandardCharsets.UTF_8;
            case "iso-8859-1", "latin1", "iso8859-1" -> StandardCharsets.ISO_8859_1;
            case "us-ascii", "ascii" -> StandardCharsets.US_ASCII;
            case "windows-1252", "cp1252" -> Charset.forName("windows-1252");
            default -> throw refused("an SVG in " + name + " is not read: UTF-8, UTF-16, ISO-8859-1,"
                    + " US-ASCII and windows-1252 are");
        };
    }

    // ----------------------------------------------------------------------------- the parser

    private static final class Parser {

        private final String text;
        private int at;
        private int elements;
        private int expanded;
        private final Map<String, String> entities = new HashMap<>();

        Parser(String text) {
            this.text = text;
        }

        Element document() {
            prolog();
            if (!peek("<") || peek("</")) {
                throw refused("no root element");
            }
            Element root = element(1);
            misc();
            if (at < text.length()) {
                throw refused("text after the root element at character " + at);
            }
            return root;
        }

        /** The declaration, comments, processing instructions and the DOCTYPE before the root. */
        private void prolog() {
            while (true) {
                skipSpace();
                if (peek("<?")) {
                    skipPast("?>");
                } else if (peek("<!--")) {
                    skipPast("-->");
                } else if (peek("<!DOCTYPE")) {
                    doctype();
                } else {
                    return;
                }
            }
        }

        private void misc() {
            while (true) {
                skipSpace();
                if (peek("<?")) {
                    skipPast("?>");
                } else if (peek("<!--")) {
                    skipPast("-->");
                } else {
                    return;
                }
            }
        }

        private void doctype() {
            at += "<!DOCTYPE".length();
            // The name and the external identifier, which is never read: up to '[' or '>'.
            while (at < text.length() && text.charAt(at) != '[' && text.charAt(at) != '>') {
                char c = text.charAt(at);
                if (c == '"' || c == '\'') {
                    int close = text.indexOf(c, at + 1);
                    if (close < 0) {
                        throw refused("the DOCTYPE's quoted text is not closed");
                    }
                    at = close;
                }
                at++;
            }
            if (at < text.length() && text.charAt(at) == '[') {
                at++;
                internalSubset();
            }
            skipSpace();
            expect(">");
        }

        private void internalSubset() {
            while (true) {
                skipSpace();
                if (peek("]")) {
                    at++;
                    return;
                } else if (peek("<!--")) {
                    skipPast("-->");
                } else if (peek("<?")) {
                    skipPast("?>");
                } else if (peek("<!ENTITY")) {
                    entity();
                } else if (at >= text.length()) {
                    throw refused("the DOCTYPE's internal subset is not closed");
                } else {
                    throw refused("a DOCTYPE may declare internal entities and nothing else");
                }
            }
        }

        private void entity() {
            at += "<!ENTITY".length();
            skipSpace();
            if (peek("%")) {
                throw refused("parameter entities are not read");
            }
            String name = name();
            skipSpace();
            if (!peek("\"") && !peek("'")) {
                throw refused("the entity " + name + " is external, and none is read");
            }
            char quote = text.charAt(at++);
            int close = text.indexOf(quote, at);
            if (close < 0) {
                throw refused("the entity " + name + " is not closed");
            }
            String value = text.substring(at, close);
            at = close + 1;
            if (!onlyCharacterReferences(value)) {
                throw refused("the entity " + name + " refers to another, and none may");
            }
            if (value.indexOf('%') >= 0) {
                throw refused("the entity " + name + " refers to a parameter entity");
            }
            skipSpace();
            expect(">");
            if (entities.size() >= MAX_ENTITIES) {
                throw refused("more than " + MAX_ENTITIES + " entities");
            }
            entities.putIfAbsent(name, value);
        }

        /** Whether every {@code &} of an entity's value begins a character reference or a predefined entity. */
        private static boolean onlyCharacterReferences(String value) {
            for (int i = value.indexOf('&'); i >= 0; i = value.indexOf('&', i + 1)) {
                int semi = value.indexOf(';', i);
                if (semi < 0 || semi - i > 12) {
                    return false;
                }
                String ref = value.substring(i + 1, semi);
                boolean numeric = ref.matches("#[0-9]{1,8}|#x[0-9a-fA-F]{1,6}");
                boolean predefined = ref.equals("lt") || ref.equals("gt") || ref.equals("amp")
                        || ref.equals("quot") || ref.equals("apos");
                if (!numeric && !predefined) {
                    return false;
                }
            }
            return true;
        }

        private Element element(int depth) {
            if (depth > MAX_DEPTH) {
                throw refused("elements nested deeper than " + MAX_DEPTH);
            }
            if (++elements > MAX_ELEMENTS) {
                throw refused("more than " + MAX_ELEMENTS + " elements");
            }
            expect("<");
            Element element = new Element(name());
            while (true) {
                boolean space = skipSpace();
                if (peek("/>")) {
                    at += 2;
                    return element;
                }
                if (peek(">")) {
                    at++;
                    break;
                }
                if (!space) {
                    throw refused("attributes run together at character " + at);
                }
                String attribute = name();
                skipSpace();
                expect("=");
                skipSpace();
                String value = attributeValue();
                if (element.attribute(attribute) != null) {
                    throw refused("the attribute " + attribute + " is given twice");
                }
                if (element.attributeCount() >= MAX_ATTRIBUTES) {
                    throw refused("an element with more than " + MAX_ATTRIBUTES + " attributes");
                }
                element.names.add(attribute);
                element.values.add(value);
            }
            content(element, depth);
            expect("</");
            String closing = name();
            if (!closing.equals(element.name)) {
                throw refused("<" + element.name + "> is closed by </" + closing + ">");
            }
            skipSpace();
            expect(">");
            return element;
        }

        private void content(Element element, int depth) {
            StringBuilder pending = new StringBuilder();
            while (true) {
                if (at >= text.length()) {
                    throw refused("<" + element.name + "> is not closed");
                }
                char c = text.charAt(at);
                if (c == '<') {
                    if (peek("</")) {
                        flush(element, pending);
                        return;
                    } else if (peek("<!--")) {
                        skipPast("-->");
                    } else if (peek("<![CDATA[")) {
                        int close = text.indexOf("]]>", at);
                        if (close < 0) {
                            throw refused("a CDATA section is not closed");
                        }
                        pending.append(text, at + 9, close);
                        at = close + 3;
                    } else if (peek("<?")) {
                        skipPast("?>");
                    } else {
                        flush(element, pending);
                        element.add(element(depth + 1));
                    }
                } else if (c == '&') {
                    String entity = entityName();
                    String value = entity == null ? null : entities.get(entity);
                    if (value != null && value.indexOf('<') >= 0) {
                        // Markup in an entity: read as if written here, once; it refers to no
                        // other entity, so this is as deep as an expansion goes.
                        flush(element, pending);
                        grow(value.length());
                        Parser inner = new Parser(value);
                        inner.elements = elements;
                        Element holder = new Element(element.name);
                        inner.contentUntilEnd(holder, depth);
                        elements = inner.elements;
                        for (Object child : holder.children) {
                            if (child instanceof Element e) {
                                element.add(e);
                            } else {
                                element.children.add(child);
                            }
                        }
                    } else {
                        pending.append(reference(entity));
                    }
                } else {
                    pending.append(c);
                    at++;
                }
            }
        }

        /** Content up to the end of the text: an entity's markup. */
        private void contentUntilEnd(Element element, int depth) {
            StringBuilder pending = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c == '<') {
                    if (peek("<!--")) {
                        skipPast("-->");
                    } else if (peek("</")) {
                        throw refused("an entity's markup closes an element it did not open");
                    } else {
                        flush(element, pending);
                        element.add(element(depth + 1));
                    }
                } else if (c == '&') {
                    pending.append(reference(entityName()));
                } else {
                    pending.append(c);
                    at++;
                }
            }
            flush(element, pending);
        }

        private static void flush(Element element, StringBuilder pending) {
            if (pending.length() > 0) {
                element.children.add(pending.toString());
                pending.setLength(0);
            }
        }

        private String attributeValue() {
            if (!peek("\"") && !peek("'")) {
                throw refused("an attribute's value is not quoted at character " + at);
            }
            char quote = text.charAt(at++);
            StringBuilder value = new StringBuilder();
            while (true) {
                if (at >= text.length()) {
                    throw refused("an attribute's value is not closed");
                }
                char c = text.charAt(at);
                if (c == quote) {
                    at++;
                    return value.toString();
                } else if (c == '<') {
                    throw refused("'<' in an attribute's value");
                } else if (c == '&') {
                    value.append(reference(entityName()));
                } else {
                    // Attribute-value normalization: white space reads as a space.
                    value.append(c == '\t' || c == '\n' || c == '\r' ? ' ' : c);
                    at++;
                }
            }
        }

        /** Reads {@code &name;} or {@code &#...;} and answers the name, or null for a character reference left at {@link #at}. */
        private @Nullable String entityName() {
            if (peek("&#")) {
                return null;
            }
            at++;
            String name = name();
            expect(";");
            return name;
        }

        /** The text a reference stands for; {@code name} null for a character reference at {@link #at}. */
        private String reference(@Nullable String name) {
            if (name == null) {
                int semi = text.indexOf(';', at);
                if (semi < 0 || semi - at > 12) {
                    throw refused("a character reference is not closed");
                }
                String digits = text.substring(at + 2, semi);
                at = semi + 1;
                int code;
                try {
                    code = digits.startsWith("x") ? Integer.parseInt(digits.substring(1), 16) : Integer.parseInt(digits);
                } catch (NumberFormatException e) {
                    throw refused("the character reference &#" + digits + "; is no number");
                }
                if (code <= 0 || code > Character.MAX_CODE_POINT || code >= 0xD800 && code <= 0xDFFF) {
                    throw refused("the character reference &#" + digits + "; is no character");
                }
                return new String(Character.toChars(code));
            }
            String predefined = switch (name) {
                case "lt" -> "<";
                case "gt" -> ">";
                case "amp" -> "&";
                case "quot" -> "\"";
                case "apos" -> "'";
                default -> null;
            };
            if (predefined != null) {
                return predefined;
            }
            String value = entities.get(name);
            if (value == null) {
                throw refused("the entity &" + name + "; is not declared");
            }
            if (value.indexOf('&') >= 0) {
                // Character references in a declared value: expanded here, once.
                Parser inner = new Parser(value);
                StringBuilder out = new StringBuilder();
                while (inner.at < value.length()) {
                    char c = value.charAt(inner.at);
                    if (c == '&') {
                        out.append(inner.reference(inner.entityName()));
                    } else {
                        out.append(c);
                        inner.at++;
                    }
                }
                value = out.toString();
            }
            grow(value.length());
            return value;
        }

        private void grow(int characters) {
            expanded += characters;
            if (expanded > MAX_EXPANSION) {
                throw refused("entities expand to more than " + MAX_EXPANSION + " characters");
            }
        }

        private String name() {
            int start = at;
            while (at < text.length()) {
                char c = text.charAt(at);
                if (Character.isLetterOrDigit(c) || c == ':' || c == '_' || c == '-' || c == '.' || c > 0x7F) {
                    at++;
                } else {
                    break;
                }
            }
            if (at == start) {
                throw refused("a name was expected at character " + at);
            }
            return text.substring(start, at);
        }

        private boolean skipSpace() {
            int start = at;
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    at++;
                } else {
                    break;
                }
            }
            return at > start;
        }

        private boolean peek(String expected) {
            return text.startsWith(expected, at);
        }

        private void expect(String expected) {
            if (!peek(expected)) {
                throw refused("'" + expected + "' was expected at character " + at);
            }
            at += expected.length();
        }

        private void skipPast(String end) {
            int close = text.indexOf(end, at);
            if (close < 0) {
                throw refused("'" + end + "' never comes");
            }
            at = close + end.length();
        }
    }
}
