package limn.components.internal;

import java.lang.invoke.MethodHandles;
import limn.components.Menu;
import limn.components.MenuItem;
import org.jspecify.annotations.Nullable;

/**
 * How a package of this module that is not {@code limn.components} reads a {@link Menu} as a
 * model: what kind each item is, its submenu, and running what choosing it does. Those are not
 * public on {@link MenuItem}, whose only readers were the widgets beside it until a menu was
 * also something a system draws.
 */
public abstract class MenuBridge {

    /** What an item is. */
    public enum Kind { COMMAND, CHECK, SUBMENU, SEPARATOR }

    private static volatile @Nullable MenuBridge installed;

    /** For {@code limn.components} to subclass. */
    protected MenuBridge() {
    }

    /**
     * Called once, by {@code limn.components}.
     *
     * @param bridge its implementation
     */
    protected static void install(MenuBridge bridge) {
        installed = bridge;
    }

    /** @return the bridge, with {@code Menu} initialized so that it is there */
    public static MenuBridge get() {
        MenuBridge bridge = installed;
        if (bridge == null) {
            try {
                MethodHandles.lookup().ensureInitialized(Menu.class);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            bridge = installed;
            if (bridge == null) {
                throw new IllegalStateException("limn.components installed no menu bridge");
            }
        }
        return bridge;
    }

    /**
     * @param item an item
     * @return what it is
     */
    public abstract Kind kind(MenuItem item);

    /**
     * @param item an item
     * @return its submenu, or {@code null} when it is not one
     */
    public abstract @Nullable Menu submenu(MenuItem item);

    /**
     * @param item an item
     * @return the name of its accelerator's key with no modifier, or {@code ""} when it has none
     */
    public abstract String acceleratorKey(MenuItem item);

    /**
     * Does what the user's choosing the item does: runs a command, flips a check and tells its
     * listener. Nothing for a disabled item, a submenu or a separator.
     *
     * @param item the item chosen
     */
    public abstract void choose(MenuItem item);
}
