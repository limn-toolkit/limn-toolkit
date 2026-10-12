package limn.backend.spi;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * The words the toolkit writes in a system's menu bar for the items that are not the
 * application's: those of the application's own menu and of a Window menu given to an
 * application that named none. In the application's language, with the wording the system uses.
 *
 * @param about      names the application
 * @param settings   opens the settings
 * @param services   the system's services
 * @param hide       names the application
 * @param hideOthers hides every other application
 * @param showAll    shows them again
 * @param quit       names the application
 * @param window     the Window menu's title
 * @param minimize   puts the window away
 * @param zoom       makes the window as large as its content asks
 */
public record SystemMenuLabels(String about, String settings, String services, String hide, String hideOthers,
        String showAll, String quit, String window, String minimize, String zoom) {

    private static final I18nString ABOUT = new I18nString("limn.appMenu.about", "About {0}");
    private static final I18nString SETTINGS = new I18nString("limn.appMenu.settings", "Settings…");
    private static final I18nString SERVICES = new I18nString("limn.appMenu.services", "Services");
    private static final I18nString HIDE = new I18nString("limn.appMenu.hide", "Hide {0}");
    private static final I18nString HIDE_OTHERS = new I18nString("limn.appMenu.hideOthers", "Hide Others");
    private static final I18nString SHOW_ALL = new I18nString("limn.appMenu.showAll", "Show All");
    private static final I18nString QUIT = new I18nString("limn.appMenu.quit", "Quit {0}");
    private static final I18nString WINDOW = new I18nString("limn.appMenu.window", "Window");
    private static final I18nString MINIMIZE = new I18nString("limn.appMenu.minimize", "Minimize");
    private static final I18nString ZOOM = new I18nString("limn.appMenu.zoom", "Zoom");

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/desktop"));
    }

    /**
     * @param applicationName the application's name, as the system shows it in its bar
     * @return the labels in the application's language now
     */
    public static SystemMenuLabels current(String applicationName) {
        return new SystemMenuLabels(ABOUT.format(applicationName), SETTINGS.get(), SERVICES.get(),
                HIDE.format(applicationName), HIDE_OTHERS.get(), SHOW_ALL.get(), QUIT.format(applicationName),
                WINDOW.get(), MINIMIZE.get(), ZOOM.get());
    }
}
