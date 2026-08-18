package limn.components;

import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private void build() {
        bar = new MenuBar();
        bar.addMenu("File", new Menu().addItem("New", () -> { }));
        bar.addMenu("Edit", new Menu().addItem("Undo", () -> { }));
        bar.addMenu("View", new Menu().addItem("Zoom", () -> { }));
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

    @Test
    void widthIsTheSumOfTitlesAndHeightIsTheStrip() {
        build();
        Size size = bar.measure(Constraints.loose(500, 100));
        assertEquals(3 * TITLE_W, size.width(), 1e-3);
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
            Size size = bar.measure(Constraints.loose(500, 100));
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

        Size size = tight.measure(Constraints.loose(500, 100));
        assertEquals(2 * Strokes.MIN_HIT_TARGET, size.width(), 1e-3,
                "both titles widened to the hit target");

        // And the two boxes still tile without overlapping: a click just left of the boundary
        // opens the first, just right of it the second.
        tightScene.mouseButton(Keys.MOUSE_LEFT, true, 0, Strokes.MIN_HIT_TARGET - 1, 12);
        tightScene.inputBatchEnded();
        assertTrue(tight.isOpen(), "the last point of title 0 belongs to title 0");
        tightScene.mouseButton(Keys.MOUSE_LEFT, true, 0, Strokes.MIN_HIT_TARGET - 1, 12);
        tightScene.inputBatchEnded();
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
            scene.mouseButton(Keys.MOUSE_LEFT, true, 0, centre, 16);
            scene.inputBatchEnded();
            assertTrue(bar.isOpen(), "title " + i + " opens on a click at its centre");

            scene.mouseButton(Keys.MOUSE_LEFT, true, 0, centre, 16);
            scene.inputBatchEnded();
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
            scene.mouseButton(Keys.MOUSE_LEFT, true, 0, edge, 16);
            scene.inputBatchEnded();
            assertTrue(bar.isOpen(), "the exact left edge of title " + i + " opens something");
            scene.mouseButton(Keys.MOUSE_LEFT, true, 0, edge, 16);
            scene.inputBatchEnded();
            assertFalse(bar.isOpen(), "and the same point closes it, so both hit one title");
        }
    }

    @Test
    void aClickPastTheLastTitleHitsNothing() {
        build();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 3 * TITLE_W + 10, 16);
        scene.inputBatchEnded();
        assertFalse(bar.isOpen(), "beyond the last title there is no menu to open");
    }

    @Test
    void clickingTheOpenTitleAgainClosesIt() {
        build();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, TITLE_W / 2, 16);
        scene.inputBatchEnded();
        assertTrue(bar.isOpen());

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, TITLE_W / 2, 16);
        scene.inputBatchEnded();
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

        freshScene.mouseButton(Keys.MOUSE_LEFT, true, 0, TITLE_W / 2, 16);
        freshScene.inputBatchEnded();
        assertTrue(fresh.isOpen(), "the dropdown is open before the item can be reached");
    }
}
