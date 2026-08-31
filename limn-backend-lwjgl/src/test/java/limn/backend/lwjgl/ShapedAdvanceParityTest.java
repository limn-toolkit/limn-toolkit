package limn.backend.lwjgl;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance proof for adding a shaper: <b>turning HarfBuzz on moves no existing screen</b>,
 * except where an f-ligature falls, and there it moves it by a known amount in a known direction.
 *
 * <p>ADR 031 measured that claim against a scratch probe before any of this existed, and recorded
 * eight strings in Roboto font units. This is the same comparison against the shipping
 * implementation, widened from those eight to every string the toolkit's own translation bundles
 * ship: the text this toolkit actually draws, in the languages it actually draws it in, rather
 * than eight samples someone chose. For each one, the width {@link ShapingRuler}{@code .shape}
 * produces is compared against the width the <b>unshaped</b> per-code-point walk produces.
 *
 * <p><b>Where that baseline comes from, and why it is not the ruler's own {@code measure}.</b> It
 * was, while {@code measure} summed advances one code point at a time beside {@code shape} rather
 * than through it. {@code ShapingRuler.measure} now <em>is</em> {@code shape(…).metrics()}, so
 * asking it here would be asking the shaped width whether it equals itself: every assertion below
 * would pass, the "moved" set would be empty, and this suite would report that shaping changes
 * nothing while quietly having stopped being a comparison at all. The baseline is therefore taken
 * one layer down, from {@link FontStore#measure}, which is the per-code-point walk itself &mdash;
 * one advance and one kern pair per code point, a face resolved per character. That is the thing
 * this whole exercise was measured against, and it is still there to measure against.
 *
 * <p><b>Font units, so the numbers are the ADR's numbers.</b> {@code stbtt_ScaleForMappingEmToPixels}
 * is {@code size / unitsPerEm}, so measuring at a size equal to the face's own em makes the scale
 * exactly {@code 1} and every width an exact integer count of font units &mdash; directly
 * comparable to the table the ADR recorded, and free of any rounding that would force a tolerance
 * onto a comparison that is supposed to be exact.
 *
 * <p><b>What this cannot see, and no headless test can.</b> It compares <em>advances</em>: the
 * numbers layout is built from, and the ones a caret, a hit test, a wrap and an ellipsis all rest
 * on. It says nothing about ink. Whether the {@code ffi} ligature rasterizes as one glyph rather
 * than three overlapping ones, whether the atlas keys it without colliding, where pixel snapping
 * puts it, and whether the paint loop walks runs the way the value says &mdash; all of that needs
 * a GL context, and the golden screenshots are where it is checked. This half is the half that can
 * be pinned exactly and reviewed as numbers; that half is the half that has to be looked at.
 */
class ShapedAdvanceParityTest {

    private ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    /**
     * The bundles' locales whose scripts Roboto draws, which is the population ADR 031 Finding 5
     * makes its claim about.
     *
     * <p>{@code hi}, {@code ja}, {@code ko} and the two Chinese tags are deliberately absent: the
     * bundled face has no Devanagari and no CJK, so every character of those bundles resolves
     * through the fallback chain or to {@code .notdef}, and comparing the two paths there would be
     * a statement about face fallback rather than about shaping. Devanagari is also the one script
     * the ADR says <em>must</em> come out different once it has a face, which is Phase 5.
     */
    private static final String[] LOCALES = {
        "cs", "de", "es", "fr", "id", "it", "nl", "pl", "pt", "pt-BR", "ru", "tr", "uk", "vi",
    };

    /** Every bundle family the toolkit and this backend ship, across both resource roots. */
    private static final String[] BUNDLES = {"components", "theme", "colorpicker", "display"};

    /**
     * Greek, which there is no bundle to draw on for, and the ADR's own eight rows so the corpus
     * is a superset of what was measured. The Greek here is the vocabulary the bundles are made of
     * &mdash; the text-menu verbs, the search placeholder, the theme names &mdash; so the script
     * is covered by the same kind of string as the rest, not by a specimen.
     */
    private static final String[] EXTRA = {
        "Waltz, bad nymph", "Théâtre", "Ação", "Ñandú", "Привет", "Ελληνικά",
        "Αντιγραφή", "Επικόλληση", "Αναζήτηση…", "Ακύρωση", "Επιλογή όλων", "Υψηλή αντίθεση",
    };

    /**
     * The ligature cases, stated as literals because nothing makes a translation contain one
     * &mdash; which is why the ADR had to reach for {@code office} and {@code fi} too, rather than
     * pointing at a screen. The partition below is computed and never assumed, so a translated
     * string that does contain one moves into this half on its own and needs no edit here.
     */
    private static final String[] LIGATURES = {
        "office", "fi", "fl", "ffi", "ffl", "flag", "waffle", "affix", "efficient",
        "Grafik", "final", "fluffy", "Profil", "Konfiguration",
    };

    /** One row of ADR 031 Finding 5: a string and its two widths, in Roboto font units. */
    private record Finding5(String text, int today, int harfBuzz) {
    }

    /**
     * Finding 5 verbatim. Recorded before the implementation existed, so every row is a prediction
     * this suite either confirms or falsifies; none of them was read back off the code.
     */
    private static final List<Finding5> FINDING_5 = List.of(
        new Finding5("Waltz, bad nymph", 16094, 16094),
        new Finding5("Théâtre", 6981, 6981),
        new Finding5("Ação", 4690, 4690),
        new Finding5("Ñandú", 5990, 5990),
        new Finding5("Привет", 7024, 7024),
        new Finding5("Ελληνικά", 8565, 8565),
        new Finding5("office", 5248, 5074),
        new Finding5("fi", 1210, 1135));

    @BeforeEach
    void installRuntime() {
        // FontStore entry points are UI-thread confined (enforced): bind the JUnit thread as the
        // UI thread, exactly as the other font tests do. Nothing here opens a window or a context.
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

    // ============================================================================================
    // The claim.
    // ============================================================================================

    /**
     * The whole acceptance criterion in one comparison: over every string this toolkit ships in
     * every language whose script the bundled face draws, the set of strings whose width shaping
     * changes is <b>exactly</b> the set with an f-ligature in it. Not "close", not "within a
     * tolerance" &mdash; the same float.
     *
     * <p>Asserting the two <em>sets</em> rather than each string in turn is what makes this an
     * acceptance test rather than a regression net. A one-sided check would pass a build where
     * shaping had quietly stopped happening: every width would agree, including {@code office}'s.
     * Requiring the ligature side to differ as well means the only way to pass is to shape, and to
     * shape exactly as the ADR predicted.
     */
    @Test
    void shapingMovesNoShippedStringExceptWhereAnFLigatureFallsInIt() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            Font units = Font.of(unitsPerEm(store));

            List<String> corpus = corpus();
            // A corpus that shrank is a corpus whose bundles fell off the test classpath, and a
            // vacuous pass here would be read as evidence that shaping changed nothing.
            assertTrue(corpus.size() >= 200,
                    "the shipped bundles are the corpus; only " + corpus.size() + " strings found");

            List<String> movedButShouldNot = new ArrayList<>();
            List<String> heldButShouldNot = new ArrayList<>();
            int multiRun = 0;
            for (String text : corpus) {
                float today = unshaped(store, text, units);
                ShapedText line = ruler.shape(text, units);
                float shaped = line.metrics().width();
                multiRun += line.runs().size() > 1 ? 1 : 0;
                boolean ligature = hasLigatureOpportunity(text);
                if (today != shaped && !ligature) {
                    movedButShouldNot.add(row(text, today, shaped, line));
                } else if (today == shaped && ligature) {
                    heldButShouldNot.add(row(text, today, shaped, line));
                }
                if (ligature) {
                    // Direction and mechanism, not just inequality: a ligature replaces glyphs
                    // with one narrower glyph, so it can only ever make a line shorter, and the
                    // glyph count has to fall below the character count for that to have been
                    // what happened. A width that moved the other way is a different bug wearing
                    // this one's clothes.
                    assertTrue(shaped < today, () -> "a ligature widened the line: " + text);
                    assertTrue(line.glyphCount() < text.codePointCount(0, text.length()),
                            () -> "no glyphs were combined, so the width moved for another "
                                    + "reason: " + text);
                }
            }

            // Exact equality, no delta, and that is the point: these two floats are sums of the
            // same integers, so a tolerance here would be hiding something rather than allowing
            // for something. What it does NOT establish is that the screen is unchanged. This
            // compares the advances layout is built from; it never rasterizes a glyph, never keys
            // the atlas, never snaps a position, and never runs the paint loop. Equal widths with
            // the wrong glyph in the atlas look identical from here and wrong on the display, so
            // the golden screenshots are the other half of this acceptance and not a formality —
            // and they are the only half that can see the ligature actually drawn.
            assertTrue(movedButShouldNot.isEmpty(),
                    () -> "ADR 031 Finding 5 says shaping changes Latin/Greek/Cyrillic width ONLY "
                            + "through the liga feature. These strings have no f-ligature in them "
                            + "and moved anyway:\n" + String.join("\n", movedButShouldNot));
            assertTrue(heldButShouldNot.isEmpty(),
                    () -> "these strings contain an f-ligature and did NOT move, which is what a "
                            + "build with shaping silently switched off looks like:\n"
                            + String.join("\n", heldButShouldNot));
            // Itemization has to be under test too, or this proves parity only for text that is
            // one run. The Russian and Ukrainian strings embed "GPU", so they split Cyrillic /
            // Latin / Cyrillic and are shaped as three separate HarfBuzz calls whose advances are
            // then summed by the builder — and they still land on today's number to the float.
            assertTrue(multiRun > 0, "no corpus string itemized into more than one run");
        }
    }

    /**
     * The eight rows ADR 031 Finding 5 recorded, in the units it recorded them in, against the
     * implementation instead of the probe. If any number here has to be edited, the ADR's
     * measurement was wrong or the pipeline changed, and either is a decision rather than a test
     * fix.
     */
    @Test
    void everyNumberFinding5RecordedIsStillTheNumber() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            Font units = Font.of(unitsPerEm(store));

            for (Finding5 row : FINDING_5) {
                assertEquals(row.today(), unshaped(store, row.text(), units),
                        () -> "the pre-shaping per-code-point width moved for: " + row.text());
                assertEquals(row.harfBuzz(), ruler.shape(row.text(), units).metrics().width(),
                        () -> "the shaped width moved for: " + row.text());
            }
        }
    }

    /**
     * The two rows that are not zero, pinned as deltas as well as as totals, because the delta is
     * the part a reviewer of a re-pinned screenshot has to recognise. A change that turned
     * ligatures off would leave both at zero; one that turned on a different feature set would
     * leave them non-zero and wrong.
     */
    @Test
    void theLigatureDeltasAreTheEnumeratedOnesAndNothingElse() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            Font units = Font.of(unitsPerEm(store));

            ShapedText office = ruler.shape("office", units);
            assertEquals(-174f, office.metrics().width() - unshaped(store, "office", units),
                    "ffi becomes one glyph, and it is 174 font units narrower than the three");
            assertEquals(4, office.glyphCount(), "six characters, four glyphs");

            ShapedText fi = ruler.shape("fi", units);
            assertEquals(-75f, fi.metrics().width() - unshaped(store, "fi", units));
            assertEquals(1, fi.glyphCount());

            // The other half of the enumeration, and the half that is easy to get wrong by
            // assuming rather than measuring: Roboto's liga covers f_i, f_l, f_f_i and f_f_l, and
            // NOT f_f. So "Öffnen" and "Effekt" shape to exactly today's width, and a predicate
            // that treated a bare "ff" as a ligature opportunity would fail this suite on real
            // German UI text.
            for (String noLigature : new String[]{"ff", "Öffnen", "Effekt"}) {
                assertEquals(unshaped(store, noLigature, units),
                        ruler.shape(noLigature, units).metrics().width(),
                        () -> "Roboto has no f_f ligature, so this must not move: " + noLigature);
            }
        }
    }

    /**
     * The same parity at the sizes a window actually draws at, so that the exactness above is not
     * an artifact of the one size that makes the arithmetic trivially exact.
     *
     * <p>At an em size and at 16 the scale is a power of two, so both paths sum exact multiples of
     * one ulp and the comparison is bit-for-bit. At a size that is not, it need not be: the
     * per-code-point walk adds {@code advance * scale} and {@code kern * scale} as two terms where
     * the shaper hands back one pre-kerned advance, and the two orderings can round apart. They
     * are measured to land on the same float anyway, but a tolerance is the honest bound on what
     * the arithmetic guarantees, and a hairline is not a layout change.
     */
    @Test
    void parityHoldsAtTheSizesTheToolkitDrawsAtAndNotOnlyAtAnEmSize() {
        try (FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            List<String> corpus = corpus();

            for (float size : new float[]{11f, 13f, 16f, 17.5f, 20f, 32f}) {
                Font font = Font.of(size);
                for (String text : corpus) {
                    if (hasLigatureOpportunity(text)) {
                        continue;
                    }
                    assertEquals(unshaped(store, text, font),
                            ruler.shape(text, font).metrics().width(), 0.005f,
                            () -> "shaping moved this line at size " + size + ": " + text);
                }
            }
        }
    }

    // ============================================================================================
    // The corpus.
    // ============================================================================================

    /**
     * The baseline: the pre-shaping per-code-point walk, taken from the store rather than from the
     * ruler. The class javadoc says why it cannot be the ruler's {@code measure} any more.
     */
    private static float unshaped(FontStore store, String text, Font font) {
        return store.measure(font, text).width();
    }

    /**
     * An f followed by an i or an l: the only sequence in this face's {@code liga} that produces a
     * glyph, and therefore the only thing Finding 5 predicts a width change for.
     */
    private static boolean hasLigatureOpportunity(String text) {
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) == 'f' && (text.charAt(i + 1) == 'i' || text.charAt(i + 1) == 'l')) {
                return true;
            }
        }
        return false;
    }

    /** Every distinct string in the corpus, bundles first, in a stable order. */
    private static List<String> corpus() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String locale : LOCALES) {
            for (String bundle : BUNDLES) {
                // Absent combinations are normal: only one bundle family is translated into every
                // locale. A missing file is a translation nobody wrote, not a broken classpath —
                // which is what the corpus-size floor in the test above is for.
                load("/limn/i18n/" + bundle + "_" + locale + ".properties", values);
            }
        }
        values.addAll(List.of(EXTRA));
        values.addAll(List.of(LIGATURES));
        return new ArrayList<>(values);
    }

    private static void load(String resource, LinkedHashSet<String> into) {
        try (InputStream in = ShapedAdvanceParityTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                return;
            }
            Properties properties = new Properties();
            // Explicit UTF-8 reader: the byte-stream overload of load() is ISO-8859-1 by
            // specification, and these bundles are UTF-8, so reading them the other way turns
            // every accented character into two and every measurement here into fiction.
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String key : properties.stringPropertyNames()) {
                into.add(properties.getProperty(key));
            }
        } catch (IOException failure) {
            throw new AssertionError("unreadable bundle: " + resource, failure);
        }
    }

    /**
     * The face's own em, so a font size can be chosen that makes the font-unit scale exactly one.
     * Read back through the scale rather than from the {@code head} table, because the scale is
     * what every measurement here actually multiplies by.
     */
    private static float unitsPerEm(FontStore store) {
        float em = 1f / store.resolve(Font.of(16)).scaleForSize(1f);
        assertEquals(Math.round(em), em, "a face's em is a whole number of font units");
        return em;
    }

    private static String row(String text, float today, float shaped, ShapedText line) {
        return String.format("  %-44s %10.3f -> %10.3f  delta %+.3f  glyphs %d  chars %d  runs %d",
                '|' + text + '|', today, shaped, shaped - today, line.glyphCount(),
                text.codePointCount(0, text.length()), line.runs().size());
    }
}
