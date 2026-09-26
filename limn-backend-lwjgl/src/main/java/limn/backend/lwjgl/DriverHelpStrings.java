package limn.backend.lwjgl;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * The text of {@link WindowsDriverDialog}: what a person who opened an application from its icon
 * reads when this Windows machine has no OpenGL 3.3 driver.
 *
 * <p>A family of its own rather than more keys in {@link DisplayStrings}': it is read at most once,
 * by a process that is about to stop, so a process that never fails never opens the file. None of
 * it is parameterized, so no translation pays {@code MessageFormat}'s apostrophe rule. The
 * Compatibility Pack keeps its English name in every language, quoted: it is the name the Store
 * search and winget both answer to.
 *
 * <p>Windows draws this text, not Limn, so the locales the text pipeline cannot yet draw (see
 * docs/design/i18n.md) read correctly here.
 */
final class DriverHelpStrings {

    static {
        // Same reason as DisplayStrings: on the module path the catalog sits in a package of the
        // backend's that only this module can read.
        I18n.addBundle(PropertyBundle.family("/limn/backend/lwjgl/i18n/driverhelp",
                name -> DriverHelpStrings.class.getResourceAsStream(name)));
    }

    /** The dialog's main instruction, the one line in larger type. */
    static final I18nString HEADING = new I18nString("limn.driverHelp.heading",
            "This computer has no graphics driver the application can use");

    /** What happened, where it happens, and the two fixes in the order to try them. */
    static final I18nString BODY = new I18nString("limn.driverHelp.body",
            "The application needs OpenGL 3.3, and Windows found no driver that provides it. This "
                    + "happens in virtual machines, in remote-desktop sessions and on graphics cards "
                    + "whose driver offers only Direct3D.\n\nIf the computer has a graphics card, "
                    + "install or update the driver from its maker. Otherwise install Microsoft's "
                    + "free compatibility pack, then open the application again.");

    /** The Store button's caption. */
    static final I18nString STORE = new I18nString("limn.driverHelp.store",
            "Open the Microsoft Store");

    /** The line under the Store button's caption. */
    static final I18nString STORE_NOTE = new I18nString("limn.driverHelp.storeNote",
            "Install the free “OpenCL, OpenGL, and Vulkan Compatibility Pack”");

    /** The winget button's caption. */
    static final I18nString WINGET = new I18nString("limn.driverHelp.winget",
            "Install with winget");

    /** The line under the winget button's caption, followed on the next line by the command. */
    static final I18nString WINGET_NOTE = new I18nString("limn.driverHelp.wingetNote",
            "Opens a command window that runs:");

    /**
     * The closing question of the plain message box, used only where the task dialog cannot be
     * had: its buttons are Yes and No, so the offer has to be a sentence.
     */
    static final I18nString ASK_STORE = new I18nString("limn.driverHelp.askStore",
            "Open the Microsoft Store to install the free “OpenCL, OpenGL, and Vulkan "
                    + "Compatibility Pack” now?");

    private DriverHelpStrings() {
    }
}
