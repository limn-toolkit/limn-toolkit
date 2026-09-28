package limn.backend.lwjgl;

/**
 * What a window fullscreen on a monitor is owed when it leaves: the position, the size and the
 * maximized state it had before it entered, which GLFW asks the window manager for and the window
 * manager does not always give.
 *
 * <p>GNOME over X11 gave something else in two ways, a 900 &times; 600 window measured over 19
 * runs on Ubuntu. In most (14) the window came back maximized, 958 &times; 699, because GNOME had
 * added the maximized state while it was fullscreen; a second request for its size did nothing
 * (0 of 8), since a maximized window keeps the size the desktop gives it, and unmaximizing it
 * first, then asking again, brought it back at its size and position (5 of 5). In the rest (4)
 * the window came back 900 &times; 563 and 37 pixels lower, as if the requested size and position
 * had been given to its frame, a 37-pixel title bar included, rather than to its content; asking
 * again for its size once the window manager had answered brought it back at 900 &times; 600
 * (3 of 3).
 *
 * <p>So leaving unmaximizes a window that was not maximized before and reports it now, and then
 * watches the window manager's answers, the size and position callbacks, for a short while: the
 * first answer that leaves the window anywhere but where it was is corrected by asking again for
 * the position and size it had. At most one unmaximize and one correction, each of which is
 * itself answered, so a window manager that refuses them cannot make this loop; and nothing is
 * watched past {@link #ANSWER_WINDOW_NANOS}, so a resize the person makes afterwards is theirs. A
 * window that was maximized is left as it comes back: its size is the desktop's.
 *
 * <p>Bookkeeping only, told what the window reports and answering what to ask of GLFW, so that a
 * test can play the window manager's answers without a window going fullscreen.
 */
final class WindowedGeometry {

    /** How long after leaving the window manager's answers are watched. */
    static final long ANSWER_WINDOW_NANOS = 500_000_000L;

    /** What to ask of GLFW after an answer. */
    enum Step {
        /** Nothing. */
        NONE,
        /** Unmaximize the window, and go on watching for the answer to that. */
        RESTORE,
        /** Ask again for {@link #x()}, {@link #y()}, {@link #width()} and {@link #height()}. */
        APPLY
    }

    private int x;
    private int y;
    private int width;
    private int height;
    private boolean maximized;

    /** Whether the answers to the last leaving are being watched. */
    private boolean watching;
    /** Whether an answer came since the last {@link #settle}. */
    private boolean answered;
    /** Whether the window was unmaximized since it left. */
    private boolean restored;
    /** When the watching ends, on the caller's clock. */
    private long until;

    /**
     * Remembers the window as it is before it enters fullscreen, and stops watching the answers to
     * an earlier leaving: nothing is owed until the window leaves again.
     *
     * @param x         the content's x in screen coordinates
     * @param y         the content's y in screen coordinates
     * @param width     the content's width in screen coordinates
     * @param height    the content's height in screen coordinates
     * @param maximized whether the window is maximized
     */
    void save(int x, int y, int width, int height, boolean maximized) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.maximized = maximized;
        watching = false;
    }

    /** Stops watching: the window went fullscreen again without a new {@link #save}. */
    void entered() {
        watching = false;
    }

    /**
     * The window has just been asked to leave fullscreen for its saved position and size; from
     * now on the window manager's answers are watched.
     *
     * @param maximizedNow whether the window reports being maximized right after the request
     * @param now          the time, on the clock {@link #settle} is given
     * @return whether to unmaximize the window now, because it was not maximized before
     */
    boolean left(boolean maximizedNow, long now) {
        watching = true;
        answered = false;
        until = now + ANSWER_WINDOW_NANOS;
        restored = !maximized && maximizedNow;
        return restored;
    }

    /** A size or position callback: an answer, or part of one, from the window manager. */
    void answered() {
        if (watching) {
            answered = true;
        }
    }

    /** @return whether answers are still watched, so the caller knows whether to read the window */
    boolean watching() {
        return watching;
    }

    /**
     * What to ask of GLFW now, given how the window is: nothing until an answer has come, at most
     * one unmaximize and one correction, and nothing once the watching is over or the window was
     * maximized before it entered.
     *
     * @param now          the time, on the clock {@link #left} was given
     * @param x            the content's x now
     * @param y            the content's y now
     * @param width        the content's width now
     * @param height       the content's height now
     * @param maximizedNow whether the window reports being maximized now
     * @param positions    whether the platform places windows, so that a position counts
     * @return what to ask
     */
    Step settle(long now, int x, int y, int width, int height, boolean maximizedNow,
                boolean positions) {
        if (!watching) {
            return Step.NONE;
        }
        if (now - until > 0 || maximized) {
            watching = false;
            return Step.NONE;
        }
        if (!answered) {
            return Step.NONE;
        }
        answered = false;
        if (maximizedNow && !restored) {
            restored = true;
            return Step.RESTORE;
        }
        boolean moved = positions && (x != this.x || y != this.y);
        if (moved || width != this.width || height != this.height) {
            watching = false;
            return Step.APPLY;
        }
        return Step.NONE;
    }

    /** @return the saved content x, in screen coordinates */
    int x() {
        return x;
    }

    /** @return the saved content y, in screen coordinates */
    int y() {
        return y;
    }

    /** @return the saved content width, in screen coordinates */
    int width() {
        return width;
    }

    /** @return the saved content height, in screen coordinates */
    int height() {
        return height;
    }
}
