package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MenuBar} and {@link PopupMenu} read right to left: which end of the strip the first
 * title is at, which corner of the anchor a dropdown hangs from, which side a submenu opens to,
 * which gutter holds the check and which the accelerator hint, and which physical arrow does
 * what.
 *
 * <p>The two files hold <b>one</b> decision between them, so they are tested in one file: the
 * bar walks between menus on the same physical key its dropdown walks between columns on, and a
 * flip applied twice — once in each file — cancels and leaves the bar walking against its own
 * submenus. The tests that would catch that are the ones that assert both halves at once.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10&nbsp;pt clusters and the row the
 * cascade actually resolved, never a picture: a mirrored layout that is inside out and a mirrored
 * layout that is a point off look the same in a screenshot and nothing alike in a hit test.
 */
class MenuMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;

    /** {@link #RULER}'s width for a string: 10 pt per code point. */
    private static float ruled(String text) {
        return 10f * (int) text.codePoints().count();
    }

    /** The left edge {@code text} was drawn at, and the assertion that it was drawn at all. */
    private static float textX(InkCanvas ink, String text) {
        Float x = ink.textX.get(text);
        assertNotNull(x, "nothing drew \"" + text + '"');
        return x;
    }

    // ------------------------------------------------------------ the canvas

    /**
     * Records the ink a menu paints. Text is keyed by the string drawn, which is also the
     * assertion that an accelerator hint is drawn as itself and never reversed; lines, paths and
     * round rects keep their order, and the menus below are built so that each list holds
     * exactly the marks its test is about.
     */
    private static final class InkCanvas extends FakeCanvas {
        final Map<String, Float> textX = new HashMap<>();
        final List<float[]> lines = new ArrayList<>();
        final List<float[]> paths = new ArrayList<>();
        final List<RoundRect> roundRects = new ArrayList<>();

        InkCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            textX.put(text, x);
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth,
                Paint paint) {
            lines.add(new float[]{x1, y1, x2, y2});
        }

        @Override
        public void drawPath(Path2D path, float strokeWidth, Paint paint) {
            List<Float> xs = new ArrayList<>();
            path.flatten(0.01f, new Path2D.Flattened() {
                @Override
                public void moveTo(float x, float y) {
                    xs.add(x);
                }

                @Override
                public void lineTo(float x, float y) {
                    xs.add(x);
                }

                @Override
                public void closePath() {
                }
            });
            float[] out = new float[xs.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = xs.get(i);
            }
            paths.add(out);
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            roundRects.add(roundRect);
        }
    }

    // ============================================================== PopupMenu

    private static final float W = 1000;
    private static final float H = 400;
    private static final float AX = 600;
    private static final float AY = 40;
    private static final float AW = 60;
    private static final float AH = 20;

    private final AtomicReference<String> chosen = new AtomicReference<>();
    private Scene scene;
    private PopupMenu popup;

    /** Open (accelerated), Wrap (a ticked check) and More (a submenu): one row of each kind. */
    private Menu menu() {
        Menu sub = new Menu().addItem("Deep", () -> chosen.set("deep"));
        Menu menu = new Menu();
        menu.add(MenuItem.of("Open", () -> chosen.set("open"))
                .setAccelerator(Accelerator.of(Keys.F5)));
        menu.addCheck("Wrap", true, value -> { });
        menu.addSubmenu("More", sub);
        return menu;
    }

    private void openPopup(LayoutDirection direction) {
        openPopup(direction, AX, menu());
    }

    /**
     * Opens the cascade as an in-scene overlay, the presentation a headless test can drive.
     * The direction reaches the parentless surface through the same host link the step does,
     * so a scene that declares one is all this needs.
     */
    private void openPopup(LayoutDirection direction, float anchorX, Menu menu) {
        scene = new Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.setLayoutDirection(direction);
        scene.layoutPass(W, H);
        popup = new PopupMenu(menu);
        popup.showInSceneForTest(scene, anchorX, AY, AW, AH);
        scene.layoutPass(W, H); // the overlay's bounds are the scene, installed in onLayout
    }

    private InkCanvas paint() {
        InkCanvas canvas = new InkCanvas(W, H);
        scene.renderFrame(canvas);
        return canvas;
    }

    /** Centre of item {@code index} of a column with no separators, relative to the column top. */
    private static float itemCentre(SizeTokens t, int index) {
        return t.menuPadV() + index * t.menuRowHeight() + t.menuRowHeight() / 2;
    }

    /** Moves the highlight onto "More", the only row with a submenu. */
    private void highlightSubmenuRow() {
        popup.keyForTest(Keys.DOWN);
        popup.keyForTest(Keys.DOWN);
        assertEquals(2, popup.highlightForTest(0), "the submenu row is the one highlighted");
    }

    // --------------------------------------------------------- where it opens

    @Test
    void aDropdownHangsFromTheAnchorsLeftCornerReadingLeftToRight() {
        openPopup(LayoutDirection.LTR);
        float[] r = popup.columnRectForTest(0);
        assertEquals(AX, r[0], EPS, "unchanged: the column's left edge is the anchor's left edge");
        assertEquals(AY + AH, r[1], EPS, "and it still drops below the anchor");
    }

    @Test
    void aDropdownHangsFromTheAnchorsRightCornerReadingRightToLeft() {
        openPopup(LayoutDirection.RTL);
        float[] r = popup.columnRectForTest(0);
        assertEquals(AX + AW, r[0] + r[2], EPS,
                "the column's RIGHT edge meets the anchor's right edge: that is its leading one");
        assertTrue(r[0] > 0 && r[0] + r[2] < W,
                "placed rather than clamped, or the assertion above proves nothing: " + r[0]);
    }

    @Test
    void aRightToLeftDropdownWithNoRoomOnTheLeftFlipsToTheAnchorsOtherEdge() {
        // The two placements swap under a mirror; they do not each change. Reading right to
        // left the fallback is "align to the anchor's LEFT edge", and it has to be reached by
        // the flip rather than by the on-screen clamp, which would have answered 0 here.
        openPopup(LayoutDirection.RTL, 10, menu());
        float[] r = popup.columnRectForTest(0);
        assertEquals(10, r[0], EPS, "flipped to the anchor's other edge, not clamped to the bounds");
    }

    @Test
    void aSubmenuOpensToTheRightReadingLeftToRight() {
        openPopup(LayoutDirection.LTR);
        highlightSubmenuRow();
        popup.keyForTest(Keys.RIGHT);
        assertEquals(2, popup.columnCountForTest());
        float[] parent = popup.columnRectForTest(0);
        float[] sub = popup.columnRectForTest(1);
        assertEquals(parent[0] + parent[2] - Strokes.SUBMENU_OVERLAP, sub[0], EPS);
    }

    @Test
    void aSubmenuOpensToTheLeftReadingRightToLeft() {
        openPopup(LayoutDirection.RTL);
        highlightSubmenuRow();
        popup.keyForTest(Keys.LEFT); // the trailing arrow, which is LEFT here
        assertEquals(2, popup.columnCountForTest(), "LEFT is the key that opens a submenu here");
        float[] parent = popup.columnRectForTest(0);
        float[] sub = popup.columnRectForTest(1);
        assertEquals(parent[0] - sub[2] + Strokes.SUBMENU_OVERLAP, sub[0], EPS,
                "it opens to the left, and the seam overlap keeps its magnitude on the other edge");
        assertTrue(sub[0] > 0, "placed rather than clamped: " + sub[0]);
    }

    @Test
    void mirroringLeavesTheColumnExactlyAsWide() {
        // The mirror swaps which gutter holds the check and which holds the arrow; the sum is
        // the same, so the column width, the scroll extent and the window that is sized from
        // them are all the same numbers in both directions.
        openPopup(LayoutDirection.LTR);
        float leftToRight = popup.columnRectForTest(0)[2];
        openPopup(LayoutDirection.RTL);
        assertEquals(leftToRight, popup.columnRectForTest(0)[2], EPS);
    }

    // ------------------------------------------------------------- the gutters

    @Test
    void aRowFillsItsGuttersFromTheLeftReadingLeftToRight() {
        openPopup(LayoutDirection.LTR);
        SizeTokens t = popup.tokensForTest();
        float[] r = popup.columnRectForTest(0);
        float x = r[0];
        float w = r[2];
        InkCanvas ink = paint();

        assertEquals(x + t.menuCheckGutter(), textX(ink, "Open"), EPS, "the label unchanged");
        assertEquals(x + t.menuCheckInset(), ink.lines.get(0)[0], EPS, "the check unchanged");
        String accel = popup.accelTextForTest(0, 0);
        assertEquals(x + w - t.menuArrowGutter() - ruled(accel), textX(ink, accel), EPS,
                "the hint still right-aligned on the arrow gutter");
        float[] chevron = ink.paths.get(0);
        float cx = x + w - t.menuArrowGutter() + t.menuArrowNudge();
        assertEquals(cx + 3 * t.menuArrowW() / 5f, chevron[1], EPS, "the chevron still points right");
    }

    @Test
    void aRowFillsItsGuttersFromTheRightReadingRightToLeft() {
        openPopup(LayoutDirection.RTL);
        SizeTokens t = popup.tokensForTest();
        float[] r = popup.columnRectForTest(0);
        float x = r[0];
        float w = r[2];
        InkCanvas ink = paint();

        // drawText places a run's LEFT edge in either direction, so a label aligned on the
        // leading gutter sits its own width back from it.
        assertEquals(x + w - t.menuCheckGutter() - ruled("Open"), textX(ink, "Open"), EPS,
                "the label starts at the leading gutter, which is now on the right");
        // The hint is aligned on the trailing margin, which is now the left one, so the width
        // subtraction disappears rather than changing sign.
        String accel = popup.accelTextForTest(0, 0);
        assertEquals(x + t.menuArrowGutter(), textX(ink, accel), EPS);
    }

    @Test
    void theSubmenuChevronPointsAtTheSideTheSubmenuOpensTo() {
        openPopup(LayoutDirection.RTL);
        SizeTokens t = popup.tokensForTest();
        float[] r = popup.columnRectForTest(0);
        InkCanvas ink = paint();

        float cx = r[0] + t.menuArrowGutter() - t.menuArrowNudge();
        float[] chevron = ink.paths.get(0);
        assertEquals(cx + 2 * t.menuArrowW() / 5f, chevron[0], EPS, "its back");
        assertEquals(cx - 3 * t.menuArrowW() / 5f, chevron[1], EPS, "its tip, which is to the left");
        assertTrue(chevron[1] < chevron[0],
                "the one piece of mirrored ink in the toolkit: it names the side a submenu opens to");
    }

    // --------------------------------------------------- what does NOT mirror

    @Test
    void theCheckMarkItselfIsNeverMirrored() {
        // Only the gutter moves. The tick is a tick: no platform mirrors one, and paintCheck
        // still draws rightwards from the left edge it is handed, so its far end is to the
        // RIGHT of its start in a right-to-left menu too.
        openPopup(LayoutDirection.RTL);
        SizeTokens t = popup.tokensForTest();
        float[] r = popup.columnRectForTest(0);
        InkCanvas ink = paint();

        float checkX = r[0] + r[2] - t.menuCheckInset() - t.checkGlyphW();
        assertEquals(checkX, ink.lines.get(0)[0], EPS, "placed in the mirrored gutter");
        assertEquals(checkX + t.checkGlyphW(), ink.lines.get(1)[2], EPS,
                "and drawn left to right inside it, exactly as it always was");
    }

    @Test
    void anAcceleratorHintIsNeverReversed() {
        // A shortcut label names physical keys. Its placement mirrors; its text does not, and
        // the recorded key IS the string that was drawn.
        openPopup(LayoutDirection.RTL);
        String accel = popup.accelTextForTest(0, 0);
        assertTrue(paint().textX.containsKey(accel),
                "the hint is drawn as itself: " + accel);
    }

    @Test
    void homeAndEndStayLogicalReadingRightToLeft() {
        // They name a position in the list, not a side of the screen, and Shift+Home has to be
        // able to mean one contiguous range wherever it is used.
        openPopup(LayoutDirection.RTL);
        popup.keyForTest(Keys.END);
        assertEquals(2, popup.highlightForTest(0), "END is the last item in declaration order");
        popup.keyForTest(Keys.HOME);
        assertEquals(0, popup.highlightForTest(0), "and HOME the first");
    }

    @Test
    void aClickInAMirroredColumnLandsOnTheRowThatPaintsThere() {
        // The hit test is rect containment against a column the placement has already made
        // physical: mirroring it as well would move the pointer twice and put this click on
        // no row at all.
        openPopup(LayoutDirection.RTL);
        SizeTokens t = popup.tokensForTest();
        float[] r = popup.columnRectForTest(0);
        popup.clickForTest(r[0] + Strokes.ROW_CLIP + 1, r[1] + itemCentre(t, 0));
        assertEquals("open", chosen.get(), "the trailing edge of the first row is still the first row");
    }

    // ============================================================== MenuBar

    private static final float BAR_W = 400;
    private static final SizeTokens MEDIUM = SizeTokens.MEDIUM;
    /** Every title below is four characters: 40 pt of text in a 64 pt box at MEDIUM. */
    private static final float TITLE_W = ruled("File") + 2 * MEDIUM.menuBarPadH();

    private MenuBar bar;
    private Scene barScene;

    private void buildBar(LayoutDirection direction) {
        bar = new MenuBar();
        bar.addMenu("File", new Menu().addItem("New", () -> { }));
        bar.addMenu("Edit", new Menu().addItem("Undo", () -> { }));
        bar.addMenu("View", new Menu().addItem("Zoom", () -> { }));
        bar.setLayoutDirection(direction);
        barScene = new Scene(bar);
        barScene.setTextRuler(RULER);
        barScene.layoutPass(BAR_W, MEDIUM.controlHeight());
    }

    private InkCanvas paintBar() {
        InkCanvas canvas = new InkCanvas(BAR_W, MEDIUM.controlHeight());
        barScene.renderFrame(canvas);
        return canvas;
    }

    /**
     * The left edge of the one chip the strip paints: the active title's, and the only round
     * rect in a frame of this bar. It is how a test sees which title the strip believes is
     * current, and it is composed from the same walk the click landed in.
     */
    private static float chipX(InkCanvas ink) {
        assertEquals(1, ink.roundRects.size(), "exactly one title is active");
        return ink.roundRects.get(0).x() - MEDIUM.menuBarChipInset();
    }

    private void clickBar(float x) {
        barScene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, MEDIUM.controlHeight() / 2);
        barScene.inputBatchEnded();
    }

    private void keyBar(int key) {
        barScene.keyEvent(key, true, false, 0);
        barScene.inputBatchEnded();
    }

    @Test
    void theStripStartsAtTheLeftEdgeReadingLeftToRight() {
        buildBar(LayoutDirection.LTR);
        clickBar(TITLE_W + TITLE_W / 2); // the middle title
        assertTrue(bar.isOpen());
        InkCanvas ink = paintBar();
        assertEquals(TITLE_W, chipX(ink), EPS, "unchanged: the second box starts one box in");
        assertEquals(TITLE_W + MEDIUM.menuBarPadH(), textX(ink, "Edit"), EPS,
                "and its title sits on the leading pad, which is the left one");
    }

    @Test
    void theStripStartsAtTheRightEdgeReadingRightToLeft() {
        buildBar(LayoutDirection.RTL);
        // The visually middle box is still the middle title; the walk counts from the right.
        clickBar(BAR_W - TITLE_W - TITLE_W / 2);
        assertTrue(bar.isOpen(), "the click landed on a title");
        InkCanvas ink = paintBar();
        assertEquals(BAR_W - 2 * TITLE_W, chipX(ink), EPS,
                "the click and the paint agree about which box that was");
        assertEquals(BAR_W - 2 * TITLE_W + MEDIUM.menuBarPadH(), textX(ink, "Edit"), EPS);
        assertEquals(BAR_W - TITLE_W + MEDIUM.menuBarPadH(), textX(ink, "File"), EPS,
                "and the first title is the one against the right edge");
    }

    @Test
    void aFlooredTitleKeepsItsSlackOnTheTrailingSide() {
        // The hit-target floor widens the box beyond text + 2 * pad, and the title is aligned
        // on the pad rather than centred: reading left to right the slack is on the right, and
        // reading right to left it is on the left. At XSMALL a one-glyph title is 10 + 2 * 6 =
        // 22 pt of content in a 24 pt box, so the two expressions differ by the 2 pt of slack
        // and a title merely painted at "x + pad" would fail this.
        SizeTokens t = SizeTokens.of(ControlSize.XSMALL);
        for (LayoutDirection direction : LayoutDirection.values()) {
            MenuBar tight = new MenuBar();
            tight.addMenu("A", new Menu().addItem("New", () -> { }));
            tight.addMenu("B", new Menu().addItem("Old", () -> { }));
            tight.setControlSize(ControlSize.XSMALL);
            tight.setLayoutDirection(direction);
            Scene tightScene = new Scene(tight);
            tightScene.setTextRuler(RULER);
            tightScene.layoutPass(200, t.controlHeight());

            InkCanvas ink = new InkCanvas(200, t.controlHeight());
            tightScene.renderFrame(ink);

            float boxX = direction == LayoutDirection.RTL
                    ? 200 - Strokes.MIN_HIT_TARGET
                    : 0;
            float expected = direction == LayoutDirection.RTL
                    ? boxX + Strokes.MIN_HIT_TARGET - t.menuBarPadH() - ruled("A")
                    : boxX + t.menuBarPadH();
            assertEquals(expected, textX(ink, "A"), EPS, "title A at " + direction);
        }
    }

    @Test
    void theBottomRuleSpansTheStripInEitherDirection() {
        // A full-width rule is not a directional coordinate and must not grow one: mirroring it
        // would draw the identical line and leave a reader believing it had a side.
        buildBar(LayoutDirection.RTL);
        InkCanvas ink = paintBar();
        float[] rule = ink.lines.get(0);
        assertEquals(0, rule[0], EPS);
        assertEquals(BAR_W, rule[2], EPS);
    }

    // ------------------------------- the arrow keys: one decision, two files

    @Test
    void theBarsArrowsAreUnchangedReadingLeftToRight() {
        buildBar(LayoutDirection.LTR);
        barScene.requestFocus(bar); // focus starts the walk on the first title
        keyBar(Keys.RIGHT);
        assertEquals(TITLE_W, chipX(paintBar()), EPS, "RIGHT moves to the next title");
        keyBar(Keys.LEFT);
        assertEquals(0, chipX(paintBar()), EPS, "and LEFT back to the previous one");
    }

    @Test
    void theBarAndItsDropdownWalkTheSameWayReadingRightToLeft() {
        // The half that lives in MenuBar: RIGHT selects the visually-right neighbour, which
        // reading right to left is the PREVIOUS title in declaration order — here that wraps
        // from the first title to the last, whose box is at the left end of the strip.
        buildBar(LayoutDirection.RTL);
        barScene.requestFocus(bar);
        keyBar(Keys.RIGHT);
        assertEquals(BAR_W - 3 * TITLE_W, chipX(paintBar()), EPS,
                "RIGHT walked toward the previous menu, which is the box further left");

        // The half that lives in PopupMenu: the same physical key at the root column asks for
        // the same neighbour. The two flips are one flip each; the callback in between means
        // previous/next and does NOT flip, or the two would cancel and the bar would walk
        // against the direction its own submenus open in.
        AtomicReference<String> walked = new AtomicReference<>();
        openPopup(LayoutDirection.RTL, AX, new Menu()
                .addItem("Um", () -> { })
                .addItem("Dois", () -> { }));
        popup.onRootLeading(() -> walked.set("previous"));
        popup.onRootTrailing(() -> walked.set("next"));

        popup.keyForTest(Keys.RIGHT);
        assertEquals("previous", walked.get(), "RIGHT at the root column asks for the previous menu");
        popup.keyForTest(Keys.LEFT);
        assertEquals("next", walked.get(), "and LEFT for the next one");
    }

    @Test
    void theDropdownsRootArrowsAreUnchangedReadingLeftToRight() {
        AtomicReference<String> walked = new AtomicReference<>();
        openPopup(LayoutDirection.LTR, AX, new Menu()
                .addItem("Um", () -> { })
                .addItem("Dois", () -> { }));
        popup.onRootLeading(() -> walked.set("previous"));
        popup.onRootTrailing(() -> walked.set("next"));

        popup.keyForTest(Keys.RIGHT);
        assertEquals("next", walked.get());
        popup.keyForTest(Keys.LEFT);
        assertEquals("previous", walked.get());
    }

    @Test
    void theClosingArrowIsTheOneThatDidNotOpenTheSubmenu() {
        openPopup(LayoutDirection.RTL);
        highlightSubmenuRow();
        popup.keyForTest(Keys.LEFT);
        assertEquals(2, popup.columnCountForTest());
        popup.keyForTest(Keys.RIGHT);
        assertEquals(1, popup.columnCountForTest(),
                "RIGHT closes what LEFT opened: the leading arrow walks back out of the cascade");
    }
}
