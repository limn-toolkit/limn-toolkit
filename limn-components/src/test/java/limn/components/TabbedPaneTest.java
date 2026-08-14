package limn.components;

import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TabbedPane selection, content visibility, header alignment (left/center/
 * right) and keyboard navigation, using the deterministic 10pt/glyph ruler so
 * header positions are exact.
 *
 * <p>Every coordinate is <b>derived from the {@link SizeTokens} row</b> rather than baked in:
 * the strip height is {@code lineHeight + 2 * tabPadV} and a header is
 * {@code 10 * glyphs + 2 * tabPadH}, so the same assertions run at all five steps instead of
 * pinning MEDIUM's 30 and 52 in five places.
 */
class TabbedPaneTest extends ComponentTestBase {

    private TabbedPane tabs;
    private Label a;
    private Label b;
    private Label c;
    private Scene scene;
    private final AtomicInteger changed = new AtomicInteger(-1);

    /**
     * Strip height at {@code step} under {@link #RULER}, which reports {@code lineHeight = 12}
     * for every font, so this is 24 / 26 / 30 / 36 / 42, not the 24.89 … 52.27 of real
     * rendering. The expression is the component's, the numbers are the ruler's.
     */
    private static float stripH(ControlSize step) {
        SizeTokens t = SizeTokens.of(step);
        return RULER.measure("Hg", t.body()).lineHeight() + 2 * t.tabPadV();
    }

    /** Header width of a {@code glyphs}-long title: 36 / 44 / 52 / 60 / 68 for two glyphs. */
    private static float headerW(ControlSize step, int glyphs) {
        return 10f * glyphs + 2 * SizeTokens.of(step).tabPadH();
    }

    private void build(TabbedPane.TabAlignment alignment) {
        build(alignment, ControlSize.MEDIUM);
    }

    private void build(TabbedPane.TabAlignment alignment, ControlSize step) {
        // Equal-width 2-glyph titles → each header is 10*2 + 2*tabPadH wide.
        a = new Label("AA");
        b = new Label("BB");
        c = new Label("CC");
        tabs = new TabbedPane().setAlignment(alignment);
        tabs.setControlSize(step);
        tabs.addTab("AA", a);
        tabs.addTab("BB", b);
        tabs.addTab("CC", c);
        tabs.onSelect(changed::set);
        scene = new Scene(tabs);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
    }

    private void click(float x, float y) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    /** Clicks the middle of header {@code index} of a left-aligned strip at {@code step}. */
    private void clickHeader(int index, ControlSize step) {
        float w = headerW(step, 2);
        click(index * w + w / 2, stripH(step) / 2);
    }

    @Test
    void firstTabIsSelectedAndOnlyItsContentVisible() {
        build(TabbedPane.TabAlignment.LEFT);
        assertEquals(0, tabs.selectedIndex());
        assertTrue(a.isVisible());
        assertFalse(b.isVisible());
        assertFalse(c.isVisible());
    }

    @Test
    void setSelectedIndexTogglesContentAndFires() {
        build(TabbedPane.TabAlignment.LEFT);
        tabs.setSelectedIndex(2);
        assertEquals(2, tabs.selectedIndex());
        assertFalse(a.isVisible());
        assertTrue(c.isVisible());
        assertEquals(2, changed.get());
    }

    @Test
    void leftAlignedHeadersStartAtTheLeftEdge() {
        build(TabbedPane.TabAlignment.LEFT);
        // Headers at [0,52) [52,104) [104,156) at MEDIUM; strip height 30.
        clickHeader(1, ControlSize.MEDIUM);
        assertEquals(1, tabs.selectedIndex());
        clickHeader(0, ControlSize.MEDIUM);
        assertEquals(0, tabs.selectedIndex());
    }

    @Test
    void centerAlignedHeadersLeaveTheLeftEdgeEmpty() {
        build(TabbedPane.TabAlignment.CENTER);
        float w = headerW(ControlSize.MEDIUM, 2);
        float y = stripH(ControlSize.MEDIUM) / 2;
        float start = (400 - 3 * w) / 2; // total 156 in 400 → start at 122
        click(20, y);
        assertEquals(0, tabs.selectedIndex(), "left edge is empty when centered");
        click(start + w / 2, y); // inside the first centered header [122,174)
        assertEquals(0, tabs.selectedIndex());
        click(start + w + w / 2, y); // inside header 1 [174,226)
        assertEquals(1, tabs.selectedIndex());
    }

