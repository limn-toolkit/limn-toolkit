package limn.scene.internal;

import limn.scene.Widget;

/**
 * A widget whose text marks an access letter: a button, a check box, a radio button, a label that
 * names a control. The scene finds them for Alt+letter within the window or the topmost modal, and
 * a control takes a letter before the menu bar. Not API: the letter is written in the text, and
 * what it does is the widget's own.
 */
public interface AccessLetterOwner {

    /** @return the access letter, upper-cased, or {@code 0} for none */
    char accessLetter();

    /**
     * @return where the keyboard goes when this is one of several with the same letter, which
     *         then moves focus among them and acts on none
     */
    Widget<?> accessLetterFocus();

    /** Does what the letter is for: presses a button, toggles a box, focuses a label's control. */
    void accessLetterPressed();

    /**
     * An owner made of a widget's own private methods, which keeps the interface out of the
     * widget's public face.
     *
     * @param letter  {@link #accessLetter()}
     * @param focus   {@link #accessLetterFocus()}
     * @param pressed {@link #accessLetterPressed()}
     */
    static AccessLetterOwner of(java.util.function.IntSupplier letter,
            java.util.function.Supplier<? extends Widget<?>> focus, Runnable pressed) {
        return new AccessLetterOwner() {
            @Override
            public char accessLetter() {
                return (char) letter.getAsInt();
            }

            @Override
            public Widget<?> accessLetterFocus() {
                return focus.get();
            }

            @Override
            public void accessLetterPressed() {
                pressed.run();
            }
        };
    }

    /**
     * Whether a key press is {@code letter}: the key printed with it, or the character the layout
     * types with it, so a Cyrillic letter is reached on a Cyrillic layout.
     *
     * @param letter    the access letter, upper-cased; 0 matches nothing
     * @param key       the press's key code, read off the keycap
     * @param codePoint what the layout types with the key, or a negative number when unknown
     */
    static boolean matches(char letter, int key, int codePoint) {
        if (letter == 0) {
            return false;
        }
        return key == letter || codePoint > 0 && Character.toUpperCase(codePoint) == letter;
    }
}
