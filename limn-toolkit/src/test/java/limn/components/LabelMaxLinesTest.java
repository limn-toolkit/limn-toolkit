package limn.components;

import limn.graphics.Rect;
import limn.graphics.ShapedText;
import limn.i18n.I18n;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Label#setMaxLines(int)}: the text broken across a set number of lines, the ellipsis at
 * the end of the last, and every label that never sets it drawing what it drew before. Measured
 * against the 10-points-a-glyph {@link #RULER}, on which a line of 75 points holds seven glyphs and
 * an ellipsis costs one.
 */
class LabelMaxLinesTest extends ComponentTestBase {

    /** Alef, bet, gimel: a right-to-left word, written as escapes so no line here mixes directions. */
    private static final String HEBREW = "אבג";

    /** Five unspaced ideographs and kana: no space to break at. */
    private static final String CJK = "今日は世界";

    /** "sawatdi chao lok": twelve Thai characters, a dictionary's boundaries at 6 and 9. */
    private static final String THAI = "สวัสดี" + "ชาว" + "โลก";

    private Locale locale;

    @BeforeEach
    void rememberLocale() {
        locale = I18n.locale();
    }

    /** The line breaker reads the UI language, so a case that sets it puts it back. */
    @AfterEach
    void restoreLocale() {
        I18n.setLocale(locale);
    }

    private static Scene laidOut(Label label, float width, float height) {
        Scene scene = new Scene(label);
        scene.setTextRuler(RULER);
        scene.layoutPass(width, height);
        return scene;
    }

    private static Size measuredAt(Label label, float width) {
        Scene scene = new Scene(label);
        scene.setTextRuler(RULER);
        return label.measure(Constraints.loose(width, 1000));
    }

    // ------------------------------------------------------------------------------ the default

    @Test
    void oneLineIsTheDefaultAndDrawsWhatALabelAlwaysDrew() {
        Label label = new Label("aaa bbb ccc ddd eee");
        assertEquals(1, label.maxLines());
        laidOut(label, 75, 100);
        assertEquals(List.of("aaa bb…"), label.displayedLines(), "one line, the ellipsis measured in");
        assertEquals(12, measuredAt(new Label("aaa bbb ccc ddd eee"), 75).height(), 1e-3);
    }

    @Test
    void setMaxLinesOfOneChangesNothing() {
        Label set = new Label("aaa bbb ccc ddd eee").setMaxLines(1);
        laidOut(set, 75, 100);
        assertEquals(List.of("aaa bb…"), set.displayedLines());
    }

    @Test
    void belowOneIsRefused() {
        Label label = new Label("text");
        assertThrows(IllegalArgumentException.class, () -> label.setMaxLines(0));
        assertThrows(IllegalArgumentException.class, () -> label.setMaxLines(-3));
        assertEquals(1, label.maxLines());
    }

    // ------------------------------------------------------------------------------ the limit

    @Test
    void twoLinesBreakAsAWrapAndEndTheSecondWithTheEllipsis() {
        Label label = new Label("aaa bbb ccc ddd eee").setMaxLines(2);
        laidOut(label, 75, 100);
        // "aaa bbb" is the first row of the walk; the rest, "ccc ddd eee" (110), is cut as one
        // line is: 65 points beside the ellipsis keep six glyphs.
        assertEquals(List.of("aaa bbb", "ccc dd…"), label.displayedLines());
        Size size = measuredAt(new Label("aaa bbb ccc ddd eee").setMaxLines(2), 75);
        assertEquals(24, size.height(), 1e-3, "two lines of 12");
        assertEquals(70, size.width(), 1e-3, "the widest line");
    }

    @Test
    void threeLinesPutTheEllipsisOnTheThird() {
        Label label = new Label("aaa bbb ccc ddd eee fff ggg").setMaxLines(3);
        laidOut(label, 75, 100);
        assertEquals(List.of("aaa bbb", "ccc ddd", "eee ff…"), label.displayedLines());
        assertEquals(36, measuredAt(new Label("aaa bbb ccc ddd eee fff ggg").setMaxLines(3), 75).height(), 1e-3);
    }

    @Test
    void aTextThatFitsInFewerLinesTakesOnlyThoseAndNoEllipsis() {
        Label label = new Label("aaa bbb ccc").setMaxLines(3);
        laidOut(label, 75, 100);
        assertEquals(List.of("aaa bbb", "ccc"), label.displayedLines());
        assertEquals(24, measuredAt(new Label("aaa bbb ccc").setMaxLines(3), 75).height(), 1e-3);
    }

    @Test
    void aLastLineThatFitsExactlyHasNoEllipsis() {
        Label label = new Label("aaa bbb ccc ddd").setMaxLines(2);
        laidOut(label, 75, 100);
        assertEquals(List.of("aaa bbb", "ccc ddd"), label.displayedLines());
    }

    @Test
    void clipLetsTheLastLineRunOnToTheEdge() {
        Label label = new Label("aaa bbb ccc ddd eee").setMaxLines(2).setOverflow(Label.Overflow.CLIP);
        laidOut(label, 75, 100);
        assertEquals(List.of("aaa bbb", "ccc ddd eee"), label.displayedLines(),
                "the rest unbroken, for the paint's clip to cut, as one line under CLIP is");
    }

    @Test
    void aLineBreakAfterTheLastLineIsMoreTextAndGetsTheEllipsis() {
        Label label = new Label("one\ntwo\nthree").setMaxLines(2);
        laidOut(label, 400, 100);
        assertEquals(List.of("one", "two…"), label.displayedLines());
    }

    @Test
    void aShorterBoxThanTheLinesClipsAndDoesNotRefit() {
        // Counted, not fitted: given the height of one line, the label still lays out two and the
        // paint clips the second, as a wrapped label given too little height does.
        Label label = new Label("aaa bbb ccc ddd eee").setMaxLines(2);
        laidOut(label, 75, 12);
        assertEquals(List.of("aaa bbb", "ccc dd…"), label.displayedLines());
    }

    // ------------------------------------------------------------------------------ scripts

    @Test
    void rightToLeftTheEllipsisEndsTheLastLineOnItsLeft() {
        String words = String.join(" ", HEBREW, HEBREW, HEBREW, HEBREW, HEBREW);
        Label label = new Label(words).setMaxLines(2);
        laidOut(label, 75, 100);
        String first = HEBREW + " " + HEBREW;
        assertEquals(List.of(first, HEBREW + " " + HEBREW.substring(0, 2) + "…"), label.displayedLines());
        ShapedText last = label.shapedLines().get(1);
        assertEquals(ShapedText.Direction.RTL, last.baseDirection());
        int ellipsis = last.text().length() - 1;
        assertEquals(0, last.selection(ellipsis, ellipsis + 1).get(0).x0(), 1e-3,
                "logically last, so drawn first from the left on a right-to-left line");
    }

    @Test
    void leftToRightTheEllipsisEndsTheLastLineOnItsRight() {
        Label label = new Label("aaa bbb ccc ddd eee").setMaxLines(2);
        laidOut(label, 75, 100);
        ShapedText last = label.shapedLines().get(1);
        int ellipsis = last.text().length() - 1;
        assertEquals(60, last.selection(ellipsis, ellipsis + 1).get(0).x0(), 1e-3);
    }

    @Test
    void unspacedCjkBreaksAtAnIdeographAndIsCutOnTheLastLine() {
        Label label = new Label(CJK + CJK).setMaxLines(2);
        laidOut(label, 30, 100);
        // Three ideographs on the first line; the seven left are cut to two and the ellipsis.
        assertEquals(List.of(CJK.substring(0, 3), CJK.substring(3) + "…"), label.displayedLines());
    }

    @Test
    void thaiBreaksAtTheDictionaryBoundaryUnderAThaiLocale() {
        I18n.setLocale(Locale.forLanguageTag("th"));
        Label label = new Label(THAI + THAI).setMaxLines(2);
        laidOut(label, 100, 100);
        String rest = THAI.substring(9) + THAI;
        String cut = RULER.ellipsize(RULER.shape(rest, label.resolvedFont()), 100).text();
        assertEquals(List.of(THAI.substring(0, 9), cut), label.displayedLines(),
                "the first line at the boundary at 9, as a wrap breaks it; the rest cut as one line");
        assertTrue(cut.endsWith("…"));
    }

    // ------------------------------------------------------------------------------ with wrap

    @Test
    void wrapAloneStillTakesAsManyLinesAsTheTextNeeds() {
        Label label = new Label("aaa bbb ccc ddd eee fff ggg").setWrap(true);
        laidOut(label, 75, 100);
        assertEquals(List.of("aaa bbb", "ccc ddd", "eee fff", "ggg"), label.displayedLines());
    }

    @Test
    void wrapWithALimitStopsAtTheLimit() {
        Label label = new Label("aaa bbb ccc ddd eee fff ggg").setWrap(true).setMaxLines(2);
        laidOut(label, 75, 100);
        assertEquals(List.of("aaa bbb", "ccc dd…"), label.displayedLines());
    }

    @Test
    void theLimitHoldsWithWrapOffAsWithItOn() {
        Label off = new Label("aaa bbb ccc ddd eee fff ggg").setWrap(false).setMaxLines(2);
        laidOut(off, 75, 100);
        Label on = new Label("aaa bbb ccc ddd eee fff ggg").setWrap(true).setMaxLines(2);
        laidOut(on, 75, 100);
        assertEquals(on.displayedLines(), off.displayedLines());
    }

    @Test
    void aLimitTakenBackToOneLeavesWhatWrapSays() {
        Label wrapped = new Label("aaa bbb ccc ddd eee fff ggg").setWrap(true).setMaxLines(2).setMaxLines(1);
        laidOut(wrapped, 75, 100);
        assertEquals(4, wrapped.displayedLines().size(), "wrap on: as many lines as the text needs");

        Label single = new Label("aaa bbb ccc ddd eee fff ggg").setMaxLines(3).setMaxLines(1);
        laidOut(single, 75, 100);
        assertEquals(List.of("aaa bb…"), single.displayedLines(), "wrap off: one line");
    }

    @Test
    void aTextSetOnALimitedLabelBreaksAgain() {
        Label label = new Label("aaa").setMaxLines(2);
        Scene scene = laidOut(label, 75, 100);
        label.setText("aaa bbb ccc ddd eee");
        scene.layoutPass(75, 100);
        assertEquals(List.of("aaa bbb", "ccc dd…"), label.displayedLines(),
                "not the one-line repaint path, which would have drawn one ellipsized line");
    }

    // ------------------------------------------------------------------------------ in place

    /**
     * A limit that changes lays out in place, as a text that changes does: the label in a fixed
     * slot repaints itself, and the field above it, which did not move, is not repainted.
     */
    @Test
    void aChangeOfTheLimitLaysOutInPlace() {
        AtomicLong nanos = new AtomicLong();
        TextField field = new TextField();
        Label label = new Label("aaa bbb ccc ddd eee fff ggg hhh iii jjj");
        Column form = new Column();
        form.gap(8).crossAlignment(Flex.CrossAlignment.START);
        form.add(field);
        form.add(new SizedBox(120, 60, label));
        Scene scene = new Scene(new Padding(Insets.all(40), form), nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        RecordingTestCanvas canvas = new RecordingTestCanvas(640, 480);
        settle(scene, canvas, nanos);
        assertEquals(1, label.displayedLines().size());

        label.setMaxLines(3);
        List<Rect> passes = new ArrayList<>();
        boolean full = false;
        for (int i = 0; i < 60; i++) {
            nanos.addAndGet(16_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                break;
            }
            full |= canvas.cleared;
            passes.addAll(canvas.passClips());
        }
        assertEquals(3, label.displayedLines().size(), "the new limit is laid out");
        assertFalse(full, "a label's limit changing inside a fixed slot is not a full frame");
        assertFalse(touches(passes, box(field)), "the field above did not move and is not repainted");
        assertTrue(touches(passes, box(label)), "the label repaints");
    }

    private static void settle(Scene scene, RecordingTestCanvas canvas, AtomicLong nanos) {
        for (int i = 0; i < 300; i++) {
            nanos.addAndGet(200_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                return;
            }
        }
        throw new AssertionError("never settled");
    }

    private static Rect box(Widget<?> w) {
        return new Rect(w.localToScene(0, 0).x(), w.localToScene(0, 0).y(), w.width(), w.height());
    }

    private static boolean touches(List<Rect> passes, Rect box) {
        for (Rect pass : passes) {
            if (pass.x() < box.x() + box.width() && box.x() < pass.x() + pass.width()
                    && pass.y() < box.y() + box.height() && box.y() < pass.y() + pass.height()) {
                return true;
            }
        }
        return false;
    }
}
