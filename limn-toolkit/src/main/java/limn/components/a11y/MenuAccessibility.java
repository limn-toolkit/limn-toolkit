package limn.components.a11y;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Action;
import limn.accessibility.ToggleFacet;

/**
 * The {@code MENU} shape, written once (ADR 045 §3; decision 98, 2026-09-22): what a menu bar,
 * a menu column and their rows publish, and what a reader's verb on a row does. A bar's title
 * and a cascade's row say the same things and obey the same rules — until this, {@code MenuBar}
 * and {@code PopupMenu} each said them in a hook of its own (124 and 42 lines), and the rule of
 * which verbs a title accepts by state (ADR 039 §1.5, amended 2026-09-14; decision 2) was
 * written twice.
 *
 * <p><b>Two halves, as {@link RowsAccessibility} has them.</b> {@link #describeRow} is a static
 * function over the facts of one row and allocates nothing; {@link #performOnRow} takes a
 * {@link Host} the widget implements over the private paths its pointer already takes, and
 * holds the rules of what a verb does.
 *
 * <p><b>The rules:</b>
 * <ul>
 *   <li>A row is a member of its column's or its bar's selection: the highlighted title or row
 *       is the selected one, and the one the keys act on carries {@code ACTIVE}.</li>
 *   <li><b>decision 11</b> — {@code FOCUS} moves the highlight onto a row and chooses nothing and
 *       opens nothing, as the arrows do; a bar offers it while no menu is down, a column on
 *       every row the arrows can land on.</li>
 *   <li><b>decision 2</b> — the verbs a row accepts are exactly the ones it publishes for its
 *       state, and the published list is the only refusal a platform can see: a closed row
 *       with a submenu opens on {@code SHOW_MENU} and on its synonym {@code EXPAND}, the open
 *       one closes on {@code COLLAPSE} alone; a command row chooses on {@code PRESS}, a check
 *       row on {@code PRESS} and on {@code TOGGLE}; a title, a submenu row and a submenu with
 *       nothing in it take no {@code PRESS}.</li>
 *   <li>A row that opens something says so ({@code HAS_POPUP}) and carries its open state; a
 *       check row carries its toggle.</li>
 * </ul>
 * The key binding a row shows, a submenu column's name and a disabled row's narrowing stay the
 * widget's: the first two are strings the widget holds, the third is the one route a synthetic
 * child has to be less enabled than its owner.
 */
public final class MenuAccessibility {

    /** What kind of row is being described or driven. */
    public enum Kind {
        /** A menu bar's title: opens a dropdown, never chooses. */
        TITLE,
        /** A row that runs a command. */
        COMMAND,
        /** A row that flips a check. */
        CHECK,
        /** A row that opens a submenu, or would if it had one. */
        SUBMENU
    }

    /**
     * The mechanisms a menu already has, over its own row handle {@code R} (a title's index, a
     * column and row), which {@link #performOnRow} drives by the rules.
     *
     * @param <R> what identifies a row to the widget
     */
    public interface Host<R> {

        /** @return what kind of row it is */
        Kind kindOf(R row);

        /** @return whether the arrows can land on the row: enabled, and not a rule */
        boolean isSelectable(R row);

        /** @return whether the row has something to open: a non-empty menu below it */
        boolean hasSubmenu(R row);

        /** @return whether what it opens is on screen now; asked only when it has a submenu */
        boolean isOpen(R row);

        /** @return whether the highlight may be moved onto the row now (a bar: while no menu is down) */
        boolean canFocus(R row);

        /**
         * Moves the highlight onto the row, taking the keyboard if it must, choosing nothing.
         *
         * @return whether it moved
         */
        boolean moveCursor(R row);

        /**
         * Opens the row's submenu, as a click on it does.
         *
         * @return whether something is on screen after: a show over a window that cannot host a
         *     popup is refused outright, and true there would report an action that did nothing
         */
        boolean open(R row);

        /** Closes the row's open submenu, leaving the highlight where it is. */
        void close(R row);

        /** Chooses a command or a check row, as a click does. */
        void choose(R row);
    }

    private MenuAccessibility() {
    }

