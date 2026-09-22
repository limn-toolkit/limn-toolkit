package limn.testing.a11y;

import limn.scene.Widget;

import java.util.List;

/**
 * A widget of the {@code MENU} shape as {@link MenuContract} drives it: a menu bar, whose rows
 * are its titles, or a popup menu, whose rows are the root column's. The contract reads the
 * highlight, the open state and the verbs off the tree; the subject says what each row is and
 * reports what was chosen.
 */
public interface MenuSubject {

    /** What a row is, which decides the verbs the contract expects on it. */
    enum Row {
        /** A bar's title over a menu with rows: opens a dropdown, never chooses. */
        TITLE,
        /** A row that runs a command on {@code PRESS}. */
        COMMAND,
        /** A row that flips a check on {@code PRESS} and on {@code TOGGLE}. */
        CHECK,
        /** A row that opens a submenu with rows in it. */
        SUBMENU,
        /** A title or a submenu row with nothing to open: the highlight lands on it, no more. */
        EMPTY,
        /** A row the arrows skip: no verb, not enabled. */
        DISABLED
    }

    /** Builds the widget in a fresh root and returns the root. */
    Widget build();

    /**
     * Shows the rows: a popup opens at its anchor, a bar has nothing to do. Called once per case
     * after the root is bound and before the tree is read.
     */
    void open();

    /** The widget that holds the keyboard while the rows are shown. */
    Widget widget();

    /** The rows the contract addresses, in order, by the name each publishes. */
    List<String> rowNames();

    /** What row {@code row} is. */
    Row kindOf(int row);

    /** The names of the command rows chosen so far, in order. */
    List<String> chosen();

    /** The values the check rows were flipped to so far, in order. */
    List<Boolean> toggled();
}
