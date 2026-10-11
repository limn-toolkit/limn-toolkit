package limn.components;

import limn.components.internal.MenuBridge;
import limn.scene.Change;
import org.jspecify.annotations.Nullable;

/** This package's side of {@link MenuBridge}: the three readings a system-drawn menu needs. */
final class MenuBridgeAccess extends MenuBridge {

    private MenuBridgeAccess() {
    }

    /** Installs the bridge; called from {@link Menu}'s initializer. */
    static void installOnce() {
        install(new MenuBridgeAccess());
    }

    @Override
    public Kind kind(MenuItem item) {
        return switch (item.kind()) {
            case COMMAND -> Kind.COMMAND;
            case CHECK -> Kind.CHECK;
            case SUBMENU -> Kind.SUBMENU;
            case SEPARATOR -> Kind.SEPARATOR;
        };
    }

    @Override
    public @Nullable Menu submenu(MenuItem item) {
        return item.submenu();
    }

    @Override
    public void choose(MenuItem item) {
        if (item.isSelectable()) {
            item.activate(Change.Origin.USER);
        }
    }
}
