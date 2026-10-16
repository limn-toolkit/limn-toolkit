package limn.graphics.internal;

import limn.graphics.Image;
import limn.graphics.SvgRenderer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The animation the toolkit reads out of an SVG and plays (ADR 066 section 5), against moments
 * computed by hand: SMIL's timing, values, key times and splines, CSS's keyframes, delays,
 * directions, fill modes, timing functions and transforms about their origin, and the frames the
 * whole is drawn as.
 */
class SvgAnimationTest {

    /** A document that keeps what it is told. */
    private static final class Recording implements SvgRenderer.Document {
        final Map<String, String> attributes = new HashMap<>();

        @Override public float width() { return 24; }
        @Override public float height() { return 24; }
        @Override public boolean setAttribute(String id, String name, String value) {
            attributes.put(id + "." + name, value);
            return true;
        }
        @Override public Image render(int width, int height) { return new Image(1, 1, new byte[4]); }
        @Override public void close() { }
    }

    private static SvgSource source(String svg) {
        return SvgSource.read(svg.getBytes(StandardCharsets.UTF_8), null);
    }

    private static String at(SvgAnimation animation, double millis, String key) {
        Recording document = new Recording();
        animation.apply(document, new SvgAnimation.Applied(), millis);
        return document.attributes.get(key);
    }

    private static double number(SvgAnimation animation, double millis, String key) {
        return Double.parseDouble(at(animation, millis, key));
    }

    private static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>";

    @Test
    void aStillSvgHasNoAnimation() {
        assertNull(source(SVG + "<circle cx='12' cy='12' r='4'/></svg>").animation());
    }

    @Test
    void smilValuesAreInterpolatedOverTheDuration() {
        SvgSource source = source(SVG + "<circle id='c' r='2'><animate attributeName='r' from='2' to='6' dur='1s' "
                + "repeatCount='indefinite'/></circle></svg>");
        SvgAnimation animation = source.animation();
        assertNotNull(animation);
        assertFalse(new String(source.text(), StandardCharsets.UTF_8).contains("animate"), "taken out of the text");
        assertEquals(2, number(animation, 0, "c.r"), 1e-9);
        assertEquals(3, number(animation, 250, "c.r"), 1e-9);
        assertEquals(5, number(animation, 750, "c.r"), 1e-9);
        assertEquals(3, number(animation, 1250, "c.r"), 1e-9, "round again");
        assertTrue(animation.loops());
    }

    @Test
    void keyTimesAndValuesShapeTheMotion() {
        SvgAnimation animation = source(SVG + "<rect id='r' width='1' height='1'><animate attributeName='x' values='0;10;0' "
                + "keyTimes='0;0.2;1' dur='1s' repeatCount='indefinite'/></rect></svg>").animation();
        assertEquals(5, number(animation, 100, "r.x"), 1e-9, "halfway through the first fifth");
        assertEquals(10, number(animation, 200, "r.x"), 1e-9);
        assertEquals(5, number(animation, 600, "r.x"), 1e-9, "halfway back over the remaining 800 ms");
    }

    @Test
    void keySplinesEaseEachSegment() {
        SvgAnimation animation = source(SVG + "<circle id='c' r='0'><animate attributeName='r' values='0;10' "
                + "calcMode='spline' keySplines='.5 0 .5 1' dur='1s' repeatCount='indefinite'/></circle></svg>").animation();
        assertEquals(5, number(animation, 500, "c.r"), 1e-6, "an ease in and out passes the middle at the middle");
        double early = number(animation, 250, "c.r");
        assertTrue(early < 2.5 && early > 0, "and starts slower than linear: " + early);
        // The curve's own value at x = 0.25, solved by hand: x(t) = 1.5t − 1.5t² + t³ gives
        // t = 0.2015…, and y(t) = 3t² − 2t³ = 0.1059…
        assertEquals(1.059, early, 0.005);
    }

    @Test
    void discreteValuesStep() {
        SvgAnimation animation = source(SVG + "<rect id='r' width='1' height='1' fill='red'><animate attributeName='fill' "
                + "values='red;blue;green' calcMode='discrete' dur='3s' repeatCount='indefinite'/></rect></svg>").animation();
        assertEquals("red", at(animation, 900, "r.fill"));
        assertEquals("blue", at(animation, 1100, "r.fill"));
        assertEquals("green", at(animation, 2900, "r.fill"));
    }

