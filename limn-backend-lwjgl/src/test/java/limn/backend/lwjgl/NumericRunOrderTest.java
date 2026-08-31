package limn.backend.lwjgl;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a paragraph direction does to the visual <b>order</b> of a run of digits, which is the
 * claim several widgets that draw numbers are built on: {@code Spinner}'s clock face, its field
 * hit test, and a chart legend entry whose series is named for a year.
 *
 * <p>ADR 032 &sect;5 asked for exactly this and named it a risk: the {@code Mode.TIME} decision
 * "rests on a claim about how {@code hh:mm} shapes" and "should be pinned by a test that shapes
 * {@code "07:30"} under an RTL base and asserts the hours are still the leading run, rather than
 * trusted". The widget-level test could not pin it &mdash; a fake ruler is direction-blind, so it
 * pins where the run was placed and not how it shaped. This one shapes.
 *
 * <p>The mechanism, so a later reader can predict the answers rather than re-run them. A European
 * digit is not a neutral: it takes an even (left-to-right) level under either base &mdash; 0 under
 * a left-to-right paragraph and 2 under a right-to-left one &mdash; so a run of digits keeps its
 * order in both. What the base decides is what happens to a character beside that run with no
 * direction of its own: an interior one joins the digits, and one at the paragraph's edge takes
 * the paragraph's own level and moves to the other end.
 *
 * <p>Headless CPU, no GL context and no window, behind the same assumption on the vendored script
 * faces {@link BaseDirectionWidthTest} uses.
 */
class NumericRunOrderTest {

    private static final Font FONT = Font.of(16);
    private static final float EPS = 1e-4f;
    /** How many script faces {@code scripts/fetch-fonts.sh} vendors; fewer is an incomplete set. */
    private static final int SCRIPT_FACES = 4;

    private ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new limn.concurrent.UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        limn.concurrent.Ui.install(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        limn.concurrent.Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    private static FontStore storeWithScripts() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        boolean present = loaded.scripts().size() == SCRIPT_FACES;
        if (!present) {
            loaded.close();
        }
        Assumptions.assumeTrue(present,
                "the script faces are not bundled on this machine; see scripts/fetch-fonts.sh");
        FontStore store = new FontStore();
        assertTrue(store.installHeavyFallbacks(loaded), "the fallbacks did not fold in");
        return store;
    }

    /**
     * Where the character at {@code charIndex} is drawn, in the line's own left-to-right space:
     * the x of the glyph that came from it. Asked of the glyph rather than of a caret because it
     * is the ink's position that the claim under test is about.
     */
    private static float visualX(ShapedText line, int charIndex) {
        for (int g = 0; g < line.glyphCount(); g++) {
            if (line.glyphCluster(g) == charIndex) {
                return line.glyphX(g);
            }
        }
        throw new AssertionError("no glyph came from char " + charIndex + " of " + line.text());
    }

