package limn.scene.event;

/**
 * A pointer event, positioned in scene coordinates (logical points, origin at
 * the window's top-left). Convert to a widget's space with
 * {@code widget.sceneToLocal(event.x(), event.y())}. {@code ENTER}/{@code EXIT} target the
 * hovered leaf and do not bubble; every other type bubbles from the target to
 * its ancestors until consumed.
 *
 * <p>{@code MOTION} is the relative-capture stream: while the window is in
 * {@link limn.backend.PointerMode#RELATIVE}, motion arrives as unbounded
 * {@link #deltaX()}/{@link #deltaY()} deltas delivered to the <em>focused</em>
 * widget (like keys, since there is no meaningful cursor position to
 * hit-test); {@link #x()}/{@link #y()} hold the stale position where the
 * capture began.
 *
 * <p>The widget a press lands on holds the pointer until it is let go of: the {@code DRAG}s and
 * the {@code RELEASE} go to it wherever the pointer is. Each of them carries a point of that
 * widget's own plane, so {@code sceneToLocal(x, y)} gives it a finite point. Where an ancestor
 * draws the widget in perspective and the pointer goes past the horizon, the scene point has no
 * point on the widget's plane at all; the scene then hands it no {@code DRAG}, the widget stays
 * where the pointer last was on its plane, and a {@code RELEASE} let go of there arrives at that
 * last point, so a slider commits the value it was showing. The drag goes on when the pointer
 * comes back in front of the horizon. When the plane itself moves while the button is held (a
 * flip under way, a panel turned by the application), the release arrives where that last point of
 * the widget lies now. The one exception is a plane that leaves nothing to reach: turned edge-on
 * to the viewer, or with that point gone behind the eye; the release still arrives, to end the
 * press, at the last scene point, where {@code sceneToLocal} answers {@code NaN}. Hover events
 * ({@code ENTER}, {@code MOVE}, {@code EXIT}) and {@code WHEEL} follow the pointer and are not
 * held to this.
 */
public final class MouseEvent extends InputEvent {

    /**
     * What the pointer did.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Type { ENTER, EXIT, MOVE, DRAG, PRESS, RELEASE, CLICK, WHEEL, MOTION }

    private final Type type;
    private final float x;
    private final float y;
    private final int button;
    private final float scrollX;
    private final float scrollY;
    private final int modifiers;
    private final int clickCount;

    /**
     * A pointer event. {@code x} and {@code y} are in <b>scene</b> coordinates, whichever widget
     * receives it: a widget turns them into its own with {@code sceneToLocal(x, y)}, which
     * converts the point as a whole.
     */
    public MouseEvent(Type type, float x, float y, int button,
                      float scrollX, float scrollY, int modifiers) {
        this(type, x, y, button, scrollX, scrollY, modifiers, 1);
    }

    /**
     * A pointer event carrying how many presses in a row this one is.
     *
     * @param clickCount 1 for a single press, 2 for the second of a double click, and so on
     */
    public MouseEvent(Type type, float x, float y, int button,
                      float scrollX, float scrollY, int modifiers, int clickCount) {
        this.clickCount = Math.max(1, clickCount);
        this.type = type;
        this.x = x;
        this.y = y;
        this.button = button;
        this.scrollX = scrollX;
        this.scrollY = scrollY;
        this.modifiers = modifiers;
    }

    /**
     * @return how many presses in a row this is — 2 on the second press of a double click — counted
     *         with the platform's own double-click interval where the backend knows it,
     *         and 1 for anything that is not a press, release or click
     */
    public int clickCount() {
        return clickCount;
    }

    /** Which pointer transition this is. */
    public Type type() {
        return type;
    }

    /** @return pointer x in scene coordinates */
    public float x() {
        return x;
    }

    /** @return pointer y in scene coordinates */
    public float y() {
        return y;
    }

    /** @return the button ({@link limn.input.Keys#MOUSE_LEFT}…), or -1 when not a button event */
    public int button() {
        return button;
    }

    /** @return accumulated horizontal wheel delta (WHEEL only) */
    public float scrollX() {
        return scrollX;
    }

    /** @return accumulated vertical wheel delta (WHEEL only) */
    public float scrollY() {
        return scrollY;
    }

    /** @return accumulated relative horizontal motion in logical points (MOTION only) */
    public float deltaX() {
        return scrollX;
    }

    /** @return accumulated relative vertical motion in logical points (MOTION only) */
    public float deltaY() {
        return scrollY;
    }

    /** @return modifier bitmask ({@link limn.input.Keys#MOD_SHIFT}…) */
    public int modifiers() {
        return modifiers;
    }

    @Override
    public String toString() {
        return "MouseEvent[" + type + " @" + x + "," + y + (button >= 0 ? " btn" + button : "") + "]";
    }
}
