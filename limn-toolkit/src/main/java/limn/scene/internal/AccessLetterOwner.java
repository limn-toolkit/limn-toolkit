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
     * Readies {@link #accessLetterFocus()} to take the keyboard, its turn come among several of one
     * letter: a radio of a group that is not the group's tab stop becomes it. Called just before
     * the scene moves the keyboard there; by default nothing.
     */
    default void accessLetterTurn() {
    }

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
        return of(letter, focus, () -> { }, pressed);
    }

    /**
     * The same, with {@link #accessLetterTurn()}.
     *
     * @param letter  {@link #accessLetter()}
     * @param focus   {@link #accessLetterFocus()}
     * @param turn    {@link #accessLetterTurn()}
     * @param pressed {@link #accessLetterPressed()}
     */
    static AccessLetterOwner of(java.util.function.IntSupplier letter,
            java.util.function.Supplier<? extends Widget<?>> focus, Runnable turn, Runnable pressed) {
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
            public void accessLetterTurn() {
                turn.run();
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
     * <p>The key code is compared only for a Latin letter or a digit, the one range where a key's
     * code is the character on its cap. Above it the codes name keys that print nothing, and
     * they are the characters of Latin Extended-A: Home is 268, which is Č, so Alt+Home pressed
     * a Czech "&amp;Člen", and Insert chose a Polish menu row marked Ą.
     *
     * @param letter    the access letter, upper-cased; 0 matches nothing
     * @param key       the press's key code, read off the keycap
     * @param codePoint what the layout types with the key, or a negative number when unknown
     */
    static boolean matches(char letter, int key, int codePoint) {
        if (letter == 0) {
            return false;
        }
        boolean onTheCap = letter >= 'A' && letter <= 'Z' || letter >= '0' && letter <= '9';
        return onTheCap && key == letter || codePoint > 0 && Character.toUpperCase(codePoint) == letter;
    }
}
