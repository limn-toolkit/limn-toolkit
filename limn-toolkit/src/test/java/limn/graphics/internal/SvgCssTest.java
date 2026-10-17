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

    /** Thirty-two compounds are matched, thirty-three refused. */
    @Test
    void aSelectorOfTooManyCompoundsIsRefused() {
        assertThrows(MalformedContentException.class, () -> SvgCss.of(parse("<svg xmlns='http://www.w3.org/2000/svg'><style>"
                + "g ".repeat(32) + "rect { fill: red }</style><rect/></svg>")));
        SvgCss.of(parse("<svg xmlns='http://www.w3.org/2000/svg'><style>" + "g ".repeat(31)
                + "rect { fill: red }</style><rect/></svg>"));
    }
}
