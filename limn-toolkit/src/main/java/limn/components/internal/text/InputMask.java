package limn.components.internal.text;

import java.util.Arrays;
import java.util.Objects;

/**
 * A pattern a text field types to: {@code 0} takes a digit, {@code A} a letter, {@code *} any
 * character, and every other character is a literal the field writes by itself. What it does is
 * one operation, {@link #conform}: given any text and a caret, the text the pattern makes of it and
 * where the caret goes, so typing, pasting with or without the literals, deleting and setting from
 * code all come out the same way.
 *
 * <p>A literal is written when the position after it is filled: a field holding {@code 123} under
 * {@code 000.000} shows {@code 123}, and the dot arrives with the fourth digit. The literals that
 * end the pattern have no position after them, so they arrive with the last one: {@code (00)}
 * holding {@code 12} shows {@code (12)}, the text a complete value has. And a literal the text
 * itself ends in stays, when it shows the text was written with its literals: the user who types
 * the {@code D} of {@code 'DE'00} sees it, and Backspace takes such a run back one character at a
 * time, so {@code 123.4} loses the 4 and then the dot.
 *
 * <p>Text between single quotes is literal, so a {@code 0}, an {@code A} or a {@code *} can be one
 * ({@code +'0' 000}), and two single quotes are one apostrophe, inside quotes and out: the rule of
 * {@code java.text.SimpleDateFormat}, which a Java developer already knows, chosen before 1.0
 * because an escape added after it would change what patterns already written mean.
 *
 * <p><b>Not API</b>: see the package.
 */
public final class InputMask {

    /** What {@link #conform} made of a text. */
    public record Conformed(String text, int caret, String raw) {
    }

    private final String pattern;
    /** The pattern with its quoting resolved: one entry per position or literal. */
    private final int[] codePoints;
    /** Whether the entry at the same index is a position to fill rather than a literal. */
    private final boolean[] slotAt;
    private final int slots;

    private InputMask(String pattern, int[] codePoints, boolean[] slotAt) {
        this.pattern = pattern;
        this.codePoints = codePoints;
        this.slotAt = slotAt;
        int count = 0;
        for (boolean slot : slotAt) {
            if (slot) {
                count++;
            }
        }
        this.slots = count;
    }

