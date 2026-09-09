package limn.scene;

/**
 * What changed on a widget, and what moved it.
 *
 * <p>Every value but a text edit is interned, so a notification allocates nothing; a text edit
 * allocates one record per edit, and not one per watcher.
 *
 * <p>This is the payload of the <b>watcher</b> channel ({@code Widget.observeChanges}), which
 * hears every change to a widget whatever moved it. The other channel is the <b>handler</b> — the
 * fluent {@code onX} slot — which is the application's one response to the user operating the
 * widget, and which runs only for {@link Origin#USER}. The whole rule is on {@link Origin}.
 *
 * <p>A change carries no payload beyond the aspect and the origin: it says what changed, and the
 * widget says what it is now. That keeps one type instead of one per widget, keeps every aspect
 * free of boxing, and makes the accessor the single source of truth — at the price of one
 * obligation, which is that <b>no widget announces a state aspect that no public accessor
 * answers</b>.
 *
 * @see ChangeObserver
 */
public sealed interface Change {

    /**
     * What about the widget changed. Closed: a widget with a state outside this list has nothing
     * a platform accessibility API can be told.
     *
     * <p>The list is in three parts, and the parts are not decoration — the accessor obligation
     * applies to the first part only.
     *
     * <p><b>State aspects</b> name something the widget still is after the notification, and a
     * public accessor answers every one of them. <b>Event aspects</b> name something that
     * happened and is over; nothing answers them, by construction, and the notification is the
     * whole of the information — a watcher that missed one has missed it. <b>{@link #LAYOUT}</b>
     * is neither: it is one marker per layout pass, sourced at the scene's root, and what it says
     * is <i>re-read the bounds you hold</i>.
     */
    enum Aspect {
        // state
        /** The widget's own value: a slider's position, a checkbox's flag, a colour. */
        VALUE,
        /** What the value may be: a minimum, a maximum, a step, an indeterminate flag. */
        RANGE,
        /** Which of the widget's children or rows are chosen. */
        SELECTION,
        /** The text of an editable widget. Only ever a {@link TextEdit}. */
        TEXT,
        /** The active descendant: the row a popup highlights, the cell a table focuses. */
        ACTIVE,
        /** Open or closed: a combo box's popup, a menu bar's menu. */
        EXPANDED,
        /** The widget's own label, the thing a reader speaks first. */
        NAME,
        /** The accessible description: a tooltip, a placeholder. */
        DESCRIPTION,
        /** Whether what the widget holds is valid, and the message that says why not. */
        VALIDITY,
        /** Whether the widget takes input. */
        ENABLED,
        /** Whether the widget is shown. */
        VISIBLE,
        /** Whether the widget can take focus. */
        FOCUSABLE,
        /** Whether the widget holds focus: one on the widget losing it, one on the widget gaining it. */
        FOCUS,
        /** Which widgets the tree holds under this one. */
        CHILDREN,
        // event
        /** The widget was invoked: a button pressed, a row opened. */
        INVOKED,
        /** A run of changes ended: a drag released, a value committed. */
        COMMITTED,
        /** The widget's content was submitted: Enter in a search field. */
        SUBMITTED,
        // pass marker
        /** A layout pass ran: re-read the bounds you hold. Sourced at the scene's root. */
        LAYOUT
    }

    /**
     * What moved the change, and the whole of this record's rule:
     *
     * <blockquote><b>The handler runs when, and only when, the user operated this widget. The
     * watchers run for every change, whatever moved it.</b></blockquote>
     *
     * <p>A component decides the origin at the seam that announces, without any judgement about
     * what a method is called: it knows whether it is inside {@code onMouseEvent},
     * {@code onKeyEvent}, {@code onCharTyped} or {@code onPreedit}, or inside a public setter.
     *
     * <p>Three consequences, and all three are subtractions. <b>Termination is structural</b>: a
     * handler's writes are not user input, so they reach no second handler and two controls bound
     * to each other through their handlers cannot recurse at all. <b>The echo guards delete</b>:
     * a caller writing a widget to keep it in step with another can no longer be heard by that
     * widget's application handler, so there is nothing to guard against. And a poll that writes
     * a widget ten times a second — a transport bar following a film — is a caller's write, so it
     * moves the bar and scrubs nothing.
     */
    enum Origin {
        /**
         * A person operated this control: a pointer, a key, an IME commit, or an assistive
         * technology performing the control's action on the person's behalf. <b>This is the only
         * origin that reaches the handler.</b>
         */
        USER,
        /**
         * A caller wrote the state this mutator names: {@code setValue}, {@code setChecked},
         * {@code setText}, {@code setSelectedIndex}, {@code setVisible}, and a verb invoked from
         * code.
         *
         * <p>A change is {@code CODE} when its aspect is the one the call's own contract names
         * <em>and the call is one an application can make</em>. A mutator that is not public has
         * no {@code CODE} reading to default to: its origin is the origin its callers hand it,
         * and if every caller is an input handler it takes {@link #USER} and nothing else.
         */
        CODE,
        /**
         * The widget moved this itself, because something else changed: a step grid re-snapping a
         * value, a list refreshing past its selection, a tab pane taking its first tab, focus
         * revoked because the widget holding it was hidden.
         *
         * <p>{@code CODE} and {@code ADJUSTMENT} are treated identically by the rule — neither
         * reaches the handler. The distinction earns its keep by naming what a bridge announces
         * as a state update rather than an interaction, and by letting a two-way binding break an
         * echo.
         */
        ADJUSTMENT
    }

