package limn.scene.event;

import org.jspecify.annotations.Nullable;

/**
 * In-progress IME composition ("preedit"): the text an input method is still
 * composing, shown inline with an underline but <em>not</em> committed to the
 * editing model. The committed result arrives later as ordinary
 * {@link CharEvent}s; preedit is purely the transient overlay shown while the
 * user is composing (e.g. typing romaji/pinyin before picking a candidate).
 *
 * <p>An {@linkplain #isEmpty() empty} event clears the composition (the user
 * committed or cancelled it). Routed to the focused widget only; a preedit
 * with nothing focused is dropped.
 *
 * <p>Part of the composing text may be the <b>target</b>: the stretch the input method is
 * converting now, which the user picks a candidate for, drawn differently from the rest.
 * {@link #targetStart()} and {@link #targetLength()} say where it is. Every index carried here
 * ({@code caret}, the target) is in <b>code points</b>, matching the platform IME.
 */
public final class PreeditEvent extends InputEvent {

    private final String text;
    private final int caret;
    private final int targetStart;
    private final int targetLength;

    /**
     * An IME composition update. Empty text ends the composition.
     *
     * @param text         the composing text; {@code null} is empty
     * @param caret        caret position within {@code text}, in code points
     * @param targetStart  where the stretch being converted starts, in code points
     * @param targetLength its length in code points; {@code 0} when nothing is being converted
     */
    public PreeditEvent(@Nullable String text, int caret, int targetStart, int targetLength) {
        this.text = text == null ? "" : text;
        this.caret = caret;
        this.targetStart = targetStart;
        this.targetLength = targetLength;
    }

    /** The composing text; empty when the composition ends (commit or cancel). */
    public String text() {
        return text;
    }

    /** Where the stretch being converted starts within {@link #text()}, in <b>code points</b>. */
    public int targetStart() {
        return targetStart;
    }

    /** How long the stretch being converted is, in <b>code points</b>; {@code 0} when there is none. */
    public int targetLength() {
        return targetLength;
    }

    /** Caret position within {@link #text()}, in <b>code points</b>. */
    public int caret() {
        return caret;
    }

    /** @return whether the composition is empty (i.e. this event clears the preedit) */
    public boolean isEmpty() {
        return text.isEmpty();
    }

    @Override
    public String toString() {
        return "PreeditEvent[\"" + text + "\" caret=" + caret + " target=" + targetStart + "+" + targetLength + "]";
    }
}