    @Test
    void theHoursStayTheLeadingRunOfAClockFaceUnderBothBases() {
        // ADR 032 §5's open risk, closed. "07:30" is entirely neutral-or-digit, so the base is the
        // whole of what decides its level -- and the level it decides is even either way.
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            for (ShapedText.Direction base : ShapedText.Direction.values()) {
                ShapedText line = ruler.shape("07:30", FONT, base);
                assertEquals(1, line.runs().size(),
                        "a clock face is one run under " + base + ", not a reordered pair");
                assertTrue(line.runs().get(0).level() % 2 == 0,
                        "digits take an even level under " + base + ": it was "
                                + line.runs().get(0).level());
                // The hours lead, the colon divides, the minutes follow: the order the widget
                // draws hh, ":" and mm in, and the order its field hit test compares against.
                assertTrue(visualX(line, 0) < visualX(line, 2),
                        "the hours precede the colon under " + base);
                assertTrue(visualX(line, 2) < visualX(line, 3),
                        "the colon precedes the minutes under " + base);
            }
        }
    }

    @Test
    void aBareNumberKeepsItsOrderWhileStillTakingTheParagraphsLevel() {
        // The case the whole conversion is for: a string with no strong character at all. The
        // order does not move, so a widget that draws one is not mirrored by this -- but the level
        // does, which is why the value has to be shaped for the direction rather than assumed.
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            for (String text : new String[]{"42", "2024", "07", "30", "1,234", "3.14"}) {
                ShapedText ltr = ruler.shape(text, FONT, ShapedText.Direction.LTR);
                ShapedText rtl = ruler.shape(text, FONT, ShapedText.Direction.RTL);
                assertEquals(0, ltr.runs().get(0).level(), text + " is level 0 under LTR");
                assertEquals(2, rtl.runs().get(0).level(), text + " is level 2 under RTL");
                for (int i = 0; i < text.length(); i++) {
                    assertEquals(visualX(ltr, i), visualX(rtl, i), EPS,
                            "char " + i + " of " + text + " is drawn in the same place either way");
                }
            }
        }
    }

    @Test
    void aLatinWordStillReadsLeftToRightUnderAnRtlBase() {
        // The first-strong rule wins over the fallback, which is what keeps a Latin series name
        // in an Arabic form readable. Asserted here so the widget tests can rely on it.
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            ShapedText rtl = ruler.shape("Vendas", FONT, ShapedText.Direction.RTL);
            assertEquals(1, rtl.runs().size(), "one run");
            assertTrue(rtl.runs().get(0).level() % 2 == 0, "an even level, so left to right");
            for (int i = 1; i < "Vendas".length(); i++) {
                assertTrue(visualX(rtl, i - 1) < visualX(rtl, i),
                        "char " + i + " follows its predecessor");
            }
        }
    }

    @Test
    void aLeadingSignMovesToTheOtherEndUnderAnRtlBase() {
        // The counter-case, and it is not a curiosity: a minus sign is a character Spinner accepts
        // and formats, so this is what a negative value in a right-to-left form actually looks
        // like. The sign is a neutral at the paragraph's EDGE, so it takes the paragraph's own odd
        // level and is drawn after the digits, while the digits keep their order between them.
        //
        // This is the case that ends the condition text-and-input.md used to state for Spinner's
        // inline editor -- "nothing in it joins, ligates or reorders, so a prefix width really is
        // a width". Under a right-to-left base a formatted negative number reorders, so the width
        // of a prefix of the string is no longer the distance to anything on screen.
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            for (String text : new String[]{"-42", "-3.14"}) {
                ShapedText ltr = ruler.shape(text, FONT, ShapedText.Direction.LTR);
                assertTrue(visualX(ltr, 0) < visualX(ltr, 1),
                        "the sign leads under LTR, which does not change");

                ShapedText rtl = ruler.shape(text, FONT, ShapedText.Direction.RTL);
                assertEquals(2, rtl.runs().size(), "the sign splits off into its own run");
                assertTrue(visualX(rtl, 0) > visualX(rtl, text.length() - 1),
                        "the sign is drawn after every digit under RTL");
                for (int i = 2; i < text.length(); i++) {
                    assertTrue(visualX(rtl, i - 1) < visualX(rtl, i),
                            "the digits keep their order between them");
                }
            }
        }
    }

    @Test
    void aStringTheFallbackDecidesHasOneRunAndSoOneWidth() {
        // The lemma two widgets in declared lockstep stand on. Checkbox sizes its label from the
        // line it shapes, with its own direction as the fallback; RadioButton sizes the same label
        // from a direction-blind measurement. Those agree, and not by luck: the ONLY text whose
        // base the fallback gets to decide is text with no strong character anywhere in it, and
        // such a text takes the paragraph's level in its entirety -- one run, one face resolution,
        // one width. A width can only move when a neutral at the paragraph's edge changes which
        // run it extends, and that needs a strong run for it to change away from; a strong run
        // would have decided the base itself and never consulted the fallback.
        //
        // Asserted here rather than beside the widgets because the component tests' fake ruler
        // returns a width per code point and cannot see a base direction at all, so it would pass
        // whether or not this held.
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            for (String text : new String[]{
                    "2024", "42", "-42", "3.14", "1,234", "07:30", "(42)", "42%", "#1",
                    "[1] (2) {3}", "<-->", "...", " ", "  ", "+/-", "", "\u00a0", "12 34"}) {
                ShapedText ltr = ruler.shape(text, FONT, ShapedText.Direction.LTR);
                ShapedText rtl = ruler.shape(text, FONT, ShapedText.Direction.RTL);
                // The premise: no strong character, so the two bases really are both reachable.
                assertEquals(ShapedText.Direction.LTR,
                        ShapedText.Direction.of(text, ShapedText.Direction.LTR),
                        "the fallback decides " + text + " under LTR");
                assertEquals(ShapedText.Direction.RTL,
                        ShapedText.Direction.of(text, ShapedText.Direction.RTL),
                        "and under RTL, or this string is not an instance of the lemma");
                assertEquals(ltr.metrics().width(), rtl.metrics().width(), EPS,
                        "the width of " + text + " does not depend on which base decided it");
            }
        }
    }

    @Test
    void aStringWithAStrongCharacterNeverConsultsTheFallbackAtAll() {
        // The other half, and the reason the lemma above is not merely "widths rarely move". A
        // text WITH a strong character can be a fraction of a point wider in one base than the
        // other -- BaseDirectionWidthTest measures exactly that -- but it resolves to the same
        // base under either fallback, so the two widgets never ask it the differing question.
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            for (String text : new String[]{"File", "Vendas", "\u05e9\u05dc\u05d5\u05dd ", "\u0631\u064a\u0627\u0644 ", "42 Notifications"}) {
                assertEquals(ShapedText.Direction.of(text, ShapedText.Direction.LTR),
                        ShapedText.Direction.of(text, ShapedText.Direction.RTL),
                        "the first strong character of " + text + " decided it, not the fallback");
                // So the line a widget shapes is the same line either way, width included.
                ShapedText decided = ruler.shape(text, FONT,
                        ShapedText.Direction.of(text, ShapedText.Direction.LTR));
                ShapedText same = ruler.shape(text, FONT,
                        ShapedText.Direction.of(text, ShapedText.Direction.RTL));
                assertEquals(decided.metrics().width(), same.metrics().width(), EPS, text);
            }
        }
    }

    @Test
    void theWidthOfEveryStringASpinnerFormatsIsTheSameInBothDirections() {
        // Why the conversion moves no coordinate. These strings carry no neutral at a paragraph
        // edge that changes face -- the sign reorders without changing width -- so a spinner
        // measured for one direction and painted in the other still agrees with itself. It is
        // asserted rather than assumed because onMeasure sizes the box from the extremes of the
        // range and paint draws the current value, and the two must not drift apart.
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            for (String text : new String[]{"07", ":", "30", "-42", "42", "2024", "-3.14", "0"}) {
                float ltr = ruler.shape(text, FONT, ShapedText.Direction.LTR).metrics().width();
                float rtl = ruler.shape(text, FONT, ShapedText.Direction.RTL).metrics().width();
                assertEquals(ltr, rtl, EPS, "the width of " + text + " does not move");
            }
        }
    }
}
