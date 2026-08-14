package limn.scene.event;

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
 * <p>The composing text is split into styled <b>blocks</b> (clauses the IME
 * draws differently); {@link #blockSizes()} gives each block's length in code
 * points, tiling {@link #text()} in order, and {@link #focusedBlock()} marks the
 * one currently being converted. Indices carried here ({@code caret},
 * block sizes) are in <b>code points</b>, matching the platform IME.
 */
public final class PreeditEvent extends InputEvent {

    private final String text;
    private final int[] blockSizes;
    private final int focusedBlock;
    private final int caret;

    /**
     * An IME composition update. {@code blockSizes} splits {@code text} into conversion
     * blocks, {@code focusedBlock} is the one being converted, and the caret is a char
     * offset into {@code text}. Empty text ends the composition.
     */
    public PreeditEvent(String text, int[] blockSizes, int focusedBlock, int caret) {
        this.text = text == null ? "" : text;
        this.blockSizes = blockSizes == null ? new int[0] : blockSizes.clone();
        this.focusedBlock = focusedBlock;
        this.caret = caret;
    }

    /** The composing text; empty when the composition ends (commit or cancel). */
    public String text() {
        return text;
    }

    /** Length in <b>code points</b> of each styled block; blocks tile {@link #text()} in order. */
    public int[] blockSizes() {
        return blockSizes.clone();
    }

    /** Index into {@link #blockSizes()} of the block being converted, or {@code -1} if none. */
    public int focusedBlock() {
        return focusedBlock;
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
        return "PreeditEvent[\"" + text + "\" caret=" + caret + " focus=" + focusedBlock + "]";
    }
}
