package limn.components.internal.text;

import java.util.Objects;

/**
 * A pattern a text field types to: {@code 0} takes a digit, {@code A} a letter, {@code *} any
 * character, and every other character is a literal the field writes by itself. What it does is
 * one operation, {@link #conform}: given any text and a caret, the text the pattern makes of it and
 * where the caret goes, so typing, pasting with or without the literals, deleting and setting from
 * code all come out the same way.
 *
 * <p>A literal is written when the position after it is filled, never before: a field holding
 * {@code 123} under {@code 000.000} shows {@code 123}, and the dot arrives with the fourth digit.
 * Backspace therefore never leaves a literal dangling at the end.
 *
 * <p><b>Not API</b>: see the package.
 */
public final class InputMask {

    /** What {@link #conform} made of a text. */
    public record Conformed(String text, int caret, String raw) {
    }

    private final String pattern;
    private final int[] codePoints;
    private final int slots;

    private InputMask(String pattern) {
        this.pattern = pattern;
        this.codePoints = pattern.codePoints().toArray();
        int count = 0;
        for (int cp : codePoints) {
            if (isSlot(cp)) {
                count++;
            }
        }
        this.slots = count;
    }

    /**
     * @param pattern the pattern; it must hold at least one position to fill
     * @return the mask
     * @throws IllegalArgumentException if the pattern holds no {@code 0}, {@code A} or {@code *}
     */
    public static InputMask of(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        InputMask mask = new InputMask(pattern);
        if (mask.slots == 0) {
            throw new IllegalArgumentException("a mask needs a position to fill: 0, A or *");
        }
        return mask;
    }

    /** @return the pattern as it was given */
    public String pattern() {
        return pattern;
    }

    /** @return how many positions the pattern has to fill */
    public int slots() {
        return slots;
    }

    private static boolean isSlot(int cp) {
        return cp == '0' || cp == 'A' || cp == '*';
    }

    private static boolean accepts(int slot, int cp) {
        if (Character.isISOControl(cp)) {
            return false;
        }
        return switch (slot) {
            case '0' -> Character.isDigit(cp);
            case 'A' -> Character.isLetter(cp);
            default -> true;
        };
    }

    /**
     * The guide a focused field shows: the pattern with a low line in every position to fill.
     *
     * @return the guide
     */
    public String guide() {
        StringBuilder out = new StringBuilder(pattern.length());
        for (int cp : codePoints) {
            out.appendCodePoint(isSlot(cp) ? '_' : cp);
        }
        return out.toString();
    }

    /**
     * Fits {@code text} to the pattern. Each position to fill takes the next character of the text
     * it accepts, passing over the ones it does not; a literal of the pattern met in the text where
     * the pattern has it is taken as that literal. What does not fit after the last position is
     * dropped.
     *
     * @param text  any text
     * @param caret a caret in {@code text}, in chars
     * @return the text the pattern makes, the caret after as many filled positions as preceded it,
     *         and the filled characters alone
     */
    public Conformed conform(String text, int caret) {
        StringBuilder out = new StringBuilder(pattern.length());
        StringBuilder raw = new StringBuilder(slots);
        StringBuilder pending = new StringBuilder();
        int rawBeforeCaret = 0;
        int newCaret = 0;
        int at = 0;
        int p = 0;
        while (p < codePoints.length && at < text.length()) {
            int slot = codePoints[p];
            if (!isSlot(slot)) {
                // Written when the next position is filled; and taken from the text when it is
                // there, so a pasted value with its literals reads the same as one without.
                pending.appendCodePoint(slot);
                int cp = text.codePointAt(at);
                if (cp == slot) {
                    at += Character.charCount(cp);
                }
                p++;
                continue;
            }
            int cp = text.codePointAt(at);
            int width = Character.charCount(cp);
            boolean beforeCaret = at < caret;
            at += width;
            if (!accepts(slot, cp)) {
                continue;
            }
            out.append(pending);
            pending.setLength(0);
            out.appendCodePoint(cp);
            raw.appendCodePoint(cp);
            p++;
            if (beforeCaret) {
                rawBeforeCaret++;
                newCaret = out.length();
            }
        }
        if (rawBeforeCaret == 0) {
            newCaret = 0;
        }
        return new Conformed(out.toString(), Math.min(newCaret, out.length()), raw.toString());
    }

    /**
     * @param text a text this mask conformed
     * @return whether every position is filled
     */
    public boolean isComplete(String text) {
        String raw = conform(text, 0).raw();
        return raw.codePointCount(0, raw.length()) == slots;
    }

    /**
     * Takes the filled character at {@code index} out of a conformed text, the others closing up:
     * what Backspace does when the character before the caret was a literal.
     *
     * @param raw   the filled characters
     * @param index which one, in code points
     * @return the filled characters without it
     */
    public static String removeRaw(String raw, int index) {
        int from = raw.offsetByCodePoints(0, index);
        int to = raw.offsetByCodePoints(from, 1);
        return raw.substring(0, from) + raw.substring(to);
    }
}
