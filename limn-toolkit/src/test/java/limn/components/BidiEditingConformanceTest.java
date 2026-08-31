package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bidirectional caret, selection and hit-testing, over the real widgets, driven through the real
 * public API: mouse events in, key events in, painted geometry and model state out.
 *
 * <p><b>Why this file exists separately from the per-widget tests.</b> Bidi caret behaviour is the
 * one part of a text widget that looks right in a screenshot while being wrong. A caret drawn at
 * the far end of a run, a discontiguous selection painted as one rectangle over text the user never
 * selected, a Backspace that removes the character to the left on screen instead of the one before
 * it in the string &mdash; each of those renders as a perfectly plausible picture. So every
 * expectation here is a <em>number</em>, stated in advance from the decisions and the frozen API,
 * and never read back from what an implementation happens to do.
 *
 * <p><b>The two axes, and why the numbers can be exact.</b> {@code ComponentTestBase.RULER} makes
 * every code point 10&nbsp;logical points wide, and the degraded shaping path measures one grapheme
 * cluster at a time, so every cluster in every fixture below is exactly 10&nbsp;pt (a Thai cluster
 * carrying a combining mark is two code points and so 20). A field's text therefore starts at
 * {@link #PAD}, the MEDIUM {@code fieldPadH}, and every caret column and selection edge is
 * {@code PAD + } a multiple of ten. None of it needs a native, a font file or a GPU.
 *
 * <p><b>Reading the caret.</b> {@code caretRect()} is the widget's own answer to "where is the
 * caret", the one the blink damages and the one the IME candidate window is anchored to, and it is
 * available without painting. One test also renders a frame and asserts the painted column equals
 * it, because a caret drawn somewhere the damage rect does not cover leaves an artifact on screen.
 *
 * <p><b>Strings.</b> Real words, every one of them written as escapes and named once, so that no
 * source line here mixes directions and reorders under an editor's own bidi; each is glossed where
 * it is declared, because a reviewer who cannot read the word cannot check the expectation.
 */
class BidiEditingConformanceTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;

    /** The MEDIUM horizontal field inset: where a field's text column starts. */
    private static final float PAD = SizeTokens.of(ControlSize.MEDIUM).fieldPadH();

    /** HORIZONTAL ELLIPSIS, U+2026: the character {@link Label} appends when it truncates. */
    private static final String ELLIPSIS = "\u2026";

    // ---------------------------------------------------------------- the words

    /**
     * Hebrew <i>shalom</i> ("peace", and the everyday greeting): shin, lamed, vav, final mem. Four
     * strong right-to-left letters, one {@code char} and one cluster apiece, and no niqqud &mdash;
     * chosen so the cluster count is unambiguous and every expected x below is a clean multiple of
     * ten.
     */
    private static final String SHALOM = "\u05E9\u05DC\u05D5\u05DD";

    /**
     * Hebrew alef, the first letter of the alphabet: one more right-to-left character to type at a
     * boundary. Deliberately <em>not</em> a letter of {@link #SHALOM}, so the assertion about where
     * it landed cannot be satisfied by a character that was already there.
     */
    private static final String ALEF = "\u05D0";

    /**
     * Arabic <i>marhaba</i> ("hello"): meem, ra, ha, ba, alef. Five strong right-to-left letters.
     * Under a real shaper these join into two or three glyphs; under the degraded path they are
     * five 10&nbsp;pt clusters, which is what makes the caret arithmetic exact while leaving the
     * <em>order</em> &mdash; the thing this file is about &mdash; genuinely right-to-left.
     */
    private static final String MARHABA = "\u0645\u0631\u062D\u0628\u0627";

    /** Arabic <i>salaam</i> ("peace"): seen, lam, alef, meem. The second word of the line below. */
    private static final String SALAAM = "\u0633\u0644\u0627\u0645";

    /**
     * Thai <i>sawatdee chao lok</i> ("hello world"), written the way Thai is written: <b>with no
     * spaces</b>. Twelve chars, ten clusters &mdash; two of the characters are combining vowel
     * signs that belong to the letter before them, which is why a caret must never be able to stop
     * at char 2 or char 5.
     */
    private static final String SAWATDEE = "\u0E2A\u0E27\u0E31\u0E2A\u0E14\u0E35"
            + "\u0E0A\u0E32\u0E27\u0E42\u0E25\u0E01";

    // ------------------------------------------------------------- the fixtures

    /**
     * Latin then Hebrew, base direction LTR (the first strong character is {@code 'a'}), 60&nbsp;pt
     * wide. The direction boundary is at char index&nbsp;2.
     *
     * <pre>
     * text       = "ab" + shalom                                          length 6
     * visual:      a[0,10)  b[10,20) | mem[20,30) vav[30,40) lamed[40,50) shin[50,60)
     * charIndex:      0        1     |     5          4          3            2
     * </pre>
     *
     * <p>Index 2 occupies <b>two</b> points: 20 (trailing {@code 'b'}) and 60 (leading shin, whose
     * leading edge is its <em>right</em> edge). Forty points apart, and a single click can mean
     * either of them.
     */
    private static final String LATIN_THEN_HEBREW = "ab" + SHALOM;

    /**
     * Arabic then a Latin technical term, base direction RTL, 80&nbsp;pt wide: the everyday case of
     * an Arabic sentence carrying a product name.
     *
     * <pre>
     * text       = marhaba + "PDF"                                        length 8
     * visual:      P[0,10) D[10,20) F[20,30) | alef[30,40) ... meem[70,80)
     * charIndex:     5        6        7     |    4                0
     * </pre>
     *
     * <p>This is the <b>discriminating</b> fixture for End: at index 8 the paragraph's own end edge
     * is x=0 and the trailing edge of the last cluster is x=30, so an edge jump that takes the
     * wrong side draws the caret thirty points from where End means. On a line with no embedded run
     * the two coincide and the assertion would be vacuous.
     */
    private static final String ARABIC_THEN_LATIN = MARHABA + "PDF";

    /**
     * Two Arabic words separated by a space, base direction RTL, 100&nbsp;pt wide and one run: char
     * {@code i} occupies {@code [90 - 10*i, 100 - 10*i)}, so the caret x <em>falls</em> as the
     * index rises. The fixture for word movement, and for the one place Left and Ctrl+Left move the
     * caret in opposite directions.
     */
    private static final String ARABIC_TWO_WORDS = MARHABA + " " + SALAAM;

    // ---------------------------------------------------------------- the field

    private TextField field;
    private Scene scene;

    /** A focused 240&times;32 single-line field holding {@code text}, at MEDIUM under RULER. */
    private void field(String text) {
        field = new TextField();
        scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.setClipboard(new TextFieldTest.MockClipboard());
        scene.layoutPass(240, 32);
        scene.requestFocus(field);
        field.setText(text);
    }

    private void key(int keyCode, int mods) {
        scene.keyEvent(keyCode, true, false, mods);
        scene.keyEvent(keyCode, false, false, mods);
        scene.inputBatchEnded();
    }

    /** A press and release at {@code localX}, on the field's centre line. */
    private void click(float localX) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, localX, 16);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, localX, 16);
        scene.inputBatchEnded();
    }

    private void type(int codePoint) {
        scene.charTyped(codePoint);
        scene.inputBatchEnded();
    }

    /** Where the field says its caret is, in its own coordinates. */
    private float caretColumn() {
        return column(field);
    }

    private static float column(TextField widget) {
        return widget.caretRect().x() - widget.localToSceneX();
    }

    /** Selects exactly {@code [start, end)} without going through a gesture. */
    private void select(int start, int end) {
        field.model().setCursor(start, false);
        field.model().setCursor(end, true);
    }

    private static Ink paint(Scene host, float width, float height) {
        Ink ink = new Ink(width, height);
        host.renderFrame(ink);
        return ink;
    }

    // ------------------------------------------------------------- hit testing

    /**
     * Pins: a click on a direction boundary resolves through the cluster under the pointer, so the
     * two insertion points that share that boundary are separately reachable, and the caret comes
     * back at the pixel the user pointed at.
     */
    @Test
    void aClickOnEitherSideOfADirectionBoundaryLandsOnTheSideThePointerWasOn() {
        field(LATIN_THEN_HEBREW);

        // x=15 is the trailing half of 'b', which reads left to right, so the trailing half is the
        // RIGHT half.
        click(PAD + 15);
        assertEquals(2, field.model().cursor(), "the boundary index");
        assertEquals(ShapedText.Affinity.UPSTREAM, field.model().caret().affinity(),
                "the pointer was in the Latin half, so the caret belongs to the character before");
        assertEquals(PAD + 20, caretColumn(), EPS, "and draws where 'b' ends");

        // x=55 is the leading half of shin, which reads right to left, so the leading half is the
        // RIGHT half. Same index, other side, forty points away.
        click(PAD + 55);
        assertEquals(2, field.model().cursor(), "the same boundary index");
        assertEquals(ShapedText.Affinity.DOWNSTREAM, field.model().caret().affinity(),
                "the pointer was in the Hebrew half, so the caret belongs to the character at it");
        assertEquals(PAD + 60, caretColumn(), EPS, "and draws where the Hebrew begins");

        // The painted column must be the one caretRect() describes: a blink damages that column and
        // nothing else, so a caret drawn anywhere else survives the repaint as an artifact.
        assertEquals(PAD + 60, paint(scene, 240, 32).caretX, EPS,
                "the painted caret and the damaged column are one answer");
    }

    // ----------------------------------------------------------------- editing

    /**
     * Pins: what the caret shows is where the next character goes. Latin typed on the Latin side of
     * the boundary occupies the cell the caret was drawn in; Hebrew typed on the Hebrew side of the
     * same boundary occupies the cell forty points away that the caret was drawn in there.
     */
    @Test
    void typingAtABoundaryPutsTheCharacterWhereTheCaretWasDrawn() {
        field(LATIN_THEN_HEBREW);
        click(PAD + 15);
        assertEquals(PAD + 20, caretColumn(), EPS, "the caret is on the Latin side");
        type('x');
        assertEquals("ab" + "x" + SHALOM, field.text());
        assertEquals(PAD + 30, caretColumn(), EPS, "the caret trails the 'x' it just typed");
        select(2, 3);
        Ink ltr = paint(scene, 240, 32);
        assertEquals(1, ltr.boxes.size(), "the typed character is one box");
        assertEquals(PAD + 20, ltr.boxes.get(0)[0], EPS,
                "and it starts exactly where the caret was standing");
        assertEquals(10, ltr.boxes.get(0)[1], EPS);

        field(LATIN_THEN_HEBREW);
        click(PAD + 55);
        assertEquals(PAD + 60, caretColumn(), EPS, "the caret is on the Hebrew side");
        type(ALEF.codePointAt(0));
        assertEquals("ab" + ALEF + SHALOM, field.text());
        // The caret does NOT move: the alef became the logically-first letter of the right-to-left
        // run, so it was drawn to the RIGHT of the caret and everything already there shifted right
        // with it. The caret staying put is what "the text grows away from the caret" looks like in
        // a box that is still anchored on its left (layout mirroring is out of scope).
        assertEquals(PAD + 60, caretColumn(), EPS, "the caret stays on the boundary it was on");
        select(2, 3);
        Ink rtl = paint(scene, 240, 32);
        assertEquals(1, rtl.boxes.size(), "the typed character is one box");
        assertEquals(PAD + 60, rtl.boxes.get(0)[0], EPS,
                "and it starts exactly where the caret was standing, forty points from the Latin");
        assertEquals(10, rtl.boxes.get(0)[1], EPS);
    }

    /**
     * Pins: Backspace and Delete are range operations on the <em>string</em>. On a boundary the
     * logical neighbour and the visual one are different characters, so this is the case where a
     * deletion driven off the screen order removes the wrong letter and looks entirely reasonable
     * doing it.
     */
    @Test
    void backspaceAndDeleteAtABoundaryRemoveTheLogicalNeighbourNotTheVisualOne() {
        field(LATIN_THEN_HEBREW);
        // Index 2 on the Hebrew side: the caret is drawn at x=60, and the cluster abutting it on
        // the LEFT is shin. The character logically before the caret is 'b', forty points away.
        click(PAD + 55);
        key(Keys.BACKSPACE, 0);
        assertEquals("a" + SHALOM, field.text(),
                "backspace took 'b'; taking shin would have left \"ab\" and dropped the greeting's "
                        + "first letter");

        field(LATIN_THEN_HEBREW);
        // Index 2 on the Latin side: the caret is drawn at x=20, and the cluster abutting it on the
        // RIGHT is the FINAL mem, the last character of the string. The character logically after
        // the caret is shin.
        click(PAD + 15);
        key(Keys.DELETE, 0);
        assertEquals("ab" + SHALOM.substring(1), field.text(),
                "delete took shin; taking mem would have eaten the word's last letter instead");
    }

    // ----------------------------------------------------------- arrow movement

    /**
     * Pins: Left and Right are visual. Six presses of Right walk the caret rightwards ten points at
     * a time across a line whose logical indices go 1, 2, 5, 4, 3, 2 &mdash; and six presses of
     * Left bring it back to the exact position, side included, that it started from.
     *
     * <p>The round trip is the assertion that cannot be faked. An index-taking step cannot make it:
     * handed a bare index on the boundary it has to guess a side, and whichever it guesses, one of
     * the six presses jumps to the far end of the line.
     */
    @Test
    void arrowsWalkAMixedLineInVisualOrderAndReturnToWhereTheyStarted() {
        field(LATIN_THEN_HEBREW);
        field.model().setCursor(0, false);
        assertEquals(PAD + 0, caretColumn(), EPS, "start at the left edge");

        float[] rightColumns = new float[6];
        int[] rightIndices = new int[6];
        for (int i = 0; i < 6; i++) {
            key(Keys.RIGHT, 0);
            rightColumns[i] = caretColumn();
            rightIndices[i] = field.model().cursor();
        }
        assertArrayEquals(new float[]{PAD + 10, PAD + 20, PAD + 30, PAD + 40, PAD + 50, PAD + 60},
                rightColumns, EPS, "Right moves right, one cluster at a time, without exception");
        assertArrayEquals(new int[]{1, 2, 5, 4, 3, 2}, rightIndices,
                "and the string indices it visits are not in order, which is the whole point");

        float[] leftColumns = new float[6];
        int[] leftIndices = new int[6];
        for (int i = 0; i < 6; i++) {
            key(Keys.LEFT, 0);
            leftColumns[i] = caretColumn();
            leftIndices[i] = field.model().cursor();
        }
        assertArrayEquals(new float[]{PAD + 50, PAD + 40, PAD + 30, PAD + 20, PAD + 10, PAD + 0},
                leftColumns, EPS, "Left retraces the same columns");
        assertArrayEquals(new int[]{3, 4, 5, 6, 1, 0}, leftIndices,
                "through the mirrored index walk");
        assertEquals(new ShapedText.Position(0, ShapedText.Affinity.DOWNSTREAM),
                field.model().caret(),
                "twelve presses land back on the position they left, side included");
    }

    // -------------------------------------------------------------- selection

    /**
     * Pins the box count. A range that is contiguous in the string stops being contiguous on the
     * line the moment it crosses a direction boundary; the smallest rectangle covering both halves
     * would highlight a character the user did not select, and a screenshot cannot tell the two
     * apart.
     */
    @Test
    void shiftArrowSelectionAcrossABoundaryPaintsTwoBoxesAndNotOne() {
        field(LATIN_THEN_HEBREW);
        field.model().setCursor(1, false);
        key(Keys.RIGHT, Keys.MOD_SHIFT);
        key(Keys.RIGHT, Keys.MOD_SHIFT);

        assertEquals(1, field.model().selectionStart());
        assertEquals(5, field.model().selectionEnd());
        assertEquals("b" + SHALOM.substring(0, 3), field.model().selectedText(),
                "'b' and the first three letters of the greeting");

        Ink ink = paint(scene, 240, 32);
        assertEquals(2, ink.boxes.size(),
                "two boxes: the final mem is drawn BETWEEN them and is not selected");
        assertEquals(PAD + 10, ink.boxes.get(0)[0], EPS, "'b'");
        assertEquals(10, ink.boxes.get(0)[1], EPS);
        assertEquals(PAD + 30, ink.boxes.get(1)[0], EPS, "vav, lamed, shin");
        assertEquals(30, ink.boxes.get(1)[1], EPS);
        assertEquals(10, ink.boxes.get(1)[0] - (ink.boxes.get(0)[0] + ink.boxes.get(0)[1]), EPS,
                "exactly one unselected cluster wide: the gap is the character the user kept");
    }

    /**
     * The same range in the multi-line editor, which paints its own per-line bands and has its own
     * three specials around them. A field and an area showing one identical line must highlight
     * identically, or the two widgets have two answers to one question.
     */
    @Test
    void aMixedLineInATextAreaHighlightsInTheSameTwoBoxesAsAField() {
        TextArea area = new TextArea();
        Scene host = new Scene(area);
        host.setTextRuler(RULER);
        host.setClipboard(new TextFieldTest.MockClipboard());
        host.layoutPass(200, 100);
        host.requestFocus(area);
        area.setText(LATIN_THEN_HEBREW);
        // No trailing newline is inside the range, so there is no break to hint at: the boxes are
        // the selection and nothing else.
        area.model().setCursor(1, false);
        area.model().setCursor(5, true);

        Ink ink = paint(host, 200, 100);
        assertEquals(2, ink.boxes.size(), "the area must not collapse the range either");
        assertEquals(PAD + 10, ink.boxes.get(0)[0], EPS);
        assertEquals(10, ink.boxes.get(0)[1], EPS);
        assertEquals(PAD + 30, ink.boxes.get(1)[0], EPS);
        assertEquals(30, ink.boxes.get(1)[1], EPS);
    }

    // ------------------------------------------------------ edges and by-word

    /**
     * Pins: Home and End are logical, so in a right-to-left paragraph Home goes to the visual RIGHT
     * and End to the visual LEFT &mdash; and each takes the side that names the paragraph's own
     * edge rather than the leading edge of whichever cluster happens to sit there.
     *
     * <p>Logical is forced, not merely conventional: Shift+Home has to produce a selection, a
     * selection is one contiguous range of the string, and the range from the caret to the visual
     * left edge of a mixed line is not one.
     */
    @Test
    void homeAndEndInARightToLeftParagraphLandOnTheParagraphEdgesAndNotOnTheEndCluster() {
        field(ARABIC_THEN_LATIN);

        key(Keys.HOME, 0);
        assertEquals(0, field.model().cursor());
        assertEquals(PAD + 80, caretColumn(), EPS,
                "Home is the start of the string, which in this paragraph is its right edge");

        key(Keys.END, 0);
        assertEquals(8, field.model().cursor());
        // The other side of index 8 is x=30, the trailing edge of the 'F' -- a real point on the
        // line, thirty points from where End means, and exactly what the by-one-unit rule would
        // have produced here.
        assertEquals(PAD + 0, caretColumn(), EPS,
                "End is the paragraph's own end edge: the left edge of the box");

        key(Keys.HOME, Keys.MOD_SHIFT);
        assertEquals(ARABIC_THEN_LATIN, field.model().selectedText(),
                "and Shift+Home takes the whole line, because the range is one piece of string");
    }

    /**
     * Pins the cost of the split, stated rather than discovered: in right-to-left text Left and
     * Ctrl+Left move the caret in <em>opposite</em> directions. The arrow is named for a direction
     * on the screen; a word is a range of the string, and {@code deleteWordBackward} has to be able
     * to remove what {@code moveWordLeft} skipped over.
     */
    @Test
    void theLeftArrowAndCtrlLeftMoveInOppositeDirectionsInARightToLeftParagraph() {
        field(ARABIC_TWO_WORDS);
        field.model().setCursor(5, false);
        assertEquals(PAD + 50, caretColumn(), EPS, "the caret starts on the space, mid-line");

        key(Keys.LEFT, 0);
        assertEquals(6, field.model().cursor());
        assertEquals(PAD + 40, caretColumn(), EPS, "Left is visual: ten points to the left");

        field.model().setCursor(5, false);
        key(Keys.LEFT, Keys.MOD_CONTROL);
        assertEquals(0, field.model().cursor(), "Ctrl+Left is logical: the start of marhaba");
        assertEquals(PAD + 100, caretColumn(), EPS,
                "which is fifty points to the RIGHT, at the paragraph's start edge");
    }

    /**
     * Pins word selection over Arabic: the gesture takes the whole word between the spaces, and one
     * direction run highlights as one box.
     *
     * <p>Driven through the word-boundary keys rather than a double click, because the toolkit's
     * pointer stream carries no click count and there is no double-click gesture to drive; these
     * are the calls such a gesture would delegate to, and they are what defines "the word under the
     * caret" for every widget here.
     */
    @Test
    void wordSelectionOverAnArabicWordTakesTheWholeWordAndPaintsItAsOneBox() {
        field(ARABIC_TWO_WORDS);
        field.model().setCursor(2, false); // inside marhaba
        key(Keys.LEFT, Keys.MOD_CONTROL);
        assertEquals(0, field.model().cursor(), "back to the word's logical start");
        key(Keys.RIGHT, Keys.MOD_CONTROL | Keys.MOD_SHIFT);

        assertEquals(0, field.model().selectionStart());
        assertEquals(5, field.model().selectionEnd());
        assertEquals(MARHABA, field.model().selectedText(), "the whole word, and only it");

        Ink ink = paint(scene, 240, 32);
        assertEquals(1, ink.boxes.size(), "one direction run, so one box");
        assertEquals(PAD + 50, ink.boxes.get(0)[0], EPS,
                "and it is the RIGHT half of the line, which is where the first word is drawn");
        assertEquals(50, ink.boxes.get(0)[1], EPS);
    }

    /**
     * Pins word selection over a script with no spaces: the editor's word rule is character-class
     * based and knows no Thai dictionary, so the whole run is one word. It takes all of it &mdash;
     * it does not fall back to one character, and it does not cut somewhere a dictionary would not.
     *
     * <p>The second half pins what a caret in Thai must never do: two of these twelve chars are
     * combining vowel signs, and the caret stops are the shaper's cluster boundaries, so pressing
     * Right steps 1, 3, 4, 6 and cannot land inside a letter-plus-vowel pair.
     */
    @Test
    void wordSelectionOverUnspacedThaiTakesTheWholeRunAndNeverSplitsACluster() {
        field(SAWATDEE);
        field.model().setCursor(4, false);
        key(Keys.LEFT, Keys.MOD_CONTROL);
        assertEquals(0, field.model().cursor(), "no space behind: the run's start is the word's");
        key(Keys.RIGHT, Keys.MOD_CONTROL | Keys.MOD_SHIFT);
        assertEquals(SAWATDEE, field.model().selectedText(),
                "no space ahead either: the whole greeting is one word to an editor with no "
                        + "dictionary, and taking less would cut where no break is allowed");

        Ink ink = paint(scene, 240, 32);
        assertEquals(1, ink.boxes.size(), "one left-to-right run, one box");
        assertEquals(PAD + 0, ink.boxes.get(0)[0], EPS);
        assertEquals(120, ink.boxes.get(0)[1], EPS, "ten clusters, two of them two chars wide");

        field.model().setCursor(0, false);
        int[] stops = new int[4];
        for (int i = 0; i < 4; i++) {
            key(Keys.RIGHT, 0);
            stops[i] = field.model().cursor();
        }
        assertArrayEquals(new int[]{1, 3, 4, 6}, stops,
                "chars 2 and 5 are combining vowel signs and are not caret stops");
    }

    // ------------------------------------------------------------ masked entry

    /**
     * Pins that the mask is untouched by all of the above. A password field's marks are not glyphs:
     * its content never reaches a shaper, its geometry is one multiplication, and it stays left to
     * right however right to left the secret is. And it still lets nothing out while masked.
     *
     * <p>The two ratios are restated here as literals rather than read from the component: a test
     * that reuses the constant under test cannot catch a wrong constant.
     */
    @Test
    void aMaskedPasswordFieldKeepsItsOwnLeftToRightGeometryAndLetsNothingOut() {
        float diameter = 0.36f;
        float advance = 0.56f;
        float size = SizeTokens.of(ControlSize.MEDIUM).body().size();
        float cell = advance * size;

        PasswordField secret = new PasswordField();
        Scene host = new Scene(secret);
        host.setTextRuler(RULER);
        TextFieldTest.MockClipboard vault = new TextFieldTest.MockClipboard();
        host.setClipboard(vault);
        host.layoutPass(240, 32);
        host.requestFocus(secret);
        secret.setText(MARHABA);

        Ink masked = paint(host, 240, 32);
        assertEquals(List.of(), masked.texts,
                "a masked field typesets nothing at all, Arabic included");
        assertEquals(5, masked.dots.size(), "one mark per grapheme cluster of the secret");
        for (int i = 0; i < 5; i++) {
            assertEquals(PAD + (i + 0.5f) * cell, masked.dots.get(i)[0], EPS,
                    "dot " + i + " sits half a cell into its own cell, counted from the LEFT");
        }
        assertEquals(diameter * size / 2, masked.dots.get(0)[2], EPS, "and is the tabled size");

        // The caret is that same arithmetic and not shaped geometry: two cells in from the left
        // edge. Routed through the shaper the secret would resolve right-to-left and index 2 would
        // draw three cells from the left instead.
        secret.model().setCursor(2, false);
        assertEquals(PAD + 2 * cell, column(secret), EPS,
                "the caret counts cells forward, whatever direction the plaintext reads");

        host.keyEvent(Keys.A, true, false, Keys.MOD_CONTROL);
        host.keyEvent(Keys.A, false, false, Keys.MOD_CONTROL);
        host.keyEvent(Keys.C, true, false, Keys.MOD_CONTROL);
        host.keyEvent(Keys.C, false, false, Keys.MOD_CONTROL);
        host.inputBatchEnded();
        assertEquals("", vault.value, "copy is refused while masked");

        secret.setRevealed(true);
        host.keyEvent(Keys.C, true, false, Keys.MOD_CONTROL);
        host.keyEvent(Keys.C, false, false, Keys.MOD_CONTROL);
        host.inputBatchEnded();
        assertEquals(MARHABA, vault.value, "and allowed once the user has revealed it");

        Ink revealed = paint(host, 240, 32);
        assertEquals(List.of(MARHABA), revealed.texts, "revealed, it typesets like any field");
        assertTrue(revealed.dots.isEmpty(), "and no marks; a stale held line would show dots");
    }

    // ------------------------------------------------------------- ellipsising

    /**
     * Pins that truncation is a cut in the <em>string</em>, not in the picture. The kept part is
     * the logical head of the word in its original order; nothing is dropped from the middle and
     * nothing is reordered. What changes in a right-to-left paragraph is only where the ellipsis is
     * drawn:
     * it is logically last, so it renders on the visual left, and that falls out of shaping the
     * concatenation rather than out of a branch anywhere.
     */
    @Test
    void aRightToLeftLabelEllipsisesItsLogicalTailAndReordersNothing() {
        Label label = new Label(MARHABA);
        Scene host = new Scene(label);
        host.setTextRuler(RULER);
        // 50pt of word in a 35pt box; the ellipsis costs 10, leaving 25 of budget: two clusters.
        host.layoutPass(35, 12);

        assertEquals(List.of(MARHABA.substring(0, 2) + ELLIPSIS), label.displayedLines(),
                "meem, ra, ellipsis");

        String shown = label.displayedLines().get(0);
        assertEquals(3, shown.length(), "two kept characters and one ellipsis");
        assertEquals(MARHABA.charAt(0), shown.charAt(0), "meem, still first");
        assertEquals(MARHABA.charAt(1), shown.charAt(1), "ra, still second");
        assertEquals(ELLIPSIS.charAt(0), shown.charAt(2), "the ellipsis, logically last");
        assertNotEquals(MARHABA.substring(3) + ELLIPSIS, shown,
                "keeping the characters drawn on the visual left would keep the word's TAIL");
    }

    // ---------------------------------------------------------------- harness

    /**
     * One frame of a text widget, in the widget's own coordinates: the selection boxes, the caret
     * column, the mask marks and any typeset run.
     *
     * <p>The translate stack is tracked because the multi-line editor puts its whole content inset
     * in a {@code translate} and draws every line at {@code x == 0}; without it "where did this box
     * land" is unanswerable from the recorded arguments.
     */
    private static final class Ink extends FakeCanvas {

        /** {@code {x, width}} per filled band, in the order the paint asked for them. */
        private final List<float[]> boxes = new ArrayList<>();

        /** {@code {cx, cy, radius}} per mask mark. */
        private final List<float[]> dots = new ArrayList<>();

        private final List<String> texts = new ArrayList<>();

        private float caretX = Float.NaN;

        private final Deque<float[]> stack = new ArrayDeque<>();
        private float tx;
        private float ty;

        Ink(float width, float height) {
            super(width, height);
        }

        @Override
        public void save() {
            super.save();
            stack.push(new float[]{tx, ty});
        }

        @Override
        public void restore() {
            super.restore();
            if (!stack.isEmpty()) { // lenient: a widget that throws mid-paint must not mask itself
                float[] saved = stack.pop();
                tx = saved[0];
                ty = saved[1];
            }
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            boxes.add(new float[]{tx + x, w});
        }

        @Override
        public void fillCircle(float cx, float cy, float radius, Paint paint) {
            dots.add(new float[]{tx + cx, ty + cy, radius});
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(text);
        }

        /** The caret is the only vertical line these fixtures draw: no button, so no divider. */
        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth,
                             Paint paint) {
            if (x1 == x2) {
                caretX = tx + x1;
            }
        }
    }
}
