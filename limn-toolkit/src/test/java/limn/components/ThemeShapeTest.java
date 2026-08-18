package limn.components;

import limn.graphics.Color;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one metric a palette carries.
 *
 * <p>{@link #shapeMovesNoWidgetByOnePoint()} is the load-bearing one, and it is not a
 * regression test; it is the premise. {@link Theme#setCurrent} assigns a volatile field and
 * notifies nobody, so a palette is only allowed to carry a metric that <em>nothing measures
 * from</em>; the editor and every theme switch rely on a shape change being a repaint. The
 * day a widget measures from a radius, this test says so, and shape stops being safe.
 */
class ThemeShapeTest extends ComponentTestBase {

    private static final Theme SQUARE = Theme.dark().toBuilder().cornerScale(0).build();
    private static final Theme ROUND = Theme.dark().toBuilder().cornerScale(3).build();

    /** One of most things the toolkit draws, so the sweep is not one widget's opinion. */
    private static Widget sampleTree() {
        Column column = new Column();
        column.gap(8).crossAlignment(Flex.CrossAlignment.START);
        column.add(new Button("Action"));
        column.add(new Button("Secondary").setSecondary(true));
        column.add(new Checkbox(Checkbox.Variant.BOX, "Box"));
        column.add(new Checkbox(Checkbox.Variant.SWITCH, "Switch"));
        column.add(new RadioButton("Radio"));
        column.add(new Label("A label with some words in it"));
        column.add(new TextField().setPlaceholder("Field"));
        column.add(new PasswordField());
        column.add(new SearchField());
        column.add(new TextArea());
        column.add(new Spinner(0, 100, 1));
        column.add(new Slider(0, 1));
        column.add(new ProgressBar());
        column.add(new ComboBox(List.of("One", "Two", "Three")));
        column.add(new SegmentedControl(List.of("A", "B")));
        column.add(new ColorPickerButton(Color.rgb(0x4FD1C5)));
        column.add(new ColorPicker());
        column.add(Separator.horizontal());

        TabbedPane tabs = new TabbedPane();
        tabs.addTab("First", new Label("first"));
        tabs.addTab("Second", new Label("second"));
        column.add(tabs);

        Row row = new Row();
        row.gap(6).add(new Button("In a row"));
        row.add(new Label("beside it"));
        column.add(row);
        return column;
    }

    /** Every widget's box, depth first, after a layout pass under {@code theme}. */
    private List<String> boxesUnder(Theme theme, ControlSize step) {
        Theme.setCurrent(theme);
        ControlSize.setProcessDefault(step);
        Widget root = sampleTree();
        Scene scene = new Scene(root);
        scene.setTextRuler(SCALED_RULER);
        scene.layoutPass(600, 4000);
        // A full frame too: a widget that only resolves geometry while painting would
        // otherwise be measured before it had an opinion.
        scene.renderFrame(new FakeCanvas(600, 4000));

        List<String> boxes = new ArrayList<>();
        collect(root, boxes);
        return boxes;
    }

    private static void collect(Widget widget, List<String> into) {
        into.add(widget.getClass().getSimpleName() + '@' + widget.x() + ',' + widget.y()
                + ' ' + widget.width() + 'x' + widget.height());
        for (Widget child : widget.children()) {
            collect(child, into);
        }
    }

    /**
     * Square corners and very round ones must lay out identically, at every size step. If
     * they ever do not, {@code Theme.tokens} is lying and a theme switch needs a relayout.
     */
    @Test
    void shapeMovesNoWidgetByOnePoint() {
        for (ControlSize step : ControlSize.values()) {
            List<String> square = boxesUnder(SQUARE, step);
            List<String> round = boxesUnder(ROUND, step);
            assertTrue(square.size() > 40, "the sample tree got smaller than the sweep needs");
            assertEquals(square, round, "shape moved geometry at " + step);
        }
    }

    /**
     * The other half of the same claim: shape moves no box, but it had better move the ink.
     * A scale that changed nothing on screen would pass every assertion above.
     */
    @Test
    void shapeMovesTheInkItIsSupposedTo() {
        assertEquals(0f, cornerPaintedBy(SQUARE), 1e-4f, "square means square");
        float plain = cornerPaintedBy(Theme.dark());
        float round = cornerPaintedBy(ROUND);
        assertEquals(SizeTokens.MEDIUM.radiusMedium(), plain, 1e-4f);
        assertEquals(plain * 3, round, 1e-4f);
    }

    /** The corner radius a Button's own fill is drawn with under {@code theme}. */
    private float cornerPaintedBy(Theme theme) {
        Theme.setCurrent(theme);
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        Button button = new Button("Action");
        Scene scene = new Scene(button);
        scene.setTextRuler(SCALED_RULER);
        scene.layoutPass(200, 60);

        CornerRecordingCanvas canvas = new CornerRecordingCanvas(200, 60);
        scene.renderFrame(canvas);
        assertTrue(canvas.firstFillRadius >= 0, "the button painted no filled rounded rect");
        return canvas.firstFillRadius;
    }

    /** Remembers the first filled rounded rect's radius, which is the control's own box. */
    private static final class CornerRecordingCanvas extends FakeCanvas {

        private float firstFillRadius = -1;

        CornerRecordingCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRoundRect(limn.graphics.RoundRect roundRect, limn.graphics.Paint paint) {
            if (firstFillRadius < 0) {
                firstFillRadius = roundRect.topLeft();
            }
        }
    }

    // --- the table -----------------------------------------------------------

    @Test
    void theDefaultShapeIsTheProcessWideTableItself() {
        for (ControlSize step : ControlSize.values()) {
            assertSame(SizeTokens.of(step), Theme.dark().tokens(step),
                    "a palette with no shape of its own must not build rows");
        }
        assertSame(SizeTokens.MEDIUM, Theme.limn().tokens(ControlSize.MEDIUM));
    }

    @Test
    void aShapedPaletteHoldsItsOwnRowsAndBuildsThemOnce() {
        assertNotSame(SizeTokens.MEDIUM, ROUND.tokens(ControlSize.MEDIUM));
        // Once, not per call: the rows carry the Fonts the backend memoizes by identity.
        assertSame(ROUND.tokens(ControlSize.MEDIUM), ROUND.tokens(ControlSize.MEDIUM));
        assertSame(SizeTokens.MEDIUM.body(), ROUND.tokens(ControlSize.MEDIUM).body(),
                "shape must not disturb the shared faces");
    }

    @Test
    void everyRadiusInTheRowFollowsTheScale() {
        for (ControlSize step : ControlSize.values()) {
            SizeTokens plain = SizeTokens.of(step);
            SizeTokens round = ROUND.tokens(step);
            assertEquals(plain.radiusSmall() * 3, round.radiusSmall(), 1e-4f);
            assertEquals(plain.radiusMedium() * 3, round.radiusMedium(), 1e-4f);
            assertEquals(plain.radiusLarge() * 3, round.radiusLarge(), 1e-4f);
            assertEquals(plain.indicatorRadius() * 3, round.indicatorRadius(), 1e-4f);
            // Everything else is untouched: spacing and type are not a palette's business.
            assertEquals(plain.spacingMedium(), round.spacingMedium());
            assertEquals(plain.controlHeight(), round.controlHeight());
            assertEquals(plain.padH(), round.padH());
        }
        assertEquals(0f, SQUARE.tokens(ControlSize.MEDIUM).radiusMedium());
    }

    /** The derived radii are expressions over the row, so they follow with no extra wiring. */
    @Test
    void theDerivedRadiiFollowToo() {
        SizeTokens plain = SizeTokens.MEDIUM;
        SizeTokens round = ROUND.tokens(ControlSize.MEDIUM);
        assertTrue(round.segPillRadius() > plain.segPillRadius());
        assertTrue(round.indicatorFocusRadius() > plain.indicatorFocusRadius());
    }

    /**
     * The tooltip is drawn by the toolkit, which has no {@link Theme}; it reads a style
     * this class installs. That hook resolved its radius from the static table, so a shaped
     * palette rounded every surface on screen except the tooltips, which is the sort of
     * thing nobody notices until a screenshot.
     */
    @Test
    void theTooltipFollowsThePalettesShapeLikeEverySurface() {
        Theme.setCurrent(ROUND);
        limn.scene.TooltipStyle round = Theme.tooltipStyle(ControlSize.MEDIUM);
        Theme.setCurrent(Theme.dark());
        limn.scene.TooltipStyle plain = Theme.tooltipStyle(ControlSize.MEDIUM);

        assertEquals(SizeTokens.MEDIUM.radiusSmall(), plain.radius(), 1e-4f);
        assertEquals(SizeTokens.MEDIUM.radiusSmall() * 3, round.radius(), 1e-4f,
                "the one surface the toolkit draws for itself must not ignore the palette");
    }

    // --- the value -----------------------------------------------------------

    @Test
    void shapeIsPartOfWhatTellsTwoPalettesApart() {
        assertEquals(Theme.dark(), Theme.dark().toBuilder().build());
        assertTrue(!Theme.dark().equals(SQUARE), "a square Dark is not Dark");
        assertEquals(SQUARE, Theme.dark().toBuilder().cornerScale(0).build());
        assertEquals(SQUARE.hashCode(), Theme.dark().toBuilder().cornerScale(0).build().hashCode());
    }

    @Test
    void theScaleIsClampedAndNeverNonsense() {
        assertEquals(0f, Theme.dark().toBuilder().cornerScale(-5).build().cornerScale);
        assertEquals(Theme.MAX_CORNER_SCALE,
                Theme.dark().toBuilder().cornerScale(1000).build().cornerScale);
        assertThrows(IllegalArgumentException.class,
                () -> Theme.dark().toBuilder().cornerScale(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> Theme.dark().toBuilder().cornerScale(Float.POSITIVE_INFINITY));
    }

    @Test
    void everyBuiltinShipsTheDefaultShape() {
        for (Theme theme : Theme.builtins()) {
            assertEquals(1f, theme.cornerScale, theme.name);
        }
    }

    @Test
    void shapeSurvivesTheRoundTrip() {
        Theme shaped = Theme.builder("Pill", true).cornerScale(2.5f).build();
        assertEquals(shaped, ThemeFormat.parse(ThemeFormat.write(shaped)));
        assertEquals(2.5f, ThemeFormat.parse(ThemeFormat.write(shaped)).cornerScale);
    }

    @Test
    void aFileWithoutAShapeGetsTheShippedRamp() {
        assertEquals(1f, ThemeFormat.parse("name = Ocean\ndark = true\n").cornerScale);
    }

    @Test
    void anImpossibleShapeInAFileIsAnError() {
        assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name = Ocean\ndark = true\ncornerScale = round\n"));
        assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name = Ocean\ndark = true\ncornerScale = -1\n"));
        assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name = Ocean\ndark = true\ncornerScale = 99\n"));
    }
}
