package limn.components;

import java.util.ArrayList;
import java.util.List;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.graphics.Paint;
import limn.graphics.ParagraphStyle;
import limn.graphics.ShapedText;
import limn.graphics.StyledText;
import limn.graphics.TextStyle;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.testing.NoopCanvas;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A label showing styled text, and its icon at each of the four positions, against the fixed ruler
 * (ten points per character, line 12).
 */
class LabelStyledTextTest extends ComponentTestBase {

    private static final Color RED = new Color(1, 0, 0, 1);

    private record Drawn(ShapedText text, int start, int end, float x, float y, Paint paint) {
        String chars() {
            return text.text().substring(start, end);
        }
    }

    private static final class Recorder extends NoopCanvas {
        final List<Drawn> drawn = new ArrayList<>();
        final List<float[]> icons = new ArrayList<>();
        final List<Paint> fills = new ArrayList<>();

        Recorder(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawText(ShapedText text, float x, float y, Paint paint) {
            drawn.add(new Drawn(text, 0, text.text().length(), x, y, paint));
        }

        @Override
        public void drawText(ShapedText text, int charStart, int charEnd, float x, float y, Paint paint) {
            drawn.add(new Drawn(text, charStart, charEnd, x, y, paint));
        }

        @Override
        public void fillRect(float x, float y, float width, float height, Paint paint) {
            fills.add(paint);
        }
    }

    /** An icon that records the square it is painted in. */
    private static Icon icon(Recorder into) {
        return new Icon() {
            @Override
            public Image image(int pixelSize, boolean dark) {
                return new Image(1, 1, new byte[4]);
            }

            @Override
            public void paint(Canvas canvas, float x, float y, float size, @Nullable Color tint, boolean dark) {
                into.icons.add(new float[] {x, y, size});
            }
        };
    }

    private Scene scene(Label label, float width, float height) {
        Scene scene = new Scene(label);
        scene.setTextRuler(RULER);
        scene.layoutPass(width, height);
        return scene;
    }

    /** A frame at the size the scene was laid out at: a frame lays the scene out at the canvas's. */
    private static Recorder frame(Scene scene, float width, float height) {
        Recorder canvas = new Recorder(width, height);
        scene.renderFrame(canvas);
        return canvas;
    }

    private static StyledText overdue() {
        return StyledText.builder().append("Due ").append("today", TextStyle.of().ink(Tone.DANGER))
                .append(" ").append("R$ 10", TextStyle.of().ink(RED).bold()).build();
    }

