package limn.components;

import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport bar and the one decision it owns: media playback reads left to right in either
 * direction, as a <em>default</em> a caller can re-declare. Everything here is arithmetic against
 * the deterministic {@link #RULER}, per the direction axis's own testing rule: assert the
 * decisions that say "does not mirror", so a later sweep cannot quietly mirror them.
 */
class MediaControlsTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float BAR_W = 420;
    private static final float BAR_H = 40;

    private MediaControls controls;
    private Scene scene;

    private void build(LayoutDirection sceneDirection) {
        controls = new MediaControls(new VideoView());
        scene = new Scene(controls);
        scene.setTextRuler(RULER);
        scene.setLayoutDirection(sceneDirection);
        scene.layoutPass(BAR_W, BAR_H);
    }

    /** The row's children in visual order is what the assertions below reason about. */
    private List<Widget> rowChildren() {
        return controls.children().get(0).children();
    }

    private float sceneX(Widget widget) {
        return widget.localToSceneX();
    }

    @Test
    void theBarReadsLeftToRightInsideARightToLeftTree() {
        build(LayoutDirection.RTL);
        List<Widget> kids = rowChildren();
        Widget play = kids.get(0);
        Widget clock = kids.get(kids.size() - 1);
        assertTrue(sceneX(play) < sceneX(clock),
                "the play button leads from the left whatever the tree reads: media transports "
                        + "are the standing exception to mirroring");
        assertTrue(sceneX(play) < BAR_W / 2, "and it leads from the left edge, not the middle");
    }

    @Test
    void theDefaultIsADeclarationACallerCanClear() {
        build(LayoutDirection.RTL);
        controls.setLayoutDirection(null); // follow the tree: the Apple-style choice
        scene.layoutPass(BAR_W, BAR_H);
        List<Widget> kids = rowChildren();
        assertTrue(sceneX(kids.get(0)) > sceneX(kids.get(kids.size() - 1)),
                "cleared to inherit, the bar mirrors like any row: the pin is a default, not a law");
    }

    @Test
    void leftToRightIsUnchanged() {
        build(LayoutDirection.LTR);
        List<Widget> kids = rowChildren();
        assertTrue(sceneX(kids.get(0)) < sceneX(kids.get(kids.size() - 1)));
        assertEquals(LayoutDirection.LTR, controls.layoutDirection());
    }

    @Test
    void slotsLandWherePlayersPutThem() {
        build(LayoutDirection.LTR);
        Widget volume = new FixedProbe();
        Widget subtitles = new FixedProbe();
        controls.addLeading(volume);
        controls.addTrailing(subtitles);
        scene.layoutPass(BAR_W, BAR_H);

        List<Widget> kids = rowChildren();
        Widget play = kids.get(0);
        Widget clock = kids.get(kids.size() - 1);
        assertTrue(sceneX(play) < sceneX(volume),
                "a leading widget sits after the play button, where players put their volume");
        assertTrue(sceneX(volume) < sceneX(subtitles),
                "and before the scrub bar, which a trailing widget follows");
        assertTrue(sceneX(subtitles) < sceneX(clock) + clock.width() + EPS,
                "the clock stays the trailing edge of the bar");
    }

    @Test
    void theViewShowsAndHidesTheBuiltInBar() {
        VideoView view = new VideoView().setPreferredSize(320, 180);
        Scene videoScene = new Scene(view);
        videoScene.setTextRuler(RULER);
        videoScene.layoutPass(320, 180);
        assertTrue(view.children().isEmpty(), "no controls exist until somebody asks");

        view.setControlsVisible(true);
        videoScene.layoutPass(320, 180);
        MediaControls built = view.controls();
        assertTrue(built.isVisible());
        assertTrue(built.height() > 0, "shown controls take a real box");
        assertEquals(180 - 8, built.y() + built.height(), EPS,
                "and hang over the picture's lower edge rather than growing the view");

        view.setControlsVisible(false);
        assertFalse(view.controls().isVisible(), "hidden again, and the instance is kept");
    }

    /** A fixed-size stand-in so slot assertions are about the bar, not about a Slider. */
    private static final class FixedProbe extends Widget {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(24, 12);
        }
    }
}
