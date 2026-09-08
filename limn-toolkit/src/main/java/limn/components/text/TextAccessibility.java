package limn.components.text;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.graphics.Rect;
import limn.graphics.ShapedText;

/**
 * What a text editor tells a screen reader and what it lets one do, shared by the single-line
 * field and the multi-line area.
 *
 * <p>The two widgets are siblings and not parent and child, so the publish of the text facet,
 * the four verbs a reader may post and the caret placement they share were once written out in
 * each; this is that code once, over the {@link Host} the two both are. The differences that are
 * real stay in the widgets: how the composed line is spliced, where the caret box is measured,
 * and the area's sticky goal column, which is one line in its {@link Host#caretMoved()}.
 *
 * <p><b>The text published while composing is the composed string</b>, the committed text with
 * the preedit spliced in at the caret, with the caret at {@link Host#composedCaretIndex()} on its
 * upstream side and a collapsed selection, because a composition paints no selection band. The
 * model's own text and caret would describe a different string from the one on the screen.
 *
 * <p><b>Every verb goes through the path the user's own gesture takes.</b> {@code SET_TEXT} is
 * select-all-then-insert and never {@code setText(String)}: that one is silent, so a reader
 * replacing the value would change the text and tell the application nothing, and it clears the
 * undo history. Select-all-then-insert handles the empty case correctly too, since an insert of
 * {@code ""} with a selection deletes it. The model decides what a newline becomes: the
 * single-line model sanitizes it to a space and a bridge has to re-read, the multi-line one keeps
 * it. {@code SHOW_MENU} focuses first, exactly as a right-click does, because the menu's Cut and
 * Paste act on the widget. {@code SET_CARET} is a collapsed range and anything else is not this
 * verb; {@code SET_SELECTION} is the click-then-shift-click gesture.
 *
 * <p>No enabled check of its own, in either verb path: the node acted on is the widget itself,
 * so the scene's gate has already walked it and every ancestor for enabled, checked that it is
 * showing, that the window is not modal-blocked and that it is inside the layer that owns input.
 */
public final class TextAccessibility {

    private TextAccessibility() {
    }

    /** The half of a text widget the shared publish and verbs need to reach. */
    public interface Host {

        /** @return the buffer, caret and selection */
        TextEditModel model();

        /** @return whether an input-method composition is open */
        boolean composing();

        /** @return the caret's index in the composed string, asked only while composing */
        int composedCaretIndex();

        /** @return whether the widget has been laid out, so that there is a caret to raise a menu at */
        boolean laidOut();

        /** Takes the keyboard focus, the way a click would. */
        void requestFocus();

        /** Raises the context menu at the caret. */
        void showContextMenuForFocus();

        /**
         * Runs an edit through the widget's own change path, so the application hears it.
         *
         * @param change the edit, made on {@link #model()}
         */
        void edit(Runnable change);

        /** The caret moved or the text changed: reveal the caret, restart the blink, repaint. */
        void caretMoved();
    }

    /**
     * Publishes the text facet: the string, the caret, the selection and the line count, composed
     * while composing.
     *
     * @param a        the node being described
     * @param host     the widget
     * @param text     the string the widget holds for the tree, composed while composing
     * @param witness  the counter that string was rebuilt against
     * @param caretBox the caret's box, or {@code null} when the widget draws no caret
     */
    public static void publish(Accessibility a, Host host, String text, long witness, Rect caretBox) {
        TextEditModel model = host.model();
        boolean composing = host.composing();
        int caret = composing ? host.composedCaretIndex() : model.cursor();
        ShapedText.Affinity affinity = composing
                ? ShapedText.Affinity.UPSTREAM : model.caretAffinity();
        int selectionStart = composing ? caret : model.selectionStart();
        int selectionEnd = composing ? caret : model.selectionEnd();
        // The line count is asked of the model rather than written as a constant, so the two
        // cannot drift if a subclass ever holds a model of the other shape.
        a.text(text, witness, caret, affinity, selectionStart, selectionEnd, model.lineCount(),
                caretBox, false);
    }

    /**
     * Raises the context menu, replaces the contents, moves the caret or sets the selection.
     *
     * @param host   the widget
     * @param action what is being asked
     * @param arg    the text for {@code SET_TEXT} and the range for the other two
     * @return whether the widget did it
     */
    public static boolean perform(Host host, Accessible.Action action, Accessible.Argument arg) {
        switch (action) {
            case SHOW_MENU -> {
                if (!host.laidOut()) {
                    return false; // no caret to raise it at until the first layout
                }
                host.requestFocus();
                host.showContextMenuForFocus();
                return true;
            }
            case SET_TEXT -> {
                if (!(arg instanceof Accessible.Argument.OfText replacement)) {
                    return false;
                }
                TextEditModel model = host.model();
                host.edit(() -> {
                    model.selectAll();
                    model.insert(replacement.text());
                });
                host.caretMoved();
                return true;
            }
            case SET_CARET -> {
                if (!(arg instanceof Accessible.Argument.OfRange range)
                        || range.start() != range.end()) {
                    return false; // a caret is a collapsed range; anything else is not this verb
                }
                return placeCaret(host, range.start(), range.start());
            }
            case SET_SELECTION -> {
                if (!(arg instanceof Accessible.Argument.OfRange range)) {
                    return false;
                }
                return placeCaret(host, range.start(), range.end());
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Puts the caret at {@code end}, with a selection back to {@code start} when the two differ.
     *
     * <p>An offset outside the text is <b>refused</b> and never clamped to a neighbour, because a
     * client that asked for character forty of a ten-character field has misunderstood something
     * and a caret quietly placed at ten hides that. An offset on a newline is a legal caret
     * position in a multi-line buffer. Both offsets are aligned to a grapheme boundary first,
     * because {@link TextEditModel#cursor()} is always on one and a bridge counting UTF-16 units
     * can name a point inside a cluster.
     *
     * <p>Refused outright while a composition is open, because the offsets a client is holding are
     * not offsets into the string the widget would place them in: the facet publishes the
     * composed string and the model counts the committed buffer alone, and the two agree up to
     * the splice and diverge after it. Placed anyway, a caret asked for one position past the
     * preedit lands short by its length, silently, because the shorter buffer's own bounds check
     * passes; and since the composed line is keyed on the cursor, the same call re-splices the
     * preedit at the new position and the text under composition visibly jumps. A refusal a
     * client can see beats either. The composition owns the caret until it commits, which is what
     * every platform's input method contract already says.
     *
     * @param host  the widget
     * @param start where the selection starts, or the caret when equal to {@code end}
     * @param end   where the caret goes
     * @return whether the caret was placed
     */
    public static boolean placeCaret(Host host, int start, int end) {
        if (host.composing()) {
            return false;
        }
        TextEditModel model = host.model();
        int length = model.length();
        if (start < 0 || start > length || end < 0 || end > length) {
            return false;
        }
        model.setCursor(model.alignToGrapheme(start), false);
        model.setCursor(model.alignToGrapheme(end), true);
        host.caretMoved();
        return true;
    }
}
