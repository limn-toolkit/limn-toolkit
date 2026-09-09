package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.concurrent.Ui;
import limn.graphics.SvgIcon;
import limn.input.Keys;
import limn.lang.Checks;
import limn.scene.Change;
import limn.scene.event.KeyEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link TextField} preset for search: a leading magnifier icon inside the
 * field and a trailing coupled clear button (the ComboBox-caret idiom). Enter
 * fires {@link #onSubmit}; the clear button empties the field.
 */
public class SearchField extends TextField {

    private Consumer<String> onSubmit;

    /** A field with a search icon, a clear button and a localized placeholder. */
    public SearchField() {
        setPlaceholder(ComponentStrings.SEARCH_PLACEHOLDER);
        setLeadingIcon(SvgIcon.fromResource("/limn/components/icons/search.svg"));
        // The named overload, and the name is the toolkit's own: the button paints a glyph, holds
        // no text and no tooltip, and an application cannot reach the drawn region to name it, so
        // the two-argument overload would publish an operable control with an empty name.
        setTrailingButton(SvgIcon.fromResource("/limn/components/icons/close.svg"),
                ComponentStrings.SEARCH_CLEAR, () -> clear(Change.Origin.USER));
    }

    /**
     * The application's response to the user pressing Enter, or an assistive technology's press:
     * called with the current query.
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this field
     * @throws IllegalStateException if a handler is already registered
     */
    public SearchField onSubmit(Consumer<String> listener) {
        Ui.checkUiThread();
        this.onSubmit = Checks.handlerSlot(onSubmit, listener, "SearchField.onSubmit");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.SUBMITTED) {
            if (onSubmit != null) {
                onSubmit.accept(text());
            }
            return;
        }
        super.handleUserChange(aspect); // TextField's own dispatch, for the TEXT the user types
    }

    /**
     * Empties the field: a caller's write, announced as one {@code TEXT} edit at {@code CODE}
     * and reaching no handler. The trailing button empties it as the user's, through the same
     * seam, so {@code onChange} hears the button and not this. UI thread only.
     */
    public void clear() {
        Ui.checkUiThread();
        clear(Change.Origin.CODE);
    }

    /** One route through the text seam, so a clear announces once whichever end asked. */
    private void clear(Change.Origin origin) {
        if (!text().isEmpty()) {
            setText("", origin);
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (event.isPressed() && !event.isRepeat() && event.key() == Keys.ENTER) {
            submit();
            event.consume();
            return;
        }
        super.onKeyEvent(event);
    }

    /**
     * Hands the current query to the submit listener: the one thing Enter does that a plain field
     * does not, held in a method so that the key and an assistive technology reach the same body.
     *
     * <p>No emptiness guard, because Enter has none: a search for nothing is a search a user can
     * ask for, and a guard here would make the two doors disagree about the same gesture. No
     * enabled guard either — the key path is gated by the scene's dispatch, which never delivers a
     * key to a disabled widget, and the accessibility path is gated in the hook below.
     */
    private void submit() {
        notifyChange(Change.of(Change.Aspect.SUBMITTED, Change.Origin.USER));
    }

    // -------------------------------------------------------- accessibility

    /**
     * The same node {@link TextField} publishes, said to be a search field and offering the one
     * verb this class adds to it.
     *
     * <p><b>The role is written after the super call and cannot move before it.</b> A role is a
     * plain write to the node's slot, so the last one wins, and the field's own hook writes
     * {@code TEXT_FIELD} into it; a subclass that declared its role first would publish a search
     * field as a plain one. That it lands on this widget's node rather than on the trailing
     * button's is the other half of the same ordering, and rests on the super hook closing every
     * synthetic child it opened.
     *
     * <p>Everything else a reader hears here is inherited and is deliberately not restated: the
     * contents with the caret and the selection, {@code EDITABLE}, the context menu, and the
     * trailing button as a child node. What this class contributes to that button is its
     * <em>name</em>, given in the constructor, and not a node of its own — the button is the
     * field's generic trailing region and declaring a second one would publish it twice.
     *
     * <p>The name needs no line at all: it is the placeholder the constructor installs, which
     * makes this the one widget in the toolkit that arrives already named with no application
     * involvement. It is the placeholder whether or not there is anything typed in the field —
     * conditioning it on emptiness would rename the node on the first keystroke and rename it back
     * on the last backspace — and because the node always has a name, a tooltip on a search field
     * becomes its description instead.
     *
     * <p>Nothing is derived here and nothing may be: both statements are constants, so a field
     * whose caret is blinking spends no memory concluding that nothing about it has moved.
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        super.onAccessibility(a);
        a.role(Accessible.Role.SEARCH_FIELD);
        // PRESS stands for Enter, which is the whole of what this class adds to input. FOCUS and
        // SCROLL_INTO_VIEW arrive free from the walk, because the field is focusable.
        a.action(Accessible.Action.PRESS);
    }

    /**
     * Runs the search, through the same body Enter reaches.
     *
     * <p><b>Everything else goes to {@code super}, and that clause is the one this class cannot
     * do without.</b> A search field is still a text field: the whole-value set an assistive
     * technology is promised on every text control, the caret and selection verbs and the context
     * menu are all the base class's, and a subclass that swallowed them to answer one verb of its
     * own would be the single text widget in the toolkit a reader cannot write into.
     *
     * <p>The enabled check is here and not in {@link #submit()}. The key path is gated by the
     * scene's own dispatch and never travels this way, and the dispatcher has already walked this
     * widget and its ancestors; this is the same restatement the other action hooks in the toolkit
     * carry, and it belongs to the door rather than to the room.
     *
     * @param action what is being asked
     * @param arg    the argument, which this verb does not take
     * @return whether this widget did it
     */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        if (action == Accessible.Action.PRESS && isEnabled()) {
            submit();
            return true;
        }
        return super.onAccessibilityAction(action, arg);
    }
}