    @Test
    void aStyledLabelAnswersItsPlainTextAndAReaderHearsItsMarkers() {
        StyledText list = StyledText.builder()
                .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER)).append("one")
                .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER)).append("two").build();
        Label label = new Label("").setText(list);
        assertEquals("one\ntwo", label.text());
        assertEquals("1. one\n2. two", label.textSource().get());
        assertSame(list, label.styledText());
    }

    @Test
    void eachPieceIsPaintedInItsInkAndTheRestInTheLabels() {
        Label label = new Label("").setText(overdue());
        Scene scene = scene(label, 300, 20);
        Theme theme = Theme.of(label);
        Recorder canvas = frame(scene, 300, 20);
        assertEquals("Due ", canvas.drawn.get(0).chars());
        assertEquals(theme.text(), canvas.drawn.get(0).paint(), "a piece with no ink: the label's");
        assertEquals("today", canvas.drawn.get(1).chars());
        assertEquals(Tone.DANGER.colorIn(theme), canvas.drawn.get(1).paint(), "a tone in this label's palette");
        Drawn amount = canvas.drawn.stream().filter(d -> d.chars().equals("R$ 10")).findFirst().orElseThrow();
        assertEquals(RED, amount.paint());
        assertEquals(700, amount.text().font().weight());
    }

    @Test
    void theLabelsToneIsTheInkOfAPieceThatSetsNone() {
        Label label = new Label("").setText(overdue()).setTone(Tone.SUCCESS);
        Scene scene = scene(label, 300, 20);
        assertEquals(Tone.SUCCESS.colorIn(Theme.of(label)), frame(scene, 300, 20).drawn.get(0).paint());
    }

    @Test
    void aDisabledLabelDrawsEveryPieceInTheDisabledColour() {
        Label label = new Label("").setText(overdue());
        label.setEnabled(false);
        Scene scene = scene(label, 300, 20);
        Color disabled = Theme.of(label).disabledText();
        for (Drawn d : frame(scene, 300, 20).drawn) {
            assertEquals(disabled, d.paint(), d.chars());
        }
    }

    @Test
    void wrapAndTheLineLimitBreakStyledTextAsTheyBreakPlainText() {
        Label wrapped = new Label("").setText(StyledText.of("aaa bbb ccc")).setWrap(true);
        new Scene(wrapped).setTextRuler(RULER);
        Size size = wrapped.measure(Constraints.loose(75, 500));
        assertEquals(24, size.height(), "two lines of twelve");

        Label one = new Label("").setText(StyledText.of("aaa bbb ccc"));
        Scene scene = scene(one, 75, 12);
        List<String> shown = frame(scene, 75, 12).drawn.stream().map(Drawn::chars).toList();
        assertEquals(List.of("aaa bb", "…"), shown, "one line, cut with an ellipsis");
    }

    @Test
    void aPageCutsAStyledLabelBetweenItsLines() {
        Label label = new Label("").setText(StyledText.builder().append("one").paragraph().append("two")
                .paragraph().append("three").build()).setWrap(true).setAlign(Label.HAlign.START, Label.VAlign.TOP);
        scene(label, 200, 36);
        assertArrayEquals(new float[] {12, 24}, limn.components.internal.LineCuts.of(label));
    }

    @Test
    void plainTextReplacesStyledText() {
        Label label = new Label("").setText(overdue());
        Scene scene = scene(label, 300, 20);
        label.setText("plain");
        scene.layoutPass(300, 20);
        assertNull(label.styledText());
        assertEquals("plain", label.text());
        assertEquals(List.of("plain"), label.displayedLines());
    }

    @Test
    void theIconCanStandAtTheEnd() {
        Recorder canvas = new Recorder(200, 20);
        Label label = new Label("abc").setIcon(icon(canvas)).setIconPosition(Label.IconPosition.END);
        Scene scene = scene(label, 200, 20);
        scene.renderFrame(canvas);
        float[] square = canvas.icons.get(0);
        assertEquals(label.width() - square[2], square[0], 1e-3, "at the right edge, reading left to right");
        assertEquals(0, canvas.drawn.get(0).x(), 1e-3, "the text at the left");
    }

    @Test
    void anIconAboveTheTextAddsItsHeightAndStandsOverIt() {
        Recorder canvas = new Recorder(200, 80);
        Label beside = new Label("abc").setIcon(icon(canvas));
        Label above = new Label("abc").setIcon(icon(canvas)).setIconPosition(Label.IconPosition.TOP)
                .setAlign(Label.HAlign.CENTER, Label.VAlign.TOP);
        new Scene(beside).setTextRuler(RULER);
        Scene scene = scene(above, 200, 80);
        Size besideSize = beside.measure(Constraints.loose(200, 200));
        Size aboveSize = above.measure(Constraints.loose(200, 200));
        assertEquals(12, besideSize.height(), "beside, the icon overhangs the line box");
        assertTrue(aboveSize.height() > 12 + 12, "above, the square and the gap are in the height");
        assertTrue(aboveSize.width() < besideSize.width(), "and nothing is taken from the width");

        scene.renderFrame(canvas);
        float[] square = canvas.icons.get(0);
        Drawn text = canvas.drawn.get(0);
        assertEquals(0, square[1], 1e-3, "the square at the top");
        assertTrue(text.y() - 8 >= square[1] + square[2], "the text below the square");
        assertEquals((200 - square[2]) / 2, square[0], 1e-3, "centred, as the lines are");
    }

    @Test
    void anIconBelowTheTextStandsUnderIt() {
        Recorder canvas = new Recorder(200, 80);
        Label below = new Label("abc").setIcon(icon(canvas)).setIconPosition(Label.IconPosition.BOTTOM)
                .setAlign(Label.HAlign.START, Label.VAlign.TOP);
        Scene scene = scene(below, 200, 80);
        scene.renderFrame(canvas);
        float[] square = canvas.icons.get(0);
        Drawn text = canvas.drawn.get(0);
        assertTrue(square[1] >= text.y() + 2, "the square under the line");
        assertEquals(0, square[0], 1e-3, "at the start");
    }

    @Test
    void aDisabledLabelPaintsABackgroundInTheDisabledFillUnderTheDisabledText() {
        Label label = new Label("").setText(StyledText.of("late", TextStyle.of().background(Tone.WARNING)));
        label.setEnabled(false);
        Scene scene = scene(label, 300, 20);
        Theme theme = Theme.of(label);
        Recorder canvas = frame(scene, 300, 20);
        assertTrue(canvas.fills.contains(theme.disabledFill()), "the background in the disabled fill");
        assertTrue(!canvas.fills.contains(theme.disabledText()), "before: in the text's own colour, a solid block");
        assertEquals(theme.disabledText(), canvas.drawn.get(0).paint());
    }

    @Test
    void styledTextNamingAFieldGivesItNoAccessLetter() {
        TextField field = new TextField();
        Label label = new Label("").setLabelFor(field).setText(StyledText.of("R&D budget"));
        assertEquals("R&D budget", label.text());
        assertEquals("R&D budget", label.accessibleLabelText().get(), "the name as written, its ampersand kept");
        assertNull(label.accessibleLabelAccessKey(), "and no Alt+D");
    }
}
