package limn.graphics.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The CSS of an SVG, as much as the toolkit reads to play its animations: the rules of its style
 * sheets and of its {@code style} attributes, which element each rule matches, which declaration
 * wins, and the {@code @keyframes} the rules name. The renderer reads the sheets for itself; what
 * is read here is what it cannot do: animate, and transform.
 *
 * <p>Selectors: a type, {@code *}, {@code .class}, {@code #id}, {@code [attribute]} and
 * {@code [attribute=value]}, {@code :root}, {@code :first-child}, {@code :last-child},
 * {@code :nth-child()} and {@code :nth-of-type()}, compounds of them, and the descendant and child
 * combinators. A selector with anything else matches nothing.
 */
final class SvgCss {

    /** One declaration: a property, its value, and whether it is important. */
    record Declaration(String property, String value, boolean important) {
    }

    /** A rule: its selectors, its declarations, and where it stands in the sheets. */
    record Rule(List<Selector> selectors, List<Declaration> declarations, int order) {
    }

    /** One keyframe: where it is in the animation, from 0 to 1, and what it declares. */
    record Keyframe(double offset, Map<String, String> declarations) {
    }

    final List<Rule> rules = new ArrayList<>();
    final Map<String, List<Keyframe>> keyframes = new HashMap<>();

    /** Reads every sheet of the document. */
    static SvgCss of(SvgXml.Element root) {
        SvgCss css = new SvgCss();
        css.collect(root);
        return css;
    }

    private void collect(SvgXml.Element element) {
        if (element.localName().equals("style")) {
            String type = element.attribute("type");
            if (type == null || type.isBlank() || type.trim().equalsIgnoreCase("text/css")) {
                parseSheet(element.text());
            }
        }
        for (SvgXml.Element child : element.elements()) {
            collect(child);
        }
    }

    // ----------------------------------------------------------------------------- parsing

    private void parseSheet(String sheet) {
        String text = stripComments(sheet);
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf('{', at);
            if (open < 0) {
                return;
            }
            String prelude = text.substring(at, open).trim();
            int statement = prelude.lastIndexOf(';');
            if (statement >= 0) {
                prelude = prelude.substring(statement + 1).trim(); // an @import or a stray ';' before it
            }
            int close = matching(text, open);
            if (close < 0) {
                return;
            }
            String body = text.substring(open + 1, close);
            if (prelude.startsWith("@")) {
                String lower = prelude.toLowerCase(Locale.ROOT);
                if (lower.startsWith("@keyframes") || lower.startsWith("@-webkit-keyframes")) {
                    String name = unquote(prelude.substring(prelude.indexOf(' ') + 1).trim());
                    keyframes.put(name, parseKeyframes(body));
                } else if (lower.startsWith("@media")) {
                    parseSheet(body); // read as if it applied: an icon has no media to ask
                }
                // @font-face, @import, @supports and the rest: not this reader's
            } else if (!prelude.isEmpty()) {
                List<Selector> selectors = new ArrayList<>();
                for (String one : splitTopLevel(prelude, ',')) {
                    selectors.add(Selector.parse(one.trim()));
                }
                rules.add(new Rule(selectors, declarations(body), rules.size()));
            }
            at = close + 1;
        }
    }

    private static List<Keyframe> parseKeyframes(String body) {
        List<Keyframe> frames = new ArrayList<>();
        int at = 0;
        while (true) {
            int open = body.indexOf('{', at);
            int close = open < 0 ? -1 : body.indexOf('}', open);
            if (close < 0) {
                break;
            }
            Map<String, String> declared = new HashMap<>();
            for (Declaration d : declarations(body.substring(open + 1, close))) {
                declared.put(d.property, d.value);
            }
            for (String selector : body.substring(at, open).split(",")) {
                String s = selector.trim().toLowerCase(Locale.ROOT);
                double offset;
                if (s.equals("from")) {
                    offset = 0;
                } else if (s.equals("to")) {
                    offset = 1;
                } else if (s.endsWith("%")) {
                    try {
                        offset = Double.parseDouble(s.substring(0, s.length() - 1)) / 100;
                    } catch (NumberFormatException e) {
                        continue;
                    }
                } else {
                    continue;
                }
                if (offset >= 0 && offset <= 1) {
                    frames.add(new Keyframe(offset, declared));
                }
            }
            at = close + 1;
        }
        frames.sort((a, b) -> Double.compare(a.offset, b.offset));
        return frames;
    }

    /** The declarations of a block or a {@code style} attribute, in order. */
    static List<Declaration> declarations(String block) {
        List<Declaration> out = new ArrayList<>();
        for (String one : splitTopLevel(stripComments(block), ';')) {
            int colon = one.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String property = one.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = one.substring(colon + 1).trim();
            boolean important = false;
            int bang = value.toLowerCase(Locale.ROOT).lastIndexOf("!important");
            if (bang >= 0) {
                important = true;
                value = value.substring(0, bang).trim();
            }
            if (!property.isEmpty() && !value.isEmpty()) {
                out.add(new Declaration(property, value, important));
            }
        }
        return out;
    }

    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (true) {
            int start = text.indexOf("/*", at);
            if (start < 0) {
                out.append(text, at, text.length());
                return out.toString();
            }
            out.append(text, at, start);
            int end = text.indexOf("*/", start + 2);
            if (end < 0) {
                return out.toString();
            }
            at = end + 2;
        }
    }

    private static int matching(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Splits on {@code separator} outside parentheses and quotes. */
    static List<String> splitTopLevel(String text, char separator) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == separator && depth == 0) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    static String unquote(String text) {
        if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    // ----------------------------------------------------------------------------- cascade

    /**
     * The value that wins for a property on an element: its {@code style} attribute's, a sheet
     * rule's by importance, specificity and order, or its presentation attribute's; null when none
     * says.
     *
     * @param presentation whether the property may be given as an attribute too
     */
    @Nullable String cascaded(SvgXml.Element element, String property, boolean presentation) {
        String best = null;
        boolean bestImportant = false;
        long bestRank = Long.MIN_VALUE;
        for (Rule rule : rules) {
            long specificity = -1;
            for (Selector selector : rule.selectors) {
                if (selector.matches(element)) {
                    specificity = Math.max(specificity, selector.specificity);
                }
            }
            if (specificity < 0) {
                continue;
            }
            for (Declaration d : rule.declarations) {
                if (!d.property.equals(property)) {
                    continue;
                }
                long rank = (specificity << 20) + rule.order;
                if (d.important && !bestImportant || d.important == bestImportant && rank >= bestRank) {
                    best = d.value;
                    bestImportant = d.important;
                    bestRank = rank;
                }
            }
        }
        String style = element.attribute("style");
        if (style != null) {
            for (Declaration d : declarations(style)) {
                if (d.property.equals(property) && (d.important || !bestImportant)) {
                    best = d.value;
                    bestImportant = d.important;
                }
            }
        }
        if (best == null && presentation) {
            best = element.attribute(property);
        }
        return best;
    }

    // ----------------------------------------------------------------------------- selectors

    /** One complex selector: compounds joined by combinators, read right to left. */
    static final class Selector {

        private final List<Compound> compounds;
        /** For each compound but the last: whether it is the parent of the next (else an ancestor). */
        private final List<Boolean> child;
        final long specificity;

        private Selector(List<Compound> compounds, List<Boolean> child, long specificity) {
            this.compounds = compounds;
            this.child = child;
            this.specificity = specificity;
        }

        static Selector parse(String text) {
            List<Compound> compounds = new ArrayList<>();
            List<Boolean> child = new ArrayList<>();
            int at = 0;
            boolean pendingChild = false;
            long ids = 0;
            long classes = 0;
            long types = 0;
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    at++;
                    continue;
                }
                if (c == '>') {
                    pendingChild = true;
                    at++;
                    continue;
                }
                if (c == '+' || c == '~') {
                    return never();
                }
                int start = at;
                while (at < text.length() && " \t\n\r>+~".indexOf(text.charAt(at)) < 0) {
                    if (text.charAt(at) == '(' || text.charAt(at) == '[') {
                        char close = text.charAt(at) == '(' ? ')' : ']';
                        int end = text.indexOf(close, at);
                        at = end < 0 ? text.length() : end + 1;
                    } else {
                        at++;
                    }
                }
                Compound compound = Compound.parse(text.substring(start, at));
                if (compound == null) {
                    return never();
                }
                if (!compounds.isEmpty()) {
                    child.add(pendingChild);
                } else if (pendingChild) {
                    return never();
                }
                pendingChild = false;
                compounds.add(compound);
                ids += compound.ids;
                classes += compound.classesSpec;
                types += compound.types;
            }
            if (compounds.isEmpty() || pendingChild) {
                return never();
            }
            return new Selector(compounds, child, Math.min(255, ids) << 16 | Math.min(255, classes) << 8 | Math.min(255, types));
        }

        private static Selector never() {
            return new Selector(List.of(), List.of(), 0);
        }

        boolean matches(SvgXml.Element element) {
            if (compounds.isEmpty()) {
                return false;
            }
            return matchFrom(compounds.size() - 1, element);
        }

        private boolean matchFrom(int index, SvgXml.Element element) {
            if (!compounds.get(index).matches(element)) {
                return false;
            }
            if (index == 0) {
                return true;
            }
            if (child.get(index - 1)) {
                SvgXml.Element parent = element.parent();
                return parent != null && matchFrom(index - 1, parent);
            }
            for (SvgXml.Element up = element.parent(); up != null; up = up.parent()) {
                if (matchFrom(index - 1, up)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** One compound selector: a type and the conditions written against it. */
    private static final class Compound {

        private @Nullable String type;
        private final List<String> classes = new ArrayList<>();
        private final List<String> idsList = new ArrayList<>();
        private final List<String[]> attributes = new ArrayList<>();
        private final List<String> pseudos = new ArrayList<>();
        int ids;
        int classesCount;
        int types;
        int classesSpec;

        static @Nullable Compound parse(String text) {
            Compound c = new Compound();
            int at = 0;
            if (at < text.length() && (Character.isLetter(text.charAt(at)) || text.charAt(at) == '*'
                    || text.charAt(at) == '_')) {
                int start = at;
                while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at))
                        || "-_*|".indexOf(text.charAt(at)) >= 0)) {
                    at++;
                }
                String type = text.substring(start, at);
                if (!type.equals("*")) {
                    int bar = type.indexOf('|');
                    c.type = bar >= 0 ? type.substring(bar + 1) : type;
                    c.types = 1;
                }
            }
            while (at < text.length()) {
                char ch = text.charAt(at);
                if (ch == '.' || ch == '#') {
                    int start = ++at;
                    while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || "-_".indexOf(text.charAt(at)) >= 0
                            || text.charAt(at) > 0x7F || text.charAt(at) == '\\')) {
                        at += text.charAt(at) == '\\' ? 2 : 1;
                    }
                    String name = text.substring(start, Math.min(at, text.length())).replace("\\", "");
                    if (name.isEmpty()) {
                        return null;
                    }
                    if (ch == '.') {
                        c.classes.add(name);
                        c.classesSpec++;
                    } else {
                        c.idsList.add(name);
                        c.ids++;
                    }
                } else if (ch == '[') {
                    int end = text.indexOf(']', at);
                    if (end < 0) {
                        return null;
                    }
                    String inside = text.substring(at + 1, end).trim();
                    int eq = inside.indexOf('=');
                    if (eq < 0) {
                        c.attributes.add(new String[] {inside, null});
                    } else if (eq > 0 && "~|^$*".indexOf(inside.charAt(eq - 1)) >= 0) {
                        return null;
                    } else {
                        c.attributes.add(new String[] {inside.substring(0, eq).trim(), unquote(inside.substring(eq + 1).trim())});
                    }
                    c.classesSpec++;
                    at = end + 1;
                } else if (ch == ':') {
                    int start = ++at;
                    if (at < text.length() && text.charAt(at) == ':') {
                        return null; // a pseudo-element: no element is one
                    }
                    while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '-')) {
                        at++;
                    }
                    String pseudo = text.substring(start, at).toLowerCase(Locale.ROOT);
                    if (at < text.length() && text.charAt(at) == '(') {
                        int end = text.indexOf(')', at);
                        if (end < 0) {
                            return null;
                        }
                        pseudo += "(" + text.substring(at + 1, end).trim().toLowerCase(Locale.ROOT) + ")";
                        at = end + 1;
                    }
                    if (!pseudo.equals("root") && !pseudo.equals("first-child") && !pseudo.equals("last-child")
                            && !pseudo.startsWith("nth-child(") && !pseudo.startsWith("nth-of-type(")
                            && !pseudo.equals("first-of-type") && !pseudo.equals("last-of-type")) {
                        return null;
                    }
                    c.pseudos.add(pseudo);
                    c.classesSpec++;
                } else {
                    return null;
                }
            }
            c.classesCount = c.classesSpec;
            return c;
        }

        boolean matches(SvgXml.Element element) {
            if (type != null && !type.equals(element.localName())) {
                return false;
            }
            if (!idsList.isEmpty() && !idsList.contains(element.attribute("id"))) {
                return false;
            }
            if (!classes.isEmpty()) {
                String attribute = element.attribute("class");
                if (attribute == null) {
                    return false;
                }
                List<String> own = List.of(attribute.trim().split("\\s+"));
                if (!own.containsAll(classes)) {
                    return false;
                }
            }
            for (String[] a : attributes) {
                String value = element.attribute(a[0]);
                if (value == null || a[1] != null && !a[1].equals(value)) {
                    return false;
                }
            }
            for (String pseudo : pseudos) {
                if (!pseudo(pseudo, element)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean pseudo(String pseudo, SvgXml.Element element) {
            SvgXml.Element parent = element.parent();
            if (pseudo.equals("root")) {
                return parent == null;
            }
            if (parent == null) {
                return pseudo.equals("first-child") || pseudo.equals("last-child") || pseudo.equals("first-of-type")
                        || pseudo.equals("last-of-type") || nth(pseudo, 1);
            }
            List<SvgXml.Element> siblings = parent.elements();
            boolean ofType = pseudo.contains("of-type");
            List<SvgXml.Element> counted = new ArrayList<>();
            for (SvgXml.Element s : siblings) {
                if (!ofType || s.localName().equals(element.localName())) {
                    counted.add(s);
                }
            }
            int index = counted.indexOf(element) + 1;
            if (pseudo.startsWith("first")) {
                return index == 1;
            }
            if (pseudo.startsWith("last")) {
                return index == counted.size();
            }
            return nth(pseudo, index);
        }

        /** Whether {@code index}, from 1, is one the {@code an+b} of an nth pseudo-class names. */
        private static boolean nth(String pseudo, int index) {
            int open = pseudo.indexOf('(');
            if (open < 0) {
                return false;
            }
            String expression = pseudo.substring(open + 1, pseudo.length() - 1).replace(" ", "");
            int a;
            int b;
            if (expression.equals("odd")) {
                a = 2;
                b = 1;
            } else if (expression.equals("even")) {
                a = 2;
                b = 0;
            } else {
                int n = expression.indexOf('n');
                try {
                    if (n < 0) {
                        a = 0;
                        b = Integer.parseInt(expression);
                    } else {
                        String aText = expression.substring(0, n);
                        a = aText.isEmpty() || aText.equals("+") ? 1 : aText.equals("-") ? -1 : Integer.parseInt(aText);
                        String bText = expression.substring(n + 1);
                        b = bText.isEmpty() ? 0 : Integer.parseInt(bText.startsWith("+") ? bText.substring(1) : bText);
                    }
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            if (a == 0) {
                return index == b;
            }
            int k = index - b;
            return k % a == 0 && k / a >= 0;
        }
    }
}