    /**
     * @param pattern the pattern; it must hold at least one position to fill
     * @return the mask
     * @throws IllegalArgumentException if the pattern holds no {@code 0}, {@code A} or {@code *}
     *                                  outside quotes, or opens a quote it never closes
     */
    public static InputMask of(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        int[] given = pattern.codePoints().toArray();
        int[] codePoints = new int[given.length];
        boolean[] slotAt = new boolean[given.length];
        int n = 0;
        boolean quoted = false;
        for (int i = 0; i < given.length; i++) {
            int cp = given[i];
            if (cp == '\'') {
                if (i + 1 < given.length && given[i + 1] == '\'') {
                    // Two quotes are one apostrophe, whether or not a quote is open.
                    codePoints[n++] = '\'';
                    i++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }
            slotAt[n] = !quoted && isSlot(cp);
            codePoints[n++] = cp;
        }
        if (quoted) {
            throw new IllegalArgumentException("the mask " + pattern + " opens a quote it never closes");
        }
        InputMask mask = new InputMask(pattern, Arrays.copyOf(codePoints, n), Arrays.copyOf(slotAt, n));
        if (mask.slots == 0) {
            throw new IllegalArgumentException("the mask " + pattern
                    + " has no position to fill: 0, A or * outside quotes");
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
        for (int i = 0; i < codePoints.length; i++) {
            out.appendCodePoint(slotAt[i] ? '_' : codePoints[i]);
        }
        return out.toString();
    }

    /**
     * Fits {@code text} to the pattern. Each position to fill takes the next character of the text
     * it accepts, passing over the ones it does not; a run of literals the text carries where the
     * pattern has it is taken as those literals (see {@link #afterRun} for when it does). What
     * does not fit after the last position is dropped.
     *
     * @param text  any text
     * @param caret a caret in {@code text}, in chars
     * @return the text the pattern makes, the caret after as many filled positions as preceded it,
     *         and the filled characters alone
     */
    public Conformed conform(String text, int caret) {
        return conform(text, caret, true);
    }

    /**
     * Fits characters known to be a value's alone, without literals, to the pattern: as
     * {@link #conform}, except that no character is ever taken as a literal, which matters for a
     * run made only of characters its position takes, where the text alone cannot say.
     *
     * @param raw   the filled characters, such as {@link Conformed#raw()} with one taken out
     * @param caret a caret in {@code raw}, in chars
     * @return what {@link #conform} returns
     */
    public Conformed conformRaw(String raw, int caret) {
        return conform(raw, caret, false);
    }

    private Conformed conform(String text, int caret, boolean readsLiterals) {
        StringBuilder out = new StringBuilder(pattern.length());
        StringBuilder raw = new StringBuilder(slots);
        StringBuilder pending = new StringBuilder();
        int rawBeforeCaret = 0;
        int filled = 0;
        int newCaret = 0;
        boolean caretAfterLiterals = false;
        int at = 0;
        int p = 0;
        while (p < codePoints.length && at < text.length()) {
            int slot = codePoints[p];
            if (!slotAt[p]) {
                // A run of literals is written when the next position is filled, and taken from
                // the text when the text carries it, so a value pasted with its literals reads
                // the same as one without. Never otherwise: a character the next position would
                // take is the value's, not the literal's, even when it is the same character.
                int end = p;
                while (end < codePoints.length && !slotAt[end]) {
                    end++;
                }
                RunRead read = readsLiterals ? afterRun(text, at, p, end) : new RunRead(at, p);
                if (read.at() > at && read.at() >= text.length()) {
                    // The text ends in the run, which it carries this far: the literals typed by
                    // hand are written now, a keystroke that shows, and the rest wait as ever.
                    for (int r = p; r < read.through(); r++) {
                        out.appendCodePoint(codePoints[r]);
                    }
                    for (int r = read.through(); r < end; r++) {
                        pending.appendCodePoint(codePoints[r]);
                    }
                    if (caret >= text.length()) {
                        newCaret = out.length();
                        caretAfterLiterals = true;
                    }
                } else {
                    for (int r = p; r < end; r++) {
                        pending.appendCodePoint(codePoints[r]);
                    }
                }
                at = read.at();
                p = end;
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
            filled++;
        }
        if (filled == slots) {
            // No position follows the literals that end the pattern, so they arrive with the last
            // one: a complete value is the pattern's text, "(12)" and not "(12". What the loop
            // already met is in pending; whatever it did not reach is literal too.
            out.append(pending);
            for (; p < codePoints.length; p++) {
                out.appendCodePoint(codePoints[p]);
            }
            if (rawBeforeCaret == slots && caret >= text.length()) {
                // Typed at the end, the caret ends the text, as it passes a literal mid-pattern.
                newCaret = out.length();
            }
        }
        if (rawBeforeCaret == 0 && !caretAfterLiterals) {
            newCaret = 0;
        }
        return new Conformed(out.toString(), Math.min(newCaret, out.length()), raw.toString());
    }

    /**
     * Where the text goes on after the run of literals {@code [from, to)} of the pattern, read
     * at {@code at}: past the literals the text carries there, or {@code at} itself when it does
     * not carry the run.
     *
     * <p>A literal the position after the run would take ({@code 5} in {@code +55 (00)}, a
     * quoted {@code 0}) is the one to be careful with, since the same character is also a value's:
     * the text carries the run only if every such literal is there, in order, and, when the run
     * also has literals that position would refuse ({@code +}, a space, a parenthesis), at least
     * one of those is there too, which is what says the text was written with its literals. So
     * a raw {@code 51987654321} keeps its area code 51 and a raw {@code 012} its 0, while
     * {@code +55 (51) 98765-4321}, and {@code +55(51)98765-4321} without its spaces, read as
     * formatted. A literal the next position would refuse may be missing: it would be passed
     * over anyway. A run made only of characters its position takes ({@code '55'00000}) is
     * carried only whole, and a text that begins with it can be told from a formatted one by
     * nothing, so it reads as formatted; where the caller knows the characters are a value's
     * alone, {@link #conformRaw} reads no literal at all.
     *
     * <p>A text that ends inside the run carries as much of it as it holds, so long as what it
     * holds shows it was written with its literals: {@code D} under {@code 'DE'00}, {@code +1}
     * under {@code +1 (000)}. A {@code 1} alone there shows nothing, and goes to the position.
     *
     * @return where the text goes on, and the end of the run's literals it carried, which is
     *         {@code from} when it carried none
     */
    private RunRead afterRun(String text, int at, int from, int to) {
        int next = to < codePoints.length ? codePoints[to] : -1;
        int t = at;
        int through = from;
        boolean refusable = false;
        boolean refusableShown = false;
        boolean whole = true;
        for (int r = from; r < to; r++) {
            if (t >= text.length()) {
                // The text ends inside the run: what it holds so far decides.
                whole = false;
                break;
            }
            int literal = codePoints[r];
            boolean taken = next >= 0 && accepts(next, literal);
            refusable |= !taken;
            if (text.codePointAt(t) == literal) {
                t += Character.charCount(literal);
                refusableShown |= !taken;
                through = r + 1;
            } else if (taken) {
                return new RunRead(at, from);
            }
        }
        // A literal the position would refuse is the evidence; a run without one is carried only
        // whole, since a part of it is as much a value's as the run's.
        return refusableShown || (!refusable && whole) ? new RunRead(t, through) : new RunRead(at, from);
    }

    /** What {@link #afterRun} read: where the text goes on, and the end of the literals carried. */
    private record RunRead(int at, int through) {
    }

    /**
     * The literals the guide shows between a conformed text and the next position to fill, where a
     * focused field draws its caret: {@code DE} for an empty field under {@code 'DE'00}, the
     * {@code .} after {@code 123} under {@code 000.000}. None when the text itself ends in a
     * literal, where the user is writing the run by hand and the caret follows what was written.
     *
     * @param written how many entries of the pattern a conformed text holds: its code points
     * @return the literals, possibly none
     */
    public String literalsAt(int written) {
        if (written > 0 && written <= codePoints.length && !slotAt[written - 1]) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = written; i < codePoints.length && !slotAt[i]; i++) {
            out.appendCodePoint(codePoints[i]);
        }
        return out.toString();
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
