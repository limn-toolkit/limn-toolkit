package limn.backend.lwjgl;

/**
 * The bookkeeping behind macOS native fullscreen, the one the green button and Control-Command-F
 * enter: a window of its own Space, reached by an animation, with the display left in the mode it
 * was in. Which request goes there is {@link #isNativeRequest}; the rest of this class is what the
 * window knows about it while AppKit animates, written without AppKit so that it can be tested
 * where there is none, and {@link MacFullscreen} carries it to the real window.
 *
 * <p><b>Why a request cannot simply be sent.</b> {@code -[NSWindow toggleFullScreen:]} starts an
 * animation of most of a second, and AppKit does not queue a second toggle sent during it: an
 * application that entered and left again at once could stay in fullscreen with nothing to say
 * so. So a request made while a transition is under way is only remembered, and sent when AppKit
 * posts the end of that transition ({@link #didChange}), if the window is not already where the
 * request wanted it. Chromium's windows keep the same record, a pending state consumed when a
 * transition completes, for the same reason.
 *
 * <p><b>What the window answers meanwhile.</b> {@link #fullscreen} answers where the window is
 * going rather than where the animation has got to: {@code true} from the moment fullscreen is asked
 * for, or from the moment the person starts it with the green button or the keyboard, and
 * {@code false} from the moment leaving is asked for or begins. That is the answer a program acting
 * on it wants: a toggle reads it to choose between entering and leaving, and a menu about to open
 * reads it to know it will be shown in a fullscreen window.
 *
 * <p><b>And when AppKit never finishes.</b> A toggle AppKit refuses posts nothing at all, and one
 * that fails part way posts its start and never its end, because the failure is told only to the
 * window's delegate, which is GLFW's. A transition that has not ended after {@link #DEADLINE_NANOS}
 * is therefore given up on: the window's own style mask, {@code NSWindowStyleMaskFullScreen}, is
 * believed from then on, and the request that did not complete is dropped rather than retried
 * forever. The same style mask is believed whenever no transition is under way and it disagrees
 * with the record, which is what keeps the answer true if the notifications could not be observed
 * at all.
 *
 * <p>Not thread-safe; the UI thread only, which on macOS is the thread AppKit posts on.
 */
final class NativeFullscreen {

    /** What the window has to ask AppKit for now, after a {@link #settle}. */
    enum Toggle {
        /** Nothing: the window is where it is wanted, or a transition is under way. */
        NONE,
        /** Send {@code toggleFullScreen:} to enter. */
        ENTER,
        /** Send {@code toggleFullScreen:} to leave. */
        EXIT
    }

    /**
     * How long a transition may run before it is taken to have failed: several times the
     * animation, which runs for well under a second, so that a machine too busy to animate
     * smoothly is not mistaken for one that refused.
     */
    static final long DEADLINE_NANOS = 3_000_000_000L;

    /** Whether the window is in native fullscreen, as AppKit last confirmed it. */
    private boolean actual;
    /** Where the window should end up: the last request, or the person's own choice. */
    private boolean wanted;
    /** Whether a transition is under way: one this window sent, or one AppKit announced. */
    private boolean busy;
    /** Whether the transition under way was sent from here and AppKit has not yet announced it. */
    private boolean sent;
    /** Where the transition under way is going. */
    private boolean toward;
    /** When the transition under way began, on the clock the caller passes. */
    private long busySince;

    /**
     * Whether a request for fullscreen goes to macOS native fullscreen rather than to a display
     * mode: on macOS, and only for the request that names no mode, neither a size nor a refresh
     * rate. Every other request, and every request elsewhere, is a fullscreen on the monitor
     * through GLFW, which switches the display's mode when the mode asked for is not the one set.
     *
     * <p>On macOS that switch is also made for the request that names no mode: GLFW lists the modes
     * Core Graphics reports, which on a Retina display do not include the mode the display is in,
     * so the current mode is matched to the nearest listed one and set. Measured on a MacBook Pro
     * at 1728 × 1117 points, scale 2: the display was switched to 1920 × 1200 at scale 1, and the
     * UI thread was held for 595 ms entering and 532 ms leaving.
     *
     * @param macOs       whether this is macOS
     * @param width       the width asked for, or &le; 0 for none
     * @param height      the height asked for, or &le; 0 for none
     * @param refreshRate the refresh rate asked for, or &le; 0 for none
     * @return whether the request is for native fullscreen
     */
    static boolean isNativeRequest(boolean macOs, int width, int height, int refreshRate) {
        return macOs && width <= 0 && height <= 0 && refreshRate <= 0;
    }

