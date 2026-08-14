package limn.scene.event;

/** Committed text input (one code point), routed like {@link KeyEvent}. */
public final class CharEvent extends InputEvent {

    private final int codepoint;

    /** One committed character; a surrogate pair arrives as a single code point. */
    public CharEvent(int codepoint) {
        this.codepoint = codepoint;
    }

    /** The committed code point, not a UTF-16 unit. */
    public int codepoint() {
        return codepoint;
    }

    @Override
    public String toString() {
        return "CharEvent[" + new String(Character.toChars(codepoint)) + "]";
    }
}
