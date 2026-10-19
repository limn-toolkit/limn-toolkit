package limn.graphics.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *
 * <p><b>Matching is bounded work</b>: a selector is tried only on the elements its last compound
 * could name (by id, its rarest class, type or attribute, as a browser files its rules) or, when
 * that compound names no id or class, on the elements of that type under an ancestor with the id
 * or class an earlier compound names (as {@code #icon-12 path} is tried only inside
 * {@code #icon-12}); a selector chains at most {@value #MAX_COMPOUNDS} compounds; each compound is
 * tried once against each element in one match however many descendant combinators could send it
 * back there; the ids and classes the sheets name are numbered, and an element's id and class
 * words looked up among them once, so that matching compares numbers and never a long name twice;
 * and a file whose sheets take more steps than its size can back ({@link #MATCH_STEPS_PER_BYTE} a
 * byte, and {@link #MIN_MATCH_STEPS} at least) is refused. A step is a compound tried, an id or
 * class tested, a type or attribute compared (and one more for every sixteen characters), or a
 * character read to look an element's names up: what a try costs is counted, not only that it
 * was made. What matching works out about the elements (their names, the answers a selector
 * remembers) is held by this object, and goes with the reading; and a file whose sheets hold more
 * than {@value #MAX_SELECTORS} selectors is refused before they are read.
 */
final class SvgCss {

    /** The most compounds one selector may chain: a file with a longer one is refused. */
    static final int MAX_COMPOUNDS = 32;
    /**
     * The most selectors a file's sheets may hold: a file with more is refused before they are
     * read. Each is some six hundred bytes once read, so a sheet of a million (7 MB) ran a 512 MB
     * heap out before matching counted a step; the largest sheets the toolkit's tests read, a
     * sprite sheet of 3,000 symbols and an export of 1,296 classes, hold under a thirtieth of it.
     */
    static final int MAX_SELECTORS = 100_000;
    /**
     * The steps a file may take to match its sheets, for each of its bytes. An element is tried
     * only against the selectors filed under its id, its classes, its type and its attributes,
     * so an export of fifty thousand paths under four hundred class rules takes about 540,000
     * steps (the class words read once, a try of a few conditions each), under a third of a step
     * for each byte of it; a chart of 200 series over 50,000 points, written either way, and a
     * sprite sheet of 3,000 symbols take under half a step a byte.
     */
    static final int MATCH_STEPS_PER_BYTE = 4;
    /**
     * The steps any file may take, however small: four million, which a file that needs them all
     * reaches in a fraction of a second, and which a file of a hundred kilobytes, a few thousand
     * elements, does not reach under a few hundred rules filed under nothing.
     */
    static final long MIN_MATCH_STEPS = 4_000_000L;

    private static final int[] NONE = new int[0];

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

    private long steps;
    /** The selectors read so far: see {@link #MAX_SELECTORS}. */
    private int selectorCount;
    /** The tries this file may make: see {@link #MATCH_STEPS_PER_BYTE}. */
    private final long maxSteps;
    /**
     * Every selector that names no id or class where the index looks (see {@link #byId}), filed
     * under what its last compound needs of an element: a type, {@code [attribute}, or {@code *}
     * for one that needs none of them. Made at the first match.
     */
    private @Nullable Map<String, List<Filed>> filed;
    /**
     * Each id and class any selector names, numbered: an element's id and class words are looked
     * up here once, and from then on matching compares numbers, so a long name is read once for
     * each element that carries it and never again for each try or each descendant.
     */
    private final Map<String, Integer> names = new HashMap<>();
    /**
     * The selectors whose last compound names an id, filed under it by its number; and those
     * whose last compound names a class and no id, filed under the one of its classes the fewest
     * selectors name, so that `.dot.series-3` waits for the elements of `series-3` and not for
     * every `dot` of a chart.
     */
    private final List<@Nullable List<Filed>> byId = new ArrayList<>();
    private final List<@Nullable List<Filed>> byClass = new ArrayList<>();
    /**
     * The selectors filed under an ancestor: under the number of the id (or the rarest class) of
     * the nearest compound before the last that names one, then under what the last compound
     * needs. A last compound that names no id or class is filed by its type, or {@code *}:
     * {@code #s12 path} is tried on the paths that have an ancestor {@code #s12}; filed under
     * {@code path} alone, a sprite sheet's four hundred such rules were each tried on every path
     * of the sheet, and an ordinary file of 112 KB was refused. A last compound whose classes are
     * all commoner than the ancestor's is filed by its rarest class: {@code .series-7 .dot} is
     * tried on the dots under {@code .series-7}, where filed under {@code dot} every point of a
     * chart was tried against every series.
     */
    private final List<@Nullable Under> underId = new ArrayList<>();
    private final List<@Nullable Under> underClass = new ArrayList<>();
    /** The types (and {@code *}) an element must have for a walk up its ancestors to find it a selector. */
    private final Set<String> typesUnder = new java.util.HashSet<>();
    /** For each name: whether a selector filed under an ancestor needs it as a class of the element itself. */
    private boolean[] classesUnder = new boolean[0];
    /**
     * The answers a selector of several descendant combinators works out in one match, for each
     * element it asked about: held here, so they go with the reading, where kept on the elements
     * they stayed with the picture, 80 bytes an element.
     */
    private final Map<SvgXml.Element, Memo> memos = new IdentityHashMap<>();
    /** Names each match that remembers its answers, in this reading. */
    private long stamps;
    /** Whether a selector is filed under an attribute, so an element's attributes are worth looking up. */
    private boolean filedByAttribute;
    /**
     * What selectors ask of each element matched, worked out once for it while its attributes
     * stay. Held here, so it goes with the reading: kept on the elements, it stayed with the
     * picture for its life at thirty times the file's size.
     */
    private final Map<SvgXml.Element, Facts> facts = new IdentityHashMap<>();
    /** The best specificity each rule has matched with in the match under way, -1 for none: reused. */
    private long[] best = new long[0];

    /** One selector of one rule, as the index holds it. */
    private record Filed(int rule, Selector selector) {
    }

    /** The selectors filed under one ancestor's id or class: by the type, and by the class, the element itself needs. */
    private static final class Under {
        final Map<String, List<Filed>> byType = new HashMap<>();
        final Map<Integer, List<Filed>> byClass = new HashMap<>();
    }

    /** The answers of one match on one element: at a compound's index, and past the compounds for an ancestor walk's. */
    private static final class Memo {
        final byte[] answers = new byte[2 * MAX_COMPOUNDS];
        long stamp;
    }

    /**
     * What a selector asks of an element, looked up once for it: the number of its id and those
     * of its class words that a selector names (sorted, each once), each -1 or left out when no
     * selector names it, and, the first time a condition names one, its attributes by name.
     */
    private static final class Facts {
        final int attributesChanged;
        final int id;
        final int[] classes;
        @Nullable Map<String, String> attributes;

        Facts(int attributesChanged, int id, int[] classes) {
            this.attributesChanged = attributesChanged;
            this.id = id;
            this.classes = classes;
        }
    }

    private SvgCss(long maxSteps) {
        this.maxSteps = maxSteps;
    }
    /** For each element asked about: the rules that match it, as pairs of a rule's index and its specificity. */
    private final Map<SvgXml.Element, int[]> matching = new IdentityHashMap<>();
    /** For each parent asked about: where each of its children stands among them. */
    private final Map<SvgXml.Element, Siblings> siblings = new IdentityHashMap<>();

    /** Reads every sheet of the document, with the tries of the smallest file. */
    static SvgCss of(SvgXml.Element root) {
        return of(root, 0);
    }

    /**
     * Reads every sheet of the document.
     *
     * @param fileBytes the length of the file, which the tries matching may make are held to
     */
    static SvgCss of(SvgXml.Element root, int fileBytes) {
        SvgCss css = new SvgCss(Math.max(MIN_MATCH_STEPS, (long) MATCH_STEPS_PER_BYTE * fileBytes));
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
                List<String> written = splitTopLevel(prelude, ',');
                selectorCount += written.size();
                if (selectorCount > MAX_SELECTORS) {
                    throw SvgXml.refused("style sheets of more than " + MAX_SELECTORS + " selectors");
                }
                List<Selector> selectors = new ArrayList<>(written.size());
                for (String one : written) {
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
        int[] matched = matching(element);
        for (int m = 0; m < matched.length; m += 2) {
            Rule rule = rules.get(matched[m]);
            long specificity = matched[m + 1];
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

    /**
     * The rules that match an element, matched once: the animation asks the cascade for a dozen
     * properties of each element, and each would match every selector again.
     */
    private int[] matching(SvgXml.Element element) {
        if (rules.isEmpty()) {
            return NONE;
        }
        int[] known = matching.get(element);
        if (known != null) {
            return known;
        }
        if (filed == null) {
            file();
        }
        // Only the selectors filed under what this element has can match it: a `.cls-7` rule is
        // never tried on an element without that class. Matching every element against every
        // selector refused an export of fifty thousand paths under four hundred class rules.
        List<Integer> touched = new ArrayList<>();
        String type = element.localName();
        tryFiled("*", element, touched);
        tryFiled(type, element, touched);
        Facts facts = facts(element);
        if (facts.id >= 0) {
            tryAll(byId.get(facts.id), element, touched);
        }
        for (int name : facts.classes) {
            tryAll(byClass.get(name), element, touched);
        }
        if (filedByAttribute) {
            for (int i = 0; i < element.attributeCount(); i++) {
                tryFiled("[" + element.attributeName(i), element, touched);
            }
        }
        boolean under = typesUnder.contains("*") || typesUnder.contains(type);
        for (int i = 0; !under && i < facts.classes.length; i++) {
            under = classesUnder[facts.classes[i]];
        }
        if (under) {
            tryUnderAncestors(element, type, facts, touched);
        }
        if (touched.isEmpty()) {
            matching.put(element, NONE);
            return NONE;
        }
        touched.sort(null);
        known = new int[touched.size() * 2];
        for (int i = 0; i < touched.size(); i++) {
            int r = touched.get(i);
            known[2 * i] = r;
            known[2 * i + 1] = (int) best[r];
            best[r] = -1;
        }
        matching.put(element, known);
        return known;
    }

    /** Tries the selectors filed under {@code key} on an element, keeping each rule's best specificity. */
    private void tryFiled(String key, SvgXml.Element element, List<Integer> touched) {
        tryAll(filed.get(key), element, touched);
    }

    /**
     * Tries the selectors filed under the ids and classes of the element's ancestors, each set
     * once however many ancestors carry its key: a walk up, a step for each ancestor and each of
     * the named classes it has, each a lookup by number.
     */
    private void tryUnderAncestors(SvgXml.Element element, String type, Facts own, List<Integer> touched) {
        Set<Under> tried = null;
        for (SvgXml.Element up = element.parent(); up != null; up = up.parent()) {
            Facts known = facts(up);
            step(1 + known.classes.length);
            if (known.id >= 0) {
                tried = tryUnder(underId.get(known.id), tried, type, own, element, touched);
            }
            for (int name : known.classes) {
                tried = tryUnder(underClass.get(name), tried, type, own, element, touched);
            }
        }
    }

    private @Nullable Set<Under> tryUnder(@Nullable Under under, @Nullable Set<Under> tried, String type, Facts own,
            SvgXml.Element element, List<Integer> touched) {
        if (under == null) {
            return tried;
        }
        if (tried == null) {
            tried = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        }
        if (tried.add(under)) {
            if (!under.byType.isEmpty()) {
                step(2 + type.length() / 16);
                tryAll(under.byType.get("*"), element, touched);
                tryAll(under.byType.get(type), element, touched);
            }
            if (!under.byClass.isEmpty()) {
                step(own.classes.length);
                for (int name : own.classes) {
                    tryAll(under.byClass.get(name), element, touched);
                }
            }
        }
        return tried;
    }

    private void tryAll(@Nullable List<Filed> candidates, SvgXml.Element element, List<Integer> touched) {
        if (candidates == null) {
            return;
        }
        for (Filed candidate : candidates) {
            if (candidate.selector.matches(element, this)) {
                if (best[candidate.rule] < 0) {
                    touched.add(candidate.rule);
                }
                best[candidate.rule] = Math.max(best[candidate.rule], candidate.selector.specificity);
            }
        }
    }

    /**
     * Numbers every id and class the selectors name, then files every selector under what its
     * last compound needs of an element, or under the ancestor it needs when that compound names
     * no id or class and an earlier one does.
     */
    private void file() {
        List<Integer> uses = new ArrayList<>();
        for (Rule rule : rules) {
            for (Selector selector : rule.selectors) {
                for (Compound compound : selector.compounds) {
                    compound.number(names, uses);
                }
            }
        }
        for (int i = 0; i < names.size(); i++) {
            byId.add(null);
            byClass.add(null);
            underId.add(null);
            underClass.add(null);
        }
        classesUnder = new boolean[names.size()];
        Map<String, List<Filed>> index = new HashMap<>();
        for (int r = 0; r < rules.size(); r++) {
            for (Selector selector : rules.get(r).selectors) {
                if (selector.compounds.isEmpty()) {
                    continue;
                }
                Filed one = new Filed(r, selector);
                Compound last = selector.compounds.get(selector.compounds.size() - 1);
                if (last.idCodes.length > 0) {
                    add(byId, rarest(last.idCodes, uses), one);
                    continue;
                }
                Compound anchor = selector.anchor();
                boolean anchorId = anchor != null && anchor.idCodes.length > 0;
                int anchorCode = anchor == null ? -1 : rarest(anchorId ? anchor.idCodes : anchor.classCodes, uses);
                if (last.classCodes.length > 0) {
                    int own = rarest(last.classCodes, uses);
                    if (anchor == null || uses.get(anchorCode) >= uses.get(own)) {
                        add(byClass, own, one);
                    } else {
                        under(anchorId, anchorCode).byClass.computeIfAbsent(own, k -> new ArrayList<>(2)).add(one);
                        classesUnder[own] = true;
                    }
                    continue;
                }
                if (anchor != null) {
                    String lastType = last.type == null ? "*" : last.type;
                    under(anchorId, anchorCode).byType.computeIfAbsent(lastType, k -> new ArrayList<>(2)).add(one);
                    typesUnder.add(lastType);
                    continue;
                }
                String key = last.type != null ? last.type : !last.attributes.isEmpty() ? "[" + last.attributes.get(0)[0] : "*";
                index.computeIfAbsent(key, k -> new ArrayList<>()).add(one);
                filedByAttribute |= key.startsWith("[");
            }
        }
        best = new long[rules.size()];
        java.util.Arrays.fill(best, -1);
        filed = index;
    }

    private Under under(boolean id, int code) {
        List<@Nullable Under> index = id ? underId : underClass;
        Under under = index.get(code);
        if (under == null) {
            under = new Under();
            index.set(code, under);
        }
        return under;
    }

    private static void add(List<@Nullable List<Filed>> index, int code, Filed one) {
        List<Filed> list = index.get(code);
        if (list == null) {
            list = new ArrayList<>(2);
            index.set(code, list);
        }
        list.add(one);
    }

    /** The one of {@code codes} the fewest selectors name. */
    private static int rarest(int[] codes, List<Integer> uses) {
        int best = codes[0];
        for (int code : codes) {
            if (uses.get(code) < uses.get(best)) {
                best = code;
            }
        }
        return best;
    }

    /**
     * What selectors ask of an element, worked out once for it and kept while its attributes
     * stay: the number of its id and those of its class words, each looked up once and charged
     * for each character read. Looking a class up in the attribute itself cost a scan of it for
     * each class tried, and a list of the words cost each word's look through those before it: a
     * single element of 200,000 classes held the reader 20 seconds. Only the words a selector
     * names are kept, so an element of a thousand classes no rule names costs nothing to hold.
     */
    private Facts facts(SvgXml.Element element) {
        Facts known = facts.get(element);
        if (known != null && known.attributesChanged == element.attributesChanged) {
            return known;
        }
        String id = element.attribute("id");
        String classes = element.attribute("class");
        step(1 + element.attributeCount() + (id == null ? 0 : id.length()) + (classes == null ? 0 : classes.length()));
        Integer idCode = id == null ? null : names.get(id);
        known = new Facts(element.attributesChanged, idCode == null ? -1 : idCode, classes == null ? NONE : codes(classes));
        facts.put(element, known);
        return known;
    }

    /** The value of an attribute a condition names, from a map made the first time one is asked of the element. */
    private @Nullable String attribute(Facts known, SvgXml.Element element, String name) {
        Map<String, String> attributes = known.attributes;
        if (attributes == null) {
            int count = element.attributeCount();
            step(count);
            attributes = new HashMap<>(count * 2);
            for (int i = 0; i < count; i++) {
                attributes.putIfAbsent(element.attributeName(i), element.attributeValue(i));
            }
            known.attributes = attributes;
        }
        return attributes.get(name);
    }

    /** The numbers of the words of a {@code class} attribute that a selector names, sorted, each once. */
    private int[] codes(String attribute) {
        int[] out = NONE;
        int count = 0;
        int at = 0;
        int length = attribute.length();
        while (at < length) {
            while (at < length && Character.isWhitespace(attribute.charAt(at))) {
                at++;
            }
            int start = at;
            while (at < length && !Character.isWhitespace(attribute.charAt(at))) {
                at++;
            }
            if (at > start) {
                Integer code = names.get(attribute.substring(start, at));
                if (code != null) {
                    if (count == out.length) {
                        out = java.util.Arrays.copyOf(out, Math.max(4, count * 2));
                    }
                    out[count++] = code;
                }
            }
        }
        if (count == 0) {
            return NONE;
        }
        out = java.util.Arrays.copyOf(out, count);
        java.util.Arrays.sort(out);
        int unique = 1;
        for (int i = 1; i < count; i++) {
            if (out[i] != out[unique - 1]) {
                out[unique++] = out[i];
            }
        }
        return unique == count ? out : java.util.Arrays.copyOf(out, unique);
    }

    /** Counts the steps of matching (see {@link #MATCH_STEPS_PER_BYTE}), and refuses the file past what its size can back. */
    private void step(long work) {
        steps += work;
        if (steps > maxSteps) {
            throw SvgXml.refused("matching the style sheets takes more than " + maxSteps + " steps");
        }
    }

    /** Where the children of one parent stand: from 1, among all of them and among those of their type, and how many of each. */
    private static final class Siblings {
        /** How many children the parent had when they were counted, so a parent changed since is counted again. */
        final int children;
        final Map<SvgXml.Element, int[]> at = new IdentityHashMap<>();

        Siblings(int children) {
            this.children = children;
        }
    }

    /** @return the element's index among its siblings and their count, then the same among those of its type; null when it is not a child */
    private int @Nullable [] position(SvgXml.Element element, SvgXml.Element parent) {
        Siblings known = siblings.get(parent);
        if (known == null || known.children != parent.children().size()) {
            known = new Siblings(parent.children().size());
            List<SvgXml.Element> all = parent.elements();
            step(all.size());
            Map<String, int[]> types = new HashMap<>();
            for (SvgXml.Element child : all) {
                types.computeIfAbsent(child.localName(), t -> new int[2])[1]++;
            }
            for (int i = 0; i < all.size(); i++) {
                SvgXml.Element child = all.get(i);
                int[] type = types.get(child.localName());
                known.at.put(child, new int[] {i + 1, all.size(), ++type[0], type[1]});
            }
            siblings.put(parent, known);
        }
        return known.at.get(element);
    }

    // ----------------------------------------------------------------------------- selectors

    /** One complex selector: compounds joined by combinators, read right to left. */
    static final class Selector {

        private final List<Compound> compounds;
        /** For each compound but the last: whether it is the parent of the next (else an ancestor). */
        private final List<Boolean> child;
        final long specificity;
        /** Whether a failed try may be made again from another ancestor: two descendant combinators or more. */
        private final boolean backtracks;

        private Selector(List<Compound> compounds, List<Boolean> child, long specificity) {
            this.compounds = compounds;
            this.child = child;
            this.specificity = specificity;
            this.backtracks = child.stream().filter(c -> !c).count() >= 2;
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
                if (compounds.size() > MAX_COMPOUNDS) {
                    throw SvgXml.refused("a selector of more than " + MAX_COMPOUNDS + " compounds");
                }
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

        /**
         * @return the nearest compound before the last that names an id or a class, which an
         *         ancestor of every element this selector matches meets; null when none does
         */
        @Nullable Compound anchor() {
            for (int i = compounds.size() - 2; i >= 0; i--) {
                Compound compound = compounds.get(i);
                if (compound.idCodes.length > 0 || compound.classCodes.length > 0) {
                    return compound;
                }
            }
            return null;
        }

        boolean matches(SvgXml.Element element, SvgCss css) {
            if (compounds.isEmpty()) {
                return false;
            }
            return matchFrom(compounds.size() - 1, element, css, backtracks ? ++css.stamps : 0);
        }

        /** The answers this match keeps for {@code element}, cleared when they are another match's. */
        private byte[] memo(SvgXml.Element element, long stamp, SvgCss css) {
            Memo memo = css.memos.get(element);
            if (memo == null) {
                memo = new Memo();
                memo.stamp = stamp;
                css.memos.put(element, memo);
            } else if (memo.stamp != stamp) {
                java.util.Arrays.fill(memo.answers, 0, 2 * compounds.size(), (byte) 0);
                memo.stamp = stamp;
            }
            return memo.answers;
        }

        /**
         * Whether compounds 0 to {@code index} match, compound {@code index} on {@code element}.
         * With two descendant combinators or more, a try that fails is made again from each
         * ancestor further down, which grows as the depth to the power of the combinators: a
         * selector of eleven compounds over forty nested groups took 24 seconds. A match that can
         * backtrack ({@code stamp} not 0) keeps each answer for the element, at {@code index} for
         * this question and past the compounds for {@link #selfOrAncestor}'s, so that each is
         * worked out once in a match.
         */
        private boolean matchFrom(int index, SvgXml.Element element, SvgCss css, long stamp) {
            byte[] memo = stamp == 0 ? null : memo(element, stamp, css);
            if (memo != null && memo[index] != 0) {
                return memo[index] == 1;
            }
            css.step(1);
            boolean matched = compounds.get(index).matches(element, css);
            if (matched && index > 0) {
                SvgXml.Element parent = element.parent();
                matched = child.get(index - 1) ? parent != null && matchFrom(index - 1, parent, css, stamp)
                        : selfOrAncestor(index - 1, parent, css, stamp);
            }
            if (memo != null) {
                memo[index] = (byte) (matched ? 1 : 2);
            }
            return matched;
        }

        /**
         * Whether compounds 0 to {@code index} match, compound {@code index} on {@code first} or on
         * one of its ancestors. A walk up, so that a deep tree costs no deep stack; every element
         * it passes is given the answer, which is the same for each of them.
         */
        private boolean selfOrAncestor(int index, SvgXml.@Nullable Element first, SvgCss css, long stamp) {
            int slot = compounds.size() + index;
            List<SvgXml.Element> walked = stamp == 0 ? null : new ArrayList<>();
            boolean found = false;
            for (SvgXml.Element up = first; up != null; up = up.parent()) {
                if (stamp != 0) {
                    byte[] memo = memo(up, stamp, css);
                    if (memo[slot] != 0) {
                        found = memo[slot] == 1;
                        break;
                    }
                    walked.add(up);
                }
                if (matchFrom(index, up, css, stamp)) {
                    found = true;
                    break;
                }
            }
            if (stamp != 0) {
                for (SvgXml.Element element : walked) {
                    memo(element, stamp, css)[slot] = (byte) (found ? 1 : 2);
                }
            }
            return found;
        }
    }

    /** One compound selector: a type and the conditions written against it. */
    private static final class Compound {

        private @Nullable String type;
        // Each condition once, as written first: `.a.a` asks no more than `.a` (it weighs more, which
        // the specificity counts apart), and a compound that repeats one condition thousands of times
        // costs one test of it.
        private final List<String> classes = new ArrayList<>();
        private final List<String> idsList = new ArrayList<>();
        private final List<String[]> attributes = new ArrayList<>();
        private final List<String> pseudos = new ArrayList<>();
        /** The numbers of its ids and classes among the names the sheets use, given before the first match. */
        private int[] idCodes = NONE;
        private int[] classCodes = NONE;
        int ids;
        int classesCount;
        int types;
        int classesSpec;

        static @Nullable Compound parse(String text) {
            Compound c = new Compound();
            Set<String> written = new java.util.HashSet<>();
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
                        if (written.add("." + name)) {
                            c.classes.add(name);
                        }
                        c.classesSpec++;
                    } else {
                        if (written.add("#" + name)) {
                            c.idsList.add(name);
                        }
                        c.ids++;
                    }
                } else if (ch == '[') {
                    int end = text.indexOf(']', at);
                    if (end < 0) {
                        return null;
                    }
                    String inside = text.substring(at + 1, end).trim();
                    int eq = inside.indexOf('=');
                    String[] condition;
                    if (eq < 0) {
                        condition = new String[] {inside, null};
                    } else if (eq > 0 && "~|^$*".indexOf(inside.charAt(eq - 1)) >= 0) {
                        return null;
                    } else {
                        condition = new String[] {inside.substring(0, eq).trim(), unquote(inside.substring(eq + 1).trim())};
                    }
                    if (written.add(condition[1] == null ? "[" + condition[0] : "[" + condition[0] + "=" + condition[1])) {
                        c.attributes.add(condition);
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
                    if (written.add(":" + pseudo)) {
                        c.pseudos.add(pseudo);
                    }
                    c.classesSpec++;
                } else {
                    return null;
                }
            }
            c.classesCount = c.classesSpec;
            return c;
        }

        /** Numbers its ids and classes among {@code names}, counting in {@code uses} how many compounds name each. */
        void number(Map<String, Integer> names, List<Integer> uses) {
            idCodes = new int[idsList.size()];
            for (int i = 0; i < idCodes.length; i++) {
                idCodes[i] = number(idsList.get(i), names, uses);
            }
            classCodes = new int[classes.size()];
            for (int i = 0; i < classCodes.length; i++) {
                classCodes[i] = number(classes.get(i), names, uses);
            }
        }

        private static int number(String name, Map<String, Integer> names, List<Integer> uses) {
            Integer code = names.get(name);
            if (code == null) {
                code = names.size();
                names.put(name, code);
                uses.add(0);
            }
            uses.set(code, uses.get(code) + 1);
            return code;
        }

        /**
         * Whether the element meets every condition, each tested until one fails and each
         * charged: an id or a class is a step, a number compared; a type or an attribute a step
         * and one for every sixteen characters it compares; a pseudo-class a step for each
         * character, which it parses; the first step is the try's own. A compound of ten
         * thousand classes costs ten thousand steps, and a chart's `.dot.series-3` costs two.
         */
        boolean matches(SvgXml.Element element, SvgCss css) {
            long work = 0;
            boolean met = true;
            if (type != null) {
                work += compare(type.length());
                met = type.equals(element.localName());
            }
            Facts facts = met && (idCodes.length > 0 || classCodes.length > 0 || !attributes.isEmpty()) ? css.facts(element) : null;
            for (int i = 0; met && i < idCodes.length; i++) {
                work++;
                met = idCodes[i] == facts.id;
            }
            for (int i = 0; met && i < classCodes.length; i++) {
                work++;
                met = java.util.Arrays.binarySearch(facts.classes, classCodes[i]) >= 0;
            }
            for (int i = 0; met && i < attributes.size(); i++) {
                String[] a = attributes.get(i);
                work += compare(a[0].length() + (a[1] == null ? 0 : a[1].length()));
                String value = css.attribute(facts, element, a[0]);
                met = value != null && (a[1] == null || a[1].equals(value));
            }
            for (int i = 0; met && i < pseudos.size(); i++) {
                work += 1 + pseudos.get(i).length();
                met = pseudo(pseudos.get(i), element, css);
            }
            // The try that brought the element here was a step, and covers its first condition.
            css.step(Math.max(0, work - 1));
            return met;
        }

        /** The steps of comparing {@code characters} characters: one, and one for every sixteen. */
        private static int compare(int characters) {
            return 1 + characters / 16;
        }

        private static boolean pseudo(String pseudo, SvgXml.Element element, SvgCss css) {
            SvgXml.Element parent = element.parent();
            if (pseudo.equals("root")) {
                return parent == null;
            }
            if (parent == null) {
                return pseudo.equals("first-child") || pseudo.equals("last-child") || pseudo.equals("first-of-type")
                        || pseudo.equals("last-of-type") || nth(pseudo, 1);
            }
            // Counted once for each parent: counting the siblings again for each of them was their
            // number squared, five seconds for sixteen thousand.
            int[] position = css.position(element, parent);
            if (position == null) {
                return false;
            }
            boolean ofType = pseudo.contains("of-type");
            int index = ofType ? position[2] : position[0];
            int count = ofType ? position[3] : position[1];
            if (pseudo.startsWith("first")) {
                return index == 1;
            }
            if (pseudo.startsWith("last")) {
                return index == count;
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
