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
 * @param edit       the title of the Edit menu the toolkit gives a panel of the system's, whose
 *                   text fields take their editing chords from the bar
 * @param undo       the six editing commands of that menu
 * @param redo       likewise
 * @param cut        likewise
 * @param copy       likewise
 * @param paste      likewise
 * @param selectAll  likewise
 */
public record SystemMenuLabels(String about, String settings, String services, String hide, String hideOthers,
        String showAll, String quit, String window, String minimize, String zoom, String edit, String undo,
        String redo, String cut, String copy, String paste, String selectAll) {

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
    private static final I18nString EDIT = new I18nString("limn.appMenu.edit", "Edit");
    private static final I18nString UNDO = new I18nString("limn.appMenu.undo", "Undo");
    private static final I18nString REDO = new I18nString("limn.appMenu.redo", "Redo");
    private static final I18nString CUT = new I18nString("limn.appMenu.cut", "Cut");
    private static final I18nString COPY = new I18nString("limn.appMenu.copy", "Copy");
    private static final I18nString PASTE = new I18nString("limn.appMenu.paste", "Paste");
    private static final I18nString SELECT_ALL = new I18nString("limn.appMenu.selectAll", "Select All");

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
                WINDOW.get(), MINIMIZE.get(), ZOOM.get(), EDIT.get(), UNDO.get(), REDO.get(), CUT.get(),
                COPY.get(), PASTE.get(), SELECT_ALL.get());
    }
}
