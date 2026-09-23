package limn.components;

import limn.testfixtures.IndexedRows;

import limn.components.date.CalendarView;
import limn.graphics.Rect;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * An animation repaints what it draws, not the widget that owns it.
 *
 * <p>ADR 043 &sect;9.2.1 and &sect;9.4. A {@code Transition} repaints its owner once per frame,
 * which is right for a button &mdash; the animation <em>is</em> the button &mdash; and wrong for
 * anything large whose animation is a ring, an outline or a divider. It is the cheapest defect in
 * the toolkit to acquire and the hardest to notice, because the picture is always correct: what
 * is wrong is only how much of it was redrawn, once per frame for the length of a fade.
 *
 * <p>Each widget is boxed strictly inside a larger window, so "the widget" and "the window" are
 * different rectangles. What is asserted is the <b>total area of the frame's repaint passes</b>,
 * not the first of them: a rim is four rectangles and reading only the first would score it as a
 * strip and pass for the wrong reason.
 *
 * <p><b>The first two frames of a focus arrival are exempt, deliberately.</b> They are
 * {@code Scene.setFocus} damaging the newly focused widget's whole box, which it must: a scene
 * cannot know that this particular widget's focus draws inside one row. Doubled by the
 * double-buffer union. What this test is about is the nine frames after them.
 */
class FadeDamageTest extends ComponentTestBase {

    private static final int W = 500;
    private static final int H = 400;
    private static final int INSET = 40;
    /** Long enough to outlast a focus fade at 60 Hz, short enough to stay a test. */
    private static final int FRAMES = 20;

    private Scene scene;
    private AtomicLong nanos;
    private RecordingTestCanvas canvas;
    /**
     * The repaint passes of the last frame that actually painted. Kept because the loop below
     * runs until the animation settles, and the canvas then holds the empty frame that ended it:
     * asking it afterwards reads nothing and passes for the wrong reason.
     */
    private List<Rect> lastPasses = List.of();

