package limn.scene.event;

/**
 * A keyboard event routed to the focused widget (bubbling to its ancestors
 * until consumed). Key codes and modifiers follow {@link limn.input.Keys}.
 */
public final class KeyEvent extends InputEvent {

    private final int key;
    private final boolean pressed;
    private final boolean repeat;
    private final int modifiers;

    /** A key transition; {@code key} and {@code modifiers} are {@code Keys} constants. */
    public KeyEvent(int key, boolean pressed, boolean repeat, int modifiers) {
        this.key = key;
        this.pressed = pressed;
        this.repeat = repeat;
        this.modifiers = modifiers;
    }

    /** The physical key, as a {@code Keys} constant, which is layout-independent. */
    public int key() {
        return key;
    }

    /** Whether this is a press (or auto-repeat) rather than a release. */
    public boolean isPressed() {
        return pressed;
    }

    /** @return {@code true} for auto-repeat presses */
    public boolean isRepeat() {
        return repeat;
    }

    /** Modifier mask held during the event; test it with the {@code Keys.MOD_*} bits. */
    public int modifiers() {
        return modifiers;
    }

    @Override
    public String toString() {
        return "KeyEvent[key=" + key + (pressed ? " press" : " release") + (repeat ? " repeat" : "") + "]";
    }
}
