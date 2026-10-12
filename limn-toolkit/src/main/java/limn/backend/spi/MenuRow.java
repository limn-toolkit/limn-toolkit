package limn.backend.spi;

import java.util.List;
import java.util.Objects;
import limn.graphics.Icon;
import limn.scene.EditCommand;
import org.jspecify.annotations.Nullable;

/**
 * One row of a menu the system draws (a tray's, a menu bar's) as a backend is handed it: what the three systems can draw of a menu's
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
 * @param icon     the picture before the label, or {@code null}
 * @param shortcutKey the name of the accelerator's key with no modifier, as {@code Q} or
 *                 {@code F5}; empty for no accelerator. A system shows it beside the label, and
 *                 where the system runs a menu's keys while the menu is open, it chooses the row
 * @param shortcutModifiers the accelerator's {@link limn.input.Keys} modifier bits
 * @param edit     the editing command the row is, or {@code null}: such a row is enabled and
 *                 chosen through the window that has the keyboard, by
 *                 {@link WindowInput#canEditFocused} and {@link WindowInput#editFocused}, and {@code enabled}
 *                 says only whether the application disabled the item
 */
public record MenuRow(int id, Kind kind, String label, boolean enabled, boolean checked, char mnemonic,
        List<MenuRow> children, @Nullable Icon icon, String shortcutKey, int shortcutModifiers,
        @Nullable EditCommand edit) {

    /** A row that is no editing command. */
    public MenuRow(int id, Kind kind, String label, boolean enabled, boolean checked, char mnemonic,
            List<MenuRow> children, @Nullable Icon icon, String shortcutKey, int shortcutModifiers) {
        this(id, kind, label, enabled, checked, mnemonic, children, icon, shortcutKey, shortcutModifiers, null);
    }

    /** A row with no picture and no accelerator. */
    public MenuRow(int id, Kind kind, String label, boolean enabled, boolean checked, char mnemonic,
            List<MenuRow> children) {
        this(id, kind, label, enabled, checked, mnemonic, children, null, "", 0, null);
    }

    /**
     * @return the accelerator as the words a menu shows where it shows words, {@code Ctrl+Shift+S},
     *         or {@code ""} for none
     */
    public String shortcutText() {
        if (shortcutKey.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if ((shortcutModifiers & limn.input.Keys.MOD_CONTROL) != 0) {
            out.append("Ctrl+");
        }
        if ((shortcutModifiers & limn.input.Keys.MOD_ALT) != 0) {
            out.append("Alt+");
        }
        if ((shortcutModifiers & limn.input.Keys.MOD_SHIFT) != 0) {
            out.append("Shift+");
        }
        if ((shortcutModifiers & limn.input.Keys.MOD_SUPER) != 0) {
            out.append("Win+");
        }
        return out.append(shortcutKey).toString();
    }

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
    public MenuRow {
        if (id <= 0) {
            throw new IllegalArgumentException("a row's number is positive: " + id);
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(shortcutKey, "shortcutKey");
        children = List.copyOf(children);
    }
}
