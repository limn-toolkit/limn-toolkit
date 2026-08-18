package limn.components;

import limn.scene.Widget;

/**
 * The Cut / Copy / Paste / Select All menu that {@link TextField} and {@link TextArea} raise on a
 * right press.
 *
 * <p>It is shared rather than written twice because the interesting part is not the four rows: it
 * is which of them are enabled, and that answer has to be identical in both widgets or the same
 * gesture on the same selection reads as a bug in one of them.
 *
 * <p><b>Why this exists at all.</b> For a user who works with the mouse, the context menu is the
 * only route to the clipboard: there is no Edit menu unless the application builds one, and menu
 * accelerators do not exist yet, so without this the clipboard was reachable exclusively by
 * Ctrl/Cmd chords.
 */
final class TextContextMenu {

    /** What the menu needs to know and do, supplied by the widget raising it. */
    interface Host {
        boolean hasSelection();

        /** Enabled and not otherwise refusing edits; false greys Cut and Paste. */
        boolean isEditable();

        /** False for a password field, which greys Cut and Copy so a secret cannot leave. */
        boolean allowsCopy();

        boolean isEmpty();

        /** The clipboard's current text, "" when it holds nothing this widget can take. */
        String clipboardText();

        void cut();

        void copy();

        void paste();

        void selectAll();
    }

    private TextContextMenu() {
    }

    /**
     * Which of the four rows a given state offers. This is the whole substance of the menu (the
     * rows themselves are fixed), so it is a value rather than four locals inside {@link #showAt},
     * where nothing could assert it: a native popup needs a real window, and the headless suite
     * has none.
     */
    record Rows(boolean cut, boolean copy, boolean paste, boolean selectAll) {
        boolean any() {
            return cut || copy || paste || selectAll;
        }
    }

    static Rows rowsFor(Host host) {
        boolean copy = host.hasSelection() && host.allowsCopy();
        // Cut is Copy plus the edit: a password field must not offer it as a way around Copy
        // being greyed, since cutting to the clipboard leaks exactly what copying would.
        boolean cut = copy && host.isEditable();
        boolean paste = host.isEditable() && !host.clipboardText().isEmpty();
        return new Rows(cut, copy, paste, !host.isEmpty());
    }

    /**
     * Opens the menu at a point in {@code anchor}'s own coordinates, what
     * {@link limn.scene.event.MouseEvent#x()} reports.
     *
     * <p>A no-op with nothing to offer: an empty, disabled field over an empty clipboard would
     * raise four dead rows, and a menu that can do nothing is worse than no menu.
     */
    static void showAt(Widget anchor, Host host, float localX, float localY) {
        ContextMenus.showAt(anchor, menuFor(host), localX, localY);
    }

    /** The four rows for this state, or {@code null} when none of them would be usable. */
    private static Menu menuFor(Host host) {
        Rows rows = rowsFor(host);
        if (!rows.any()) {
            return null;
        }
        Menu menu = new Menu();
        menu.add(MenuItem.of(ComponentStrings.TEXT_MENU_CUT, host::cut).setEnabled(rows.cut()));
        menu.add(MenuItem.of(ComponentStrings.TEXT_MENU_COPY, host::copy).setEnabled(rows.copy()));
        menu.add(MenuItem.of(ComponentStrings.TEXT_MENU_PASTE, host::paste)
                .setEnabled(rows.paste()));
        menu.addSeparator();
        menu.add(MenuItem.of(ComponentStrings.TEXT_MENU_SELECT_ALL, host::selectAll)
                .setEnabled(rows.selectAll()));
        return menu;
    }
}
