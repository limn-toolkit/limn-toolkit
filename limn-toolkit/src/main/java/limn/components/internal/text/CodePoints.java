package limn.components.internal.text;

/**
 * Offsets between the two ways a string is counted: the {@code char} index every
 * {@link String} method takes, and the code-point index an input method reports.
 *
 * <p>An IME describes its preedit in code points, since that is what the user typed, and a
 * text widget holds the preedit as a Java string and places things in it by {@code char}. The
 * conversion was written in TextField and again in TextArea; this is it once.
 */
public final class CodePoints {

    private CodePoints() {
    }

    /**
     * The {@code char} offset in {@code text} of the code point at {@code codePointIndex}.
     *
     * @param text           the string
     * @param codePointIndex the index in code points; at or below zero answers zero, and at or
     *                       past the end answers the string's length
     * @return the char offset
     */
    public static int charIndex(String text, int codePointIndex) {
        if (codePointIndex <= 0) {
            return 0;
        }
        int total = text.codePointCount(0, text.length());
        return codePointIndex >= total ? text.length() : text.offsetByCodePoints(0, codePointIndex);
    }
}