    /**
     * Publishes a menu bar's container half: its role, its orientation and the selection its
     * titles are members of. The name and the key that opens the bar stay the widget's.
     *
     * @param a the builder, positioned on the bar
     */
    public static void describeBar(Accessibility a) {
        a.role(Accessible.Role.MENU_BAR);
        a.state(Accessible.State.HORIZONTAL);
        a.selection(false, false);
    }

    /**
     * Publishes a menu column's container half: its role, its orientation and the selection its
     * rows are members of. Not required and not multiple: a column of nothing but disabled rows
     * has no highlight at all, and one row is current. A submenu's name and the column's scroll
     * facet stay the widget's.
     *
     * @param a the builder, positioned on the column
     */
    public static void describeMenu(Accessibility a) {
        a.role(Accessible.Role.MENU);
        a.state(Accessible.State.VERTICAL);
        a.selection(false, false);
    }

    /**
     * Publishes one row's membership, its toggle, its open state, its cursor mark and its verbs,
     * from the facts alone. The verbs are the widget's own: a title and a row are synthetic
     * children the bar or the column describes and performs for. Allocates nothing.
     *
     * @param a          the builder, positioned on the row
     * @param kind       what kind of row it is
     * @param selectable whether the arrows can land on it (enabled, not a rule)
     * @param selected   whether it is the highlighted one
     * @param position   its one-based position among the rows it is counted with
     * @param size       how many rows it is counted with
     * @param hasSubmenu whether it has something to open; false for a title over an empty menu
     *                   and for a submenu row with nothing in it
     * @param open       whether what it opens is on screen; ignored when it has nothing to open
     * @param cursor     whether the keys act on it now: the highlighted title of a bar that has
     *                   the keyboard, or the highlighted row of the deepest open column
     * @param focus      whether {@code FOCUS} may move the highlight onto it now
     * @param checked    whether a check row is on; ignored for any other kind
     */
    public static void describeRow(Accessibility a, Kind kind, boolean selectable,
                                   boolean selected, int position, int size,
                                   boolean hasSubmenu, boolean open, boolean cursor,
                                   boolean focus, boolean checked) {
        a.selectionItem(selected, position, size);
        if (kind == Kind.CHECK) {
            a.toggle(checked ? ToggleFacet.State.ON : ToggleFacet.State.OFF);
        }
        if (cursor) {
            a.state(Accessible.State.ACTIVE);
        }
        if (focus) {
            a.action(Action.FOCUS);
        }
        if (hasSubmenu) {
            a.state(Accessible.State.HAS_POPUP);
            a.expand(open);
            // The two-argument form; the variable-argument one allocates an array per call.
            if (open) {
                a.action(Action.COLLAPSE);
            } else {
                a.action(Action.SHOW_MENU, Action.EXPAND);
            }
        } else if (selectable && kind == Kind.COMMAND) {
            a.action(Action.PRESS);
        } else if (selectable && kind == Kind.CHECK) {
            a.action(Action.PRESS, Action.TOGGLE);
        }
    }

    /**
     * Performs a verb a reader sent to a row, by the rules above.
     *
     * @param host the widget's mechanisms
     * @param row  the row addressed
     * @param verb the verb
     * @return whether it was performed; false for a verb the row's state refuses
     */
    public static <R> boolean performOnRow(Host<R> host, R row, Action verb) {
        switch (verb) {
            case FOCUS -> {
                if (!host.isSelectable(row) || !host.canFocus(row)) {
                    return false;
                }
                return host.moveCursor(row);
            }
            case SHOW_MENU, EXPAND -> {
                if (!host.hasSubmenu(row) || !host.isSelectable(row) || host.isOpen(row)) {
                    return false;
                }
                return host.open(row);
            }
            case COLLAPSE -> {
                if (!host.hasSubmenu(row) || !host.isOpen(row)) {
                    return false;
                }
                host.close(row);
                return true;
            }
            case PRESS, TOGGLE -> {
                Kind kind = host.kindOf(row);
                boolean chooses = kind == Kind.CHECK
                        || kind == Kind.COMMAND && verb == Action.PRESS;
                if (!chooses || host.hasSubmenu(row) || !host.isSelectable(row)) {
                    return false;
                }
                host.choose(row);
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