    private void mount(Widget root, Insets insets) {
        nanos = new AtomicLong();
        scene = new Scene(new Padding(insets, root), nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        canvas = new RecordingTestCanvas(W, H);
        for (int i = 0; i < 200; i++) {
            nanos.addAndGet(200_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                return;
            }
        }
        throw new AssertionError("the widget never settled, so a reading would measure the "
                + "settling rather than the gesture");
    }

    private void mount(Widget root) {
        mount(root, Insets.all(INSET));
    }

    /**
     * Runs the animation and asserts every frame from {@code skip} on damaged no more than
     * {@code maxShare} of {@code widget}.
     *
     * @return how many frames painted, so a caller can prove the animation actually ran
     */
    private int assertFadeStaysUnder(Widget widget, int skip, float maxShare, String what) {
        float box = Math.max(1, widget.width() * widget.height());
        int painted = 0;
        float worst = 0;
        for (int i = 0; i < FRAMES; i++) {
            nanos.addAndGet(16_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                break;
            }
            painted++;
            lastPasses = canvas.passClips();
            if (i < skip) {
                continue;
            }
            assertFalse(canvas.cleared, what + " cleared the frame, so it repainted the window");
            float share = canvas.damagedArea() / box;
            worst = Math.max(worst, share);
            assertTrue(share <= maxShare, what + " repainted " + Math.round(share * 100)
                    + "% of its widget on frame " + i + " (ceiling " + Math.round(maxShare * 100)
                    + "%), passes " + canvas.passClips());
        }
        assertTrue(painted > 3, what + " painted only " + painted
                + " frame(s), so no animation ran and this asserted nothing");
        return painted;
    }

    // ------------------------------------------------------------ already right, and must stay

    /**
     * The divider owns its own transitions, so hovering it repaints a band and not the panes.
     * This is what the other cases were measured against, and it is the pattern to copy: give the
     * animation an owner that <em>is</em> what moves.
     */
    @Test
    void aSplitPaneDividerFadesWithoutRepaintingThePanes() {
        SplitPane pane = SplitPane.horizontal(new Spinner(0, 100, 1), new Spinner(0, 100, 1));
        mount(pane);
        drive(scene).mouseMoved(INSET + pane.width() / 2, INSET + 100);
        drive(scene).inputBatchEnded();
        assertFadeStaysUnder(pane, 0, 0.15f, "a pointer arriving on a split pane's divider");
    }

    /** A bar's fade IS the whole bar, so its damage is its own box and must not exceed it. */
    @Test
    void aScrollBarFadesWithinItsOwnBox() {
        ScrollBar bar = new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
            private float offset;

            @Override public float contentLength() {
                return 2000;
            }

            @Override public float viewportLength() {
                return 300;
            }

            @Override public float offset() {
                return offset;
            }

            @Override public void setOffset(float value) {
                offset = value;
            }
        }).setPolicy(ScrollBar.Policy.ALWAYS);
        // Through the padding, not a SizedBox: Padding stretches its child to the inner box
        // whatever the child measured, and a bar as wide as the window measures nothing.
        float t = ScrollBar.thickness();
        mount(bar, new Insets(INSET, INSET, INSET, W - INSET - t));
        drive(scene).mouseMoved(bar.x() + bar.width() / 2, bar.y() + 100);
        drive(scene).inputBatchEnded();
        int painted = 0;
        for (int i = 0; i < FRAMES; i++) {
            nanos.addAndGet(16_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                break;
            }
            painted++;
            for (Rect pass : canvas.passClips()) {
                // Its own box plus the outset every invalidate carries, and nothing of the host.
                assertTrue(pass.x() >= bar.x() - 2 && pass.width() <= bar.width() + 4,
                        "a scroll bar's fade damaged " + pass + ", which is wider than the bar at "
                                + bar.x() + " " + bar.width());
            }
        }
        assertTrue(painted > 3, "the bar's fade never ran");
    }

    // ------------------------------------------------------------ narrowed, and must stay so

    /** The ring is one row's outline; the list used to repaint every row of itself for it. */
    @Test
    void aListFocusFadeRepaintsOneRow() {
        ListView<Integer> list = IndexedRows.list(new IndexedRows() {
            @Override public int rowCount() {
                return 500;
            }

            @Override public Widget rowAt(int index) {
                return new limn.scene.layout.SizedBox(10, 24);
            }

            @Override public void recycle(Widget widget) {
            }
        });
        mount(list);
        list.setSelectedIndex(4);
        for (int i = 0; i < 5; i++) {
            nanos.addAndGet(200_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
        }
        list.requestFocus();
        // One row of nine, so a tenth; the ceiling is loose enough to survive a theme.
        assertFadeStaysUnder(list, 2, 0.20f, "a list's focus fade");
    }

    /** The fade morphs a border, so it repaints a rim: four passes, not one filled box. */
    @Test
    void aTextAreaFocusFadeRepaintsTheBorderAndNotTheText() {
        TextArea area = new TextArea();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append("line ").append(i).append('\n');
        }
        area.setText(text.toString());
        mount(area);
        area.requestFocus();
        assertFadeStaysUnder(area, 2, 0.25f, "a text area's focus fade");
        // Four sides, and separately: the scene merges damage whose union wastes little, and a
        // rim's union is the whole widget, so one pass here would mean the rim was merged away.
        assertTrue(lastPasses.size() == 4,
                "a border fade should damage four strips, got " + lastPasses);
    }

    /** One ring on one cell, wherever the roving cursor is. */
    @Test
    void aCalendarFocusFadeRepaintsOneCell() {
        CalendarView calendar = new CalendarView();
        mount(calendar);
        calendar.requestFocus();
        assertFadeStaysUnder(calendar, 2, 0.10f, "a calendar's focus fade");
    }
}
