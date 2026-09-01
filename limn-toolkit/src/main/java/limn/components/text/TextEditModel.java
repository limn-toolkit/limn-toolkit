package limn.components.text;

import limn.graphics.ShapedText;
import limn.graphics.ShapedText.Affinity;
import limn.graphics.ShapedText.Position;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, headless text-editing model shared by TextField/PasswordField (single
 * line) and TextArea (multiline): cursor, anchor-based selection, line-aware
 * Home/End and Up/Down with a sticky goal column, bounded undo/redo. Indices
 * are {@code char} offsets into the text; all movement and deletion step by
 * <b>grapheme cluster</b> (regex {@code \X}), so combining marks and ZWJ emoji
 * are never split; a surrogate pair is the simplest such cluster.
 *
 * <p>Line lookups ({@code lineOf}/{@code lineCount}/{@code lineText}/…) are
 * served from a line-start index rebuilt lazily after an edit, so paint-time
 * queries never rescan the whole buffer.
 *
 * <p><b>The caret is an index and a side</b>, {@link #caret()}, because on a direction boundary one
 * index is two points on the screen and only the side says which. The side lives here, beside the
 * cursor, rather than in the widget: every method below that writes {@code cursor} would otherwise
 * need a paired assignment at three call sites apiece, and {@link #undo()} restores a cursor, so it
 * has to restore a side with it — an undo that put a correct index back on yesterday's side draws
 * the caret at the far end of the run, one keystroke later, with nothing to trace it to.
 *
 * <p><b>Two movement axes, and they are not interchangeable.</b> {@link #moveVisualLeft} and
 * {@link #moveVisualRight} are what the arrow keys press: a step left or right <em>on the screen</em>,
 * which needs the shaped line to answer. Everything else here — Home, End, word movement,
 * Backspace, Delete, Up and Down — is <b>logical</b>, a step through the string, because each of
 * them has to name a contiguous range: {@code Shift+Home} makes a selection, and a selection is
 * {@code (anchor, cursor)}. The consequence is stated rather than discovered: in right-to-left text
 * Left and Ctrl+Left move the caret in opposite directions, exactly as they do on Windows and in
 * GTK.
 *
 * <p>Taking a {@link ShapedText} costs this package no dependency it did not have: the type is an
 * immutable value whose accessors return nothing but primitives, {@code String}s and records of
 * those, so this class still draws nothing and knows no widget, and every case below is pinned by a
 * test with no scene, no ruler and no window.
 */
public final class TextEditModel {

    /** One extended grapheme cluster, the unit of caret movement and deletion. */
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    /** Undo depth (snapshots): enough for a long session, bounded for memory. */
    private static final int MAX_UNDO = 200;

    private final StringBuilder buffer = new StringBuilder();
    private final boolean singleLine;
    private int cursor;
    /** Which side of {@link #cursor} the caret draws on; see {@link #caret()}. */
    private Affinity cursorAffinity = Affinity.DOWNSTREAM;
    /**
     * Selection anchor (char index), or -1 when there is no selection. Deliberately a bare
     * {@code int}: its only job is to be one end of the logical range {@code selection(start, end)}
     * takes, nothing draws it, and an index reached by clicking either side of a direction boundary
     * has to select the same characters both times.
     */
    private int anchor = -1;
    /** Sticky column (char offset within line) for Up/Down runs; -1 = unset. */
    private int goalColumn = -1;

    // Line-start cache: starts[i] is the char index where line i begins.
    // Rebuilt lazily (linesDirty) after any buffer mutation.
    private int[] lineStarts = {0};
    private int cachedLineCount = 1;
    private boolean linesDirty;
    private long textVersion;

    // Line damage since the last clearLineDamage(): one splice held precisely, a second edit
    // before the clear widens to the whole document. See lineDamage() for why one is enough.
    private boolean damagePending;
    private boolean damageWhole;
    private int damageFirst;
    private int damageOldLast;
    private int damageNewLast;
    /** Line count when the first pending edit began: the old extent of a whole-document splice. */
    private int damageOldLineCount;

    // Undo/redo: full snapshots pushed before each mutation; runs of plain
    // typing (and runs of deleting) coalesce into a single step.
    private enum EditKind { OTHER, TYPING, DELETING }

    /**
     * The side travels with the cursor. An undo that restored only the index would put a correct
     * caret on the wrong side of a direction boundary, where it draws a whole run away from where
     * the next character lands, and the next arrow press would jump.
     */
    private record Snapshot(String text, int cursor, Affinity affinity, int anchor) {
    }

    private final ArrayDeque<Snapshot> undoStack = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redoStack = new ArrayDeque<>();
    private EditKind lastEdit = EditKind.OTHER;

    /** A model for one line (newlines rejected on insert) or for many. */
    public TextEditModel(boolean singleLine) {
        this.singleLine = singleLine;
    }

    // ------------------------------------------------------------------ text

    /** The whole buffer. */
    public String text() {
        return buffer.toString();
    }

    /** Substring {@code [from, to)} without copying the whole buffer (hot paint paths). */
    public String textRange(int from, int to) {
        return buffer.substring(from, to);
    }

    /** Buffer length in {@code char}s, not in grapheme clusters. */
    public int length() {
        return buffer.length();
    }

    /**
     * Replaces the whole content programmatically; clears the undo history. The caret lands at the
     * end of the buffer on its {@link Affinity#DOWNSTREAM} side, which is the paragraph's own end
     * edge — the visual <em>left</em> for right-to-left content, where the next typed character
     * goes.
     */
    public void setText(String text) {
        noteWholeEdit();
        buffer.setLength(0);
        buffer.append(sanitize(text));
        cursor = buffer.length();
        cursorAffinity = Affinity.DOWNSTREAM;
        anchor = -1;
        goalColumn = -1;
        markTextChanged();
        undoStack.clear();
        redoStack.clear();
        lastEdit = EditKind.OTHER;
    }

    private String sanitize(String raw) {
        String value = raw == null ? "" : raw;
        return singleLine ? value.replace("\r", "").replace("\n", " ") : value.replace("\r", "");
    }

    /** Inserts at the cursor, replacing any selection. */
    public void insert(String raw) {
        insertInternal(raw, EditKind.OTHER);
    }

    /** Committed keyboard input; consecutive calls coalesce into one undo step. */
    public void insertCodePoint(int codepoint) {
        insertInternal(new String(Character.toChars(codepoint)), EditKind.TYPING);
    }

    private void insertInternal(String raw, EditKind kind) {
        String value = sanitize(raw);
        if (value.isEmpty() && !hasSelection()) {
            return; // e.g. pasting an empty clipboard: not an edit, nothing recorded
        }
        remember(hasSelection() ? EditKind.OTHER : kind); // replacing a selection never coalesces
        // One note for the whole splice, delete and insert together: two notes would compose,
        // and composition widens to the whole document (see lineDamage()), turning every
        // type-over-a-selection into a full re-derivation for the view holding per-line state.
        noteEditStart(selectionStart(), selectionEnd());
        deleteSelectionRaw();
        buffer.insert(cursor, value);
        cursor += value.length();
        // The caret TRAILS what was just typed, so the next character of the same script appears
        // where the caret is. That is what UPSTREAM means, and it is why every edit below leaves it.
        cursorAffinity = Affinity.UPSTREAM;
        goalColumn = -1;
        markTextChanged();
        noteEditEnd(cursor);
    }

    /** Deletes the selection, or the grapheme cluster before the cursor. */
    public void backspace() {
        if (hasSelection()) {
            deleteSelection();
            return;
        }
        if (cursor > 0) {
            remember(EditKind.DELETING);
            int previous = previousGrapheme(cursor);
            noteEditStart(previous, cursor);
            buffer.delete(previous, cursor);
            cursor = previous;
            markTextChanged();
            noteEditEnd(cursor);
        }
        cursorAffinity = Affinity.UPSTREAM;
        anchor = -1;
        goalColumn = -1;
    }

    /** Deletes the selection, or the grapheme cluster after the cursor. */
    public void deleteForward() {
        if (hasSelection()) {
            deleteSelection();
            return;
        }
        if (cursor < buffer.length()) {
            remember(EditKind.DELETING);
            int next = nextGrapheme(cursor);
            noteEditStart(cursor, next);
            buffer.delete(cursor, next);
            markTextChanged();
            noteEditEnd(cursor);
        }
        cursorAffinity = Affinity.UPSTREAM;
        anchor = -1;
        goalColumn = -1;
    }

    // ------------------------------------------------------------- selection

    /** Whether a non-empty range is selected. */
    public boolean hasSelection() {
        return anchor >= 0 && anchor != cursor;
    }

    /** Lower selection bound; equals {@link #cursor()} when nothing is selected. */
    public int selectionStart() {
        return hasSelection() ? Math.min(anchor, cursor) : cursor;
    }

    /** Upper selection bound; equals {@link #cursor()} when nothing is selected. */
    public int selectionEnd() {
        return hasSelection() ? Math.max(anchor, cursor) : cursor;
    }

    /** The selected text, or an empty string when there is no selection. */
    public String selectedText() {
        return hasSelection() ? buffer.substring(selectionStart(), selectionEnd()) : "";
    }

    /** @return whether a selection existed and was removed */
    public boolean deleteSelection() {
        if (!hasSelection()) {
            anchor = -1;
            return false;
        }
        remember(EditKind.OTHER);
        noteEditStart(selectionStart(), selectionEnd());
        deleteSelectionRaw();
        noteEditEnd(cursor);
        return true;
    }

    /** Selection removal without recording; the caller has already snapshotted. */
    private void deleteSelectionRaw() {
        if (!hasSelection()) {
            anchor = -1;
            return;
        }
        int start = selectionStart();
        buffer.delete(start, selectionEnd());
        cursor = start;
        cursorAffinity = Affinity.UPSTREAM;
        anchor = -1;
        goalColumn = -1;
        markTextChanged();
    }

    /** Selects the whole buffer and leaves the cursor at its end. */
    public void selectAll() {
        anchor = 0;
        cursor = buffer.length();
        cursorAffinity = Affinity.DOWNSTREAM;
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /** Drops the selection, leaving the cursor where it is. */
    public void clearSelection() {
        anchor = -1;
    }

    // ------------------------------------------------------------- undo/redo

    /** Records the current state as an undo step (unless coalescing with the last). */
    private void remember(EditKind kind) {
        boolean coalesce = kind != EditKind.OTHER && kind == lastEdit && !undoStack.isEmpty();
        if (!coalesce) {
            undoStack.push(new Snapshot(text(), cursor, cursorAffinity, anchor));
            while (undoStack.size() > MAX_UNDO) {
                undoStack.removeLast();
            }
        }
        redoStack.clear();
        lastEdit = kind;
    }

    /** @return whether there was anything to undo */
    public boolean undo() {
        if (undoStack.isEmpty()) {
            return false;
        }
        redoStack.push(new Snapshot(text(), cursor, cursorAffinity, anchor));
        restore(undoStack.pop());
        return true;
    }

    /** @return whether there was anything to redo */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        undoStack.push(new Snapshot(text(), cursor, cursorAffinity, anchor));
        restore(redoStack.pop());
        return true;
    }

    /** Whether an undo step is available. */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** Whether a redo step is available; any new edit discards the redo stack. */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    private void restore(Snapshot snapshot) {
        noteWholeEdit();
        buffer.setLength(0);
        buffer.append(snapshot.text());
        cursor = snapshot.cursor();
        cursorAffinity = snapshot.affinity();
        anchor = snapshot.anchor();
        goalColumn = -1;
        markTextChanged();
        lastEdit = EditKind.OTHER;
    }

    // ---------------------------------------------------------------- cursor

    /** Caret position as a {@code char} offset, always on a grapheme boundary. */
    public int cursor() {
        return cursor;
    }

    /**
     * The caret: the insertion index and which side of it the caret is on. The index alone is what
     * an edit, the clipboard and the IME need; the pair is what the <em>line</em> needs, because on
     * a direction boundary one index is two points on the screen and a caret drawn at the wrong one
     * tells the user something false about where their next character lands.
     *
     * @return where the caret is, index and side; never null
     */
    public Position caret() {
        return new Position(cursor, cursorAffinity);
    }

    /**
     * Places the cursor; {@code select} extends/starts a selection from the old spot. The caret
     * takes {@link Affinity#DOWNSTREAM}, the side a programmatic placement has nothing better to go
     * on than. A caller that <em>does</em> know the side — a click, a drag, a visual arrow — calls
     * {@link #setCaret} instead.
     *
     * @param index  where the caret goes, clamped into the buffer
     * @param select whether this extends a selection
     */
    public void setCursor(int index, boolean select) {
        int clamped = Math.max(0, Math.min(index, buffer.length()));
        updateAnchor(select);
        cursor = clamped;
        cursorAffinity = Affinity.DOWNSTREAM;
        goalColumn = -1;
        lastEdit = EditKind.OTHER; // a caret jump ends a typing/deleting run
    }

    /**
     * Places the caret, side included: what a click, a drag and a visual arrow all produce.
     * {@code select} extends or starts a selection from the old spot, exactly as {@link #setCursor}.
     *
     * @param position where the caret goes; its index is clamped into the buffer
     * @param select   whether this extends a selection
     * @throws NullPointerException if {@code position} is null
     */
    public void setCaret(Position position, boolean select) {
        Objects.requireNonNull(position, "position");
        int clamped = Math.max(0, Math.min(position.charIndex(), buffer.length()));
        updateAnchor(select);
        cursor = clamped;
        cursorAffinity = position.affinity();
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    private void updateAnchor(boolean select) {
        if (select) {
            if (anchor < 0) {
                anchor = cursor;
            }
        } else {
            anchor = -1;
        }
    }

    /**
     * Moves the caret one grapheme cluster left <b>in the string</b>, extending the selection when
     * {@code select} is set and collapsing it to the left edge when it is not. This is the
     * <em>logical</em> step, which is not what the Left arrow key does once anything reorders:
     * {@link #moveVisualLeft} is that.
     *
     * @param select whether this extends a selection
     */
    public void moveLeft(boolean select) {
        if (!select && hasSelection()) {
            cursor = selectionStart();
            anchor = -1;
        } else {
            updateAnchor(select);
            if (cursor > 0) {
                cursor = previousGrapheme(cursor);
            }
        }
        // Backward motion by one unit lands on the LEADING edge of what it stopped before.
        cursorAffinity = Affinity.DOWNSTREAM;
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /**
     * Moves the caret one grapheme cluster right <b>in the string</b>, extending the selection when
     * {@code select} is set and collapsing it to the right edge when it is not. The logical mirror
     * of {@link #moveLeft}, and not the Right arrow key; see {@link #moveVisualRight}.
     *
     * @param select whether this extends a selection
     */
    public void moveRight(boolean select) {
        if (!select && hasSelection()) {
            cursor = selectionEnd();
            anchor = -1;
        } else {
            updateAnchor(select);
            if (cursor < buffer.length()) {
                cursor = nextGrapheme(cursor);
            }
        }
        // Forward motion by one unit lands TRAILING what it just passed.
        cursorAffinity = Affinity.UPSTREAM;
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /**
     * Start of the current line (start of text on single-line models), in <b>logical</b> order —
     * so in a right-to-left paragraph this moves the caret to the visual <em>right</em>. That is
     * what Windows edit controls, GTK's {@code DISPLAY_LINE_ENDS} movement and Cocoa's
     * {@code moveToBeginningOfLine:} all do, and it is forced anyway: {@code Shift+Home} has to
     * produce a selection, a selection is one contiguous range of the string, and the range from
     * the caret to the visual left edge of a mixed line is not one.
     *
     * @param select whether this extends a selection
     */
    public void moveHome(boolean select) {
        updateAnchor(select);
        cursor = lineStart(cursor);
        // An edge jump takes the side that names the PARAGRAPH's own edge, not the leading edge of
        // whichever cluster happens to sit first: on a line beginning with an embedded run those
        // are different points, and the other one is where Home visibly lands wrong.
        cursorAffinity = Affinity.UPSTREAM;
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /**
     * End of the current line (end of text on single-line models), in <b>logical</b> order: the
     * mirror of {@link #moveHome}, so in a right-to-left paragraph it moves the caret to the visual
     * <em>left</em>.
     *
     * @param select whether this extends a selection
     */
    public void moveEnd(boolean select) {
        updateAnchor(select);
        cursor = lineEnd(cursor);
        cursorAffinity = Affinity.DOWNSTREAM; // the paragraph's own end edge; see moveHome
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    // ------------------------------------------------------- visual movement

    /**
     * Moves the caret one step <b>left on the screen</b>, over {@code line}: the Left arrow,
     * whatever direction the text under it runs.
     *
     * <p>{@code line} is the shaped form of the line the cursor sits on and {@code lineStart} is the
     * buffer index that line begins at, so a single-line model passes {@code 0}. A cursor outside
     * the line is clamped into it rather than rejected: a caret restored from a stale view has to
     * produce a position, not an exception.
     *
     * <p>With a selection and {@code select == false} this collapses to {@link #selectionStart()}
     * and moves no further, which is deliberately the LOGICAL end: a selection can span lines, its
     * two ends can sit in different runs, and the visually left end of a multi-line range is not
     * defined. On a line that reorders nothing the two answers coincide.
     *
     * @param line      the shaped line the cursor is on
     * @param lineStart buffer index where that line starts
     * @param select    whether this extends a selection
     * @return whether anything moved; {@code false} means the caret was already at the line's left
     *         edge and a multi-line caller should change line
     * @throws NullPointerException if {@code line} is null
     */
    public boolean moveVisualLeft(ShapedText line, int lineStart, boolean select) {
        Objects.requireNonNull(line, "line");
        if (!select && hasSelection()) {
            cursor = selectionStart();
            cursorAffinity = Affinity.DOWNSTREAM;
            anchor = -1;
            goalColumn = -1;
            lastEdit = EditKind.OTHER;
            // True even though no arrow step was taken: the caret DID move, and a caller that
            // read false here would collapse the selection and hop to the previous line at once.
            return true;
        }
        return step(line, lineStart, select, true);
    }

    /**
     * Moves the caret one step <b>right on the screen</b>: the mirror of {@link #moveVisualLeft} in
     * every respect, collapsing a selection to {@link #selectionEnd()} instead.
     *
     * @param line      the shaped line the cursor is on
     * @param lineStart buffer index where that line starts
     * @param select    whether this extends a selection
     * @return whether anything moved; {@code false} means the caret was already at the line's right
     *         edge and a multi-line caller should change line
     * @throws NullPointerException if {@code line} is null
     */
    public boolean moveVisualRight(ShapedText line, int lineStart, boolean select) {
        Objects.requireNonNull(line, "line");
        if (!select && hasSelection()) {
            cursor = selectionEnd();
            cursorAffinity = Affinity.UPSTREAM;
            anchor = -1;
            goalColumn = -1;
            lastEdit = EditKind.OTHER;
            return true;
        }
        return step(line, lineStart, select, false);
    }

    /** One visual arrow step over {@code line}; the two public forms differ only in direction. */
    private boolean step(ShapedText line, int lineStart, boolean select, boolean left) {
        updateAnchor(select);
        lastEdit = EditKind.OTHER;
        goalColumn = -1;
        int local = Math.max(0, Math.min(cursor - lineStart, line.text().length()));
        Position from = new Position(local, cursorAffinity);
        Position to = left ? line.caretLeft(from) : line.caretRight(from);
        // Write the clamped position back either way. The step may have found nothing while the
        // clamp still moved the caret onto this line, and leaving a stale off-line index behind
        // would make the NEXT press step from somewhere the user cannot see.
        cursor = Math.max(0, Math.min(lineStart + to.charIndex(), buffer.length()));
        cursorAffinity = to.affinity();
        return !to.equals(from);
    }

    /** Up one line, keeping the sticky goal column. No-op on single-line models. */
    public void moveUp(boolean select) {
        moveVertically(select, -1);
    }

    /** Down one line, keeping the sticky goal column. */
    public void moveDown(boolean select) {
        moveVertically(select, +1);
    }

    private void moveVertically(boolean select, int direction) {
        if (singleLine) {
            return;
        }
        updateAnchor(select);
        lastEdit = EditKind.OTHER;
        // A goal column is a programmatic placement on the target line, so it takes DOWNSTREAM on
        // every path out of here, the early returns at the first and last line included.
        cursorAffinity = Affinity.DOWNSTREAM;
        int start = lineStart(cursor);
        if (goalColumn < 0) {
            goalColumn = cursor - start;
        }
        int targetStart;
        if (direction < 0) {
            if (start == 0) {
                cursor = 0;
                goalColumn = -1;
                return;
            }
            targetStart = lineStart(start - 1);
        } else {
            int end = lineEnd(cursor);
            if (end >= buffer.length()) {
                cursor = buffer.length();
                goalColumn = -1;
                return;
            }
            targetStart = end + 1;
        }
        int targetEnd = lineEnd(targetStart);
        // Never land inside a grapheme cluster (surrogate pair, combining run).
        cursor = alignToGrapheme(Math.min(targetStart + goalColumn, targetEnd));
    }

    // ------------------------------------------------------- word / document

    /**
     * Previous word boundary: Ctrl/Alt+Left. Extends the selection when {@code select}.
     *
     * <p><b>Logical, and it stays logical</b>: {@link #deleteWordBackward()} has to remove a
     * contiguous range of the string, so the boundary this lands on has to be the end of one. In
     * right-to-left text that means Ctrl+Left and {@link #moveVisualLeft} move the caret in
     * opposite directions, which is what Windows and GTK do and the lesser of the two evils.
     *
     * @param select whether this extends a selection
     */
    public void moveWordLeft(boolean select) {
        updateAnchor(select);
        cursor = alignToGrapheme(previousWordBoundary(cursor));
        cursorAffinity = Affinity.DOWNSTREAM; // backward motion by one unit; see moveLeft
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /**
     * Next word boundary: Ctrl/Alt+Right. Extends the selection when {@code select}. Logical, for
     * the reason {@link #moveWordLeft} gives.
     *
     * @param select whether this extends a selection
     */
    public void moveWordRight(boolean select) {
        updateAnchor(select);
        // Forward motion snaps a mid-cluster boundary UP to the cluster end;
        // snapping down would land at or before the cursor and stall forever
        // (a char-class boundary can fall inside a cluster: NFD accents, keycaps).
        cursor = alignToGraphemeForward(nextWordBoundary(cursor));
        cursorAffinity = Affinity.UPSTREAM; // forward motion by one unit; see moveRight
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /**
     * Start of the whole text: Ctrl+Home / Cmd+Up. Logical, and so the visual right edge of a
     * right-to-left first line; see {@link #moveHome}.
     *
     * @param select whether this extends a selection
     */
    public void moveDocumentStart(boolean select) {
        updateAnchor(select);
        cursor = 0;
        cursorAffinity = Affinity.UPSTREAM; // an edge jump; see moveHome
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /**
     * End of the whole text: Ctrl+End / Cmd+Down. Logical; see {@link #moveEnd}.
     *
     * @param select whether this extends a selection
     */
    public void moveDocumentEnd(boolean select) {
        updateAnchor(select);
        cursor = buffer.length();
        cursorAffinity = Affinity.DOWNSTREAM; // an edge jump; see moveEnd
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /** Deletes the selection, or from the cursor back to the previous word boundary. */
    public void deleteWordBackward() {
        if (hasSelection()) {
            deleteSelection();
            return;
        }
        if (cursor > 0) {
            remember(EditKind.OTHER);
            int start = alignToGrapheme(previousWordBoundary(cursor));
            noteEditStart(start, cursor);
            buffer.delete(start, cursor);
            cursor = start;
            markTextChanged();
            noteEditEnd(cursor);
        }
        cursorAffinity = Affinity.UPSTREAM; // an edit, not a motion
        anchor = -1;
        goalColumn = -1;
    }

    /** Deletes the selection, or from the cursor forward to the next word boundary. */
    public void deleteWordForward() {
        if (hasSelection()) {
            deleteSelection();
            return;
        }
        if (cursor < buffer.length()) {
            int end = alignToGraphemeForward(nextWordBoundary(cursor));
            if (end > cursor) { // only snapshot undo state for a real edit
                remember(EditKind.OTHER);
                noteEditStart(cursor, end);
                buffer.delete(cursor, end);
                markTextChanged();
                noteEditEnd(cursor);
            }
        }
        cursorAffinity = Affinity.UPSTREAM; // an edit, not a motion
        anchor = -1;
        goalColumn = -1;
    }

    private enum CharClass { WHITESPACE, WORD, OTHER }

    /** By CODE POINT: a per-char version classes every supplementary-plane
     *  letter (its surrogates) as punctuation and breaks words at them. */
    private static CharClass classOf(int cp) {
        if (Character.isWhitespace(cp)) {
            return CharClass.WHITESPACE;
        }
        return Character.isLetterOrDigit(cp) || cp == '_' ? CharClass.WORD : CharClass.OTHER;
    }

    /** Combining marks take their base's class: they never open or break a run. */
    private static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /** @return the next word boundary at or after {@code from}: skip whitespace, then one same-class run */
    public int nextWordBoundary(int from) {
        int i = Math.max(0, Math.min(from, buffer.length()));
        while (i < buffer.length()) {
            int cp = Character.codePointAt(buffer, i);
            if (classOf(cp) != CharClass.WHITESPACE) {
                break;
            }
            i += Character.charCount(cp);
        }
        if (i < buffer.length()) {
            CharClass run = classOf(Character.codePointAt(buffer, i));
            while (i < buffer.length()) {
                int cp = Character.codePointAt(buffer, i);
                if (classOf(cp) != run && !isMark(cp)) {
                    break;
                }
                i += Character.charCount(cp);
            }
        }
        return i;
    }

    /** @return the word boundary at or before {@code from}: skip whitespace back, then one same-class run */
    public int previousWordBoundary(int from) {
        int i = Math.max(0, Math.min(from, buffer.length()));
        while (i > 0) {
            int cp = Character.codePointBefore(buffer, i);
            if (classOf(cp) != CharClass.WHITESPACE) {
                break;
            }
            i -= Character.charCount(cp);
        }
        if (i > 0) {
            // Going backward the marks come before their base: the run's class
            // is the first non-mark code point behind the cursor.
            int probe = i;
            int cp = Character.codePointBefore(buffer, probe);
            while (isMark(cp) && probe - Character.charCount(cp) > 0) {
                probe -= Character.charCount(cp);
                cp = Character.codePointBefore(buffer, probe);
            }
            CharClass run = classOf(cp);
            while (i > 0) {
                int back = Character.codePointBefore(buffer, i);
                if (classOf(back) != run && !isMark(back)) {
                    break;
                }
                i -= Character.charCount(back);
            }
        }
        return i;
    }

    // ------------------------------------------------------------- graphemes

    /** @return the grapheme boundary after {@code index} (≤ {@code length()}) */
    public int nextGrapheme(int index) {
        int clamped = Math.max(0, Math.min(index, buffer.length()));
        if (clamped >= buffer.length()) {
            return buffer.length();
        }
        Matcher matcher = GRAPHEME.matcher(buffer);
        matcher.region(clamped, buffer.length());
        return matcher.find() ? matcher.end() : buffer.length();
    }

    /** @return the start of the grapheme cluster ending at or containing {@code index} (≥ 0) */
    public int previousGrapheme(int index) {
        int clamped = Math.max(0, Math.min(index, buffer.length()));
        if (clamped <= 0) {
            return 0;
        }
        // Clusters never span '\n', and the window bound keeps one keypress
        // O(1) instead of O(cursor) on a long single-line document.
        int scan = graphemeScanStart(clamped, lineStart(clamped - 1));
        Matcher matcher = GRAPHEME.matcher(buffer);
        matcher.region(scan, buffer.length());
        while (matcher.find()) {
            if (matcher.end() >= clamped) {
                return matcher.start();
            }
        }
        return scan;
    }

    /** Snaps {@code index} forward to the cluster boundary at or after it (forward motion). */
    public int alignToGraphemeForward(int index) {
        int clamped = Math.max(0, Math.min(index, buffer.length()));
        int start = alignToGrapheme(clamped);
        return start == clamped ? clamped : nextGrapheme(start);
    }

    /** Snaps {@code index} to the start of the cluster containing it (hit testing). */
    public int alignToGrapheme(int index) {
        int clamped = Math.max(0, Math.min(index, buffer.length()));
        if (clamped <= 0 || clamped >= buffer.length()) {
            return clamped;
        }
        int scan = graphemeScanStart(clamped, lineStart(clamped));
        Matcher matcher = GRAPHEME.matcher(buffer);
        matcher.region(scan, buffer.length());
        while (matcher.find()) {
            if (matcher.end() > clamped) {
                return matcher.start(); // == clamped when already a boundary
            }
        }
        return clamped;
    }

    /** Longest window a single backward cluster scan pays for; it opens earlier
     *  only to escape continuation characters, so correctness never depends on it. */
    private static final int GRAPHEME_SCAN_WINDOW = 64;

    /** A bounded start (≥ {@code bound}) for a cluster scan that must cover {@code clamped}. */
    private int graphemeScanStart(int clamped, int bound) {
        int start = clamped - GRAPHEME_SCAN_WINDOW;
        if (start <= bound) {
            return bound;
        }
        // Never open the match region inside a cluster: back out of continuation
        // characters (combining marks, joiner tails, variation selectors, keycaps,
        // skin tones, low surrogates) and out of regional-indicator (flag) runs,
        // whose pairing depends on the run's true start. Pathological runs
        // (zalgo) degrade to the full line-start scan, never to a wrong answer.
        while (start > bound && !safeClusterStart(start)) {
            start--;
        }
        return start;
    }

    private boolean safeClusterStart(int i) {
        char c = buffer.charAt(i);
        if (Character.isLowSurrogate(c)) {
            return false;
        }
        int cp = Character.codePointAt(buffer, i);
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) {
            return false;
        }
        if (cp == 0x200D || (cp >= 0xFE00 && cp <= 0xFE0F) || cp == 0x20E3
                || (cp >= 0x1F3FB && cp <= 0x1F3FF) || isRegionalIndicator(cp)) {
            return false;
        }
        return buffer.charAt(i - 1) != 0x200D; // joined to the previous cluster
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    // ----------------------------------------------------------------- lines

    /** @return char index of the start of the line containing {@code index} */
    public int lineStart(int index) {
        int i = Math.max(0, Math.min(index, buffer.length()));
        return lineStartOfLine(lineOf(i));
    }

    /** @return char index of the end of the line containing {@code index} (before the newline) */
    public int lineEnd(int index) {
        int i = Math.max(0, Math.min(index, buffer.length()));
        int line = lineOf(i);
        return line + 1 < lineCount() ? lineStarts[line + 1] - 1 : buffer.length();
    }

    /** @return zero-based line number of {@code index} */
    public int lineOf(int index) {
        ensureLines();
        int limit = Math.max(0, Math.min(index, buffer.length()));
        // Binary search: the last line whose start is <= limit.
        int lo = 0;
        int hi = cachedLineCount - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= limit) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Number of lines; always at least 1, and 1 for a single-line model. */
    public int lineCount() {
        ensureLines();
        return cachedLineCount;
    }

    /** @return char index where the zero-based {@code line} starts */
    public int lineStartOfLine(int line) {
        ensureLines();
        return lineStarts[Math.max(0, Math.min(line, cachedLineCount - 1))];
    }

    /** @return the text of the zero-based {@code line} (no trailing newline) */
    public String lineText(int line) {
        ensureLines();
        if (line < 0 || line >= cachedLineCount) {
            return "";
        }
        int start = lineStarts[line];
        int end = line + 1 < cachedLineCount ? lineStarts[line + 1] - 1 : buffer.length();
        return buffer.substring(start, end);
    }

    /**
     * Bumped by every mutation of the buffer, and by nothing else: a cursor move or a selection
     * change leaves it alone. A view caching anything derived from the text (materialized lines,
     * measured widths) compares this rather than re-deriving: it is the difference between a
     * caret blink repainting and a caret blink rebuilding the screenful it repaints.
     *
     * @return a value that changes whenever the text does; meaningless except compared to itself
     */
    public long textVersion() {
        return textVersion;
    }

    /**
     * The lines edits have replaced: lines {@code [firstLine, oldLastLine]} of the text as it
     * stood at the last {@link #clearLineDamage()} became lines {@code [firstLine, newLastLine]}
     * of the text as it stands now. Lines before {@code firstLine} are untouched; lines after
     * {@code oldLastLine} are untouched too, shifted down to follow {@code newLastLine}.
     *
     * <p>Exists for a view deriving <em>per-line</em> state from this buffer &mdash; a soft-wrap
     * row map is the one that forced it. {@link #textVersion()} says <em>that</em> the text moved;
     * for a per-line derivation that answer alone means re-deriving every line, and re-deriving a
     * line means re-shaping it, so a keystroke in a long document would re-shape the document
     * (the cliff ADR&nbsp;031&nbsp;&sect;8.2 measured at 22&nbsp;ms per character typed). This says
     * <em>which</em> lines, which turns the keystroke back into work proportional to the edit.
     */
    public record LineDamage(int firstLine, int oldLastLine, int newLastLine) {
    }

    /**
     * What the edits since the last {@link #clearLineDamage()} touched, or {@code null} when the
     * text has not changed since. One splice is held precisely; a second edit before the clear
     * widens the answer to the whole document rather than composing, because the consumer this
     * exists for syncs after every edit &mdash; the widget's change handler runs before the next
     * one can land &mdash; so composition is the rare path, and a conservative whole-document
     * answer there is a correct re-derivation, never a wrong splice. {@link #setText} and
     * {@link #undo}/{@link #redo} answer the whole document for the same reason: what they
     * replace is unbounded.
     */
    public LineDamage lineDamage() {
        if (!damagePending) {
            return null;
        }
        if (damageWhole) {
            return new LineDamage(0, damageOldLineCount - 1, lineCount() - 1);
        }
        return new LineDamage(damageFirst, damageOldLast, damageNewLast);
    }

    /** Forgets the recorded damage: the consumer has re-derived what it holds. */
    public void clearLineDamage() {
        damagePending = false;
        damageWhole = false;
    }

    /**
     * Records the line range an edit is about to replace. Called <b>before</b> the buffer moves,
     * because the range is a fact about the old text; {@link #noteEditEnd} closes it after.
     */
    private void noteEditStart(int start, int oldEnd) {
        if (damagePending) {
            // A second edit before the consumer cleared: widen rather than compose. The old
            // extent captured at the first edit still bounds everything both edits replaced.
            damageWhole = true;
            return;
        }
        damagePending = true;
        damageOldLineCount = lineCount();
        damageFirst = lineOf(start);
        damageOldLast = lineOf(oldEnd);
    }

    /** Closes the note {@link #noteEditStart} opened, after the buffer (and its lines) moved. */
    private void noteEditEnd(int newEnd) {
        if (!damageWhole) {
            damageNewLast = lineOf(newEnd);
        }
    }

    /** An edit with no useful bound — setText, undo, redo — damages the whole document. */
    private void noteWholeEdit() {
        if (!damagePending) {
            damagePending = true;
            damageOldLineCount = lineCount();
        }
        damageWhole = true;
    }

    private void markTextChanged() {
        linesDirty = true;
        textVersion++;
    }

    /** Rebuilds the line-start index after an edit (lazy, once per mutation). */
    private void ensureLines() {
        if (!linesDirty && lineStarts.length > 0) {
            return;
        }
        int count = 1;
        for (int i = 0; i < buffer.length(); i++) {
            if (buffer.charAt(i) == '\n') {
                count++;
            }
        }
        if (lineStarts.length < count) {
            lineStarts = new int[Math.max(count, lineStarts.length * 2)];
        }
        lineStarts[0] = 0;
        int line = 1;
        for (int i = 0; i < buffer.length(); i++) {
            if (buffer.charAt(i) == '\n') {
                lineStarts[line++] = i + 1;
            }
        }
        cachedLineCount = count;
        linesDirty = false;
    }
}