    /** @return what about the widget changed */
    Aspect aspect();

    /** @return what moved it */
    Origin origin();

    /** @return whether the user operated the widget, which is the only origin a handler runs for */
    default boolean fromUser() {
        return origin() == Origin.USER;
    }

    /**
     * The interned value for this pair. Never allocates.
     *
     * @param aspect what changed; never {@link Aspect#TEXT}
     * @param origin what moved it
     * @return the one instance for the pair
     * @throws IllegalArgumentException for {@link Aspect#TEXT}, which only {@link #edit} can
     *         make: {@code TEXT} is the one aspect a watcher may narrow on, so it must never
     *         arrive as a {@link State} and give that watcher a {@code ClassCastException} at its
     *         own site, with no diagnostic anywhere near the component that announced it
     */
    static Change of(Aspect aspect, Origin origin) {
        Change interned = State.TABLE[aspect.ordinal() * State.ORIGINS + origin.ordinal()];
        if (interned == null) {
            throw new IllegalArgumentException(
                    "Aspect." + aspect + " is only ever a Change.TextEdit; use Change.edit(...)");
        }
        return interned;
    }

    /**
     * A text edit, with the offsets a platform text-changed event wants. Allocates one record per
     * edit, and not one per watcher.
     *
     * @param origin what moved it
     * @param offset where the damaged range begins, in chars
     * @param removed how many chars it replaced
     * @param inserted how many chars it inserted
     * @return the edit
     */
    static TextEdit edit(Origin origin, int offset, int removed, int inserted) {
        return new TextEdit(origin, offset, removed, inserted);
    }

    /**
     * Every aspect but a text edit; {@link #aspect()} is never {@link Aspect#TEXT}.
     *
     * <p>A class with a private constructor rather than a record, because every one of its
     * fifty-one values is interned in a table built once and there is no second way to make one.
     * Its twin {@link TextEdit} <em>is</em> a record for the opposite reason: it carries numbers
     * that differ per edit, so it is allocated rather than looked up.
     */
    final class State implements Change {

        private static final int ORIGINS = Origin.values().length;

        /**
         * One instance per (aspect, origin) pair, {@code TEXT}'s three left null so {@link #of}
         * can refuse them. Fifty-one values, built once at class initialisation.
         */
        private static final State[] TABLE = new State[Aspect.values().length * ORIGINS];

        static {
            for (Aspect aspect : Aspect.values()) {
                if (aspect == Aspect.TEXT) {
                    continue;
                }
                for (Origin origin : Origin.values()) {
                    TABLE[aspect.ordinal() * ORIGINS + origin.ordinal()] = new State(aspect, origin);
                }
            }
        }

        private final Aspect aspect;
        private final Origin origin;

        private State(Aspect aspect, Origin origin) {
            this.aspect = aspect;
            this.origin = origin;
        }

        @Override
        public Aspect aspect() {
            return aspect;
        }

        @Override
        public Origin origin() {
            return origin;
        }

        @Override
        public String toString() {
            return aspect + "/" + origin;
        }
    }

    /**
     * A text edit: {@link #aspect()} is always {@link Aspect#TEXT}, so
     * {@code aspect() == TEXT} and {@code instanceof Change.TextEdit} are the same question in
     * both directions.
     *
     * @param origin what moved the edit
     * @param offset where the damaged range begins, in chars
     * @param removed how many chars it replaced
     * @param inserted how many chars it inserted
     */
    record TextEdit(Origin origin, int offset, int removed, int inserted) implements Change {

        @Override
        public Aspect aspect() {
            return Aspect.TEXT;
        }
    }
}
