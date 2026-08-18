package limn.components;

import limn.i18n.I18nString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * An ordered list of {@link MenuItem}s: the reusable content of a
 * {@link PopupMenu} (context menu / dropdown), a {@link MenuBar} top-level
 * entry, or a submenu. Pure model; presenters render it.
 *
 * <pre>{@code
 * Menu file = new Menu()
 *     .addItem("New", () -> newDoc())
 *     .addItem("Open…", () -> open())
 *     .addSeparator()
 *     .addSubmenu("Recentes", recentsMenu);
 * }</pre>
 */
public final class Menu {

    private final List<MenuItem> items = new ArrayList<>();
    private final List<MenuItem> view = Collections.unmodifiableList(items);
    private int modCount;

    /** Appends an item, including a {@linkplain MenuItem#separator() separator}. */
    public Menu add(MenuItem item) {
        items.add(Objects.requireNonNull(item, "item"));
        modCount++; // every future mutator must bump this (presenters resync on it)
        return this;
    }

    /** Bumped on every mutation; open presenters rebuild their snapshot when it moves. */
    int modCount() {
        return modCount;
    }

    /** Adds a command item. */
    public Menu addItem(I18nString label, Runnable action) {
        return add(MenuItem.of(label, action));
    }

    /** A checkable item whose label follows the UI language. */
    public Menu addCheck(I18nString label, boolean checked, Consumer<Boolean> onToggle) {
        return add(MenuItem.check(label, checked, onToggle));
    }

    /** A nested menu whose label follows the UI language. */
    public Menu addSubmenu(I18nString label, Menu submenu) {
        return add(MenuItem.submenu(label, submenu));
    }

    /** A command item with a fixed label. */
    public Menu addItem(String label, Runnable action) {
        return add(MenuItem.of(label, action));
    }

    /** Adds a checkable item. */
    public Menu addCheck(String label, boolean checked, Consumer<Boolean> onToggle) {
        return add(MenuItem.check(label, checked, onToggle));
    }

    /** Adds a submenu item. */
    public Menu addSubmenu(String label, Menu submenu) {
        return add(MenuItem.submenu(label, submenu));
    }

    /** Adds a divider (collapsed if it would be leading/trailing/doubled at render time). */
    public Menu addSeparator() {
        return add(MenuItem.separator());
    }

    /**
     * Removes every item, so a menu whose contents are <em>data</em> (recent
     * files, open windows, connected devices) can be rebuilt from that data.
     *
     * <p>Rebuilt in place rather than replaced, and that is the reason this
     * exists: a {@link MenuBar} entry and a {@link MenuItem#submenu} both hold
     * the {@code Menu} instance, so handing them a fresh one would leave every
     * holder pointing at the list that is no longer current. Clearing bumps
     * {@link #modCount()} like any other mutation, so a popup already on screen
     * rebuilds its geometry instead of drawing rows that are gone.
     */
    public Menu clear() {
        items.clear();
        modCount++;
        return this;
    }

    /** The items, in order, an unmodifiable view. */
    public List<MenuItem> items() {
        return view;
    }

    /**
     * Depth cap for the accelerator walk. A submenu is a plain reference, so an application can
     * build a cycle (a menu reachable from its own submenu); the cap is what makes the walk
     * terminate on one instead of recursing until the stack ends. Deeper than this is not a menu
     * anyone can use with a pointer.
     */
    private static final int MAX_SUBMENU_DEPTH = 16;

    /**
     * @return the first enabled item in this menu or any submenu below it whose accelerator is
     *         exactly {@code (key, modifiers)}, or {@code null}. Depth-first in declaration
     *         order, so the shallowest declaration of a duplicated chord wins.
     */
    MenuItem findAccelerator(int key, int modifiers) {
        return findAccelerator(key, modifiers, 0);
    }

    private MenuItem findAccelerator(int key, int modifiers, int depth) {
        if (depth >= MAX_SUBMENU_DEPTH) {
            return null;
        }
        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            Accelerator accelerator = item.accelerator();
            // isSelectable() rather than isEnabled(): a disabled row must not be reachable by its
            // chord any more than by the pointer, and that is the one predicate both go through.
            if (accelerator != null && item.isSelectable() && accelerator.matches(key, modifiers)) {
                return item;
            }
            if (item.hasSubmenu() && item.isEnabled()) {
                MenuItem found = item.submenu().findAccelerator(key, modifiers, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Whether this menu has no items at all. */
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
