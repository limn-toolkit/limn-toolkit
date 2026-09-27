package limn.backend.lwjgl;

import limn.backend.lwjgl.NativeFullscreen.Toggle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On macOS a fullscreen that names no mode is native fullscreen, and what a window says about it
 * while AppKit animates. The request that names no mode used to go to GLFW's fullscreen on the
 * monitor there too, which switched a Retina display from 1728 × 1117 at scale 2 to 1920 × 1200 at
 * scale 1 and held the UI thread for more than half a second each way. The transitions below are
 * the notifications AppKit posts, fed by hand, because a test must not take the machine's display
 * fullscreen.
 */
class NativeFullscreenTest {

    private static final long MS = 1_000_000L;

    @Test
    void onMacOsOnlyTheRequestThatNamesNoModeIsNative() {
        assertTrue(NativeFullscreen.isNativeRequest(true, 0, 0, 0), "enterFullscreen()");
        assertTrue(NativeFullscreen.isNativeRequest(true, -1, -1, -1), "every value <= 0 names none");
        assertFalse(NativeFullscreen.isNativeRequest(true, 1280, 720, 0), "a size is a mode");
        assertFalse(NativeFullscreen.isNativeRequest(true, 1280, 720, 60), "a Resolution is a mode");
        assertFalse(NativeFullscreen.isNativeRequest(true, 0, 0, 60), "a rate alone asks for a mode");
        assertFalse(NativeFullscreen.isNativeRequest(true, 1280, 0, 0), "and so does one dimension");
        assertFalse(NativeFullscreen.isNativeRequest(false, 0, 0, 0),
                "Windows, X11 and Wayland keep GLFW's, whose mode list holds the current mode");
    }

    @Test
    void enteringSendsOneToggleAndAnswersFullscreenFromTheRequestOn() {
        NativeFullscreen state = new NativeFullscreen();
        assertFalse(state.fullscreen(0, false));
        state.want(true);
        assertTrue(state.fullscreen(0, false), "where the window is going, before AppKit has moved");
        assertEquals(Toggle.ENTER, state.settle(0, false));
        assertEquals(Toggle.NONE, state.settle(10 * MS, false), "sent once, not once per loop turn");
        assertFalse(state.animating(10 * MS), "sent is not yet begun");

        state.willChange(true, 20 * MS);
        assertTrue(state.animating(30 * MS));
        assertEquals(Toggle.NONE, state.settle(400 * MS, true));
        assertTrue(state.fullscreen(400 * MS, true), "throughout the animation");
        state.didChange(true);
        assertFalse(state.animating(800 * MS));
        assertEquals(Toggle.NONE, state.settle(800 * MS, true));
        assertTrue(state.fullscreen(800 * MS, true));
        assertTrue(state.settledInFullscreen());
        assertFalse(state.windowed());
    }

    @Test
    void aRequestMadeDuringTheAnimationIsSentWhenItEnds() {
        NativeFullscreen state = new NativeFullscreen();
        state.want(true);
        assertEquals(Toggle.ENTER, state.settle(0, false));
        state.willChange(true, 5 * MS);
        state.want(false); // the application changed its mind at once
        assertFalse(state.fullscreen(100 * MS, true), "answers the latest request");
        assertEquals(Toggle.NONE, state.settle(100 * MS, true),
                "AppKit does not queue a toggle sent during a transition, so none is sent");

        state.didChange(true);
        assertEquals(Toggle.EXIT, state.settle(700 * MS, true), "sent the moment the entry ends");
        assertEquals(Toggle.NONE, state.settle(710 * MS, true));
        state.willChange(false, 720 * MS);
        assertFalse(state.fullscreen(720 * MS, true));
        state.didChange(false);
        assertEquals(Toggle.NONE, state.settle(1400 * MS, false));
        assertTrue(state.windowed());
    }

    @Test
    void theAnnouncementOfItsOwnToggleDoesNotOverrideALaterRequest() {
        NativeFullscreen state = new NativeFullscreen();
        state.want(true);
        assertEquals(Toggle.ENTER, state.settle(0, false));
        state.want(false); // before AppKit has even begun
        state.willChange(true, 5 * MS); // the toggle sent above, not a choice of the person's
        assertFalse(state.fullscreen(5 * MS, true));
        state.didChange(true);
        assertEquals(Toggle.EXIT, state.settle(700 * MS, true));
    }

