package limn.components;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import limn.backend.spi.MenuBarReading;
import limn.backend.spi.MenuRow;
import limn.scene.Change;
import limn.scene.EditCommand;
import org.jspecify.annotations.Nullable;

/**
 * One reading of a menu bar for a system that keeps the bar outside the window: the bar's menus
 * as rows, with the items the system has a place of its own for taken out of the menus they
 * were written in.
 *
 * <p>Plain Java over the menus, changing none of them: the same {@link Menu} may be open in a
 * window, or be a tray's, while it is read here.
 */
final class SystemBarReader {

    /** Deeper than anybody opens; a menu that is its own ancestor stops here. */
    private static final int MAX_DEPTH = 16;

    private final Map<Integer, MenuItem> items = new HashMap<>();
    private int next = 1;
    private @Nullable MenuRow about;
    private @Nullable MenuRow settings;
    private boolean quitSeen;
    private boolean roleTwice;
    private boolean editingChordTaken;
    private MenuBarReading reading = MenuBarReading.EMPTY;

    /** A menu of the bar as the reader is given it. */
    record Source(String title, Menu menu) {
    }

    private SystemBarReader() {
    }

    /**
     * @param menus      the bar's menus in its order
     * @param windowMenu the one named as the Window menu, or {@code null}
     * @param helpMenu   the one named as the Help menu, or {@code null}
     */
    static SystemBarReader read(List<Source> menus, @Nullable Menu windowMenu, @Nullable Menu helpMenu,
            boolean rightToLeft) {
        SystemBarReader reader = new SystemBarReader();
        List<MenuBarReading.Top> tops = new ArrayList<>();
        for (Source source : menus) {
            MenuBarReading.Kind kind = source.menu() == helpMenu ? MenuBarReading.Kind.HELP
                    : source.menu() == windowMenu ? MenuBarReading.Kind.WINDOW : MenuBarReading.Kind.PLAIN;
            List<MenuRow> rows = reader.rows(source.menu(), new ArrayList<>());
            // A menu emptied by the items the system took is not shown; the Help menu stays for
            // the search the system puts in it, and the Window menu for the system's own items.
            if (!rows.isEmpty() || kind != MenuBarReading.Kind.PLAIN) {
                tops.add(new MenuBarReading.Top(source.title(), kind, rows));
            }
        }
        reader.reading = new MenuBarReading(reader.about, reader.settings, tops, rightToLeft);
        return reader;
    }

    MenuBarReading reading() {
        return reading;
    }

    /** @return whether a role was given to two items, the second of which stayed an ordinary item */
    boolean roleGivenTwice() {
        return roleTwice;
    }

    /** @return whether an item that is no editing command sits on an editing command's chord */
    boolean editingChordTaken() {
        return editingChordTaken;
    }

    /** Runs the item a row of this reading stood for, when it can still be chosen. */
    void choose(int id) {
        MenuItem item = items.get(id);
        if (item != null && item.role().editCommand() == null && item.isSelectable()) {
            item.activate(Change.Origin.USER);
        }
    }

    private List<MenuRow> rows(Menu menu, List<Menu> above) {
        above.add(menu);
        List<MenuRow> out = new ArrayList<>();
        boolean separatorOwed = false;
        for (MenuItem item : menu.items()) {
            if (item.isSeparator()) {
                // Owed, and paid only before a row that follows one: never first, last or doubled,
                // which is also what clears the separator an item taken by the system left behind.
                separatorOwed = !out.isEmpty();
                continue;
            }
            if (takenBySystem(item)) {
                continue;
            }
            MenuRow row = row(item, above);
            if (row == null) {
                continue;
            }
            if (separatorOwed) {
                out.add(new MenuRow(next++, MenuRow.Kind.SEPARATOR, "", false, false, (char) 0, List.of()));
                separatorOwed = false;
            }
            out.add(row);
        }
        above.remove(above.size() - 1);
        return out;
    }

    /** About and Settings are remembered for the application's menu; Quit is the system's own. */
    private boolean takenBySystem(MenuItem item) {
        switch (item.role()) {
            case ABOUT -> {
                if (about != null) {
                    roleTwice = true;
                    return false;
                }
                about = plain(item);
                return true;
            }
            case SETTINGS -> {
                if (settings != null) {
                    roleTwice = true;
                    return false;
                }
                settings = plain(item);
                return true;
            }
            case QUIT -> {
                if (quitSeen) {
                    roleTwice = true;
                    return false;
                }
                quitSeen = true;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private MenuRow plain(MenuItem item) {
        int id = next++;
        items.put(id, item);
        return new MenuRow(id, MenuRow.Kind.COMMAND, labelOf(item), item.isEnabled(), false, (char) 0, List.of());
    }

    private @Nullable MenuRow row(MenuItem item, List<Menu> above) {
        int id = next++;
        items.put(id, item);
        Accelerator accelerator = item.accelerator();
        String key = accelerator == null ? "" : accelerator.keyLabel();
        int modifiers = accelerator == null ? 0 : accelerator.modifiers();
        switch (item.kind()) {
            case CHECK -> {
                return new MenuRow(id, MenuRow.Kind.CHECK, labelOf(item), item.isEnabled(), item.isChecked(),
                        item.mnemonic(), List.of(), item.icon(), key, modifiers);
            }
            case SUBMENU -> {
                Menu submenu = item.submenu();
                if (submenu == null || above.contains(submenu) || above.size() >= MAX_DEPTH) {
                    return new MenuRow(id, MenuRow.Kind.SUBMENU, labelOf(item), false, false, item.mnemonic(),
                            List.of(), item.icon(), "", 0);
                }
                boolean hadItems = !submenu.isEmpty();
                List<MenuRow> children = rows(submenu, above);
                if (children.isEmpty() && hadItems) {
                    return null; // emptied by the items the system took
                }
                return new MenuRow(id, MenuRow.Kind.SUBMENU, labelOf(item), item.isEnabled() && !children.isEmpty(),
                        false, item.mnemonic(), children, item.icon(), "", 0);
            }
            default -> {
                EditCommand edit = item.role().editCommand();
                if (edit == null && accelerator != null && onAnEditingChord(accelerator)) {
                    editingChordTaken = true;
                }
                // An editing command's row says only whether the application disabled the item:
                // whether the command can be done is asked of the window that has the keyboard.
                boolean enabled = edit == null ? item.isEnabled() : item.isEnabledByItsOwnFlag();
                return new MenuRow(id, MenuRow.Kind.COMMAND, labelOf(item), enabled, false, item.mnemonic(),
                        List.of(), item.icon(), key, modifiers, edit);
            }
        }
    }

    private static boolean onAnEditingChord(Accelerator accelerator) {
        int command = Accelerator.commandModifier();
        int modifiers = accelerator.modifiers();
        int key = accelerator.key();
        boolean plain = modifiers == command;
        boolean shifted = modifiers == (command | limn.input.Keys.MOD_SHIFT);
        return plain && (key == limn.input.Keys.Z || key == limn.input.Keys.X || key == limn.input.Keys.C
                || key == limn.input.Keys.V || key == limn.input.Keys.A || key == limn.input.Keys.Y)
                || shifted && key == limn.input.Keys.Z;
    }

    private static String labelOf(MenuItem item) {
        String label = item.label();
        return label == null ? "" : label;
    }
}
