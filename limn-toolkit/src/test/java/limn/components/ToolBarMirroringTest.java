package limn.components;

import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolBar} and {@link ContextMenus} read right to left: which end of the strip the first
 * item is at, and which corner of the focused widget the keyboard's context menu drops from.
 *
 * <p>The two are tested in one file because each holds a single site and because the second is
 * only legible next to the first. A strip mirrors its placed coordinate and leaves its cursor
 * walk alone; a menu mirrors the corner it hangs from and leaves the pointer alone. Written
 * apart, the second reads as an omission.
 *
 * <p>Every expectation is arithmetic against {@link SizeTokens} and the deterministic
 * {@link #RULER} rather than a picture: a strip that is inside out and a strip that is a point
 * off look the same in a screenshot and nothing alike in a hit test.
 */
class ToolBarMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;

    private static final SizeTokens MEDIUM = SizeTokens.MEDIUM;
    private static final float PAD = MEDIUM.toolBarPad();
    private static final float GAP = MEDIUM.toolBarGap();

    /** Wider than the items need, so nothing below is clamped into agreeing with a test. */
    private static final float BAR_W = 400;
    private static final float BAR_H = 28 + 2 * PAD;

    /** A fixed-size stand-in, so the assertions are about the bar and not about a Button. */
    private static final class Block extends Widget {
        private final float w;
        private final float h;

        Block(float w, float h) {
            this.w = w;
            this.h = h;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(w, h);
        }
    }

    private ToolBar bar;

    private void buildBar(LayoutDirection direction, Widget... items) {
        bar = new ToolBar();
        for (Widget item : items) {
            bar.addItem(item);
        }
        bar.setLayoutDirection(direction);
        Scene scene = new Scene(bar);
        scene.setTextRuler(RULER);
        scene.layoutPass(BAR_W, BAR_H);
    }

    // ------------------------------------------------------- the strip's placement

    @Test
    void itemsStartAtTheLeftPadReadingLeftToRight() {
        Block first = new Block(40, 20);
        Block second = new Block(30, 28);
        Block third = new Block(20, 16);
        buildBar(LayoutDirection.LTR, first, second, third);

        assertEquals(PAD, first.x(), EPS, "unchanged: the first item still starts at toolBarPad");
        assertEquals(PAD + 40 + GAP, second.x(), EPS);
        assertEquals(PAD + 40 + GAP + 30 + GAP, third.x(), EPS);
    }

    @Test
    void itemsStartAtTheRightPadReadingRightToLeft() {
        Block first = new Block(40, 20);
        Block second = new Block(30, 28);
        Block third = new Block(20, 16);
        buildBar(LayoutDirection.RTL, first, second, third);

        // The cursor walk is the same walk; only the placed coordinate is reflected, so each item
        // sits at width() - cursor - itsOwnWidth and the order it was added in survives intact.
        assertEquals(BAR_W - PAD - 40, first.x(), EPS,
                "the item added first is the rightmost one");
        assertEquals(BAR_W - (PAD + 40 + GAP) - 30, second.x(), EPS);
        assertEquals(BAR_W - (PAD + 40 + GAP + 30 + GAP) - 20, third.x(), EPS);

        assertEquals(BAR_W - PAD, first.x() + 40, EPS,
                "and the pad it starts from is the same token at either end");
        assertTrue(third.x() > 0,
                "placed rather than clamped, or the assertions above prove nothing: " + third.x());
    }

    @Test
    void theTwoDirectionsAreExactReflectionsOfEachOther() {
        float[] widths = {40, 30, 20};
        Block[] ltr = {new Block(40, 20), new Block(30, 28), new Block(20, 16)};
        Block[] rtl = {new Block(40, 20), new Block(30, 28), new Block(20, 16)};

        buildBar(LayoutDirection.LTR, ltr);
        float[] ltrX = {ltr[0].x(), ltr[1].x(), ltr[2].x()};
        buildBar(LayoutDirection.RTL, rtl);

        // Item by item: ltrX + rtlX + itsWidth == the bar's width. It holds only because the
        // leading and the trailing pad are one token, which is what lets the placement mirror
        // with no correction term of its own.
        for (int i = 0; i < widths.length; i++) {
            assertEquals(BAR_W, ltrX[i] + rtl[i].x() + widths[i], EPS,
                    "item " + i + " is not the reflection of itself");
        }
    }

    @Test
    void aSingleItemAndAnEmptyBarBothStillMirror() {
        buildBar(LayoutDirection.RTL); // no items: the loop never runs and nothing throws
        assertEquals(BAR_W, bar.width(), EPS);

        Block only = new Block(50, 20);
        buildBar(LayoutDirection.RTL, only);
        assertEquals(BAR_W - PAD - 50, only.x(), EPS);
        assertEquals(BAR_W - PAD, only.x() + 50, EPS, "flush against the pad it reads from");
    }

    // ------------------------------------------- what does NOT move on the strip

    @Test
    void theMeasuredWidthIsTheSameNumberInBothDirections() {
        // onMeasure sums pad + items + gaps + pad, which is a width and not a placement. A
        // measure pass that mirrored would report a different size for the same items and put
        // the two passes into disagreement about how wide the bar is.
        Size ltr = new ToolBar().addItem(new Block(40, 20)).addItem(new Block(30, 28))
                .measure(Constraints.loose(500, 200));

        ToolBar mirrored = new ToolBar().addItem(new Block(40, 20)).addItem(new Block(30, 28));
        mirrored.setLayoutDirection(LayoutDirection.RTL);
        Size rtl = mirrored.measure(Constraints.loose(500, 200));

        assertEquals(PAD + 40 + GAP + 30 + PAD, ltr.width(), EPS);
        assertEquals(ltr.width(), rtl.width(), EPS, "a bar cannot change size by changing side");
        assertEquals(ltr.height(), rtl.height(), EPS);
    }

    @Test
    void theVerticalCentringDoesNotMirror() {
        // The cross axis has no side, so its expectation is the same expression in both
        // directions and a branch that reached it would be visible here at once.
        Block tall = new Block(30, 28);
        Block shortOne = new Block(40, 16);
        buildBar(LayoutDirection.RTL, tall, shortOne);

        float innerH = bar.height() - 2 * PAD;
        assertEquals(PAD + (innerH - 28) / 2, tall.y(), EPS);
        assertEquals(PAD + (innerH - 16) / 2, shortOne.y(), EPS);
    }

    @Test
    void theSeparatorInsetMemoHoldsNoDirection() {
        // The inset the bar pushes onto the separators it built is a ControlSize number, not a
        // measured or shaped one, so it must NOT gain a direction: keying it on one would make
        // every direction change re-push an identical value and mark layout dirty for nothing.
        Separator ltrRule = ruleOf(barWithSeparator(LayoutDirection.LTR));
        Separator rtlRule = ruleOf(barWithSeparator(LayoutDirection.RTL));

        assertEquals(MEDIUM.toolBarSepInset(), ltrRule.inset(), EPS);
        assertEquals(ltrRule.inset(), rtlRule.inset(), EPS,
                "the inset is a step's number and has no side");
        // The rule itself is a vertical line, so it does not mirror on its own account. It only
        // rides the reflected cursor, exactly as any other item does.
        assertEquals(ltrRule.width(), rtlRule.width(), EPS);
        assertEquals(BAR_W - ltrRule.x() - ltrRule.width(), rtlRule.x(), EPS);
    }

    private ToolBar barWithSeparator(LayoutDirection direction) {
        ToolBar built = new ToolBar();
        built.addItem(new Block(20, 20));
        built.addSeparator();
        built.addItem(new Block(20, 20));
        built.setLayoutDirection(direction);
        Scene scene = new Scene(built);
        scene.setTextRuler(RULER);
        scene.layoutPass(BAR_W, BAR_H);
        return built;
    }

    private static Separator ruleOf(ToolBar built) {
        return (Separator) built.children().get(1);
    }

    // ============================================================== ContextMenus

    private static final float SCENE_W = 800;
    private static final float SCENE_H = 400;

    /** The attached region's box inside the scene: never the scene's own, so a bug cannot hide. */
    private static final float REGION_X = 100;
    private static final float REGION_Y = 40;
    private static final float REGION_W = 600;
    private static final float REGION_H = 300;

    /** The focused widget's box, in the region's coordinates and then in the scene's. */
    private static final float FIELD_LOCAL_X = 200;
    private static final float FIELD_LOCAL_Y = 60;
    private static final float FIELD_W = 120;
    private static final float FIELD_H = 24;
    private static final float FIELD_X = REGION_X + FIELD_LOCAL_X;
    private static final float FIELD_Y = REGION_Y + FIELD_LOCAL_Y;

    /** A focusable stand-in for the row or field the keyboard route drops its menu from. */
    private static final class Focusable extends Widget {
        Focusable() {
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(FIELD_W, FIELD_H);
        }
    }

    /** Puts its one child at a known physical box: the same box in either direction. */
    private static final class Holder extends Widget {
        private final Focusable field;

        Holder(Focusable field) {
            this.field = field;
            add(field);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            field.measure(Constraints.loose(FIELD_W, FIELD_H));
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            field.layoutBox(FIELD_LOCAL_X, FIELD_LOCAL_Y, FIELD_W, FIELD_H);
        }
    }

    /** Puts the attached region away from the scene origin, so the scene conversion is real. */
    private static final class Frame extends Widget {
        private final Widget region;

        Frame(Widget region) {
            this.region = region;
            add(region);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            region.measure(Constraints.loose(REGION_W, REGION_H));
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            region.layoutBox(REGION_X, REGION_Y, REGION_W, REGION_H);
        }
    }

    /** Records the round rects a frame fills; the first a cascade paints is its column. */
    private static final class ColumnCanvas extends FakeCanvas {
        final List<RoundRect> roundRects = new ArrayList<>();

        ColumnCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            roundRects.add(roundRect);
        }
    }

    private Focusable field;
    private Widget region;
    private Scene menuScene;

    /**
     * A region wrapping one focusable field, on a window that cannot place a popup of its own,
     * so the cascade is drawn as an in-scene overlay: the one presentation a headless test can
     * lay out and paint.
     */
    private void buildRegion(LayoutDirection sceneDirection) {
        field = new Focusable();
        region = ContextMenus.attach(new Holder(field),
                () -> new Menu().addItem("Open", () -> { }));
        menuScene = new Scene(new Frame(region));
        menuScene.setTextRuler(RULER);
        menuScene.setLayoutDirection(sceneDirection);
        menuScene.bind(new StubWindow(false));
        menuScene.layoutPass(SCENE_W, SCENE_H);
    }

    /** The column the open cascade painted, in scene coordinates. */
    private RoundRect openedColumn() {
        menuScene.layoutPass(SCENE_W, SCENE_H); // the overlay's bounds are installed in onLayout
        ColumnCanvas canvas = new ColumnCanvas(SCENE_W, SCENE_H);
        menuScene.renderFrame(canvas);
        assertFalse(canvas.roundRects.isEmpty(), "no cascade was painted");
        RoundRect column = canvas.roundRects.get(0);
        assertTrue(column.x() > 0 && column.x() + column.width() < SCENE_W,
                "the column was clamped to the bounds, so its edges prove nothing: " + column.x());
        return column;
    }

    private void pressMenuKey() {
        menuScene.keyEvent(Keys.MENU, true, false, 0);
        menuScene.inputBatchEnded();
    }

    // ------------------------------------------------ the keyboard route's corner

    @Test
    void theKeyboardRouteDropsFromTheFocusedWidgetsLowerLeftReadingLeftToRight() {
        buildRegion(LayoutDirection.LTR);
        menuScene.requestFocus(field);
        pressMenuKey();

        RoundRect column = openedColumn();
        assertEquals(FIELD_X, column.x(), EPS,
                "unchanged: the column's left edge is the field's left edge");
        assertEquals(FIELD_Y + FIELD_H, column.y(), EPS, "and it still drops below the field");
    }

    @Test
    void theKeyboardRouteDropsFromTheFocusedWidgetsLowerRightReadingRightToLeft() {
        buildRegion(LayoutDirection.RTL);
        menuScene.requestFocus(field);
        pressMenuKey();

        RoundRect column = openedColumn();
        // The menu hangs from the field's leading corner, which is its right one here, and the
        // cascade grows away from that corner, so the column's RIGHT edge is what meets it.
        assertEquals(FIELD_X + FIELD_W, column.x() + column.width(), EPS,
                "the column meets the edge the field starts reading from");
        assertEquals(FIELD_Y + FIELD_H, column.y(), EPS,
                "the drop is downward in both directions: the vertical has no side");
    }

    /**
     * The direction is the focused widget's own, not the region's — for the corner and for the
     * cascade both. A right-to-left field inside a left-to-right form starts reading at its own
     * right edge, and the menu both hangs from that corner and grows the way the field reads,
     * exactly as it would in a form that read the field's way throughout.
     *
     * <p>This assertion changed once, deliberately. The anchor point took the field's direction
     * before the cascade did, so a menu whose corner was the field's but whose growth was the
     * region's opened away from the field it dropped from; the old expectation pinned that gap
     * while it was a recorded defect, and was rewritten when the popup began anchoring on the
     * widget the corner comes from.
     */
    @Test
    void aRightToLeftFieldInsideALeftToRightRegionDropsFromItsOwnLeadingCorner() {
        buildRegion(LayoutDirection.LTR);
        field.setLayoutDirection(LayoutDirection.RTL);
        menuScene.layoutPass(SCENE_W, SCENE_H);
        menuScene.requestFocus(field);
        pressMenuKey();

        RoundRect column = openedColumn();
        assertEquals(FIELD_X + FIELD_W, column.x() + column.width(), EPS,
                "the column meets the field's own leading corner and grows the way the field "
                        + "reads, not the way the region around it does");
        assertEquals(FIELD_Y + FIELD_H, column.y(), EPS, "the drop is downward either way");
    }

    @Test
    void theFallbackWithNothingFocusedTakesTheRegionsOwnLeadingCorner() {
        buildRegion(LayoutDirection.RTL);
        // Nothing has focus, so the anchor is the region itself, which is the documented
        // fallback and the only place left that is still related to the request.
        ContextMenus.showForFocus(region, new Menu().addItem("Open", () -> { }));

        RoundRect column = openedColumn();
        assertEquals(REGION_X + REGION_W, column.x() + column.width(), EPS,
                "the fallback mirrors too: the region's leading corner, not its left one");
    }

    // ---------------------------------------- what the pointer route does NOT do

    /**
     * A menu raised at the pointer lands on the pointer reading either way. Which corner of the
     * column meets that point is {@link PopupMenu}'s decision and is taken once, there; a second
     * reflection here would move the menu away from the spot the user aimed at.
     *
     * <p>Asserted as the relation between the two directions rather than as an absolute x on
     * purpose. {@code showAt} converts its point in a way that its own Javadoc and its callers
     * disagree about, and that disagreement is a separate defect: pinning an absolute number
     * here would pin whichever answer happens to be in the file today.
     */
    @Test
    void thePointerRouteOpensAtThePointerInBothDirections() {
        float px = 300;
        float py = 150;

        buildRegion(LayoutDirection.LTR);
        menuScene.mouseButton(Keys.MOUSE_RIGHT, true, 0, px, py);
        menuScene.inputBatchEnded();
        RoundRect ltr = openedColumn();

        buildRegion(LayoutDirection.RTL);
        menuScene.mouseButton(Keys.MOUSE_RIGHT, true, 0, px, py);
        menuScene.inputBatchEnded();
        RoundRect rtl = openedColumn();

        assertEquals(ltr.width(), rtl.width(), EPS, "the column is exactly as wide either way");
        assertEquals(ltr.x(), rtl.x() + rtl.width(), EPS,
                "the same point, met by the other corner: the point itself did not move");
        assertEquals(ltr.y(), rtl.y(), EPS, "and the y has no side at all");
    }

    /**
     * The gesture is a button identity, not a coordinate. A mirrored interface does not swap the
     * user's mouse buttons, and the keyboard's two routes name physical keys.
     */
    @Test
    void theGestureItselfDoesNotMirror() {
        LayoutDirection.setProcessDefault(LayoutDirection.RTL);
        try {
            assertTrue(ContextMenus.isRequest(press(Keys.MOUSE_RIGHT)),
                    "the secondary button is still the secondary button");
            assertFalse(ContextMenus.isRequest(press(Keys.MOUSE_LEFT)));
            assertTrue(ContextMenus.isRequest(new KeyEvent(Keys.MENU, true, false, 0)));
            assertTrue(ContextMenus.isRequest(
                    new KeyEvent(Keys.F10, true, false, Keys.MOD_SHIFT)));
        } finally {
            LayoutDirection.setProcessDefault(LayoutDirection.LTR);
        }
    }

    private static MouseEvent press(int button) {
        return new MouseEvent(MouseEvent.Type.PRESS, 0, 0, button, 0, 0, 0);
    }
}
