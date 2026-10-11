package limn.backend.spi;

import java.util.List;
import java.util.Objects;

/**
 * One row of a tray's menu as a backend is handed it: what the three systems can draw of a menu's
 * item, and a number to say back when the user chooses it.
 *
 * <p>The rows of one reading are numbered from 1 in the order they are read, a submenu's rows
 * after their parent, so the same menu read twice has the same numbers. A number means nothing
 * once the menu has been read again.
 *
 * @param id       the row's number in this reading; positive
 * @param kind     what the row is
 * @param label    the row's text, as the application wrote it: a backend escapes what its system
 *                 reads as a marker; empty for a separator
 * @param enabled  whether it can be chosen; a submenu with no rows is not enabled
 * @param checked  for a {@link Kind#CHECK}, whether it is checked
 * @param mnemonic the access letter, uppercased, or 0
 * @param children a submenu's rows; empty for any other kind
 */
public record TrayRow(int id, Kind kind, String label, boolean enabled, boolean checked, char mnemonic,
        List<TrayRow> children) {

    /** What a row is. */
    public enum Kind {
        /** Chosen, it runs an action. */
        COMMAND,
        /** Chosen, it flips its check mark. */
        CHECK,
        /** It opens its children. */
        SUBMENU,
        /** A line between groups; never first, last or beside another. */
        SEPARATOR
    }

    /**
     * @throws IllegalArgumentException when {@code id} is not positive
     */
    public TrayRow {
        if (id <= 0) {
            throw new IllegalArgumentException("a row's number is positive: " + id);
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        children = List.copyOf(children);
    }
}