    @Test
    void coloursAreMixed() {
        SvgAnimation animation = source(SVG + "<rect id='r' width='1' height='1'><animate attributeName='fill' "
                + "from='#000000' to='#ff0000' dur='1s' repeatCount='indefinite'/></rect></svg>").animation();
        assertEquals("#800000ff", at(animation, 500, "r.fill"));
    }

    @Test
    void aNegativeBeginStartsPartWayThrough() {
        SvgAnimation animation = source(SVG + "<circle id='c' r='0'><animate attributeName='r' from='0' to='10' dur='1s' "
                + "begin='-0.25s' repeatCount='indefinite'/></circle></svg>").animation();
        assertEquals(2.5, number(animation, 0, "c.r"), 1e-9);
    }

    @Test
    void anAnimationThatEndsFreezesOrGivesItsValueBack() {
        SvgAnimation frozen = source(SVG + "<circle id='c' r='1'><animate attributeName='r' to='9' dur='1s' "
                + "fill='freeze'/></circle></svg>").animation();
        assertEquals(9, number(frozen, 5000, "c.r"), 1e-9);
        assertFalse(frozen.loops());
        SvgAnimation removed = source(SVG + "<circle id='c' r='1'><animate attributeName='r' to='9' dur='1s' "
                + "begin='1s'/></circle></svg>").animation();
        assertEquals("1", at(removed, 500, "c.r"), "before it begins: the element's own");
        assertEquals("1", at(removed, 2500, "c.r"), "after it ends: its own again");
        assertEquals(5, number(removed, 1500, "c.r"), 1e-9);
    }

    @Test
    void setHoldsItsValueWhileInEffect() {
        SvgAnimation animation = source(SVG + "<rect id='r' width='1' height='1' visibility='hidden'>"
                + "<set attributeName='visibility' to='visible' begin='0.5s' dur='1s'/></rect></svg>").animation();
        assertEquals("hidden", at(animation, 100, "r.visibility"));
        assertEquals("visible", at(animation, 1000, "r.visibility"));
        assertEquals("hidden", at(animation, 2000, "r.visibility"));
    }

    @Test
    void animateTransformReplacesOrAddsToTheElementsOwn() {
        SvgAnimation replace = source(SVG + "<rect id='r' width='1' height='1' transform='translate(5 5)'>"
                + "<animateTransform attributeName='transform' type='rotate' from='0 12 12' to='360 12 12' dur='2s' "
                + "repeatCount='indefinite'/></rect></svg>").animation();
        assertEquals("rotate(90 12 12)", at(replace, 500, "r.transform"));
        SvgAnimation sum = source(SVG + "<rect id='r' width='1' height='1' transform='translate(5 5)'>"
                + "<animateTransform attributeName='transform' type='scale' from='1' to='3' dur='2s' additive='sum' "
                + "repeatCount='indefinite'/></rect></svg>").animation();
        assertEquals("translate(5 5) scale(2)", at(sum, 1000, "r.transform"));
    }

    @Test
    void anAnimationOfAnotherElementByReference() {
        SvgSource source = source(SVG + "<circle cx='5' cy='5' r='2'/><animate href='#none' attributeName='r' to='3' dur='1s'/>"
                + "<circle id='target' r='1'/><animate href='#target' attributeName='r' from='1' to='3' dur='1s' "
                + "repeatCount='indefinite'/></svg>");
        assertEquals(2, number(source.animation(), 500, "target.r"), 1e-9);
    }

    @Test
    void anAnimatedElementWithoutAnIdIsGivenOne() {
        SvgSource source = source(SVG + "<circle r='1'><animate attributeName='r' from='1' to='3' dur='1s' "
                + "repeatCount='indefinite'/></circle></svg>");
        String text = new String(source.text(), StandardCharsets.UTF_8);
        assertTrue(text.contains("id=\"limn-anim-0\""), text);
        assertEquals(2, number(source.animation(), 500, "limn-anim-0.r"), 1e-9);
    }

