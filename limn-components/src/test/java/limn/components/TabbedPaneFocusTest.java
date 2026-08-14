package limn.components;

import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roving focus in the tab strip: the whole strip is a single tab stop; only the
 * <em>selected</em> header is focusable, so Tab/Shift+Tab land on it (arrows move
 * between tabs once inside). Regression: Shift+Tab from below the pane used to
 * land on the <em>last</em> header, because every header was focusable.
 *
 * <p>Also the per-step home of the overflow chevrons: the three strip controls are squares of
 * the strip height, so their hit boxes move with the {@link limn.scene.ControlSize} step and a
 * coordinate baked at MEDIUM would silently miss at the other four.
 */
class TabbedPaneFocusTest extends ComponentTestBase {

    private TabbedPane tabs;
    private Button below;
    private Scene scene;

    /** The header of tab {@code index} (headers are the strip's children, in order). */
    private Widget headerOf(int index) {
        Widget strip = tabs.children().get(0);
        return strip.children().get(index);
    }

    private void build(int tabCount) {
        build(tabCount, null);
    }

    private void build(int tabCount, ControlSize step) {
        tabs = new TabbedPane();
        if (step != null) {
            tabs.setControlSize(step);
        }
        for (int i = 1; i <= tabCount; i++) {
            tabs.addTab("Tab " + i, new Label("content " + i)); // labels: not focusable
        }
        below = new Button("Depois");
        Column col = new Column();
        col.crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new SizedBox(SizedBox.UNSET, 160, tabs));
        col.add(below);
        scene = new Scene(col);
        scene.setTextRuler(RULER);
        scene.layoutPass(500, 300);
    }

    /** Clicks the next (›) chevron, whose box is the second square from the right edge. */
    private void clickNextChevron() {
        float stripH = tabs.children().get(0).height();
        float cx = tabs.localToSceneX() + tabs.width() - 1.5f * stripH; // › chevron
        float cy = tabs.localToSceneY() + stripH / 2;
        scene.mouseButton(limn.input.Keys.MOUSE_LEFT, true, 0, cx, cy);
        scene.mouseButton(limn.input.Keys.MOUSE_LEFT, false, 0, cx, cy);
        scene.inputBatchEnded(); // no layout pass in between
    }

    @Test
    void chevronScrollShiftsHeadersSynchronously() {
        // The Scrollable contract: revealInView re-reads coordinates between
        // nested scrollables in one pass, so the strip must move its headers
        // in the same event, not on the next layout.
        build(30); // far more tabs than 500pt fits: the strip overflows
        float before = headerOf(0).x();
        clickNextChevron();
        assertTrue(headerOf(0).x() < before - 1,
                "headers shift in the same event: " + headerOf(0).x() + " vs " + before);
    }

    /**
     * The same gesture at every step. 500 pt is wider than {@code 4 * stripHeight} at all five
     * (168 pt at the widest), so all three controls are present everywhere and the › chevron
     * stays the second square from the right, at whatever size that square happens to be.
     */
    @Test
    void chevronScrollFollowsTheStepAtEveryStep() {
        for (ControlSize step : ControlSize.values()) {
            build(30, step);
            Widget strip = tabs.children().get(0);
            assertTrue(strip.width() >= strip.height(),
                    step + ": the viewport never collapses under the controls");
            float before = headerOf(0).x();
            clickNextChevron();
            assertTrue(headerOf(0).x() < before - 1,
                    step + ": the chevron scrolls the strip, " + headerOf(0).x() + " vs " + before);
        }
    }

    @Test
    void onlyTheSelectedHeaderIsFocusable() {
        build(4);
        for (int i = 0; i < 4; i++) {
            assertTrue(headerOf(i).isFocusable() == (i == 0), "initially only tab 0's header");
        }
        tabs.setSelectedIndex(2);
        for (int i = 0; i < 4; i++) {
            assertTrue(headerOf(i).isFocusable() == (i == 2), "focusability roves with selection");
        }
    }

    @Test
    void shiftTabFromBelowLandsOnTheSelectedHeader() {
        build(4);
        tabs.setSelectedIndex(1);
        below.requestFocus();

        scene.focusTraverse(true); // Shift+Tab

        assertSame(headerOf(1), scene.focusedWidget(),
                "backward traversal must land on the selected header (used to hit the last one)");
    }

    @Test
    void tabForwardFromTheHeaderSkipsTheOtherHeaders() {
        build(4);
        tabs.setSelectedIndex(1);
        scene.focusTraverse(false); // from nothing → first focusable = selected header
        assertSame(headerOf(1), scene.focusedWidget());

        scene.focusTraverse(false); // Tab: strip is one stop, leaves to the button
        assertSame(below, scene.focusedWidget(), "one tab stop for the whole strip");
    }

    @Test
    void focusFollowsAProgrammaticSelectionWhenTheStripIsFocused() {
        build(4);
        below.requestFocus();
        scene.focusTraverse(true); // onto the selected header (tab 0)
        assertSame(headerOf(0), scene.focusedWidget());

        tabs.setSelectedIndex(3); // e.g. the "go to tab" menu / overflow popup

        assertSame(headerOf(3), scene.focusedWidget(), "roving focus follows the selection");
        assertFalse(headerOf(0).isFocusable(), "the old header stopped being a tab stop");
    }
}
