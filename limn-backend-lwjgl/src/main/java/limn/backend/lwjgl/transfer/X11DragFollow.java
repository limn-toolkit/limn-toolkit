package limn.backend.lwjgl.transfer;

import limn.transfer.DropAction;

import java.util.List;
import java.util.Set;

/**
 * A drag from another program over one of our windows on X11, followed by reading the pointer
 * once its source no longer says where it is. X thread.
 *
 * <p>A toolkit (GTK 4, read in its X11 drag code) sends the enter and the first position to the
 * window {@code XdndProxy} names, and at its next move sends that window a leave and goes on with
 * an enter and positions to the window under the pointer itself, which here is GLFW's: GLFW
 * answers them, accepting files whenever they were offered and anything else never, and hears
 * the drop. The scene, told of the first position and then of the leave, saw the pointer once at
 * the window's edge: a page never scrolled under a file resting at its bottom edge, and nothing
 * under the pointer showed it would take the file.
 *
 * <p>So a leave that comes while the pointer is still over the window, a button held and Escape
 * up, is taken for that turn, when what is offered includes files: the drag stays on, and each
 * turn of the X thread reads the pointer and tells the scene where it is when it moved. The
 * offer is narrowed to files and the action to a copy, since only files arrive through GLFW's
 * drop, which tells the source it was a copy. The source still hears GLFW's answer to every
 * position it sends, so while the pointer rests the scene's answer is sent to it again every
 * {@link #SEAL_TICKS} turns: the source acts on the last answer it had when the button is let
 * go, and after a rest that is ours. The follow ends when the button is let go, at Escape, and
 * when the pointer leaves the window, which the source tells GLFW and nobody else.
 */
final class X11DragFollow {

    /** Turns of the X thread, sixteen milliseconds each while a drag is on, between two answers to a resting pointer. */
    static final int SEAL_TICKS = 6;

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
        /** The drag is over for this window: the scene is told it left. */
        LEFT
    }

    /**
     * The pointer as a turn of the X thread reads it from the server.
     *
     * @param rootX  on the root window
     * @param rootY  on the root window
     * @param state  the buttons and modifier keys held, as {@code XQueryPointer} answers them
     * @param escape whether Escape is held
     * @param over   whether it is over the window the drag is over; see {@link #over}
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

    /**
     * Whether a pointer is over the window a drag is over.
     *
     * @param inside whether the point is inside the window
     * @param aware  the window that speaks XDND found at the point from the root down, or 0
     * @param ours   the window
     * @return inside it, and no other window that takes drags on top of it there; a point where
     *         none is found (the source's own drag picture, or a window that takes no drags) counts
     */
    static boolean over(boolean inside, long aware, long ours) {
        return inside && (aware == 0 || aware == ours);
    }

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
        active = true;
        x = at.rootX();
        y = at.rootY();
        still = 0;
        return true;
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
        if (!at.buttonHeld() || at.escape() || !at.over()) {
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

    /** The drag is no longer followed: a new one entered, or the source spoke to us again. */
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
