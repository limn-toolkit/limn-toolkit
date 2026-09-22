package limn.components;

import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.sound.AudioStreamSource;
import limn.sound.PlayOptions;
import limn.video.MediaPlayer;
import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

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

    // ------------------------------------------------------------------- sound

    /** The mute button and the volume box sit at fixed positions after the play button. */
    private Widget muteButton() {
        return rowChildren().get(1);
    }

    private Widget volumeSlider() {
        return rowChildren().get(2);
    }

    private Widget playButton() {
        return rowChildren().get(0);
    }

    /**
     * Paints once, which arms the poll, and then pumps the UI queue until the first tick has
     * refreshed the bar. A paint announces nothing, so the refresh rides the tick alone and the
     * test waits for it the way the window would: at most one poll interval.
     */
    private void renderAndTick() {
        scene.renderFrame(new FakeCanvas(BAR_W, BAR_H));
        ui.pumpUntil(() -> playButton().isEnabled());
    }

    @Test
    void theVolumeAppearsExactlyWhenTheMediaCanSound() {
        build(LayoutDirection.LTR);
        assertFalse(muteButton().isVisible(), "no player: the bar carries no dead volume");
        assertFalse(volumeSlider().isVisible());

        controls.view().setPlayer(new MediaPlayer(new FakeVideo()));
        renderAndTick();
        assertFalse(muteButton().isVisible(), "a player with no soundtrack offers none either");

        build(LayoutDirection.LTR);
        controls.view().setPlayer(new MediaPlayer(new FakeVideo())
                .setAudio(new FakeAudio(), PlayOptions.DEFAULTS));
        renderAndTick();
        assertTrue(muteButton().isVisible(), "a soundtrack brings the pair with it");
        assertTrue(volumeSlider().isVisible());
    }

    @Test
    void theModesOverrideWhatTheMediaSays() {
        build(LayoutDirection.LTR);
        controls.setSound(MediaControls.Sound.ON);
        assertTrue(muteButton().isVisible(), "ON offers the pair with nothing to sound");
        controls.setSound(MediaControls.Sound.OFF);
        assertFalse(muteButton().isVisible(), "OFF hides it whatever the media has");
    }

    @Test
    void muteIsAGainOfZeroThatRemembersTheLevel() {
        build(LayoutDirection.LTR);
        controls.setVolume(0.6f);
        controls.setMuted(true);
        assertTrue(controls.isMuted());
        assertEquals(0.6f, controls.volume(), EPS, "the level survives the silence");
        controls.setMuted(false);
        assertFalse(controls.isMuted());
        assertEquals(0.6f, controls.volume(), EPS, "and unmuting restores exactly it");
    }

    /**
     * A drag's keyframe seeks are spaced on the scene's clock. They are one clock in an
     * application; in a test or a filmed capture the wall spaced them by however fast the machine
     * delivered the moves, which here, with no time between them at all, let one seek through for
     * the whole drag.
     */
    @Test
    void aDragsSeeksAreSpacedOnTheScenesClock() {
        AtomicLong nanos = new AtomicLong();
        TestVideoStream stream = new TestVideoStream(640, 360);
        stream.durationMicros = 60_000_000L;
        controls = new MediaControls(new VideoView().setSource(stream));
        scene = new Scene(controls, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(BAR_W, BAR_H);
        Slider bar = scrubBar(controls);
        float y = bar.localToSceneY() + bar.height() / 2;

        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, alongBar(bar, 0.2f), y);
        drive(scene).inputBatchEnded();
        assertEquals(1, stream.seeks, "the press seeks");

        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        drive(scene).mouseMoved(alongBar(bar, 0.4f), y);
        drive(scene).inputBatchEnded();
        assertEquals(1, stream.seeks, "a tenth of a second on is inside the interval");

        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
        drive(scene).mouseMoved(alongBar(bar, 0.6f), y);
        drive(scene).inputBatchEnded();
        assertEquals(2, stream.seeks, "three tenths on, the next seek goes through");
        assertEquals(VideoStreamSource.SeekMode.KEYFRAME, stream.seekedMode, "a drag seeks keyframes");

        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, alongBar(bar, 0.6f), y);
        drive(scene).inputBatchEnded();
        assertEquals(3, stream.seeks, "and letting go lands, throttled by nothing");
        assertEquals(VideoStreamSource.SeekMode.EXACT, stream.seekedMode);
    }

    /** @return the transport's scrub bar, the one slider over a thousand steps, or null */
    private static Slider scrubBar(Widget root) {
        if (root instanceof Slider slider && slider.max() == 1000) {
            return slider;
        }
        for (Widget child : root.children()) {
            Slider found = scrubBar(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** @return the scene x {@code fraction} of the way along {@code bar}'s box */
    private static float alongBar(Slider bar, float fraction) {
        return bar.localToSceneX() + bar.width() * fraction;
    }

    /** A do-nothing video source: enough for a player that is never started. */
    private static final class FakeVideo implements VideoStreamSource {
        @Override public int width() { return 16; }
        @Override public int height() { return 16; }
        @Override public PixelFormat pixelFormat() { return PixelFormat.I420; }
        @Override public VideoColor color() { return VideoColor.BT709_LIMITED; }
        @Override public int frameRateNum() { return 30; }
        @Override public int frameRateDen() { return 1; }
        @Override public Read readFrame() { return Read.PENDING; }
        @Override public VideoFrame frame() { return null; }
        @Override public void reset() { }
        @Override public void close() { }
    }

    /** A do-nothing audio source: its existence is the whole fact under test. */
    private static final class FakeAudio implements AudioStreamSource {
        @Override public int channels() { return 1; }
        @Override public int sampleRate() { return 8000; }
        @Override public int readFrames(short[] out, int maxFrames) { return 0; }
        @Override public void reset() { }
        @Override public void close() { }
    }

    /** A fixed-size stand-in so slot assertions are about the bar, not about a Slider. */
    private static final class FixedProbe extends Widget {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(24, 12);
        }
    }
}
