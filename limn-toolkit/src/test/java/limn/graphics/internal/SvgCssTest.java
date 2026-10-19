package limn.graphics.internal;

import limn.io.MalformedContentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cascade the animation reads (ADR 066 section 5): which element a selector matches, and that
 * matching is bounded work however a file nests its elements and chains its compounds (ADR 061).
 */
class SvgCssTest {

    private static SvgXml.Element parse(String svg) {
        return SvgXml.parse(svg.getBytes(StandardCharsets.UTF_8));
    }

    private static SvgXml.Element byId(SvgXml.Element element, String id) {
        if (id.equals(element.attribute("id"))) {
            return element;
        }
        for (SvgXml.Element child : element.elements()) {
            SvgXml.Element found = byId(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Descendant combinators may send a try back up the tree: {@code .a .b .a rect} holds only
     * through the outer {@code .a}, past an inner one that has no {@code .b} above it in the
     * right place, and remembering an answer must not stop the walk that finds it.
     */
    @Test
    void descendantCombinatorsFindTheAncestorThatHoldsPastOnesThatDoNot() {
        SvgXml.Element root = parse("<svg xmlns='http://www.w3.org/2000/svg'><style>"
                + ".a .b .a rect { fill: red } .b .a .b rect { fill: blue } svg > g.a > .b rect { stroke: green }"
                + " .x .a .b rect { opacity: .5 }</style>"
                + "<g class='a'><g class='b'><g class='c'><g class='a'><g><rect id='in'/></g></g></g></g></g>"
                + "<g class='b'><rect id='out'/></g></svg>");
        SvgCss css = SvgCss.of(root);
        SvgXml.Element in = byId(root, "in");
        assertEquals("red", css.cascaded(in, "fill", false));
        assertEquals("green", css.cascaded(in, "stroke", false), "a child combinator among descendants");
        assertNull(css.cascaded(in, "opacity", false), "no .x above");
        assertNull(css.cascaded(byId(root, "out"), "fill", false));
    }

    @Test
    void positionsAmongSiblingsAreCountedAsBefore() {
        SvgXml.Element root = parse("<svg xmlns='http://www.w3.org/2000/svg'><style>"
                + "rect:nth-child(2n) { fill: red } rect:nth-of-type(2) { stroke: blue } circle:last-child { fill: green }"
                + " rect:first-of-type { opacity: .5 } rect:last-of-type { opacity: .25 }</style>"
                + "<rect id='r1'/><circle id='c1'/><rect id='r2'/><rect id='r3'/><circle id='c2'/></svg>");
        SvgCss css = SvgCss.of(root);
        // The style sheet is the first child, so r1 is the second and r3 the fifth.
        assertNull(css.cascaded(byId(root, "r3"), "fill", false));
        assertNull(css.cascaded(byId(root, "c1"), "fill", false), "a circle, but not the last child");
        for (String[] expected : List.of(new String[] {"r1", "fill", "red"}, new String[] {"r2", "fill", "red"},
                new String[] {"r2", "stroke", "blue"}, new String[] {"c2", "fill", "green"},
                new String[] {"r1", "opacity", ".5"}, new String[] {"r3", "opacity", ".25"})) {
            assertEquals(expected[2], css.cascaded(byId(root, expected[0]), expected[1], false), String.join(" ", expected));
        }
    }

    /** A selector of eleven compounds over forty nested groups took 24 seconds: each try is made once now. */
    @Test
    void manyDescendantCombinatorsOverDeepNestingAreMatchedQuickly() {
        StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg'><style>nope g g g g g g g g g rect"
                + " { fill: red } g g g g g g g g g rect { stroke: blue }</style>");
        svg.append("<g>".repeat(36)).append("<rect id='deep'/>").append("</g>".repeat(36)).append("</svg>");
        SvgXml.Element root = parse(svg.toString());
        long start = System.nanoTime();
        SvgCss css = SvgCss.of(root);
        assertNull(css.cascaded(byId(root, "deep"), "fill", false));
        assertEquals("blue", css.cascaded(byId(root, "deep"), "stroke", false));
        long took = System.nanoTime() - start;
        assertTrue(took < 500_000_000L, took / 1_000_000 + " ms");
    }

    /**
     * A rule is filed under what its last compound needs (an id, its first class, a type, an
     * attribute, or nothing) and tried only on elements that have it: every way an element can
     * be named still finds its rules, and a rule an element cannot match is not among them.
     */
    @Test
    void filedRulesMatchEveryElementTheyName() {
        SvgXml.Element root = parse("<svg xmlns='http://www.w3.org/2000/svg' xmlns:svg='http://www.w3.org/2000/svg'><style>"
                + "#one { fill: red } .b.a { stroke: blue } svg|rect { opacity: .5 } [data-x] { stroke-width: 3 }"
                + " [data-y='2'] { stroke-width: 4 } :nth-child(3) { fill-opacity: .2 } * { color: green }"
                + " g > .c { stroke-opacity: .7 } .a, #two { fill: orange }</style>"
                + "<g><rect id='one' class='a  b' data-x='1'/><circle id='two' class='c' data-y='2'/>"
                + "<svg:rect id='three' class='b'/></g></svg>");
        SvgCss css = SvgCss.of(root);
        SvgXml.Element one = byId(root, "one");
        SvgXml.Element two = byId(root, "two");
        SvgXml.Element three = byId(root, "three");
        assertEquals("red", css.cascaded(one, "fill", false), "an id outranks a class");
        assertEquals("blue", css.cascaded(one, "stroke", false), "filed under .b, matched with .a too");
        assertNull(css.cascaded(three, "stroke", false), ".b alone is not .b.a");
        assertEquals(".5", css.cascaded(one, "opacity", false));
        assertEquals(".5", css.cascaded(three, "opacity", false), "a type, prefixed or not");
        assertEquals("3", css.cascaded(one, "stroke-width", false));
        assertEquals("4", css.cascaded(two, "stroke-width", false));
        assertEquals(".2", css.cascaded(three, "fill-opacity", false), "a pseudo-class alone is filed under nothing");
        assertEquals("green", css.cascaded(two, "color", false));
        assertEquals(".7", css.cascaded(two, "stroke-opacity", false));
        assertEquals("orange", css.cascaded(two, "fill", false), "the rule's second selector");
    }

    /**
     * An animation is a child like any other until the reading is done, as it is in a browser:
     * a rect followed by its {@code animate} is not the last child, whether or not the animation
     * asked the cascade about it first.
     */
    @Test
    void anAnimationCountsAmongItsSiblingsWhileTheCascadeIsRead() {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><style>@keyframes k { to { opacity: 0 } }"
                + " rect:last-child { animation: k 1s infinite }</style>"
                + "<g><rect id='a' width='1' height='1'/><animate href='#a' attributeName='x' from='0' to='1' dur='1s'"
                + " repeatCount='indefinite'/></g>"
                + "<g><rect id='b' width='1' height='1'/><animate href='#b' attributeName='fill' to='red' dur='1s'"
                + " repeatCount='indefinite'/></g></svg>";
        SvgSource source = SvgSource.read(svg.getBytes(StandardCharsets.UTF_8), null);
        java.util.Map<String, String> set = new java.util.HashMap<>();
        source.animation().apply(new limn.graphics.SvgRenderer.Document() {
            @Override public float width() { return 24; }
            @Override public float height() { return 24; }
            @Override public boolean setAttribute(String id, String name, String value) {
                set.put(id + "." + name, value);
                return true;
            }
            @Override public limn.graphics.Image render(int width, int height) { return new limn.graphics.Image(1, 1, new byte[4]); }
            @Override public void close() { }
        }, new SvgAnimation.Applied(), 500);
        assertNull(set.get("a.opacity"), "a is followed by its animation");
        assertNull(set.get("b.opacity"), "and so is b, which the animation's cascade asked about first");
        String text = new String(source.text(), StandardCharsets.UTF_8);
        assertTrue(!text.contains("animate"), "the animations leave the document once read: " + text);
    }

    /**
     * A selector whose last compound names no id or class is filed under the nearest earlier one
     * that does, and tried only under an ancestor that has it: what it matches is what it
     * matched when it was tried on every element of its type.
     */
    @Test
    void aRuleFiledUnderAnAncestorMatchesWhatItNames() {
        SvgXml.Element root = parse("<svg xmlns='http://www.w3.org/2000/svg' class='dark'><style>"
                + "#s1 path { fill: red } #s1 > path { stroke: blue } .dark path { opacity: .5 }"
                + " .t .u rect, #s2 * { stroke-width: 3 } #s1 [data-x] { fill-opacity: .2 } svg.dark #s1 g circle { color: green }"
                + " .t circle.c { stroke-opacity: .7 } #s1 .t path { fill: orange }</style>"
                + "<symbol id='s1'><path id='p1'/><g class='t'><path id='p2' data-x='1'/><circle id='c1' class='c'/></g></symbol>"
                + "<g id='s2'><path id='p3' class='t' data-x='1'/><g class='u'><rect id='r1'/></g></g>"
                + "<g class='t'><g class='u'><rect id='r2'/></g></g></svg>");
        SvgCss css = SvgCss.of(root);
        assertEquals("red", css.cascaded(byId(root, "p1"), "fill", false));
        assertEquals("orange", css.cascaded(byId(root, "p2"), "fill", false), "under .t under #s1, more specific");
        assertNull(css.cascaded(byId(root, "p3"), "fill", false), "not under #s1");
        assertEquals("blue", css.cascaded(byId(root, "p1"), "stroke", false));
        assertNull(css.cascaded(byId(root, "p2"), "stroke", false), "#s1 is not its parent");
        assertEquals(".5", css.cascaded(byId(root, "p3"), "opacity", false), "the root's class");
        assertNull(css.cascaded(byId(root, "s1"), "opacity", false), "a symbol is not a path");
        assertEquals("3", css.cascaded(byId(root, "r1"), "stroke-width", false), "#s2 *");
        assertEquals("3", css.cascaded(byId(root, "r2"), "stroke-width", false), ".t .u rect");
        assertEquals("3", css.cascaded(byId(root, "p3"), "stroke-width", false), "#s2 * on a path too");
        assertEquals(".2", css.cascaded(byId(root, "p2"), "fill-opacity", false), "an attribute under #s1");
        assertNull(css.cascaded(byId(root, "p3"), "fill-opacity", false));
        assertEquals("green", css.cascaded(byId(root, "c1"), "color", false));
        assertEquals(".7", css.cascaded(byId(root, "c1"), "stroke-opacity", false), "filed under its own class");
        assertNull(css.cascaded(byId(root, "p3"), "color", false), "nothing green outside #s1");
    }

    /**
     * A selector whose last compound's classes are commoner than an ancestor's is filed under the
     * ancestor's class and its own rarest one: {@code .s1 .dot} is tried on dots under
     * {@code .s1} only, and matches what it matched when it was tried on every dot.
     */
    @Test
    void aSeriesRuleFiledUnderItsGroupMatchesWhatItNames() {
        SvgXml.Element root = parse("<svg xmlns='http://www.w3.org/2000/svg'><style>"
                + ".dot { stroke: none } .s1 .dot { fill: red } .s2 .dot { fill: blue } .s1 > .dot.big { opacity: .5 }"
                + " .s2 g .dot { stroke-width: 3 } .dot .s1 .dot { color: green }</style>"
                + "<g class='s1'><circle id='a' class='dot'/><circle id='b' class='dot big'/><g><circle id='c' class='dot big'/></g></g>"
                + "<g class='s2'><circle id='d' class='dot'/><g><circle id='e' class='dot s1'/></g></g>"
                + "<circle id='f' class='dot'/><g class='dot'><g class='s1'><circle id='h' class='dot'/></g></g></svg>");
        SvgCss css = SvgCss.of(root);
        assertEquals("red", css.cascaded(byId(root, "a"), "fill", false));
        assertEquals("red", css.cascaded(byId(root, "c"), "fill", false));
        assertEquals("blue", css.cascaded(byId(root, "d"), "fill", false));
        assertEquals("blue", css.cascaded(byId(root, "e"), "fill", false), "s1 on the element itself is not an ancestor");
        assertNull(css.cascaded(byId(root, "f"), "fill", false));
        assertEquals("none", css.cascaded(byId(root, "f"), "stroke", false));
        assertEquals(".5", css.cascaded(byId(root, "b"), "opacity", false));
        assertNull(css.cascaded(byId(root, "c"), "opacity", false), "s1 is not its parent");
        assertEquals("3", css.cascaded(byId(root, "e"), "stroke-width", false));
        assertNull(css.cascaded(byId(root, "d"), "stroke-width", false));
        assertEquals("green", css.cascaded(byId(root, "h"), "color", false));
        assertNull(css.cascaded(byId(root, "a"), "color", false));
    }

    /**
     * A condition written twice is tested once but weighs twice, as in a browser: {@code .a.a}
     * outranks a later {@code .a}, and {@code [x][x]} a later {@code [x]}.
     */
    @Test
    void aRepeatedConditionStillCountsTowardsSpecificity() {
        SvgXml.Element root = parse("<svg xmlns='http://www.w3.org/2000/svg'><style>"
                + ".a.a { fill: red } .a { fill: blue } [data-x][data-x] { stroke: red } [data-x] { stroke: blue }"
                + " #i#i { opacity: .5 } #i { opacity: .25 }</style><rect id='i' class='a' data-x='1'/></svg>");
        SvgCss css = SvgCss.of(root);
        SvgXml.Element rect = byId(root, "i");
        assertEquals("red", css.cascaded(rect, "fill", false));
        assertEquals("red", css.cascaded(rect, "stroke", false));
        assertEquals(".5", css.cascaded(rect, "opacity", false));
    }

    /**
     * An element's class words and attributes are looked up once and kept, but not past a change:
     * the animation reading gives an element an id or a transform while the sheets are matched,
     * and a later match sees what the element holds then.
     */
    @Test
    void whatAnElementHoldsIsReadAgainOnceItChanges() {
        SvgXml.Element root = parse("<svg xmlns='http://www.w3.org/2000/svg'><style>"
                + ".b rect { fill: red } #late rect { stroke: blue } [data-k='2'] rect { opacity: .5 }</style>"
                + "<g id='g' class='a' data-k='1'><rect id='r'/></g></svg>");
        SvgCss css = SvgCss.of(root);
        SvgXml.Element g = byId(root, "g");
        assertNull(css.cascaded(g, "fill", false), "g asked about first, with its words looked up");
        g.setAttribute("class", "a b");
        g.setAttribute("id", "late");
        g.setAttribute("data-k", "2");
        SvgXml.Element rect = g.elements().get(0);
        assertEquals("red", css.cascaded(rect, "fill", false));
        assertEquals("blue", css.cascaded(rect, "stroke", false));
        assertEquals(".5", css.cascaded(rect, "opacity", false));
    }

    /** Thirty-two compounds are matched, thirty-three refused. */
    @Test
    void aSelectorOfTooManyCompoundsIsRefused() {
        assertThrows(MalformedContentException.class, () -> SvgCss.of(parse("<svg xmlns='http://www.w3.org/2000/svg'><style>"
                + "g ".repeat(32) + "rect { fill: red }</style><rect/></svg>")));
        SvgCss.of(parse("<svg xmlns='http://www.w3.org/2000/svg'><style>" + "g ".repeat(31)
                + "rect { fill: red }</style><rect/></svg>"));
    }
}