    /**
     * Records where the window should end up. Nothing is sent from here; {@link #settle} says what
     * to send, so that a request made during a transition waits for its end.
     *
     * @param fullscreen whether the window should be in native fullscreen
     */
    void want(boolean fullscreen) {
        wanted = fullscreen;
    }

    /**
     * AppKit announced that a transition begins: {@code NSWindowWillEnterFullScreenNotification}
     * or {@code NSWindowWillExitFullScreenNotification}. A transition this window did not send is
     * the person's, from the green button, the keyboard or the menu, and what they chose becomes
     * what is wanted.
     *
     * @param entering whether the window is entering fullscreen
     * @param now      the time, on the caller's clock
     */
    void willChange(boolean entering, long now) {
        boolean ours = busy && sent && toward == entering;
        if (!ours) {
            wanted = entering;
        }
        busy = true;
        sent = false;
        toward = entering;
        busySince = now;
    }

    /**
     * AppKit announced that a transition ended: {@code NSWindowDidEnterFullScreenNotification} or
     * {@code NSWindowDidExitFullScreenNotification}. The next {@link #settle} sends whatever was
     * asked for while it ran.
     *
     * <p>An end somewhere other than where the transition was going is a failure: AppKit
     * announces a failed exit as an entry, as Chromium records. The window stays where it is, and
     * the request that sent it is dropped rather than sent again and again, as is an end nobody
     * announced the start of.
     *
     * @param entered whether the window is now in fullscreen
     */
    void didChange(boolean entered) {
        if (!busy || toward != entered) {
            wanted = entered;
        }
        actual = entered;
        busy = false;
        sent = false;
    }

    /**
     * Brings the record up to date and says what to send.
     *
     * @param now      the time, on the caller's clock
     * @param observed whether the window's style mask holds {@code NSWindowStyleMaskFullScreen}
     *                 right now
     * @return the toggle to send now; when it is not {@link Toggle#NONE} the record already counts
     *         it as sent, so the caller must send it
     */
    Toggle settle(long now, boolean observed) {
        if (busy && now - busySince > DEADLINE_NANOS) {
            // Never ended, or never began: believe the window and drop what did not happen.
            busy = false;
            sent = false;
            actual = observed;
            wanted = observed;
            return Toggle.NONE;
        }
        if (!busy && observed != actual) {
            // A change nobody announced: believe the window, and take it for the person's choice.
            actual = observed;
            wanted = observed;
        }
        if (busy || wanted == actual) {
            return Toggle.NONE;
        }
        busy = true;
        sent = true;
        toward = wanted;
        busySince = now;
        return wanted ? Toggle.ENTER : Toggle.EXIT;
    }

    /**
     * Whether the window is, or is going to be, in native fullscreen: where it is going rather
     * than where the animation has got to, and the window's own style mask once a transition has
     * run past {@link #DEADLINE_NANOS}. Changes nothing.
     *
     * @param now      the time, on the caller's clock
     * @param observed whether the window's style mask holds {@code NSWindowStyleMaskFullScreen}
     * @return the answer for {@code isFullscreen()}
     */
    boolean fullscreen(long now, boolean observed) {
        if (busy && now - busySince > DEADLINE_NANOS) {
            return observed;
        }
        return wanted;
    }

    /**
     * Whether AppKit has announced a transition and not yet its end, within
     * {@link #DEADLINE_NANOS}: not a toggle only sent, which AppKit has not begun and which is
     * never sent to a window that is gone by the time it would be.
     *
     * @param now the time, on the caller's clock
     * @return whether the window is being animated
     */
    boolean animating(long now) {
        return busy && !sent && now - busySince <= DEADLINE_NANOS;
    }

    /**
     * @return whether the window is out of native fullscreen, wants to stay out, and no transition
     *         is under way: the only state in which a fullscreen on the monitor may be entered,
     *         because GLFW's changes the style mask of a window AppKit is animating
     */
    boolean windowed() {
        return !wanted && !actual && !busy;
    }

    /**
     * @return whether the window is in native fullscreen, wants to stay there, and no transition
     *         is under way: a request to leave it has failed, or was replaced by the person's own
     */
    boolean settledInFullscreen() {
        return wanted && actual && !busy;
    }
}
