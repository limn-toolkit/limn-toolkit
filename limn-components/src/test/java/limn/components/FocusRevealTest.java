package limn.components;

import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.Insets;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto-scroll on focus: the {@link Scene} asks every {@code Scrollable} ancestor
 * to reveal the newly focused widget ({@link Widget#revealInView()}), so keyboard
 * traversal (Tab/Shift+Tab) never lands on an off-screen widget; previously the
 * viewport stayed put and focus went "blind". Also covers the {@code Scrollable}
 * SPI directly on {@link ListView}.
 */
class FocusRevealTest extends ComponentTestBase {

    private Button[] buttons;
    private Column col;
    private ScrollView scroll;
    private Scene scene;

    /** A 150pt-tall viewport over ~20 stacked buttons (well past the viewport). */
    private void build() {
        col = new Column();
        col.crossAlignment(Flex.CrossAlignment.STRETCH);
        buttons = new Button[20];
        for (int i = 0; i < buttons.length; i++) {
            buttons[i] = new Button("Button " + i);
            col.add(buttons[i]);
        }
        scroll = new ScrollView(col);
        scene = new Scene(scroll);
        scene.setTextRuler(RULER);
        scene.layoutPass(240, 150);
        assertTrue(scroll.maxOffsetY() > 0, "precondition: the column overflows the viewport");
    }

    /** The button's y inside the viewport (scroll view at the scene origin). */
    private float viewportY(Button button) {
        return button.localToSceneY() - scroll.localToSceneY();
    }

    private void assertVisible(Button button) {
        float y = viewportY(button);
        assertTrue(y >= -0.01f && y + button.height() <= scroll.height() + 0.01f,
                "focused button inside the viewport (y=" + y + ")");
    }

    @Test
    void focusOnAJustAddedWidgetDefersRevealToAfterLayout() {
        // The add-row-and-focus pattern: the new widget still sits at (0,0)
        // against already-offset scroll content, so an immediate reveal would
        // snap the viewport to the top and leave focus off-screen.
        build();
        buttons[15].requestFocus();
        float scrolled = scroll.offsetY();
        assertTrue(scrolled > 0, "precondition: viewport scrolled down");

        Button added = new Button("Added");
        col.add(added); // marks layout dirty; geometry is stale until the pass
        added.requestFocus();
        assertEquals(scrolled, scroll.offsetY(), 0.01f,
                "no reveal on stale geometry (would snap to the top)");

        scene.layoutPass(240, 150);
        assertVisible(added); // the deferred reveal ran with real geometry
    }

    @Test
    void requestFocusScrollsTheViewportToTheWidget() {
        build();
        assertEquals(0f, scroll.offsetY(), 0.01f);

        buttons[15].requestFocus();

        assertTrue(scroll.offsetY() > 0, "the viewport followed the focus");
        assertVisible(buttons[15]);
    }

    @Test
    void tabTraversalKeepsEveryFocusedButtonVisible() {
        build();
        for (int i = 0; i < buttons.length; i++) {
            scene.focusTraverse(false); // Tab
            assertEquals(buttons[i], scene.focusedWidget());
            assertVisible(buttons[i]);
        }
        assertTrue(scroll.offsetY() >= scroll.maxOffsetY() - 0.01f,
                "walking to the last button scrolled to the end");
    }

    @Test
    void shiftTabWrappingToTheLastButtonRevealsTheBottom() {
        build();
        scene.focusTraverse(true); // Shift+Tab with nothing focused → wraps to the last
        assertEquals(buttons[buttons.length - 1], scene.focusedWidget());
        assertVisible(buttons[buttons.length - 1]);
    }

    /**
     * The ring, not the box. A Button paints {@code FOCUS_RING_OUTSET} beyond its bounds,
     * and revealing the bare bounds parks it flush against the clip, where the ring that
     * says it is focused is the part that gets cut.
     */
    private void assertRingVisible(Button button) {
        float y = viewportY(button);
        float outset = button.paintOutset();
        assertTrue(outset > 0, "precondition: a Button declares a ring outside its box");
        assertTrue(y - outset >= -0.01f && y + button.height() + outset <= scroll.height() + 0.01f,
                "the focus ring is inside the viewport too (y=" + y + ", outset=" + outset + ")");
    }

    /** The same stack, padded inside the scroll view the way a real form is built. */
    private void buildPadded(float inset) {
        col = new Column();
        col.crossAlignment(Flex.CrossAlignment.STRETCH);
        buttons = new Button[20];
        for (int i = 0; i < buttons.length; i++) {
            buttons[i] = new Button("Button " + i);
            col.add(buttons[i]);
        }
        scroll = new ScrollView(new Padding(Insets.all(inset), col));
        scene = new Scene(scroll);
        scene.setTextRuler(RULER);
        scene.layoutPass(240, 150);
        assertTrue(scroll.maxOffsetY() > 0, "precondition: the content overflows the viewport");
    }

    @Test
    void tabTraversalKeepsTheFocusRingVisibleAndNotOnlyTheBox() {
        // Padded, because the two halves are not interchangeable: this covers the middle
        // of the scroll, which padding alone cannot reach, and the ends, which the reveal
        // alone cannot reach. Together every button in the run keeps its ring.
        buildPadded(16);
        for (int i = 0; i < buttons.length; i++) {
            scene.focusTraverse(false);
            assertRingVisible(buttons[i]);
        }
    }

    /**
     * What the reveal cannot do on its own, stated rather than discovered later: at the
     * very top of unpadded content there is nowhere left to scroll, so the first widget's
     * ring is clipped however the rect is inflated. Only an inset inside the content puts
     * pixels there. The pair is the fix; this is the half that is not.
     */
    @Test
    void unpaddedContentStillClipsTheRingAtItsEnds() {
        build();
        buttons[0].requestFocus();
        assertEquals(0f, scroll.offsetY(), 0.01f, "already at the top: nothing to scroll back");
        assertEquals(0f, viewportY(buttons[0]), 0.01f, "and so the ring has no room above it");

        buildPadded(16);
        buttons[0].requestFocus();
        assertRingVisible(buttons[0]);
    }

    @Test
    void alreadyVisibleFocusDoesNotScroll() {
        build();
        buttons[0].requestFocus();
        assertEquals(0f, scroll.offsetY(), 0.01f, "no movement when already visible");
    }

    // ------------------------------------------------------------- ListView SPI

    /** A fixed-height spacer row. */
    private static final class Row extends Widget {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), 40);
        }
    }

    @Test
    void listViewRevealRectScrollsTheRowIntoView() {
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return 30;
            }

            @Override
            public Widget rowAt(int index) {
                return new Row();
            }
        });
        Scene listScene = new Scene(list);
        listScene.setTextRuler(RULER);
        listScene.layoutPass(200, 120); // 3 rows visible of 30

        assertEquals(0, list.firstVisibleIndex());
        list.revealRect(0, 400, 200, 40); // a rect one page below the viewport
        listScene.layoutPass(200, 120);   // normalize the anchor

        assertTrue(list.firstVisibleIndex() > 0, "the list scrolled toward the revealed rect");
    }
}
