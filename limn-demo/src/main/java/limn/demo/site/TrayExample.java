package limn.demo.site;

import limn.backend.Backend;
import limn.backend.ClosePolicy;
import limn.backend.LoopLifetime;
import limn.backend.NativeWindow;
import limn.components.Button;
import limn.components.Menu;
import limn.components.MenuItem;
import limn.desktop.Tray;
import limn.graphics.Icon;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;

/**
 * The worked examples the guide shows for an icon in the system's tray. Compiled by
 * {@code ./gradlew check}, so the sample a reader copies builds.
 */
public final class TrayExample {

    private TrayExample() {
    }

    // #region guide:tray
    /** An icon with a menu, and a click that brings the window. */
    static Tray ordersIcon(Backend backend, NativeWindow window, Icon icon, Icon shape) {
        MenuItem paused = MenuItem.check("Pause syncing", false, on -> { });
        Tray tray = Tray.create(backend, "orders");        // the same identifier on every run
        tray.setTitle("Orders")
                .setIcon(icon)                             // shown as it is
                .setMonochromeIcon(shape)                  // a shape that takes the panel's colour
                .setTooltip("Orders: 3 waiting")
                .setMenu(new Menu()
                        .addItem("Open Orders", () -> bring(window))
                        .add(paused)
                        .addSeparator()
                        .addItem("Quit", backend::requestQuit))
                .onActivate(() -> bring(window));          // also an item of the menu, above
        return tray;
    }

    static void bring(NativeWindow window) {
        window.toFront();   // shown if hidden, restored if minimized, in front and focused
    }
    // #endregion

    // #region guide:tray-close
    /** Closing the window leaves the application in the tray, where the desktop has one. */
    static void closeToTray(Backend backend, NativeWindow window, Tray tray) {
        backend.setLoopLifetime(LoopLifetime.UNTIL_QUIT);  // the loop outlives its windows
        Runnable choose = () -> window.setClosePolicy(tray.isAvailable()
                ? ClosePolicy.HIDE                         // the icon is the way back
                : ClosePolicy.END_APPLICATION);            // no tray: a hidden window would be lost
        choose.run();
        tray.onAvailabilityChange(choose);                 // a tray can appear or go while running
    }
    // #endregion

    // #region guide:tray-standin
    /**
     * For an application with no main window, on a desktop with no tray: a small window of its
     * commands that lives minimized in the dock. Brought back by the user, it shows them; one
     * chosen, it minimizes again.
     */
    static Widget<?> standIn(Tray tray, NativeWindow small, Runnable preferences, Runnable quit) {
        Button open = new Button("Preferences").setSecondary(true);
        Button end = new Button("Quit").setSecondary(true);
        open.onAction(() -> {
            preferences.run();
            small.minimize();
        });
        end.onAction(quit);
        Runnable choose = () -> {
            if (tray.isAvailable()) {
                small.hide();                              // the icon does this job
            } else {
                small.show();
                small.minimize();
            }
        };
        choose.run();
        tray.onAvailabilityChange(choose);
        Column commands = new Column();
        commands.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        commands.add(open);
        commands.add(end);
        return commands;
    }
    // #endregion
}
