package limn.components;

import limn.graphics.Paint;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.layout.Column;
import limn.scene.layout.Row;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * {@link MenuBar} title geometry and hit mapping, pinned before the size axis reaches it.
 *
 * <p>Title width, title left and the hit test are three separate walks over the same
 * {@code titleWidth(i)} in the source: measure, paint and {@code titleAt}. The invariant that
 * matters is that they agree: the strip a click lands on must be the strip that painted there,
 * and the dropdown must anchor under it. A conversion that resolves tokens twice inside one
 * component breaks exactly this, silently, and nothing else in the suite would notice.
 */
class MenuBarTest extends ComponentTestBase {

    private MenuBar bar;
    private Scene scene;

    /** File, Edit and View, each with one item, so that each can open. */
    private static MenuBar threeTitles() {
        MenuBar built = new MenuBar();
        built.addMenu("File", new Menu().addItem("New", () -> { }));
        built.addMenu("Edit", new Menu().addItem("Undo", () -> { }));
        built.addMenu("View", new Menu().addItem("Zoom", () -> { }));
        return built;
    }

    private void build() {
        bar = threeTitles();
        scene = new Scene(bar);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, MEDIUM.controlHeight());
    }

    private static final SizeTokens MEDIUM = SizeTokens.MEDIUM;

    /**
     * A title's box at a given step, under RULER's 10 pt per code point. Derived rather than
     * baked: the four walks in the source (measure, paint, {@code titleAt}, the dropdown anchor)
     * all go through one {@code titleWidth}, and the point of this file is that they agree;
     * pinning a MEDIUM literal here would only re-state MEDIUM.
     */
    private static float titleWidth(SizeTokens t, String title) {
        return Math.max(Strokes.MIN_HIT_TARGET,
                10f * title.length() + 2 * t.menuBarPadH());
    }

    /** Every title in {@link #build} is four characters: 64 pt at MEDIUM. */
    private static final float TITLE_W = titleWidth(MEDIUM, "File");

    /** No width to fill and 100 pt of height: what a bar asked for its titles is asked under. */
    private static final Constraints UNBOUNDED_WIDTH =
            new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0, 100);

    @Test
    void widthIsTheSumOfTitlesAndHeightIsTheStrip() {
        build();
        Size size = bar.measure(UNBOUNDED_WIDTH);
        assertEquals(3 * TITLE_W, size.width(), 1e-3, "with no width offered, the titles' own");
        assertEquals(MEDIUM.controlHeight(), size.height(), 1e-3,
                "the strip IS the step's control height, so a bar lines up with the buttons");
    }

    @Test
    void theStripAndItsTitlesFollowTheStep() {
        // The bar is chrome, and chrome that ignored the step would leave a SMALL toolbar
        // sitting under a MEDIUM-height menu bar. Both axes move: the strip on the height ramp
        // and the titles on the padding ramp, which are deliberately different ramps.
        for (ControlSize step : ControlSize.values()) {
            build();
            bar.setControlSize(step);
            SizeTokens t = SizeTokens.of(step);
            Size size = bar.measure(UNBOUNDED_WIDTH);
            assertEquals(t.controlHeight(), size.height(), 1e-3, "strip height at " + step);
            assertEquals(3 * titleWidth(t, "File"), size.width(), 1e-3, "titles at " + step);
        }
    }

    @Test
    void aOneGlyphTitleIsStillAnAimableTarget() {
        // The width-axis half of the accessibility floor: titleAt has zero slop, so at XSMALL a
        // one-character title would be 2*6 + 10 = 22 pt, under the 24 pt target even though the
        // strip clears it vertically. The floor widens the box; it is a no-op at MEDIUM (2*12
        // alone already pays it), which is why nothing above moves.
        MenuBar tight = new MenuBar();
        tight.addMenu("A", new Menu().addItem("New", () -> { }));
        tight.addMenu("B", new Menu().addItem("Old", () -> { }));
        Scene tightScene = new Scene(tight);
        tightScene.setTextRuler(RULER);
        tight.setControlSize(ControlSize.XSMALL);
        tightScene.layoutPass(400, 24);

        Size size = tight.measure(UNBOUNDED_WIDTH);
        assertEquals(2 * Strokes.MIN_HIT_TARGET, size.width(), 1e-3,
                "both titles widened to the hit target");

        // And the two boxes still tile without overlapping: a click just left of the boundary
        // opens the first, just right of it the second.
        drive(tightScene).mouseButton(Keys.MOUSE_LEFT, true, 0, Strokes.MIN_HIT_TARGET - 1, 12);
        drive(tightScene).inputBatchEnded();
        assertTrue(tight.isOpen(), "the last point of title 0 belongs to title 0");
        drive(tightScene).mouseButton(Keys.MOUSE_LEFT, true, 0, Strokes.MIN_HIT_TARGET - 1, 12);
        drive(tightScene).inputBatchEnded();
        assertFalse(tight.isOpen(), "and the same point closes it");
    }

    @Test
    void aClickLandsOnTheTitleThatPaintsThere() {
        build();
        // One probe per title, at its centre, opened and then toggled shut again: the mapping
        // is a prefix sum, so an off-by-one in any of the three walks (measure, paint,
        // titleAt) shows up here. Closing by clicking the same title rather than by Escape:
        // Escape is PopupMenu's, and the popup is an overlay that a key event delivered to
        // this scene never reaches.
        for (int i = 0; i < 3; i++) {
            float centre = i * TITLE_W + TITLE_W / 2;
            drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, centre, 16);
            drive(scene).inputBatchEnded();
            assertTrue(bar.isOpen(), "title " + i + " opens on a click at its centre");

            drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, centre, 16);
            drive(scene).inputBatchEnded();
            assertFalse(bar.isOpen(), "title " + i + " closes on a second click at the same x");
        }
    }

    @Test
    void aClickOnEachTitleBoundaryLandsOnTheTitleToItsRight() {
        build();
        // The boundary case the prefix sum gets wrong first: titleAt uses [x, x + w), so the
        // exact left edge of title i belongs to i, and one point before it belongs to i - 1.
        for (int i = 1; i < 3; i++) {
            float edge = i * TITLE_W;
            drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, edge, 16);
            drive(scene).inputBatchEnded();
            assertTrue(bar.isOpen(), "the exact left edge of title " + i + " opens something");
            drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, edge, 16);
            drive(scene).inputBatchEnded();
            assertFalse(bar.isOpen(), "and the same point closes it, so both hit one title");
        }
    }

    @Test
    void aClickPastTheLastTitleHitsNothing() {
        build();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 3 * TITLE_W + 10, 16);
        drive(scene).inputBatchEnded();
        assertFalse(bar.isOpen(), "beyond the last title there is no menu to open");
    }

    @Test
    void clickingTheOpenTitleAgainClosesIt() {
        build();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, TITLE_W / 2, 16);
        drive(scene).inputBatchEnded();
        assertTrue(bar.isOpen());

        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, TITLE_W / 2, 16);
        drive(scene).inputBatchEnded();
        assertFalse(bar.isOpen(), "the same title toggles");
    }

    @Test
    void anItemActionFires() {
        build();
        AtomicReference<String> fired = new AtomicReference<>();
        MenuBar fresh = new MenuBar();
        fresh.addMenu("File", new Menu().addItem("New", () -> fired.set("New")));
        Scene freshScene = new Scene(fresh);
        freshScene.setTextRuler(RULER);
        freshScene.layoutPass(400, MEDIUM.controlHeight());

        drive(freshScene).mouseButton(Keys.MOUSE_LEFT, true, 0, TITLE_W / 2, 16);
        drive(freshScene).inputBatchEnded();
        assertTrue(fresh.isOpen(), "the dropdown is open before the item can be reached");
    }

    // ------------------------------------------------------- the width it is offered

    /** A window wider than the three titles' 192 pt, so taking it and not taking it differ. */
    private static final float WINDOW_W = 600;
    private static final float WINDOW_H = 200;

    /** Records every line a frame draws: the bottom rule's extent is only visible in paint. */
    private static final class LineCanvas extends FakeCanvas {
        final List<float[]> lines = new ArrayList<>();

        LineCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth,
                Paint paint) {
            lines.add(new float[]{x1, y1, x2, y2});
        }
    }

    /**
     * The shape the guide shows: a bar at the top of a column left at its default START cross
     * alignment, which offers its children the window's width without imposing it, and a button
     * below it that can hold the keyboard.
     */
    private Button buildInColumn() {
        bar = threeTitles();
        Button below = new Button("OK");
        Column page = new Column();
        page.add(bar);
        page.add(below);
        scene = new Scene(page);
        scene.setTextRuler(RULER);
        scene.layoutPass(WINDOW_W, WINDOW_H);
        return below;
    }

    @Test
    void aBarInAColumnTakesTheWholeWidthItIsOffered() {
        // Until 2026-09-28 the bar answered its titles' sum whatever it was offered, so in the
        // guide's own column it stopped, bottom rule and all, 192 pt into a 600 pt window: a
        // menu bar narrower than its window, which no platform draws.
        buildInColumn();
        assertEquals(WINDOW_W, bar.measure(Constraints.loose(WINDOW_W, WINDOW_H)).width(), 1e-3,
                "a bounded width is taken whole");
        assertEquals(0, bar.x(), 1e-3);
        assertEquals(WINDOW_W, bar.width(), 1e-3, "and the column lays the strip out across it");
        assertEquals(MEDIUM.controlHeight(), bar.height(), 1e-3, "at the height it always had");

        LineCanvas canvas = new LineCanvas(WINDOW_W, WINDOW_H);
        scene.renderFrame(canvas);
        assertEquals(1, canvas.lines.size(), "the bottom rule is the one line in the frame");
        float[] rule = canvas.lines.get(0);
        assertEquals(0, rule[0], 1e-3);
        assertEquals(WINDOW_W, rule[2], 1e-3, "the bottom rule runs the width of the window");
    }

    @Test
    void anUnboundedWidthLeavesTheBarAsWideAsItsTitles() {
        // A row offers a child outside Expanded no width to fill, and a bar there is as wide as
        // what it holds, the way a Separator falls back to its own length.
        bar = threeTitles();
        Row row = new Row();
        row.add(bar);
        scene = new Scene(row);
        scene.setTextRuler(RULER);
        scene.layoutPass(WINDOW_W, WINDOW_H);
        assertEquals(3 * TITLE_W, bar.width(), 1e-3);
        assertEquals(MEDIUM.controlHeight(), bar.height(), 1e-3);
    }

    @Test
    void theStripPastTheLastTitleIsLeftToWhatIsBeneathIt() {
        // The width the bar now takes has nothing in it past the last title, and it stays what
        // it was when the bar ended there: a press on it opens nothing and takes the keyboard
        // from nobody, and the pointer over it is the one beneath rather than the titles' hand.
        // A bar that answered its whole box took the focus from the button on that press and
        // put its keyboard cursor on File.
        Button below = buildInColumn();
        assertSame(bar, bar.hitTest(3 * TITLE_W - 1, 16), "the last point of View is the bar's");
        assertNull(bar.hitTest(3 * TITLE_W, 16), "the first point past it is not");
        assertNull(bar.hitTest(WINDOW_W - 1, 16), "nor the last point of the strip");

        scene.requestFocus(below);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, WINDOW_W - 10, 16);
        drive(scene).inputBatchEnded();
        assertTrue(below.isFocused(), "the press past the titles left the keyboard where it was");
        assertFalse(bar.isOpen());
    }

    @Test
    void aPressOnTheEmptyStripClosesADropdownDrawnInTheScene() {
        // Drawn in the scene, the dropdown covers the bar and lets the titles through, so that
        // pointing at a neighbour still switches menus. The empty strip is not a title and is
        // not let through, so a press there is a press outside the menu and closes it, as a press
        // anywhere in the owner closes a dropdown that is a window of its own.
        //
        // A window, unlike every other scene in this file: without one the dropdown is refused
        // and there is nothing on the screen for a press to reach or to close.
        bar = threeTitles().setDisplayMode(DisplayMode.IN_SCENE);
        Column page = new Column();
        page.add(bar);
        scene = new Scene(page);
        scene.setTextRuler(RULER);
        StubWindow window = new StubWindow();
        scene.bind(window);
        FakeCanvas canvas = new FakeCanvas(window.framebufferWidth(), window.framebufferHeight());
        scene.renderFrame(canvas);
        assertEquals(window.framebufferWidth(), bar.width(), 1e-3,
                "the strip spans the window, so the last press below lands inside its box");

        pressAndFrame(TITLE_W / 2, canvas);
        assertTrue(bar.isOpen(), "File is down");
        pressAndFrame(TITLE_W + TITLE_W / 2, canvas);
        assertTrue(bar.isOpen(), "a press on Edit reached the bar through the dropdown");
        pressAndFrame(window.framebufferWidth() - 10, canvas);
        assertFalse(bar.isOpen(), "a press on the empty strip reached the dropdown and closed it");
    }

    @Test
    void escapeGivesTheKeyboardBackToTheFieldF10CameFrom() {
        bar = threeTitles();
        TextField notes = new TextField();
        Column page = new Column();
        page.add(bar);
        page.add(notes);
        scene = new Scene(page);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 200);
        scene.requestFocus(notes);

        key(Keys.F10);
        assertSame(bar, scene.focusedWidget(), "F10 moves the keyboard to the bar");
        key(Keys.ESCAPE);
        assertSame(notes, scene.focusedWidget(),
                "Escape gives it back to the field, as a native menu bar does; it went nowhere");

        key(Keys.F10);
        key(Keys.F10);
        assertSame(notes, scene.focusedWidget(), "and so does F10 pressed again");
    }

    @Test
    void escapeAfterWalkingToANeighbourDrawnInTheSceneLeavesTheBarFocused() {
        // Right from an open dropdown closes it and opens the next, and the one walked from is
        // still fading beneath when the next is pushed, holding the keyboard: the next saved that
        // fading menu as where the keyboard goes back to, and its Escape focused nothing.
        bar = threeTitles().setDisplayMode(DisplayMode.IN_SCENE);
        TextField notes = new TextField();
        Column page = new Column();
        page.add(bar);
        page.add(notes);
        scene = new Scene(page);
        scene.setTextRuler(RULER);
        StubWindow window = new StubWindow();
        scene.bind(window);
        FakeCanvas canvas = new FakeCanvas(window.framebufferWidth(), window.framebufferHeight());
        scene.renderFrame(canvas);
        scene.requestFocus(notes);

        key(Keys.F10);
        key(Keys.DOWN);
        scene.renderFrame(canvas);
        assertTrue(bar.isOpen(), "File is down");
        key(Keys.RIGHT);
        scene.renderFrame(canvas);
        key(Keys.ESCAPE);
        settle(canvas);
        assertFalse(bar.isOpen(), "Escape closed Edit");
        assertSame(bar, scene.focusedWidget(), "and the keyboard is on the bar again");
        key(Keys.ESCAPE);
        assertSame(notes, scene.focusedWidget(), "and a second Escape leaves it for the field");
    }

    private void key(int key) {
        drive(scene).keyEvent(key, true, false, 0);
        drive(scene).keyEvent(key, false, false, 0);
        drive(scene).inputBatchEnded();
    }

    /** Renders until every dropdown's fade has ended, in wall time, as the fades are. */
    private void settle(FakeCanvas canvas) {
        long until = System.currentTimeMillis() + 1_000;
        while (System.currentTimeMillis() < until) {
            scene.renderFrame(canvas);
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pressAndFrame(float x, FakeCanvas canvas) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, 16);
        drive(scene).inputBatchEnded();
        scene.renderFrame(canvas); // lays out the overlay the press may have pushed
    }
}
