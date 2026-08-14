package limn.scene.event;

/**
 * A pointer event, positioned in scene coordinates (logical points, origin at
 * the window's top-left). Convert to a widget's space with
 * {@code widget.sceneToLocalX/Y}. {@code ENTER}/{@code EXIT} target the
 * hovered leaf and do not bubble; every other type bubbles from the target to
 * its ancestors until consumed.
 *
 * <p>{@code MOTION} is the relative-capture stream: while the window is in
 * {@link limn.backend.PointerMode#RELATIVE}, motion arrives as unbounded
 * {@link #deltaX()}/{@link #deltaY()} deltas delivered to the <em>focused</em>
 * widget (like keys, since there is no meaningful cursor position to
 * hit-test); {@link #x()}/{@link #y()} hold the stale position where the
 * capture began.
 */
public final class MouseEvent extends InputEvent {

    public enum Type { ENTER, EXIT, MOVE, DRAG, PRESS, RELEASE, CLICK, WHEEL, MOTION }

    private final Type type;
    private final float x;
    private final float y;
    private final int button;
    private final float scrollX;
    private final float scrollY;
    private final int modifiers;

    /** A pointer event; {@code x} and {@code y} are in the receiving widget's coordinates. */
    public MouseEvent(Type type, float x, float y, int button,
                      float scrollX, float scrollY, int modifiers) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.button = button;
        this.scrollX = scrollX;
        this.scrollY = scrollY;
        this.modifiers = modifiers;
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
