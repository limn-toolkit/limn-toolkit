package limn.components.text;

import java.util.ArrayDeque;
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
 */
public final class TextEditModel {

    /** One extended grapheme cluster, the unit of caret movement and deletion. */
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    /** Undo depth (snapshots): enough for a long session, bounded for memory. */
    private static final int MAX_UNDO = 200;

    private final StringBuilder buffer = new StringBuilder();
    private final boolean singleLine;
    private int cursor;
    /** Selection anchor (char index), or -1 when there is no selection. */
    private int anchor = -1;
    /** Sticky column (char offset within line) for Up/Down runs; -1 = unset. */
    private int goalColumn = -1;

    // Line-start cache: starts[i] is the char index where line i begins.
    // Rebuilt lazily (linesDirty) after any buffer mutation.
    private int[] lineStarts = {0};
    private int cachedLineCount = 1;
    private boolean linesDirty;
    private long textVersion;

    // Undo/redo: full snapshots pushed before each mutation; runs of plain
    // typing (and runs of deleting) coalesce into a single step.
    private enum EditKind { OTHER, TYPING, DELETING }

    private record Snapshot(String text, int cursor, int anchor) {
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

    /** Replaces the whole content programmatically; clears the undo history. */
    public void setText(String text) {
        buffer.setLength(0);
        buffer.append(sanitize(text));
        cursor = buffer.length();
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
        deleteSelectionRaw();
        buffer.insert(cursor, value);
        cursor += value.length();
        goalColumn = -1;
        markTextChanged();
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
            buffer.delete(previous, cursor);
            cursor = previous;
            markTextChanged();
        }
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
            buffer.delete(cursor, nextGrapheme(cursor));
            markTextChanged();
        }
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
        deleteSelectionRaw();
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
        anchor = -1;
        goalColumn = -1;
        markTextChanged();
    }

    /** Selects the whole buffer and leaves the cursor at its end. */
    public void selectAll() {
        anchor = 0;
        cursor = buffer.length();
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
            undoStack.push(new Snapshot(text(), cursor, anchor));
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
        redoStack.push(new Snapshot(text(), cursor, anchor));
        restore(undoStack.pop());
        return true;
    }

    /** @return whether there was anything to redo */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        undoStack.push(new Snapshot(text(), cursor, anchor));
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
        buffer.setLength(0);
        buffer.append(snapshot.text());
        cursor = snapshot.cursor();
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

    /** Places the cursor; {@code select} extends/starts a selection from the old spot. */
    public void setCursor(int index, boolean select) {
        int clamped = Math.max(0, Math.min(index, buffer.length()));
        updateAnchor(select);
        cursor = clamped;
        goalColumn = -1;
        lastEdit = EditKind.OTHER; // a caret jump ends a typing/deleting run
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
     * Moves the caret one grapheme cluster left, extending the selection when
     * {@code select} is set and collapsing it to the left edge when it is not.
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
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /**
     * Moves the caret one grapheme cluster right, extending the selection when
     * {@code select} is set and collapsing it to the right edge when it is not.
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
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /** Start of the current line (start of text on single-line models). */
    public void moveHome(boolean select) {
        updateAnchor(select);
        cursor = lineStart(cursor);
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /** End of the current line (end of text on single-line models). */
    public void moveEnd(boolean select) {
        updateAnchor(select);
        cursor = lineEnd(cursor);
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
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

    /** Previous word boundary: Ctrl/Alt+Left. Extends the selection when {@code select}. */
    public void moveWordLeft(boolean select) {
        updateAnchor(select);
        cursor = alignToGrapheme(previousWordBoundary(cursor));
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /** Next word boundary: Ctrl/Alt+Right. Extends the selection when {@code select}. */
    public void moveWordRight(boolean select) {
        updateAnchor(select);
        // Forward motion snaps a mid-cluster boundary UP to the cluster end;
        // snapping down would land at or before the cursor and stall forever
        // (a char-class boundary can fall inside a cluster: NFD accents, keycaps).
        cursor = alignToGraphemeForward(nextWordBoundary(cursor));
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /** Start of the whole text: Ctrl+Home / Cmd+Up. */
    public void moveDocumentStart(boolean select) {
        updateAnchor(select);
        cursor = 0;
        goalColumn = -1;
        lastEdit = EditKind.OTHER;
    }

    /** End of the whole text: Ctrl+End / Cmd+Down. */
    public void moveDocumentEnd(boolean select) {
        updateAnchor(select);
        cursor = buffer.length();
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
            buffer.delete(start, cursor);
            cursor = start;
            markTextChanged();
        }
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
                buffer.delete(cursor, end);
                markTextChanged();
            }
        }
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
