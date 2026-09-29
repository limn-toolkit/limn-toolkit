package limn.scene.event;

import limn.input.Keys;

/**
 * A keyboard event routed to the focused widget (bubbling to its ancestors
 * until consumed). Key codes and modifiers follow {@link limn.input.Keys}.
 *
 * <p>A key is told two ways. {@link #key()} is the physical key, where it sits on the keyboard,
 * named after the key at that place on a US layout: what a game wants, since W is up wherever the
 * layout moved the letter. {@link #shortcutKey()} is the key the active layout prints there when it
 * is a letter or a digit: what a shortcut wants, since the user reads Ctrl+Z off the keycap, and on
 * a French AZERTY keyboard the key printed Z is where a US layout has W.
 */
public final class KeyEvent extends InputEvent {

    private final int key;
    private final int layoutCodePoint;
    private final boolean pressed;
    private final boolean repeat;
    private final int modifiers;

    /**
     * A key transition on a US layout, where the key printed on a keycap is the physical key's
     * name: what a test types.
     *
     * @param key       the physical key, a {@code Keys} constant
     * @param pressed   a press or an auto-repeat rather than a release
     * @param repeat    an auto-repeat
     * @param modifiers the {@code Keys.MOD_*} bits held
     */
    public KeyEvent(int key, boolean pressed, boolean repeat, int modifiers) {
        this(key, Keys.usLayoutCodePoint(key), pressed, repeat, modifiers);
    }

    /**
     * A key transition.
     *
     * @param key             the physical key, a {@code Keys} constant
     * @param layoutCodePoint what the active layout types at that key with no modifier held, or
     *                        {@code 0} for a key that types nothing (an arrow, a function key)
     * @param pressed         a press or an auto-repeat rather than a release
     * @param repeat          an auto-repeat
     * @param modifiers       the {@code Keys.MOD_*} bits held
     */
    public KeyEvent(int key, int layoutCodePoint, boolean pressed, boolean repeat, int modifiers) {
        this.key = key;
        this.layoutCodePoint = layoutCodePoint;
        this.pressed = pressed;
        this.repeat = repeat;
        this.modifiers = modifiers;
    }

    /**
     * The physical key, as a {@code Keys} constant: where it is on the keyboard, whatever the
     * layout prints on it, named after the key at that place on a US layout.
     */
    public int key() {
        return key;
    }

    /**
     * @return what the active layout types at this key with no modifier held, as a code point, or
     *         {@code 0} for a key that types nothing
     */
    public int layoutCodePoint() {
        return layoutCodePoint;
    }

    /**
     * The key a shortcut is read from: the letter or digit the active layout prints on this key,
     * as the {@code Keys} constant of that letter or digit, and {@link #key()} for every other key.
     * On AZERTY the key where a US layout has W answers {@code Keys.Z}, so Ctrl+Z is the key the
     * user sees Z on; a layout whose letters are not Latin (Cyrillic, Greek) answers the physical
     * key, as native applications do, so Ctrl+Z stays where Z is on a US keyboard.
     *
     * @return a {@code Keys} constant
     */
    public int shortcutKey() {
        return Keys.shortcutKeyOf(key, layoutCodePoint);
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
