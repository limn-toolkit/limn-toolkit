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

    /** Thirty-two compounds are matched, thirty-three refused. */
    @Test
    void aSelectorOfTooManyCompoundsIsRefused() {
        assertThrows(MalformedContentException.class, () -> SvgCss.of(parse("<svg xmlns='http://www.w3.org/2000/svg'><style>"
                + "g ".repeat(32) + "rect { fill: red }</style><rect/></svg>")));
        SvgCss.of(parse("<svg xmlns='http://www.w3.org/2000/svg'><style>" + "g ".repeat(31)
                + "rect { fill: red }</style><rect/></svg>"));
    }
}
