package limn.desktop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import limn.backend.spi.TrayRow;
import limn.components.Menu;
import limn.components.MenuItem;
import limn.components.internal.MenuBridge;
import org.jspecify.annotations.Nullable;

/**
 * One reading of a {@link Menu} as the rows a system draws, and which item each row's number
 * stands for.
 */
final class TrayMenu {

    /** As deep as a menu is read; a submenu below it is a row that opens nothing. */
    static final int MAX_DEPTH = 16;

    private final MenuBridge bridge = MenuBridge.get();
    private final Map<Integer, MenuItem> items = new HashMap<>();
    private final List<TrayRow> rows;
    private int next = 1;

    private TrayMenu(@Nullable Menu menu) {
        rows = menu == null ? List.of() : read(menu, new ArrayList<>());
    }

    /**
     * @param menu the application's menu, or {@code null}
     * @return its rows now
     */
    static TrayMenu read(@Nullable Menu menu) {
        return new TrayMenu(menu);
    }

    List<TrayRow> rows() {
        return rows;
    }

    /** @return the item a row's number stands for in this reading, or {@code null} */
    @Nullable MenuItem item(int id) {
        return items.get(id);
    }

    /**
     * @param menu a menu
     * @return whether a submenu below it, at any depth this reader goes to, is one of the menus
     *         above it
     */
    static boolean containsItself(Menu menu) {
        return containsItself(menu, new ArrayList<>(), MenuBridge.get());
    }

    private static boolean containsItself(Menu menu, List<Menu> above, MenuBridge bridge) {
        if (above.contains(menu)) {
            return true;
        }
        if (above.size() >= MAX_DEPTH) {
            return false;
        }
        above.add(menu);
        for (MenuItem item : menu.items()) {
            Menu submenu = bridge.submenu(item);
            if (submenu != null && containsItself(submenu, above, bridge)) {
                return true;
            }
        }
        above.remove(above.size() - 1);
        return false;
    }

    private List<TrayRow> read(Menu menu, List<Menu> above) {
        above.add(menu);
        List<TrayRow> out = new ArrayList<>();
        boolean separatorOwed = false;
        for (MenuItem item : menu.items()) {
            MenuBridge.Kind kind = bridge.kind(item);
            if (kind == MenuBridge.Kind.SEPARATOR) {
                // Owed, and paid only before a row that follows one: never first, last or doubled.
                separatorOwed = !out.isEmpty();
                continue;
            }
            if (separatorOwed) {
                out.add(new TrayRow(next++, TrayRow.Kind.SEPARATOR, "", false, false, (char) 0, List.of()));
                separatorOwed = false;
            }
            int id = next++;
            items.put(id, item);
            String label = item.label() == null ? "" : item.label();
            switch (kind) {
                case CHECK -> out.add(new TrayRow(id, TrayRow.Kind.CHECK, label, item.isEnabled(),
                        item.isChecked(), item.mnemonic(), List.of()));
                case SUBMENU -> {
                    Menu submenu = bridge.submenu(item);
                    // A menu that is its own ancestor, or one deeper than anybody opens, opens nothing.
                    List<TrayRow> children = submenu == null || above.contains(submenu)
                            || above.size() >= MAX_DEPTH ? List.of() : read(submenu, above);
                    out.add(new TrayRow(id, TrayRow.Kind.SUBMENU, label,
                            item.isEnabled() && !children.isEmpty(), false, item.mnemonic(), children));
                }
                default -> out.add(new TrayRow(id, TrayRow.Kind.COMMAND, label, item.isEnabled(), false,
                        item.mnemonic(), List.of()));
            }
        }
        above.remove(above.size() - 1);
        return out;
    }

    /** Does what choosing the row does, when the row is one of this reading and can be chosen. */
    void choose(int id) {
        MenuItem item = items.get(id);
        if (item != null) {
            bridge.choose(item);
        }
    }
}