    /**
     * The renderer opens an image's reference as a path, so an animation that moved one would read
     * any picture on the disk into an SVG from bytes, which may show none (ADR 066 section 6).
     */
    @Test
    void noAnimationMovesAReference() {
        String[] moving = {
            "<image width='4' height='4'><set attributeName='href' to='/etc/private.png'/></image>",
            "<image width='4' height='4'><set attributeName=' HREF ' to='/etc/private.png'/></image>",
            "<image width='4' height='4'><animate attributeName='href' values='\\\\host\\share\\a.png;/x.png' "
                    + "dur='1s' calcMode='discrete' repeatCount='indefinite'/></image>",
            "<image width='4' height='4'><set attributeName='xlink:href' to='/etc/private.png'/></image>",
            "<style>@keyframes k { from { href: /etc/private.png } to { href: /etc/private.png } }"
                    + " image { animation: k 1s infinite }</style><image width='4' height='4'/>",
        };
        for (String body : moving) {
            SvgAnimation animation = source(SVG + body + "<rect width='1' height='1'><animate attributeName='x' "
                    + "from='0' to='1' dur='1s' repeatCount='indefinite'/></rect></svg>").animation();
            assertNotNull(animation, body);
            for (double millis : new double[] {0, 250, 500, 999}) {
                Recording document = new Recording();
                animation.apply(document, new SvgAnimation.Applied(), millis);
                for (String key : document.attributes.keySet()) {
                    assertFalse(key.toLowerCase(java.util.Locale.ROOT).endsWith("href"), body + " set " + key);
                }
            }
        }
    }

    // ----------------------------------------------------------------------------- CSS

    @Test
    void keyframesPlayOnTheElementsTheirRulesMatch() {
        SvgAnimation animation = source(SVG + "<style>@keyframes fade { 0% { opacity: 1 } 50% { opacity: .2 } 100% { opacity: 1 } }"
                + " .dot { animation: fade 1s linear infinite } .dot:nth-child(3) { animation-delay: -.5s }</style>"
                + "<circle id='a' class='dot' r='1'/><circle id='b' class='dot' r='1'/><rect id='c' width='1' height='1'/>"
                + "</svg>").animation();
        assertEquals(1, number(animation, 0, "a.opacity"), 1e-9);
        assertEquals(0.6, number(animation, 250, "a.opacity"), 1e-9);
        assertEquals(0.2, number(animation, 0, "b.opacity"), 1e-9, "the third child: half a cycle ahead");
        assertNull(at(animation, 0, "c.opacity"), "a rule that matches nothing moves nothing");
    }

    @Test
    void aTimingFunctionEasesEachSegmentAndAKeyframeMaySetItsOwn() {
        SvgAnimation eased = source(SVG + "<style>@keyframes grow { from { r: 0 } to { r: 10 } }"
                + " #c { animation: grow 1s ease-in-out infinite }</style><circle id='c' r='0'/></svg>").animation();
        assertEquals(5, number(eased, 500, "c.r"), 1e-6);
        assertTrue(number(eased, 200, "c.r") < 2, "ease-in-out starts slowly");
        SvgAnimation stepped = source(SVG + "<style>@keyframes grow { from { r: 0; animation-timing-function: steps(2) } to { r: 10 } }"
                + " #c { animation: grow 1s infinite }</style><circle id='c' r='0'/></svg>").animation();
        assertEquals(0, number(stepped, 400, "c.r"), 1e-9);
        assertEquals(5, number(stepped, 600, "c.r"), 1e-9);
    }

    @Test
    void directionsAndFillModesAreRead() {
        SvgAnimation alternate = source(SVG + "<style>@keyframes move { from { x: 0 } to { x: 10 } }"
                + " #r { animation: move 1s linear infinite alternate }</style><rect id='r' width='1' height='1'/></svg>").animation();
        assertEquals(2.5, number(alternate, 250, "r.x"), 1e-9);
        assertEquals(7.5, number(alternate, 1250, "r.x"), 1e-9, "the second turn runs back");
        SvgAnimation forwards = source(SVG + "<style>@keyframes move { to { x: 10 } }"
                + " #r { animation: move 1s linear 2 forwards }</style><rect id='r' x='0' width='1' height='1'/></svg>").animation();
        assertEquals(10, number(forwards, 9000, "r.x"), 1e-9, "after its two turns it rests where it ended");
        assertEquals(5, number(forwards, 1500, "r.x"), 1e-9, "a missing from is the element's own value");
        assertFalse(forwards.loops());
    }

