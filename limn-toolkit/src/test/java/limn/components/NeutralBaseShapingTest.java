package limn.components;

import limn.components.chart.Chart;
import limn.components.chart.ChartPoint;
import limn.components.chart.ChartSeries;
import limn.graphics.Canvas;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The neutral fallback, in the files that used to draw plain strings: {@link Spinner},
 * {@link MenuBar}, {@link PopupMenu}, {@link Chart}, {@link Checkbox} and the mark
 * {@link MenuInk} places for two of them. ADR 032's Decision 7 says a widget resolves the base
 * direction of the text it owns and hands it to the shaper; these are the widgets it could not
 * reach until they held the lines they draw.
 *
 * <p><b>Several widgets and one file, because it is one decision.</b> The same three questions are
 * asked of each: a string with no strong character takes the widget's own direction, a string
 * with one keeps deciding for itself, and the left-to-right case is exactly what it always was.
 * Split across six files those would read as six coincidences.
 *
 * <p><b>What is asserted, and why it is not a screenshot.</b> Every widget here now draws through
 * {@code Canvas.drawText(ShapedText, …)}, so the recorded value carries the paragraph direction it
 * was shaped for. That is the fact under test, and it is invisible in a picture: a run of digits
 * is drawn in the same order and at the same width under either base, and differs only in the
 * bidi level it took &mdash; which is precisely what decides where the next character lands, where
 * a caret sits, and which end a sign is drawn at.
 *
 * <p>The fake {@link #RULER} is font-blind, so nothing here asserts a glyph. It is <em>not</em>
 * direction-blind: {@code TextRuler.shape}'s default reorders with {@code java.text.Bidi}, which
 * is what lets the reordering assertions below run with no font file. What a real face does to a
 * base direction is pinned in the backend's own {@code NumericRunOrderTest}.
 */
class NeutralBaseShapingTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final SizeTokens MEDIUM = SizeTokens.of(ControlSize.MEDIUM);

    /** Records the shaped lines a frame drew, in order, with the x each was placed at. */
    private static final class LineRecorder extends FakeCanvas {
        final List<ShapedText> lines = new ArrayList<>();
        final List<Float> xs = new ArrayList<>();

        LineRecorder(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawText(ShapedText text, float x, float y, Paint paint) {
            lines.add(text);
            xs.add(x);
        }

        /** The one line whose text is {@code text}, and the assertion that it was drawn at all. */
        ShapedText line(String text) {
            for (ShapedText line : lines) {
                if (line.text().equals(text)) {
                    return line;
                }
            }
            throw new AssertionError("nothing drew \"" + text + "\"; drew " + texts());
        }

        float xOf(String text) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).text().equals(text)) {
                    return xs.get(i);
                }
            }
            throw new AssertionError("nothing drew \"" + text + "\"; drew " + texts());
        }

        List<String> texts() {
            List<String> out = new ArrayList<>();
            for (ShapedText line : lines) {
                out.add(line.text());
            }
            return out;
        }
    }

    /** The base every line of {@code text} in this frame was shaped for. */
    private static void assertBase(ShapedText.Direction expected, ShapedText line, String why) {
        assertEquals(expected, line.baseDirection(), why);
    }

    /**
     * Where the character at {@code charIndex} is drawn within its line, left to right. Asked of
     * the glyph, because the claim these make is about where ink lands.
     */
    private static float visualX(ShapedText line, int charIndex) {
        for (int g = 0; g < line.glyphCount(); g++) {
            if (line.glyphCluster(g) == charIndex) {
                return line.glyphX(g);
            }
        }
        throw new AssertionError("no glyph came from char " + charIndex + " of " + line.text());
    }

    // ------------------------------------------------------------------ Spinner

    private static final float SPIN_W = MEDIUM.spinnerWidth();
    private static final float SPIN_H = MEDIUM.controlHeight();

    private Spinner spinner;
    private Scene spinnerScene;

    private void buildSpinner(Spinner s, LayoutDirection direction) {
        spinner = s;
        spinner.setLayoutDirection(direction);
        spinnerScene = new Scene(spinner);
        spinnerScene.setTextRuler(RULER);
        spinnerScene.layoutPass(SPIN_W, SPIN_H);
    }

    private LineRecorder paintSpinner() {
        LineRecorder recorder = new LineRecorder(SPIN_W, SPIN_H);
        spinnerScene.renderFrame(recorder);
        return recorder;
    }

    @Test
    void aSpinnersValueIsShapedForTheSpinnersOwnDirection() {
        // A formatted number has no strong character anywhere in it, so the fallback is the whole
        // of what decides its paragraph. This is the case Decision 7 exists for.
        buildSpinner(new Spinner(0, 100, 1).setValue(42), LayoutDirection.RTL);
        assertBase(ShapedText.Direction.RTL, paintSpinner().line("42"),
                "a bare number reads the way the form around it reads");

        buildSpinner(new Spinner(0, 100, 1).setValue(42), LayoutDirection.LTR);
        assertBase(ShapedText.Direction.LTR, paintSpinner().line("42"),
                "and left to right is unchanged");
    }

    @Test
    void aClockFaceShapesAllThreeOfItsFieldsForOneParagraph() {
        // Three draws, so three lines -- the two fields are highlighted separately and the colon
        // is drawn in a different ink. They must agree about the paragraph or the run they compose
        // is measured against two.
        buildSpinner(Spinner.time().setValue(7 * 60 + 30), LayoutDirection.RTL);
        LineRecorder r = paintSpinner();
        for (String field : new String[]{"07", ":", "30"}) {
            assertBase(ShapedText.Direction.RTL, r.line(field), field + " reads with the form");
        }
        // And the order does not move with it: ADR 032 §5's decision, at the widget.
        assertTrue(r.xOf("07") < r.xOf(":") && r.xOf(":") < r.xOf("30"),
                "the hours still lead the minutes: hh:mm does not mirror");
    }

    @Test
    void aClockFaceIsUnchangedReadingLeftToRight() {
        buildSpinner(Spinner.time().setValue(7 * 60 + 30), LayoutDirection.LTR);
        LineRecorder r = paintSpinner();
        for (String field : new String[]{"07", ":", "30"}) {
            assertBase(ShapedText.Direction.LTR, r.line(field), field);
        }
        assertTrue(r.xOf("07") < r.xOf(":") && r.xOf(":") < r.xOf("30"));
    }

    @Test
    void aNegativeValueDrawsItsSignAtTheOtherEndWhenTheFormReadsRightToLeft() {
        // The case that ends the condition docs/design/text-and-input.md used to state for this
        // widget's inline editor. A minus sign is a neutral at the paragraph's EDGE, so under a
        // right-to-left base it takes the paragraph's own odd level and is drawn AFTER the digits,
        // while the digits keep their order between them. A prefix width cannot describe that, and
        // for as long as the caret was placed by one it was placed somewhere the ink is not.
        buildSpinner(new Spinner(-100, 100, 1).setValue(-42), LayoutDirection.RTL);
        ShapedText line = paintSpinner().line("-42");
        assertBase(ShapedText.Direction.RTL, line, "the value reads with the form");
        assertTrue(visualX(line, 0) > visualX(line, 2),
                "the sign is drawn after both digits");
        assertTrue(visualX(line, 1) < visualX(line, 2),
                "and the digits keep their own order between them");

        buildSpinner(new Spinner(-100, 100, 1).setValue(-42), LayoutDirection.LTR);
        ShapedText plain = paintSpinner().line("-42");
        assertBase(ShapedText.Direction.LTR, plain, "left to right is unchanged");
        assertTrue(visualX(plain, 0) < visualX(plain, 1), "the sign still leads");
    }

    @Test
    void theTypedTextIsShapedForTheSpinnersDirectionToo() {
        // The editor's own line, which is the one every caret, click and selection box in the
        // widget is now asked of.
        buildSpinner(new Spinner(0, 1000, 1), LayoutDirection.RTL);
        spinnerScene.requestFocus(spinner);
        "42".codePoints().forEach(spinnerScene::charTyped);
        spinnerScene.inputBatchEnded();
        assertTrue(spinner.isEditing(), "typing a digit starts an edit");
        assertBase(ShapedText.Direction.RTL, paintSpinner().line("42"),
                "the text being typed reads the way the form reads");
    }

    // ------------------------------------------------------------------ MenuBar

    private static final float BAR_W = 400;

    private MenuBar bar;
    private Scene barScene;

    private void buildBar(LayoutDirection direction, String... titles) {
        bar = new MenuBar();
        for (String title : titles) {
            bar.addMenu(title, new Menu().addItem("x", () -> { }));
        }
        bar.setLayoutDirection(direction);
        barScene = new Scene(bar);
        barScene.setTextRuler(RULER);
        barScene.layoutPass(BAR_W, MEDIUM.controlHeight());
    }

    private LineRecorder paintBar() {
        LineRecorder recorder = new LineRecorder(BAR_W, MEDIUM.controlHeight());
        barScene.renderFrame(recorder);
        return recorder;
    }

    @Test
    void aNeutralMenuTitleTakesTheStripsDirectionAndALatinOneDoesNot() {
        // Both in one strip, so the assertion is that the fallback is consulted per title rather
        // than applied to the strip: "2024" has nothing to decide with and takes the interface's
        // direction, and "File" decided at its F before the fallback was reached.
        buildBar(LayoutDirection.RTL, "2024", "File");
        LineRecorder r = paintBar();
        assertBase(ShapedText.Direction.RTL, r.line("2024"),
                "a title that is a year reads with the strip");
        assertBase(ShapedText.Direction.LTR, r.line("File"),
                "a title with a strong character still decides for itself");
    }

    @Test
    void aStripReadingLeftToRightIsUnchanged() {
        buildBar(LayoutDirection.LTR, "2024", "File");
        LineRecorder r = paintBar();
        assertBase(ShapedText.Direction.LTR, r.line("2024"), "the default is unchanged");
        assertBase(ShapedText.Direction.LTR, r.line("File"), "and so is a Latin title");
    }

    // ---------------------------------------------------------------- PopupMenu

    private static final float POP_W = 500;
    private static final float POP_H = 400;

    private Scene popupScene;
    private PopupMenu popup;

    private void openPopup(LayoutDirection direction, String... labels) {
        Menu menu = new Menu();
        for (String label : labels) {
            menu.addItem(label, () -> { });
        }
        popupScene = new Scene(new Label("root"));
        popupScene.setTextRuler(RULER);
        popupScene.setLayoutDirection(direction);
        popupScene.layoutPass(POP_W, POP_H);
        popup = new PopupMenu(menu);
        popup.showInSceneForTest(popupScene, 40, 40, 60, 24);
        popupScene.layoutPass(POP_W, POP_H);
    }

    private LineRecorder paintPopup() {
        LineRecorder recorder = new LineRecorder(POP_W, POP_H);
        popupScene.renderFrame(recorder);
        return recorder;
    }

    @Test
    void aCascadeShapesItsHeldLabelLinesForTheDirectionItWasOpenedWith() {
        // The cache-key assertion. A column's shaped lines are a snapshot taken once at open and
        // read by every later paint, so the direction has to be IN that snapshot: the column is
        // sized from these widths and the rows are painted from these lines, and a snapshot taken
        // without the fallback sizes one line and paints another.
        openPopup(LayoutDirection.RTL, "2024", "Open");
        LineRecorder r = paintPopup();
        assertBase(ShapedText.Direction.RTL, r.line("2024"),
                "a neutral label was shaped with the cascade's own direction");
        assertBase(ShapedText.Direction.LTR, r.line("Open"),
                "and a strong one still decided for itself");

        openPopup(LayoutDirection.LTR, "2024", "Open");
        LineRecorder plain = paintPopup();
        assertBase(ShapedText.Direction.LTR, plain.line("2024"), "left to right is unchanged");
        assertBase(ShapedText.Direction.LTR, plain.line("Open"), "and so is a Latin label");
    }

    @Test
    void anAcceleratorHintDecidesForItselfAndIsNeverReversed() {
        // The hint names physical keys, so it must read as itself. Most hints say so in their own
        // first character -- "F5", "Ctrl+N" -- so the first-strong rule decides them and the
        // cascade's fallback is never reached. It is shaped through the same call as the label
        // anyway, so that the one that IS neutral (a bare "," or "/") is decided rather than
        // defaulted, and so that the width the column reserved is the width of this line.
        Menu menu = new Menu();
        menu.add(MenuItem.of("Open", () -> { }).setAccelerator(Accelerator.of(Keys.F5)));
        popupScene = new Scene(new Label("root"));
        popupScene.setTextRuler(RULER);
        popupScene.setLayoutDirection(LayoutDirection.RTL);
        popupScene.layoutPass(POP_W, POP_H);
        popup = new PopupMenu(menu);
        popup.showInSceneForTest(popupScene, 40, 40, 60, 24);
        popupScene.layoutPass(POP_W, POP_H);

        LineRecorder r = paintPopup();
        ShapedText hint = r.line("F5");
        assertBase(ShapedText.Direction.LTR, hint,
                "its own F decided it, inside a cascade reading the other way");
        assertTrue(visualX(hint, 0) < visualX(hint, 1),
                "and it is drawn as itself: F before 5, never reversed");
    }

    // -------------------------------------------------------------------- Chart

    private static final float CHART_W = 400;
    private static final float CHART_H = 300;

    /** A chart with no marks of its own, so a frame holds only {@link Chart}'s own lines. */
    private static final class BareChart extends Chart {
        @Override
        protected void paintContent(Canvas canvas, float x, float y, float w, float h) {
        }

        @Override
        protected ChartPoint pickAt(float localX, float localY) {
            return null;
        }
    }

    private Scene chartScene;

    private void buildChart(LayoutDirection direction, String... seriesNames) {
        BareChart chart = new BareChart();
        chart.setAnimationDuration(0);
        for (int i = 0; i < seriesNames.length; i++) {
            chart.addSeries(ChartSeries.of(seriesNames[i], i + 1));
        }
        chart.setLayoutDirection(direction);
        chartScene = new Scene(chart);
        chartScene.setTextRuler(RULER);
        chartScene.layoutPass(CHART_W, CHART_H);
    }

    private LineRecorder paintChart() {
        LineRecorder recorder = new LineRecorder(CHART_W, CHART_H);
        chartScene.renderFrame(recorder);
        return recorder;
    }

    @Test
    void aLegendEntryNamedForAYearTakesTheChartsDirection() {
        // The case ADR 032 §9.2 named when it said a chart is where this matters: a series named
        // for a year, a quarter or a channel number carries no strong character at all, so the
        // interface's own direction is the only thing that can decide its paragraph.
        buildChart(LayoutDirection.RTL, "2024", "Vendas");
        LineRecorder r = paintChart();
        assertBase(ShapedText.Direction.RTL, r.line("2024"),
                "a series named for a year reads with the chart");
        assertBase(ShapedText.Direction.LTR, r.line("Vendas"),
                "a series with a strong character still decides for itself");
    }

    @Test
    void aChartReadingLeftToRightIsUnchanged() {
        buildChart(LayoutDirection.LTR, "2024", "Vendas");
        LineRecorder r = paintChart();
        assertBase(ShapedText.Direction.LTR, r.line("2024"), "the default is unchanged");
        assertBase(ShapedText.Direction.LTR, r.line("Vendas"), "and so is a named series");
    }

    @Test
    void aChartTitleIsShapedForTheChartToo() {
        BareChart chart = new BareChart();
        chart.setAnimationDuration(0);
        chart.setTitle("2024");
        chart.setLayoutDirection(LayoutDirection.RTL);
        chartScene = new Scene(chart);
        chartScene.setTextRuler(RULER);
        chartScene.layoutPass(CHART_W, CHART_H);

        LineRecorder r = paintChart();
        assertBase(ShapedText.Direction.RTL, r.line("2024"),
                "the title reads the way the chart reads");
        // The title is placed from the width of the line drawn, so the two cannot disagree: it is
        // pinned one pad in from the edge reading starts on.
        assertEquals(CHART_W - MEDIUM.spacingMedium() - r.line("2024").metrics().width(),
                r.xOf("2024"), EPS,
                "and it is placed from that same line's width");
    }

    // ----------------------------------------------------------------- Checkbox

    private static final float CHECK_W = 200;
    private static final float CHECK_H = 40;

    private Scene checkboxScene;

    private void buildCheckbox(String label, LayoutDirection direction) {
        Checkbox box = new Checkbox(Checkbox.Variant.BOX, label);
        box.setLayoutDirection(direction);
        checkboxScene = new Scene(box);
        checkboxScene.setTextRuler(RULER);
        checkboxScene.layoutPass(CHECK_W, CHECK_H);
    }

    private LineRecorder paintCheckbox() {
        LineRecorder recorder = new LineRecorder(CHECK_W, CHECK_H);
        checkboxScene.renderFrame(recorder);
        return recorder;
    }

    @Test
    void aNeutralCheckboxLabelTakesTheRowsDirectionAndALatinOneDoesNot() {
        buildCheckbox("2024", LayoutDirection.RTL);
        assertBase(ShapedText.Direction.RTL, paintCheckbox().line("2024"),
                "a label that is a year reads the way the form reads");

        buildCheckbox("File", LayoutDirection.RTL);
        assertBase(ShapedText.Direction.LTR, paintCheckbox().line("File"),
                "a label with a strong character still decides for itself");
    }

    @Test
    void aCheckboxReadingLeftToRightIsUnchanged() {
        buildCheckbox("2024", LayoutDirection.LTR);
        assertBase(ShapedText.Direction.LTR, paintCheckbox().line("2024"),
                "the default is unchanged");

        buildCheckbox("File", LayoutDirection.LTR);
        assertBase(ShapedText.Direction.LTR, paintCheckbox().line("File"), "and so is a Latin one");
    }

    @Test
    void aCheckboxPlacesItsLabelAgainstTheWidthItReserved() {
        // The half that is easy to skip: the box is sized from the shaped line and the label is
        // placed from the same one, so the row's own width and the ink inside it cannot disagree.
        // Reading right to left the label ends one gap before the indicator, so its left edge is a
        // whole label width back from there.
        buildCheckbox("2024", LayoutDirection.RTL);
        LineRecorder r = paintCheckbox();
        float labelWidth = r.line("2024").metrics().width();
        float indicator = MEDIUM.indicator();
        assertEquals(CHECK_W - indicator - MEDIUM.gapLabel() - labelWidth, r.xOf("2024"), EPS,
                "the mirrored label starts a label width back from the gap it ends at");

        buildCheckbox("2024", LayoutDirection.LTR);
        LineRecorder plain = paintCheckbox();
        assertEquals(indicator + MEDIUM.gapLabel(), plain.xOf("2024"), EPS,
                "and reading left to right it starts one gap past the indicator, as it always did");
    }

    @Test
    void aCheckboxAndARadioButtonStillReserveTheSameLabelColumn() {
        // The two are in declared lockstep and interchangeable in a form column, and they now
        // arrive at that column by different spellings: Checkbox sizes from the line it shapes,
        // RadioButton from a direction-blind measurement. They agree because the only text whose
        // base the fallback decides is text with no strong character, and such a text is one run
        // whose width does not depend on the base -- the lemma is asserted against real faces in
        // the backend's own shaping tests, since this ruler cannot see a direction at all.
        for (LayoutDirection direction : LayoutDirection.values()) {
            for (String label : new String[]{"2024", "File"}) {
                Checkbox box = new Checkbox(Checkbox.Variant.BOX, label);
                RadioButton radio = new RadioButton(label);
                limn.scene.layout.Row row = new limn.scene.layout.Row();
                row.add(box);
                row.add(radio);
                row.setLayoutDirection(direction);
                Scene s = new Scene(row);
                s.setTextRuler(RULER);
                s.layoutPass(400, 60);
                assertEquals(box.width(), radio.width(), EPS,
                        direction + " / " + label + ": one optical column for the pair");
            }
        }
    }

    // --------------------------------------------------- what did not change

    @Test
    void everyLineTheseWidgetsDrawIsShapedRatherThanHandedOverAsAString() {
        // The structural half of the conversion, asserted once so that a later edit that reaches
        // for canvas.drawText(String, ...) in one of these files is caught here rather than by a
        // direction that quietly stops arriving. A String draw carries no base at all.
        StringCountingCanvas canvas = new StringCountingCanvas(SPIN_W, SPIN_H);
        buildSpinner(new Spinner(-100, 100, 1).setValue(-42), LayoutDirection.RTL);
        spinnerScene.renderFrame(canvas);
        assertEquals(0, canvas.strings, "a Spinner drew a bare string");
        assertFalse(canvas.shaped.isEmpty(), "and it did draw something");

        canvas = new StringCountingCanvas(BAR_W, MEDIUM.controlHeight());
        buildBar(LayoutDirection.RTL, "2024", "File");
        barScene.renderFrame(canvas);
        assertEquals(0, canvas.strings, "a MenuBar drew a bare string");

        canvas = new StringCountingCanvas(POP_W, POP_H);
        openPopup(LayoutDirection.RTL, "2024", "Open");
        popupScene.renderFrame(canvas);
        assertEquals(0, canvas.strings, "a PopupMenu drew a bare string");

        canvas = new StringCountingCanvas(CHART_W, CHART_H);
        buildChart(LayoutDirection.RTL, "2024", "Vendas");
        chartScene.renderFrame(canvas);
        assertEquals(0, canvas.strings, "a Chart drew a bare string");

        canvas = new StringCountingCanvas(CHECK_W, CHECK_H);
        buildCheckbox("2024", LayoutDirection.RTL);
        checkboxScene.renderFrame(canvas);
        assertEquals(0, canvas.strings, "a Checkbox drew a bare string");
        assertFalse(canvas.shaped.isEmpty(), "and it did draw its label");
    }

    /**
     * Counts the two seams apart. {@code Canvas.drawText(ShapedText, …)} defaults to delegating
     * to the string form, so overriding both is the only way to tell a widget that shaped from
     * one that did not.
     */
    private static final class StringCountingCanvas extends FakeCanvas {
        int strings;
        final List<ShapedText> shaped = new ArrayList<>();

        StringCountingCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawText(String text, float x, float y, limn.graphics.Font font, Paint paint) {
            strings++;
        }

        @Override
        public void drawText(ShapedText text, float x, float y, Paint paint) {
            shaped.add(text);
        }
    }

    @Test
    void theWidgetsResolveTheirDirectionWhenAskedRatherThanWhenBuilt() {
        // Every one of these resolves the fallback inside the pass that uses it, so a widget built
        // before it had a parent -- which is every widget -- is not stuck with the direction it
        // had then. Re-parenting is the case: the same Spinner, moved under a mirrored scene.
        Spinner s = new Spinner(0, 100, 1).setValue(42);
        Scene ltr = new Scene(s);
        ltr.setTextRuler(RULER);
        ltr.layoutPass(SPIN_W, SPIN_H);
        LineRecorder before = new LineRecorder(SPIN_W, SPIN_H);
        ltr.renderFrame(before);
        assertBase(ShapedText.Direction.LTR, before.line("42"), "built and drawn left to right");

        s.setLayoutDirection(LayoutDirection.RTL);
        ltr.layoutPass(SPIN_W, SPIN_H);
        LineRecorder after = new LineRecorder(SPIN_W, SPIN_H);
        ltr.renderFrame(after);
        assertBase(ShapedText.Direction.RTL, after.line("42"),
                "and the very next frame reads the other way: nothing captured a direction");
        assertNotNull(after.line("42"));
    }
}