    @Test
    void theGreenButtonIsReportedAsFullscreenAndLeftByARequest() {
        NativeFullscreen state = new NativeFullscreen();
        state.willChange(true, 0); // the person pressed the green button, or Control-Command-F
        assertTrue(state.fullscreen(0, true), "from the moment the animation starts");
        assertEquals(Toggle.NONE, state.settle(100 * MS, true), "nothing to send: it is theirs");
        state.didChange(true);
        assertTrue(state.fullscreen(900 * MS, true));

        state.want(false); // exitFullscreen() leaves it as it leaves its own
        assertEquals(Toggle.EXIT, state.settle(2000 * MS, true));
        state.willChange(false, 2005 * MS);
        state.didChange(false);
        assertEquals(Toggle.NONE, state.settle(3000 * MS, false));
        assertFalse(state.fullscreen(3000 * MS, false));
    }

    @Test
    void thePersonLeavingIsReportedAsWindowed() {
        NativeFullscreen state = new NativeFullscreen();
        state.want(true);
        state.settle(0, false);
        state.willChange(true, 0);
        state.didChange(true);
        state.willChange(false, 5000 * MS); // the green button again, from the revealed title bar
        assertFalse(state.fullscreen(5000 * MS, false));
        state.didChange(false);
        assertEquals(Toggle.NONE, state.settle(6000 * MS, false), "and it is not sent back in");
        assertTrue(state.windowed());
    }

    @Test
    void aToggleAppKitNeverBeginsIsDroppedAtTheDeadlineAndNotRetried() {
        NativeFullscreen state = new NativeFullscreen();
        state.want(true);
        assertEquals(Toggle.ENTER, state.settle(0, false));
        long late = NativeFullscreen.DEADLINE_NANOS + MS;
        assertTrue(state.fullscreen(late - 2 * MS, false), "still going there, as far as it knows");
        assertFalse(state.fullscreen(late, false), "past the deadline, the window's own word");
        assertEquals(Toggle.NONE, state.settle(late, false));
        assertTrue(state.windowed(), "the request is dropped");
        assertEquals(Toggle.NONE, state.settle(late + 10 * MS, false), "and never sent again");
    }

    @Test
    void anEntryThatNeverEndsIsGivenUpOnWithTheStyleMasksWord() {
        NativeFullscreen state = new NativeFullscreen();
        state.want(true);
        state.settle(0, false);
        state.willChange(true, MS); // begun, and the end is told only to GLFW's delegate
        long late = MS + NativeFullscreen.DEADLINE_NANOS + MS;
        assertTrue(state.animating(late - 2 * MS));
        assertFalse(state.animating(late), "a close waiting for it is not held for ever");
        assertEquals(Toggle.NONE, state.settle(late, false));
        assertTrue(state.windowed());
    }

    @Test
    void anExitThatEndsInFullscreenStaysThereAndIsNotRetried() {
        NativeFullscreen state = new NativeFullscreen();
        state.willChange(true, 0);
        state.didChange(true);
        state.want(false);
        assertEquals(Toggle.EXIT, state.settle(MS, true));
        state.willChange(false, 2 * MS);
        state.didChange(true); // AppKit announces the window in fullscreen: leaving failed
        assertEquals(Toggle.NONE, state.settle(900 * MS, true), "not sent again and again");
        assertTrue(state.fullscreen(900 * MS, true), "the window says where it is");
        assertTrue(state.settledInFullscreen(), "which drops a fullscreen on the monitor waiting for it");
    }

    @Test
    void aChangeNobodyAnnouncedIsBelievedFromTheStyleMask() {
        NativeFullscreen state = new NativeFullscreen();
        assertEquals(Toggle.NONE, state.settle(0, true), "not taken back out");
        assertTrue(state.fullscreen(0, true));
        assertTrue(state.settledInFullscreen());
        state.want(false);
        assertEquals(Toggle.EXIT, state.settle(MS, true));
    }

    @Test
    void aFullscreenOnTheMonitorWaitsUntilTheWindowIsOut() {
        NativeFullscreen state = new NativeFullscreen();
        assertTrue(state.windowed(), "a new window may go straight to the monitor");
        state.want(true);
        state.settle(0, false);
        assertFalse(state.windowed(), "not while the entry is merely sent");
        state.willChange(true, MS);
        state.didChange(true);
        state.want(false); // enterFullscreen(mode) leaves native fullscreen first
        state.settle(900 * MS, true);
        assertFalse(state.windowed(), "nor while leaving is sent");
        state.willChange(false, 905 * MS);
        assertFalse(state.windowed(), "nor while it animates");
        state.didChange(false);
        assertTrue(state.windowed(), "only once AppKit says the window is out");
    }
}