    @Test
    void aCssTransformTurnsAboutItsOriginInTheViewBox() {
        SvgAnimation animation = source(SVG + "<style>@keyframes spin { to { transform: rotate(360deg) } }"
                + " .s { transform-origin: center; animation: spin 1s linear infinite }</style>"
                + "<path id='p' class='s' d='M12 2 L14 4'/></svg>").animation();
        // A quarter turn about (12, 12): x' = -y + 24, y' = x.
        assertEquals("matrix(0 1 -1 0 24 0)", at(animation, 250, "p.transform"));
    }

    @Test
    void aCssTransformOnItsOwnIsWrittenAsTheAttributeTheRendererReads() {
        SvgSource source = source(SVG + "<style>.t { transform: translate(5px, 2px) }</style>"
                + "<rect class='t' width='1' height='1'/></svg>");
        assertNull(source.animation());
        assertTrue(new String(source.text(), StandardCharsets.UTF_8).contains("transform=\"matrix(1 0 0 1 5 2)\""));
    }

    @Test
    void aFillBoxOriginIsTheElementsOwnCentre() {
        SvgAnimation animation = source(SVG + "<style>@keyframes spin { to { transform: rotate(180deg) } }"
                + " #r { transform-box: fill-box; transform-origin: 50% 50%; animation: spin 2s linear infinite }</style>"
                + "<rect id='r' x='2' y='4' width='4' height='2'/></svg>").animation();
        // Halfway through, a quarter turn about (4, 5): x' = 9 - y, y' = x + 1.
        assertEquals("matrix(0 1 -1 0 9 1)", at(animation, 1000, "r.transform"));
    }

    // ----------------------------------------------------------------------------- frames

    @Test
    void framesCoverThePeriodAtTheRateAsked() {
        SvgAnimation animation = source(SVG + "<circle id='a' r='1'><animate attributeName='r' from='1' to='2' dur='0.75s' "
                + "repeatCount='indefinite'/></circle><circle id='b' r='1'><animate attributeName='r' from='1' to='2' "
                + "dur='0.5s' repeatCount='indefinite'/></circle></svg>").animation();
        assertTrue(animation.loops());
        assertEquals(36, animation.frameCount(24), "the least common multiple, 1.5 s, at 24 a second");
        assertEquals(90, animation.frameCount(60));
        int total = 0;
        for (int f = 0; f < 36; f++) {
            total += animation.frameMillis(24, f);
        }
        assertEquals(1500, total, "the frames' times add up to the period exactly");
        assertEquals(750, animation.timeOf(24, 18), 1e-9);
    }

    @Test
    void anAnimationThatEndsPlaysItsLengthOnceAndRestsOnTheEnd() {
        SvgAnimation animation = source(SVG + "<circle id='c' r='1'><animate attributeName='r' to='9' dur='1s' "
                + "fill='freeze'/></circle></svg>").animation();
        assertEquals(25, animation.frameCount(24), "24 frames and the last moment");
        assertEquals(1000, animation.timeOf(24, 24), 1e-9);
    }

    // ----------------------------------------------------------------------------- chains

    /**
     * Three bars chained as indicators chain them: the first begins at 0 and again a quarter
     * second before the third ends, the others a set time after the first begins. The chain
     * comes round every 0.3 + 0.75 − 0.25 = 0.8 s.
     */
    private static final String CHAIN = SVG
            + "<rect id='a' y='0' width='1' height='1'><animate id='first' begin='0;third.end-0.25s' attributeName='y' "
            + "dur='0.75s' values='0;10' fill='freeze'/></rect>"
            + "<rect id='b' y='0' width='1' height='1'><animate begin='first.begin+0.15s' attributeName='y' "
            + "dur='0.75s' values='0;10' fill='freeze'/></rect>"
            + "<rect id='c' y='0' width='1' height='1'><animate id='third' begin='first.begin+0.3s' attributeName='y' "
            + "dur='0.75s' values='0;10' fill='freeze'/></rect></svg>";

