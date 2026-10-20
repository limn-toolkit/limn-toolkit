package limn.components.internal.text;

import java.util.Arrays;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

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
 * <p>An edit of a text the pattern already wrote is not fitted that way: {@link #edit} works on
 * the value, so a literal the edit shifts is never read again as a character a position takes,
 * which a text read afresh cannot promise, and the value's characters are never pushed off the
 * end to make room for what the edit inserts.
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

    /**
     * Applies an edit to a text this mask wrote, in terms of its value: the filled characters the
     * edit removed go, the characters of {@code inserted} a position takes go in where it landed,
     * and the text is written again from the value, so a literal the edit shifted is never read
     * as a value character. {@code inserted} is read from the entry it lands on, literals
     * included: a fragment pasted with its literals, or the literal under the caret typed over, is
     * taken as those literals, and the caret steps past them. With no position free, the inserted
     * characters that find none are dropped: an edit never pushes the value's own characters off
     * the end, and one of which nothing finds a position is refused.
     *
     * <p>The value's characters after the edit move by as many positions as the edit inserted
     * more, or fewer, than it took. Each must land in a position that takes it: on a pattern whose
     * positions are all of one kind each always does, and on one that mixes them a character that
     * would land in a position refusing it ({@code C} on a digit of {@code AAA-0000-**}) would be
     * lost, so the edit is refused instead. A digit moving into an any position, or within its
     * run of digits, lands in one that takes it, and the edit is made.
     *
     * <p>A Backspace or Delete with nothing selected ({@code step} -1 or 1) that took only
     * literals, which writing the text again puts back, takes the filled character beyond them
     * instead, as the user meant, on the same terms; a literal the text ended in is not put back,
     * so it goes alone.
     *
     * @param text     a text this mask wrote, such as {@link Conformed#text()}
     * @param from     where the edit's range begins in {@code text}, in chars
     * @param to       where it ends, in chars
     * @param inserted what took its place
     * @param step     -1 for a Backspace, 1 for a Delete, with nothing selected; 0 otherwise
     * @return the text, the caret after what was inserted, and the filled characters; or
     *         {@code null} when the edit is refused, which leaves the text as it was
     */
    public @Nullable Conformed edit(String text, int from, int to, String inserted, int step) {
        int a = text.codePointCount(0, from);
        int b = a + text.codePointCount(from, to);
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        boolean tookValue = false;
        int entry = 0;
        for (int k = 0; k < text.length(); entry++) {
            int cp = text.codePointAt(k);
            if (entry < codePoints.length && slotAt[entry]) {
                if (entry < a) {
                    before.appendCodePoint(cp);
                } else if (entry >= b) {
                    after.appendCodePoint(cp);
                } else {
                    tookValue = true;
                }
            }
            k += Character.charCount(cp);
        }
        int kept = before.codePointCount(0, before.length());
        Read typed = read(inserted, inserted.length(), true, a);
        String added = typed.raw();
        int reach = typed.entries();
        int room = slots - kept - after.codePointCount(0, after.length());
        if (added.codePointCount(0, added.length()) > room) {
            // No position for the rest: what was inserted gives way, never the value.
            room = Math.max(room, 0);
            if (room == 0 && !tookValue) {
                return null;
            }
            added = added.substring(0, added.offsetByCodePoints(0, room));
            reach = room == 0 ? 0 : slotEntry(kept + room - 1) + 1 - a;
        }
        String raw = before + added + after;
        Conformed fit = conformRaw(raw, before.length() + added.length());
        if (fit.raw().length() != raw.length()) {
            // A character the edit moved landed in a position of another kind, which passed over
            // it: the edit would lose a character the user did not take, so it is not made.
            return null;
        }
        String fitted = fit.text();
        int written = fitted.codePointCount(0, fitted.length());
        int caretEntry = Math.max(fitted.codePointCount(0, fit.caret()), a + reach);
        if (after.isEmpty()) {
            // No value follows the edit, so the text may end in literals written by hand: those
            // before it and those it typed stay, and so do those after it, which are put back
            // over a literal the edit took between them (the run is written in one piece). Only
            // a literal the text ended in, taken from the end, goes.
            int oldWritten = text.codePointCount(0, text.length());
            int limit = Math.max(a + reach, b < oldWritten ? oldWritten : 0);
            if (written < limit && written < codePoints.length && !slotAt[written]) {
                StringBuilder out = new StringBuilder(fitted);
                for (; written < limit && written < codePoints.length && !slotAt[written]; written++) {
                    out.appendCodePoint(codePoints[written]);
                }
                fitted = out.toString();
            }
        }
        if (step != 0 && !tookValue && inserted.isEmpty() && fitted.equals(text)) {
            // Only literals went, and writing the text again put them back: the key came to
            // nothing, so it takes the filled character beyond them.
            String value = before + after.toString();
            int gone = step < 0 ? kept - 1 : kept;
            if (gone >= 0 && gone < value.codePointCount(0, value.length())) {
                String left = removeRaw(value, gone);
                Conformed closed = conformRaw(left, left.offsetByCodePoints(0, gone));
                return closed.raw().length() == left.length() ? closed : null;
            }
        }
        int caret = fitted.offsetByCodePoints(0, Math.min(caretEntry, written));
        return new Conformed(fitted, caret, fit.raw());
    }

    /**
     * What {@link #overwrite} made of a text: the text, its filled characters, and the selection
     * left over it, {@code selectionStart == selectionEnd} for a caret.
     */
    public record Overwritten(String text, String raw, int selectionStart, int selectionEnd) {
    }

    /**
     * Whether the chars {@code [from, to)} of a text this mask wrote hold a position to fill, so
     * that typing over them {@linkplain #overwrite overwrites} rather than {@linkplain #edit edits}.
     *
     * @param text a text this mask wrote
     * @param from where the range begins, in chars
     * @param to   where it ends, in chars
     * @return whether a position lies in the range
     */
    public boolean holdsPosition(String text, int from, int to) {
        int a = text.codePointCount(0, from);
        int b = a + text.codePointCount(from, to);
        for (int entry = a; entry < b && entry < codePoints.length; entry++) {
            if (slotAt[entry]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the chars {@code [from, to)} of a text this mask wrote take in its whole value:
     * every position the text fills lies in the range, whatever literals lie in it or around it,
     * and there is at least one. Typing or pasting over such a selection starts a new value.
     *
     * @param text a text this mask wrote
     * @param from where the range begins, in chars
     * @param to   where it ends, in chars
     * @return whether the range holds every filled position of the text
     */
    public boolean selectsWholeValue(String text, int from, int to) {
        int a = text.codePointCount(0, from);
        int b = a + text.codePointCount(from, to);
        int written = text.codePointCount(0, text.length());
        boolean any = false;
        for (int entry = 0; entry < written && entry < codePoints.length; entry++) {
            if (slotAt[entry]) {
                if (entry < a || entry >= b) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    /**
     * Typing or pasting over a selection: the characters of {@code inserted} a position takes
     * replace the selected positions one by one, from the first, and nothing moves. A paste is
     * read from the entry the selection begins on, as {@link #edit} reads it, so a fragment carried
     * with its literals fills the same positions as one without. A keystroke is read from the first
     * selected position: a literal the selection begins on is selected, not under the caret, so
     * the {@code 1} typed over {@code 1 (4} of {@code +1 (415)} is the area code's, not the
     * literal's. Where a filled position follows
     * the selection, what finds no selected position is dropped, since going on would move that
     * character; where none does, the selection ends the value and what is left goes on into the
     * free positions after it, as an {@link #edit} there would, moving nothing. The selected
     * positions not reached keep their characters and stay selected, from the first of them to
     * the selection's end; when every selected position was filled, the caret goes after the last
     * character written.
     *
     * @param text     a text this mask wrote
     * @param from     where the selection begins, in chars
     * @param to       where it ends, in chars; the range holds a position
     * @param inserted what was typed or pasted
     * @param keystroke whether {@code inserted} is one key typed rather than a paste
     * @return the text, its filled characters and the selection left, or {@code null} when no
     *         character of {@code inserted} is taken by the position it would fill, which leaves
     *         the text as it was
     */
    public @Nullable Overwritten overwrite(String text, int from, int to, String inserted,
                                           boolean keystroke) {
        int[] cps = text.codePoints().toArray();
        int a = text.codePointCount(0, from);
        int b = a + text.codePointCount(from, to);
        int[] selected = new int[b - a];
        int count = 0;
        for (int entry = a; entry < b && entry < codePoints.length; entry++) {
            if (slotAt[entry]) {
                selected[count++] = entry;
            }
        }
        String added = read(inserted, inserted.length(), true, keystroke ? selected[0] : a).raw();
        if (added.codePointCount(0, added.length()) > count && !filledFrom(cps, b)) {
            // The selection ends the value: what is left of a paste fills the free positions
            // after it, which moves nothing, as the same paste with nothing selected would.
            Conformed edited = edit(text, from, to, inserted, 0);
            if (edited != null) {
                return new Overwritten(edited.text(), edited.raw(), edited.caret(), edited.caret());
            }
        }
        int[] fill = added.codePoints().limit(count).toArray();
        if (fill.length == 0) {
            return null;
        }
        for (int k = 0; k < fill.length; k++) {
            cps[selected[k]] = fill[k];
        }
        String out = new String(cps, 0, cps.length);
        StringBuilder raw = new StringBuilder(slots);
        for (int entry = 0; entry < cps.length && entry < codePoints.length; entry++) {
            if (slotAt[entry]) {
                raw.appendCodePoint(cps[entry]);
            }
        }
        int end = out.offsetByCodePoints(0, b);
        if (fill.length < count) {
            return new Overwritten(out, raw.toString(), out.offsetByCodePoints(0, selected[fill.length]), end);
        }
        int caret = out.offsetByCodePoints(0, selected[fill.length - 1] + 1);
        return new Overwritten(out, raw.toString(), caret, caret);
    }

    /** Whether a text of code points {@code cps} fills a position at entry {@code from} or after. */
    private boolean filledFrom(int[] cps, int from) {
        for (int entry = from; entry < cps.length && entry < codePoints.length; entry++) {
            if (slotAt[entry]) {
                return true;
            }
        }
        return false;
    }

    /** @return the entry of the pattern that holds the {@code index}-th position to fill */
    private int slotEntry(int index) {
        for (int i = 0, n = 0; i < codePoints.length; i++) {
            if (slotAt[i] && n++ == index) {
                return i;
            }
        }
        return codePoints.length;
    }

    /**
     * Reads a text this mask wrote as it stands, without fitting it: what an undo or a redo puts
     * back is such a text, and reading it afresh with {@link #conform} could take a literal as a
     * value where the text cannot say, as {@code '55'00000} can't.
     *
     * @param text  any text
     * @param caret a caret in {@code text}, in chars
     * @return the text, the caret and its filled characters, or {@code null} when the mask would
     *         not have written this text: a literal out of place, a character its position does
     *         not take, a position left empty before the end, or a complete value without the
     *         literals that end the pattern
     */
    public @Nullable Conformed written(String text, int caret) {
        StringBuilder raw = new StringBuilder(slots);
        int entry = 0;
        int filled = 0;
        for (int k = 0; k < text.length(); entry++) {
            int cp = text.codePointAt(k);
            if (entry >= codePoints.length) {
                return null;
            }
            if (slotAt[entry]) {
                if (!accepts(codePoints[entry], cp)) {
                    return null;
                }
                raw.appendCodePoint(cp);
                filled++;
            } else if (cp != codePoints[entry]) {
                return null;
            }
            k += Character.charCount(cp);
        }
        if (filled == slots && entry < codePoints.length) {
            return null;
        }
        return new Conformed(text, caret, raw.toString());
    }

    private Conformed conform(String text, int caret, boolean readsLiterals) {
        Read read = read(text, caret, readsLiterals, 0);
        return new Conformed(read.text(), read.caret(), read.raw());
    }

    /**
     * What {@link #read} made of a text: the conformed text, written from entry {@code start} on,
     * the caret, the filled characters, and how many entries of the pattern the text holds.
     */
    private record Read(String text, int caret, String raw, int entries) {
    }

    /**
     * The fitting itself, of a text that begins at entry {@code start} of the pattern: zero for a
     * whole value, the entry an edit lands on for what the edit inserts. A text that begins inside
     * a run of literals is read as the rest of that run, the part before {@code start} being
     * already in the text it lands in.
     */
    private Read read(String text, int caret, boolean readsLiterals, int start) {
        StringBuilder out = new StringBuilder(pattern.length());
        StringBuilder raw = new StringBuilder(slots);
        StringBuilder pending = new StringBuilder();
        int rawBeforeCaret = 0;
        int filled = 0;
        int newCaret = 0;
        boolean caretAfterLiterals = false;
        int entries = 0;
        int at = 0;
        int p = start;
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
                int runStart = p;
                while (p == start && runStart > 0 && !slotAt[runStart - 1]) {
                    runStart--;
                }
                RunRead read = readsLiterals ? afterRun(text, at, runStart, p, end) : new RunRead(at, p);
                if (read.at() > at && read.at() >= text.length()) {
                    // The text ends in the run, which it carries this far: the literals typed by
                    // hand are written now, a keystroke that shows, and the rest wait as ever.
                    for (int r = p; r < read.through(); r++) {
                        out.appendCodePoint(codePoints[r]);
                        entries++;
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
            entries += pending.codePointCount(0, pending.length()) + 1;
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
        if (start == 0 && filled == slots) {
            // No position follows the literals that end the pattern, so they arrive with the last
            // one: a complete value is the pattern's text, "(12)" and not "(12". What the loop
            // already met is in pending; whatever it did not reach is literal too.
            entries += pending.codePointCount(0, pending.length()) + codePoints.length - p;
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
        return new Read(out.toString(), Math.min(newCaret, out.length()), raw.toString(), entries);
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
     * <p>Read from inside the run ({@code runStart < from}), the literals before {@code from} are
     * already written where the text lands, and they are shown: the {@code 1} typed after the
     * {@code +} of {@code +1 (000)} is the literal's.
     *
     * @return where the text goes on, and the end of the run's literals it carried, which is
     *         {@code from} when it carried none
     */
    private RunRead afterRun(String text, int at, int runStart, int from, int to) {
        int next = to < codePoints.length ? codePoints[to] : -1;
        int t = at;
        int through = from;
        boolean refusable = false;
        boolean refusableShown = false;
        boolean whole = true;
        for (int r = runStart; r < from; r++) {
            boolean taken = next >= 0 && accepts(next, codePoints[r]);
            refusable |= !taken;
            refusableShown |= !taken;
        }
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
     * Which chars of a text laid out on the pattern from entry {@code first} on belong to its
     * literals: code point {@code k} of the text is entry {@code first + k}, as in a text this
     * mask wrote ({@code first} 0), its guide, or the guide's unfilled tail. A code point past the
     * pattern's end is not a literal.
     *
     * @param text  the text
     * @param first the entry its first code point stands on
     * @return one flag per char of {@code text}, both chars of a surrogate pair alike
     */
    public boolean[] literals(String text, int first) {
        boolean[] out = new boolean[text.length()];
        int entry = first;
        for (int k = 0; k < text.length(); entry++) {
            int width = Character.charCount(text.codePointAt(k));
            boolean literal = entry >= 0 && entry < codePoints.length && !slotAt[entry];
            for (int w = 0; w < width; w++) {
                out[k + w] = literal;
            }
            k += width;
        }
        return out;
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
