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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the paragraph direction does to a line's <b>width</b>, which is the measurement the
 * direction axis is built on: a base direction is an input to shaping and not only to placement.
 *
 * <p>The mechanism, and the reason this is arithmetic rather than a screenshot: the base decides
 * which bidi level a <em>boundary neutral</em> takes, that decides which run the neutral extends,
 * and that decides which face measures it. The faces disagree about the width of a space, so a
 * line of mixed content comes out a fraction of a point different. That fraction is why
 * {@code ShapedText.matches} takes a direction and why {@code Widget.measure} keys on one.
 *
 * <p>Headless CPU, no GL context and no window, behind the same assumption on the vendored script
 * faces the other shaping tests use.
 */
class BaseDirectionWidthTest {

    private static final Font FONT = Font.of(16);
    private static final float EPS = 1e-4f;
    /** How many script faces {@code scripts/fetch-fonts.sh} vendors; fewer is an incomplete set. */
    private static final int SCRIPT_FACES = 4;

    /** Arabic riyal, with the trailing space that is the boundary neutral. */
    private static final String RIYAL_SPACE = "ريال ";
    /** The same word with the space in front of it instead. */
    private static final String SPACE_RIYAL = " ريال";
    /** Hebrew shalom followed by a Latin word: the seam is the space between them. */
    private static final String SHALOM_WORLD = "שלום world";

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

    /** {@code width(RTL) - width(LTR)} for one string at one size. */
    private static float delta(ShapingRuler ruler, String text, Font font) {
        float ltr = ruler.shape(text, font, ShapedText.Direction.LTR).metrics().width();
        float rtl = ruler.shape(text, font, ShapedText.Direction.RTL).metrics().width();
        return rtl - ltr;
    }

    @Test
    void aLineWithNoBoundaryNeutralMeasuresTheSameInBothDirections() {
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            for (String text : new String[]{
                    "42", "(42)", "(...)", "12:30", "3.14", "42%", "[1] (2) {3}", "<-->", "",
                    SPACE_RIYAL}) {
                assertEquals(0, delta(ruler, text, FONT), EPS,
                        "no boundary neutral changes run membership in: " + text);
            }
        }
    }

    @Test
    void aTrailingNeutralJoinsTheOtherRunAndTheFacesDisagreeAboutASpace() {
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            float arabic = delta(ruler, RIYAL_SPACE, FONT);
            float hebrew = delta(ruler, SHALOM_WORLD, FONT);

            assertNotEquals(0f, arabic, "the direction reaches the width");
            assertTrue(arabic > 0 && arabic < 1,
                    "bounded and sub-point: it was " + arabic);
            assertTrue(hebrew > arabic,
                    "Hebrew's space is the wider of the two faces': " + hebrew + " vs " + arabic);
        }
    }

    @Test
    void everyTrailingNeutralCostsOneFaceDifferenceAndTheyAccumulate() {
        // ADR 032 Finding 8 claimed this does NOT accumulate -- "one trailing space, two, five,
        // ten, twenty: the delta is +0.1913 every time" -- and used that to argue the offset is
        // bounded rather than the linear drift ADR 031 had to defend a scroll extent against.
        // Measured, it is exactly linear: one face-difference PER trailing neutral.
        //
        // The finding's mechanism was right and its generalisation was not. A run of trailing
        // neutrals sits at the paragraph's edge, so ALL of it takes the paragraph level and ALL
        // of it changes face with the base. What genuinely does not move is an INTERIOR neutral,
        // which already extends the run it follows under either base -- asserted below, because
        // it is the half of the claim that holds and the reason the effect is small in practice.
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            float one = delta(ruler, RIYAL_SPACE, FONT);
            for (int n = 2; n <= 6; n++) {
                String text = "\u0631\u064A\u0627\u0644" + " ".repeat(n);
                assertEquals(n * one, delta(ruler, text, FONT), 1e-3f,
                        n + " trailing neutrals cost n times one, not one");
            }
        }
    }

    @Test
    void anInteriorNeutralDoesNotMoveAtAllAndNeitherDoesALeadingOne() {
        // The half of Finding 8's mechanism that holds, and the reason a real line's offset stays
        // small: only neutrals at the paragraph edge change run membership.
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            for (int n = 1; n <= 3; n++) {
                String interior = "\u0631\u064A\u0627\u0644" + " ".repeat(n) + "\u0631\u064A\u0627\u0644";
                assertEquals(0, delta(ruler, interior, FONT), EPS,
                        n + " interior neutrals already extend the run they follow");
                assertEquals(0, delta(ruler, " ".repeat(n) + "\u0631\u064A\u0627\u0644", FONT), EPS,
                        n + " leading neutrals take the same run either way");
            }
        }
    }

    @Test
    void theFourMeasuredRowsOfTheAdrStillReproduce() {
        // ADR 032 Finding 8's table, re-measured against this worktree. Every row is exact to the
        // six decimal places the ADR quoted, which is what makes the mechanism a mechanism rather
        // than a correlation -- and what makes the accumulation correction above a correction to
        // one sentence rather than to the finding.
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            assertEquals(0.191250f, delta(ruler, RIYAL_SPACE, FONT), 1e-5f);
            assertEquals(0.351242f, delta(ruler, SHALOM_WORLD, FONT), 1e-5f);
            assertEquals(0.191254f, delta(ruler, "Total: 42 \u0631\u064A\u0627\u0644 (SAR)", FONT), 1e-5f);
            assertEquals(0.000000f, delta(ruler, SPACE_RIYAL, FONT), 1e-5f);
        }
    }

    @Test
    void itScalesWithTheTypeSizeBecauseItIsAnAdvance() {
        try (FontStore store = storeWithScripts()) {
            ShapingRuler ruler = new ShapingRuler(store);
            float small = delta(ruler, RIYAL_SPACE, Font.of(11));
            float medium = delta(ruler, RIYAL_SPACE, Font.of(16));
            float large = delta(ruler, RIYAL_SPACE, Font.of(32));
            assertTrue(small < medium && medium < large,
                    "it is a difference of two advances, so it scales: "
                            + small + " " + medium + " " + large);
            // Two advances at 32pt are exactly twice two advances at 16pt.
            assertEquals(2 * medium, large, 1e-3f, "linear in the size");
            // The ADR's own size ramp, re-measured: exact at every step it quoted.
            assertEquals(0.131485f, delta(ruler, RIYAL_SPACE, Font.of(11)), 1e-5f);
            assertEquals(0.155390f, delta(ruler, RIYAL_SPACE, Font.of(13)), 1e-5f);
            assertEquals(0.209179f, delta(ruler, RIYAL_SPACE, Font.of(17.5f)), 1e-5f);
            assertEquals(0.239063f, delta(ruler, RIYAL_SPACE, Font.of(20)), 1e-5f);
            assertEquals(0.382500f, delta(ruler, RIYAL_SPACE, Font.of(32)), 1e-5f);
        }
    }
}