    @Test
    void rightAlignedHeadersSitAtTheRightEdge() {
        build(TabbedPane.TabAlignment.RIGHT);
        float w = headerW(ControlSize.MEDIUM, 2);
        float y = stripH(ControlSize.MEDIUM) / 2;
        click(20, y);
        assertEquals(0, tabs.selectedIndex(), "left edge is empty when right-aligned");
        click(400 - w / 2, y); // inside header 2, flush with the right edge
        assertEquals(2, tabs.selectedIndex());
    }

    /**
     * The split the pane has to keep: an application naming a tab that does not exist is a bug and
     * is told so, while a Right arrow off the last header is a key with nowhere to go and stays
     * put. The setter's throw is the contract of the public setter, not of the pane's own
     * bookkeeping.
     */
    @Test
    void anIndexThatIsNotATabIsRefusedWhileArrowingPastAnEndIsNot() {
        build(TabbedPane.TabAlignment.LEFT);
        clickHeader(2, ControlSize.MEDIUM); // select and focus the last header
        changed.set(-1);

        assertThrows(IndexOutOfBoundsException.class, () -> tabs.setSelectedIndex(3));
        assertThrows(IndexOutOfBoundsException.class, () -> tabs.setSelectedIndex(-1));
        assertEquals(2, tabs.selectedIndex(), "a refused index moves nothing");
        assertEquals(-1, changed.get(), "and announces nothing");

        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(2, tabs.selectedIndex(), "Right past the last tab stays on it");
        assertEquals(-1, changed.get(), "a key that moved nothing announces nothing either");
    }

    @Test
    void arrowKeysMoveSelectionWhenAHeaderIsFocused() {
        build(TabbedPane.TabAlignment.LEFT);
        clickHeader(0, ControlSize.MEDIUM); // select + focus header 0
        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(1, tabs.selectedIndex());
        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(2, tabs.selectedIndex());
        scene.keyEvent(Keys.LEFT, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(1, tabs.selectedIndex());
    }

    @Test
    void clickingATabFocusesTheFirstFocusableInsideItsPanel() {
        Button inside = new Button("go");
        tabs = new TabbedPane();
        tabs.addTab("AA", new Label("AA")); // label panel: nothing focusable
        tabs.addTab("BB", inside);          // focusable panel content
        scene = new Scene(tabs);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);

        clickHeader(1, ControlSize.MEDIUM); // header BB is [52,104)
        assertEquals(1, tabs.selectedIndex());
        assertSame(inside, scene.focusedWidget(),
                "click lands on the panel's first focusable, not the tab header");
    }

    @Test
    void contentFillsBelowTheStrip() {
        build(TabbedPane.TabAlignment.LEFT);
        Widget content = a;
        // strip height 30 → content laid out at y=30, height 270.
        assertEquals(30, content.y(), 1e-3);
        assertEquals(400, content.width(), 1e-3);
        assertEquals(270, content.height(), 1e-3);
    }

    /**
     * The showcase case: two independent ramps (type and tabPadV) compose into a non-linear
     * strip ladder, and the whole layout (content top, content height, header hit boxes)
     * follows it. MEDIUM here is the identity row, so this also guards the conversion.
     */
    @Test
    void everyStepLaysTheStripOutOnItsOwnRamp() {
        for (ControlSize step : ControlSize.values()) {
            build(TabbedPane.TabAlignment.LEFT, step);
            float h = stripH(step);
            assertEquals(h, a.y(), 1e-3, step + ": content starts under the strip");
            assertEquals(300 - h, a.height(), 1e-3, step + ": content fills the rest");

            clickHeader(1, step);
            assertEquals(1, tabs.selectedIndex(), step + ": the second header owns [w, 2w)");
            clickHeader(2, step);
            assertEquals(2, tabs.selectedIndex(), step + ": the third header owns [2w, 3w)");
        }
    }

    /**
     * Guard for the claim in {@code TabbedPane.stripHeight}'s javadoc that the three square
     * overflow controls need no {@code MIN_HIT_TARGET} clamp. Uses {@link #SCALED_RULER},
     * because it is the <em>shipped</em> strip height that has to clear the floor, 24.89 at
     * XSMALL, which is why {@code tabPadV} starts at 6 rather than 4.
     */
    @Test
    void theStripClearsTheHitTargetFloorAtEveryStepWithoutAClamp() {
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            float shipped = SCALED_RULER.measure("Hg", t.body()).lineHeight() + 2 * t.tabPadV();
            assertTrue(shipped >= Strokes.MIN_HIT_TARGET,
                    step + ": strip height " + shipped + " must clear the WCAG floor");
        }
    }
}
