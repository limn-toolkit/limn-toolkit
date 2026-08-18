package limn.components;

import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the kitchen-sink "footer steals the click" bug: a tab whose
 * content is taller than the panel overflows (TabbedPane does not clip), and
 * the overflowing bottom rows are painted over a later sibling: the perf
 * footer. Because "later children win" in {@link Widget#hitTest}, the footer
 * captures the click on those rows, so a short window makes the bottom button
 * unclickable. Wrapping the content in a {@link ScrollView} keeps it inside the
 * panel, restoring the click at any window height.
 *
 * <p>Mirrors the demo layout {@code Column[ Expanded(TabbedPane), footer ]} with
 * the deterministic 10pt/glyph ruler so positions are exact.
 */
class TabScrollOverflowTest extends ComponentTestBase {

    private static final float FOOTER_H = 150;

    /** Sum of a widget's origin up the parent chain → its scene-space y. */
    private static float sceneY(Widget w) {
        float y = 0;
        for (Widget c = w; c != null; c = c.parent()) {
            y += c.y();
        }
        return y;
    }

    private static float sceneX(Widget w) {
        float x = 0;
        for (Widget c = w; c != null; c = c.parent()) {
            x += c.x();
        }
        return x;
    }

    @Test
    void unboundedHeightMeasuresStripPlusContent() {
        // Inside a scrollable parent (unbounded max height) the pane must
        // report strip + selected content, not collapse to the strip alone
        // and lay the content out with zero height.
        TabbedPane pane = new TabbedPane();
        pane.addTab("One", new SizedBox(120, 300));
        pane.addTab("Two", new SizedBox(120, 50));
        ScrollView scroll = new ScrollView(pane);
        Scene scene = new Scene(scroll);
        scene.setTextRuler(RULER);
        scene.layoutPass(240, 150);

        assertTrue(pane.height() > 300, "strip + content, got " + pane.height());
        Widget content = pane.children().stream()
                .filter(c -> c instanceof SizedBox && c.isVisible()).findFirst().orElseThrow();
        assertEquals(300, content.height(), 0.5f, "selected content keeps its natural height");
    }

    /** Strip height at {@code step} under the degenerate ruler: 24 / 26 / 30 / 36 / 42. */
    private static float stripH(ControlSize step) {
        SizeTokens t = SizeTokens.of(step);
        return RULER.measure("Hg", t.body()).lineHeight() + 2 * t.tabPadV();
    }

    /**
     * The narrow-pane overflow policy. Three square strip controls cost {@code 3 * stripHeight}
     * (75 pt at XSMALL but 157 pt at XLARGE in real rendering), so a narrow XLARGE pane once
     * drove {@code viewWidth} to 0 while {@code overflowing} stayed true: every header
     * unreachable, and the chevron enable state computed against a zero-width viewport.
     *
     * <p>The policy is to SHRINK the controls, not to drop them. Dropping two of the three
     * below a width threshold fixed the collapse but made the viewport non-monotone in the
     * pane's width: at MEDIUM a 137pt pane got a 102pt viewport and a 138pt pane got 34pt,
     * because full mode costs {@code 2 * stripHeight} more than compact mode at every width, so
     * no threshold can remove the discontinuity. Capping each control at a sixth of the pane
     * keeps the viewport at or above half the pane everywhere.
     */
    @Test
    void aNarrowPaneShrinksItsStripControlsInsteadOfDroppingThem() {
        TabbedPane pane = new TabbedPane();
        pane.setControlSize(ControlSize.XLARGE);
        for (int i = 1; i <= 8; i++) {
            pane.addTab("Tab " + i, new SizedBox(40, 40));
        }
        Scene scene = new Scene(pane);
        scene.setTextRuler(RULER);
        float paneWidth = 160; // well under 3 * stripHeight, where the old policy collapsed
        scene.layoutPass(paneWidth, 200);

        Widget viewport = pane.children().get(0);
        Widget prev = pane.children().get(1);
        Widget next = pane.children().get(2);
        Widget list = pane.children().get(3);

        assertTrue(viewport.width() >= paneWidth / 2,
                "the viewport keeps at least half the pane: " + viewport.width());
        assertTrue(prev.isVisible() && next.isVisible() && list.isVisible(),
                "all three affordances survive: shrunk, not dropped");
        assertTrue(list.width() <= paneWidth / 6 + 1e-3,
                "each control is capped at a sixth of the pane: " + list.width());
        assertSame(list, pane.hitTest(list.x() + list.width() / 2, list.y() + list.height() / 2),
                "a shrunken chevron is still hit-testable at its centre");
        assertEquals(paneWidth, list.x() + list.width(), 1e-3, "flush with the right edge");

        // A second full pass at the same width is a fixed point: the oscillation guard the
        // class javadoc is about.
        float before = viewport.children().get(0).x();
        scene.relayout();
        scene.layoutPass(paneWidth, 200);
        assertEquals(before, viewport.children().get(0).x(), 1e-3,
                "the scroll offset is stable across identical layout passes");
    }

    /**
     * The defect the shrink policy exists to prevent: widening the pane must never shrink the
     * tab viewport. Swept a point at a time across the range where the old threshold lived.
     */
    @Test
    void wideningThePaneNeverShrinksTheViewport() {
        for (ControlSize step : ControlSize.values()) {
            TabbedPane pane = new TabbedPane();
            pane.setControlSize(step);
            for (int i = 1; i <= 8; i++) {
                pane.addTab("Tab " + i, new SizedBox(40, 40));
            }
            Scene scene = new Scene(pane);
            scene.setTextRuler(RULER);
            Widget viewport = pane.children().get(0);

            float previous = -1;
            for (float w = 60; w <= 400; w += 1) {
                scene.layoutPass(w, 200);
                float current = viewport.width();
                assertTrue(current >= previous - 1e-3,
                        step + ": widening " + (w - 1) + " -> " + w + " shrank the viewport "
                                + previous + " -> " + current);
                previous = current;
            }
        }
    }

    /** A tall column (4×40 spacers + gaps + button) whose last row is {@code button}. */
    private static Column tallContent(Button button) {
        Column content = new Column();
        content.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        for (int i = 0; i < 4; i++) {
            content.add(new SizedBox(SizedBox.UNSET, 40));
        }
        content.add(button);
        return content;
    }

    /** Builds {@code Column[ Expanded(tabs[content]), footer ]} laid out short. */
    private Scene shortSceneWith(Widget tabContent, Button button, TabbedPane[] outTabs) {
        TabbedPane tabs = new TabbedPane();
        tabs.addTab("Actions", tabContent);
        outTabs[0] = tabs;

        Column page = new Column();
        page.crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(Expanded.of(tabs, 1));
        page.add(new SizedBox(SizedBox.UNSET, FOOTER_H)); // the "perf footer" leaf sibling

        Scene scene = new Scene(page);
        scene.setTextRuler(RULER);
        // Height 300: tabs get 300-150 = 150; strip ~30 → content box ~120,
        // well short of the ~190pt tall content, so it overflows.
        scene.layoutPass(400, 300);
        return scene;
    }

    private static void click(Scene scene, float x, float y) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    @Test
    void withoutAScrollViewTheOverflowingButtonIsStolenByTheFooter() {
        AtomicInteger clicks = new AtomicInteger();
        Button button = new Button("Play sound");
        button.onAction(clicks::incrementAndGet);
        TabbedPane[] tabs = new TabbedPane[1];
        Scene scene = shortSceneWith(tallContent(button), button, tabs);

        float tabBottom = sceneY(tabs[0]) + tabs[0].height();
        float buttonBottom = sceneY(button) + button.height();
        assertTrue(buttonBottom > tabBottom,
                "precondition: the bottom button overflows the panel (into the footer band), "
                        + buttonBottom + " > " + tabBottom);

        // Click the button where it is actually painted: over the footer.
        click(scene, sceneX(button) + button.width() / 2, sceneY(button) + button.height() / 2);
        assertEquals(0, clicks.get(),
                "the footer sibling steals the click, so the button never fires");
    }

    @Test
    void wrappingTheTabContentInAScrollViewKeepsTheButtonClickable() {
        AtomicInteger clicks = new AtomicInteger();
        Button button = new Button("Play sound");
        button.onAction(clicks::incrementAndGet);
        ScrollView scroll = new ScrollView(tallContent(button));
        TabbedPane[] tabs = new TabbedPane[1];
        Scene scene = shortSceneWith(scroll, button, tabs);

        // The scroll viewport (the tab content) stays fully inside the panel; no
        // overflow can reach the footer band anymore.
        float tabBottom = sceneY(tabs[0]) + tabs[0].height();
        assertTrue(sceneY(scroll) + scroll.height() <= tabBottom + 1e-3,
                "the ScrollView viewport is clamped to the panel");
        assertTrue(scroll.maxOffsetY() > 0, "precondition: the content is taller than the viewport");

        // Scroll the bottom row into view, then click it: the hit now lands on
        // the button inside the panel instead of on the footer.
        scroll.scrollTo(0, scroll.maxOffsetY());
        float buttonCenterY = sceneY(button) + button.height() / 2;
        assertTrue(buttonCenterY < tabBottom, "the button is inside the viewport after scrolling");
        click(scene, sceneX(button) + button.width() / 2, buttonCenterY);
        assertEquals(1, clicks.get(), "the button fires when its content scrolls instead of overflowing");
    }
}
