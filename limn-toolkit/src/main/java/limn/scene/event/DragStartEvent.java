package limn.scene.event;

/**
 * The question a widget is asked when a drag may begin on it: a press that travelled a few
 * points, or the drag key on a focused widget. The widget answers with a {@code Drag}, or with
 * null to leave the gesture alone.
 */
public final class DragStartEvent {

    private final float x;
    private final float y;
    private final int modifiers;
    private final boolean keyboard;

    /**
     * @param x         where the press began, in scene coordinates
     * @param y         where the press began, in scene coordinates
     * @param modifiers the modifier keys held
     * @param keyboard  whether the drag key asked, and not a pointer
     */
    public DragStartEvent(float x, float y, int modifiers, boolean keyboard) {
        this.x = x;
        this.y = y;
        this.modifiers = modifiers;
        this.keyboard = keyboard;
    }

    /** @return where the press began, in scene coordinates; the widget's centre for a keyboard drag */
    public float x() {
        return x;
    }

    /** @return where the press began, in scene coordinates; the widget's centre for a keyboard drag */
    public float y() {
        return y;
    }

    /** @return the modifier keys held, as {@code Keys.MOD_*} bits */
    public int modifiers() {
        return modifiers;
    }

    /**
     * @return whether the keyboard asked. The point is then not where anything is: a widget drags
     *         what is selected or focused in it
     */
    public boolean isKeyboard() {
        return keyboard;
    }
}
