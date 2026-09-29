package limn.backend.spi;

/**
 * What text a window asks the platform for, set with {@link WindowPeer#setTextInput}. Two requests
 * that look like one: typed characters, which a list's type-ahead needs, and input-method
 * composition, which only a text field wants. The SDL3 spike measured the difference: a platform
 * that delivers no characters while composition is off broke every type-ahead.
 */
public enum TextInput {

    /** No text: keys only. Characters typed are not delivered. */
    NONE,

    /**
     * Typed characters, one committed code point at a time, with the input method off: what a
     * control that is not text wants, so a keystroke goes past the input method untouched while a
     * list still hears the letters for its type-ahead.
     */
    CHARACTERS,

    /**
     * Characters and the input method's composition, with its preedit and candidate window: what a
     * text field wants.
     */
    COMPOSITION
}
