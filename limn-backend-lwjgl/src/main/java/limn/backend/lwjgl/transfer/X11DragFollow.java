package limn.backend.lwjgl.transfer;

import limn.transfer.DropAction;

import java.util.List;
import java.util.Set;

/**
 * A drag from another program over one of our windows on X11, followed by reading the pointer
 * once its source no longer says where it is. X thread; the time is handed in.
 *
 * <p>A toolkit (GTK 4, read in its X11 drag code) sends the enter and the first position to the
 * window {@code XdndProxy} names, and from its next move on talks to the window under the pointer
 * itself, which here is GLFW's: GLFW answers, accepting files whenever they were offered and
 * anything else never, and hears the drop. The scene, told of the first position alone, saw the
 * pointer once at the window's edge: a page never scrolled under a file resting at its bottom
 * edge, and nothing under the pointer showed it would take the file.
 *
 * <p>So the drag is followed when what is offered includes files, the pointer is over the window
 * with a button held and Escape up, and either the source sends a leave (the code read sends one
 * as it turns away; the guest's file manager did not), or it falls silent: the pointer moved, as
 * this side reads it, and the source said nothing for {@link #SILENCE_NANOS}, counted from the
 * later of that move and this side's last answer, with no position of the source's unanswered. A
 * source that talks to us sends a position at once when the pointer moves, unless it waits for
 * our answer, and a source of this program's own is never followed. A position from the source ends
 * the follow: it talks to us again.
 *
 * <p>Each turn then tells the scene where the pointer is when it moved. The offer is narrowed to
 * files and the action to a copy, since only files arrive through GLFW's drop, which tells the
 * source it was a copy. The source still hears GLFW's answer to every position it sends, so while
 * the pointer rests the scene's answer is sent to it again every {@link #SEAL_TICKS} turns: the
 * source acts on the last answer it had when the button is let go, and after a rest that is ours.
 * The follow ends when the button is let go, at Escape, and when the pointer leaves the window,
 * which the source tells GLFW and nobody else.
 */
final class X11DragFollow {

    /** Turns of the X thread, sixteen milliseconds each while a drag is on, between two answers to a resting pointer. */
    static final int SEAL_TICKS = 6;

    /** How long the pointer stands away from the source's last point, unsaid, before the drag is followed. */
    static final long SILENCE_NANOS = 80_000_000L;

    /** How long the buttons are up, with no drop and no leave, before a drag over a window is taken as gone. */
    static final long STALE_NANOS = 1_000_000_000L;

    /** What the scene may be asked to take a followed drag as: what GLFW tells the source it was. */
    static final Set<DropAction> ALLOWED = Set.of(DropAction.COPY);

    /** Buttons one to five held, in the state {@code XQueryPointer} answers. */
    private static final int BUTTONS = 0x1f << 8;

    /** What a turn of the X thread does. */
    enum Step {
        /** Nothing: no drag is followed, or the pointer rests between two answers. */
        NOTHING,
        /** The pointer moved: the scene is asked about the new point. */
        OVER,
        /** The pointer rests: the scene's last answer is sent to the source again. */
        SEAL,
        /** The button was let go, or the pointer left the window: the scene is told the drag left. */
        LEFT,
        /** Escape: the scene is told the drag left, and the source that nothing here takes it. */
        CALLED_OFF
    }

    /**
     * The pointer as a turn of the X thread reads it from the server.
     *
     * @param rootX  on the root window
     * @param rootY  on the root window
     * @param state  the buttons and modifier keys held, as {@code XQueryPointer} answers them
     * @param escape whether Escape is held
     * @param over   whether it is inside the window the drag is over
     */
    record Sample(int rootX, int rootY, int state, boolean escape, boolean over) {

        boolean buttonHeld() {
            return (state & BUTTONS) != 0;
        }
    }

    private boolean active;
    private int x;
    private int y;
    private int still;
    /** The pointer as this side last read it since the source last named a point; or none. */
    private boolean sampled;
    private int sampleX;
    private int sampleY;
    /** When the source last named a point, or this side last answered it. */
    private long quietSince;
    /** The source named a point this side has not answered yet: it waits, and its silence says nothing. */
    private boolean unanswered;
    /** When this side first saw the pointer move since the source last named a point; or never. */
    private boolean away;
    private long awaySince;
    /** When the buttons were first seen up with the drag not followed; or none. */
    private boolean released;
    private long releasedSince;

