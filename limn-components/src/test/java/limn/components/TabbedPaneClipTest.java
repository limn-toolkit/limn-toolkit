package limn.components;

import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A panel stays inside the pane.
 *
 * <p>{@code onLayout} hands the selected panel a box, and a box is only a box: nothing stops a
 * child from measuring taller than it and painting past the bottom edge. Until this was asserted,
 * nothing did stop it: a panel with no scroll view around it, in a window short enough, drew
 * straight over whatever the pane was sitting on, and it read as a container with no bounds rather
 * than as content that is too big.
 *
 * <p>Clipping is not a substitute for scrolling and is not asserted as one. What it guarantees is
 * that overflow looks like overflow.
 */
class TabbedPaneClipTest extends ComponentTestBase {

    /** A panel that insists on being far taller than whatever box it is given. */
    private static final class TallPanel extends Widget {

        static final float HEIGHT = 4000;

        private final Label deepInside = new Label("bottom");

        TallPanel() {
            add(deepInside);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return new Size(constraints.hasBoundedWidth() ? constraints.maxWidth() : 200, HEIGHT);
        }

        @Override
        protected void onLayout() {
            // Near the very bottom of the panel, which is far below the pane's own bottom edge.
            deepInside.measure(Constraints.loose(width(), 20));
            deepInside.layoutBox(0, HEIGHT - 20, width(), 20);
        }

        Label deepInside() {
            return deepInside;
        }
    }

    private TabbedPane tabs;
    private TallPanel tall;
    private Scene scene;

    private void build() {
        tall = new TallPanel();
        tabs = new TabbedPane();
        tabs.addTab("AA", tall);
        tabs.addTab("BB", new Label("BB"));
        scene = new Scene(tabs);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
    }

    @Test
    void theSelectedPanelIsClippedToThePane() {
        build();
        assertTrue(tabs.clipsChildren(),
                "a pane whose panel can be taller than its box has to declare that it clips: "
                        + "isShowing() and the partial-render damage clamp both read this");
    }

    /**
     * The behavioural half, and the one that matters at run time: a widget laid out past the pane's
     * bottom edge is not on screen. Animations, tickers and video decoding all stop on
     * {@code isShowing()}, so without the clip declaration a widget scrolled or overflowed out of a
     * tab keeps the frame loop running at full rate while painting nothing anybody can see.
     */
    @Test
    void aWidgetPastTheBottomEdgeIsNotShowing() {
        build();
        assertNotNull(tall.deepInside().scene());
        assertTrue(tall.deepInside().isVisible(), "its own flag is set; it is the pane that hides it");
        assertFalse(tall.deepInside().isShowing(),
                "a widget " + TallPanel.HEIGHT + "pt down a 300pt pane is not on screen");
    }

    /** And the ordinary case still is on screen, so the clip is not simply hiding everything. */
    @Test
    void aWidgetInsideTheBoxIsStillShowing() {
        Label inside = new Label("inside");
        tabs = new TabbedPane();
        tabs.addTab("AA", inside);
        scene = new Scene(tabs);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        assertTrue(inside.isShowing());
    }

    /**
     * The paint is clipped too, and not merely declared to be: removing only the
     * {@code paintChildren} override would leave {@code isShowing()} answering correctly while the
     * pixels still escaped.
     *
     * <p>The pane is deliberately INSET from the scene. A full repaint pass pushes a clip the size
     * of the frame, so a pane filling the frame produces a rectangle nothing can tell apart from
     * the scene's own; inset by 25 on every side, a clip of the pane's box is unmistakably the
     * pane's.
     */
    @Test
    void thePaintIsClippedToThePaneBounds() {
        tall = new TallPanel();
        tabs = new TabbedPane();
        tabs.addTab("AA", tall);
        scene = new Scene(new limn.scene.layout.Padding(limn.scene.Insets.all(INSET), tabs));
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);

        float paneWidth = 400 - 2 * INSET;
        float paneHeight = 300 - 2 * INSET;
        RecordingTestCanvas canvas = new RecordingTestCanvas(400, 300);
        scene.renderFrame(canvas);

        assertTrue(canvas.clips.stream().anyMatch(c -> Math.abs(c.width() - paneWidth) < 0.5f
                        && Math.abs(c.height() - paneHeight) < 0.5f),
                "no clip of the pane's own " + paneWidth + "x" + paneHeight + " box was pushed, so "
                        + "the panel's " + TallPanel.HEIGHT + "pt painted past it; clips were "
                        + canvas.clips);
    }

    private static final float INSET = 25;
}