    @Test
    void aChainOfAnimationsLoopsAtItsOwnPeriod() {
        SvgAnimation animation = source(CHAIN).animation();
        assertTrue(animation.loops(), "a chain that comes round is a loop");
        assertEquals(19, animation.frameCount(24), "0.8 s at 24 a second");
        int total = 0;
        for (int f = 0; f < 19; f++) {
            total += animation.frameMillis(24, f);
        }
        assertEquals(800, total);
    }

    @Test
    void eachLinkBeginsWhenItsBaseSays() {
        SvgAnimation animation = source(CHAIN).animation();
        assertEquals(0, number(animation, 100, "b.y"), 1e-9, "the second has not begun at 0.1 s");
        assertEquals(10.0 * 0.05 / 0.75, number(animation, 200, "b.y"), 1e-3, "0.05 s into it at 0.2 s");
        assertEquals(10, number(animation, 790, "a.y"), 1e-9, "the first ended at 0.75 s and holds");
        assertEquals(10.0 * 0.1 / 0.75, number(animation, 900, "a.y"), 1e-3, "and began again at 0.8 s");
        assertEquals(10.0 * 0.1 / 0.75, number(animation, 1700, "a.y"), 1e-3, "and again at 1.6 s");
    }

    @Test
    void aChainThatDoesNotComeRoundEnds() {
        SvgAnimation animation = source(SVG + "<rect id='a' width='1' height='1'><animate id='one' attributeName='x' "
                + "from='0' to='5' dur='1s' fill='freeze'/></rect><rect id='b' width='1' height='1'><animate "
                + "begin='one.end' attributeName='x' from='0' to='5' dur='1s' fill='freeze'/></rect></svg>").animation();
        assertFalse(animation.loops());
        assertEquals(0, number(animation, 900, "b.x"), 1e-9);
        assertEquals(2.5, number(animation, 1500, "b.x"), 1e-9);
        assertEquals(49, animation.frameCount(24), "two seconds and the last moment");
    }

    /**
     * An iteration count that looks like a number and is none made {@code Images.decode} throw
     * NumberFormatException (ADR 061 promises one exception); a browser drops such a
     * declaration, and so does the toolkit.
     */
    @Test
    void anAnimationShorthandWithANumberThatIsNoneIsDropped() {
        for (String count : new String[] {".", "1.2.3", ".."}) {
            assertNull(source(SVG + "<style>@keyframes k { to { opacity: 0 } }</style><rect width='4' height='4' "
                    + "style='animation: k 1s " + count + "'/></svg>").animation(), count);
        }
        assertNotNull(source(SVG + "<style>@keyframes k { to { opacity: 0 } }</style><rect width='4' height='4' "
                + "style='animation: k 1s .5'/></svg>").animation(), "a count of half a turn");
    }

    /**
     * Each round of working out a chain moves it on by one link, over every animation: forty
     * pairs restarting each other every millisecond took nine seconds, and a hundred and sixty
     * thirty-six. Past a count of the work, the file is refused.
     */
    @Test
    void aChainOfMillisecondLinksIsRefusedQuickly() {
        StringBuilder svg = new StringBuilder(SVG);
        for (int i = 0; i < 40; i++) {
            svg.append("<rect><animate id='a").append(i).append("' attributeName='x' from='0' to='1' dur='1ms' begin='0;b")
                    .append(i).append(".end'/><animate id='b").append(i).append("' attributeName='y' from='0' to='1' "
                    + "dur='1ms' begin='a").append(i).append(".end'/></rect>");
        }
        svg.append("</svg>");
        long start = System.nanoTime();
        org.junit.jupiter.api.Assertions.assertThrows(limn.io.MalformedContentException.class, () -> source(svg.toString()));
        assertTrue(System.nanoTime() - start < 2_000_000_000L, (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    @Test
    void aBeginningOnAnEventIsNotPlayed() {
        assertNull(source(SVG + "<rect width='1' height='1'><animate begin='click' attributeName='x' to='5' dur='1s'/>"
                + "</rect></svg>").animation());
    }
}