    /**
     * What GLFW can deliver of an offer.
     *
     * @param canonical the canonical types offered
     * @return files, when they are offered; nothing otherwise
     */
    static List<String> deliverable(List<String> canonical) {
        return canonical.contains(ClipboardTypes.FILES) ? List.of(ClipboardTypes.FILES) : List.of();
    }

    /**
     * A drag entered: nothing is followed, and nothing is known of where the source says it is.
     *
     * @param now the time, in nanoseconds
     */
    void entered(long now) {
        active = false;
        sampled = false;
        quietSince = now;
        unanswered = false;
        away = false;
        released = false;
    }

    /**
     * The source named a point: it talks to us, and a follow, if one was on, ends. The point is not
     * compared with the pointer: a source may name it in other units (a scaled toolkit), and a move
     * is what this side sees the pointer do between two of its own readings.
     *
     * @param now the time, in nanoseconds
     */
    void heard(long now) {
        active = false;
        sampled = false;
        quietSince = now;
        unanswered = true;
        away = false;
    }

    /**
     * This side answered the source: one waiting for the answer sends its next position from now.
     *
     * @param now the time, in nanoseconds
     */
    void answered(long now) {
        quietSince = now;
        unanswered = false;
    }

    /**
     * A leave from the source of the drag over the window: whether it is the source turning to the
     * window itself, and the drag is followed from now on.
     *
     * @param at      the pointer now
     * @param offered the canonical types the drag offers
     * @param dropped whether the drop was already said
     * @return whether the drag is followed, from the point {@code at}
     */
    boolean keepsAfterLeave(Sample at, List<String> offered, boolean dropped) {
        if (dropped || !at.over() || !at.buttonHeld() || at.escape() || deliverable(offered).isEmpty()) {
            return false;
        }
        start(at);
        return true;
    }

    /**
     * A turn of the X thread while a drag is over the window and not followed: whether its source
     * has fallen silent, and the drag is followed from now on.
     *
     * @param now     the time, in nanoseconds
     * @param at      the pointer now
     * @param offered the canonical types the drag offers
     * @param dropped whether the drop was already said
     * @param ours    whether the source is this program's own
     * @return whether the drag is followed, from the point {@code at}
     */
    boolean keepsAfterSilence(long now, Sample at, List<String> offered, boolean dropped, boolean ours) {
        if (active || dropped || ours || deliverable(offered).isEmpty()) {
            return false;
        }
        if (sampled && !away && (at.rootX() != sampleX || at.rootY() != sampleY)) {
            away = true;
            awaySince = now;
        }
        sampled = true;
        sampleX = at.rootX();
        sampleY = at.rootY();
        if (!away || unanswered || !at.over() || !at.buttonHeld() || at.escape()
                || now - Math.max(awaySince, quietSince) < SILENCE_NANOS) {
            return false;
        }
        start(at);
        return true;
    }

    /**
     * A turn of the X thread while a drag is over the window and not followed: whether it is gone
     * with nothing said, the buttons having been up for {@link #STALE_NANOS} with no drop and no
     * leave (a source that dropped on GLFW's window, or went away). The drop a source says at the
     * release comes long before.
     *
     * @param now the time, in nanoseconds
     * @param at  the pointer now
     * @return whether the drag is to be forgotten
     */
    boolean stale(long now, Sample at) {
        if (active || at.buttonHeld()) {
            released = false;
            return false;
        }
        if (!released) {
            released = true;
            releasedSince = now;
        }
        return now - releasedSince >= STALE_NANOS;
    }

    private void start(Sample at) {
        active = true;
        x = at.rootX();
        y = at.rootY();
        still = 0;
    }

    /**
     * A turn of the X thread.
     *
     * @param at the pointer now
     * @return what to do
     */
    Step tick(Sample at) {
        if (!active) {
            return Step.NOTHING;
        }
        if (at.escape()) {
            active = false;
            return Step.CALLED_OFF;
        }
        if (!at.buttonHeld() || !at.over()) {
            active = false;
            return Step.LEFT;
        }
        if (at.rootX() != x || at.rootY() != y) {
            x = at.rootX();
            y = at.rootY();
            still = 0;
            return Step.OVER;
        }
        return ++still % SEAL_TICKS == 0 ? Step.SEAL : Step.NOTHING;
    }

    /** The drag is no longer followed: it is over, or forgotten. */
    void stop() {
        active = false;
    }

    boolean active() {
        return active;
    }

    /** @return the point last told, on the root window */
    int x() {
        return x;
    }

    /** @return the point last told, on the root window */
    int y() {
        return y;
    }
}
