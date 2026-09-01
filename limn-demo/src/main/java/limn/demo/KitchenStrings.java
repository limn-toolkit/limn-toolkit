package limn.demo;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * Every user-visible string of the Kitchen Sink.
 *
 * <p>One domain for one screen, which is how an application is meant to use this: the
 * static block registers the family, so touching any constant here is what makes the
 * translations available. Generated keys stay in one place so a translator gets one
 * file per screen instead of a diff across thirty.
 */
final class KitchenStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/i18n/kitchen"));
    }

    static final I18nString TITLE =
            new I18nString("kitchen.title", "Limn UI: Kitchen Sink");
    static final I18nString READY =
            new I18nString("kitchen.status.ready", "Ready.");
    static final I18nString LOAD_DATA =
            new I18nString("kitchen.action.loadData", "Load data");
    static final I18nString LOADING =
            new I18nString("kitchen.status.loading", "Loading…");
    static final I18nString LOADED =
            new I18nString("kitchen.status.loaded", "Loaded: {0} records");
    static final I18nString NAME_PLACEHOLDER =
            new I18nString("kitchen.form.namePlaceholder", "Type your name");
    static final I18nString STATUS_NAME =
            new I18nString("kitchen.status.name", "name: {0}");
    static final I18nString NAME =
            new I18nString("kitchen.form.name", "Name");
    static final I18nString PASSWORD =
            new I18nString("kitchen.form.password", "Password");
    static final I18nString REVEAL =
            new I18nString("kitchen.form.reveal", "reveal");
    static final I18nString FAVORITE_THEME =
            new I18nString("kitchen.form.favoriteTheme", "Favorite theme");
    static final I18nString STATUS_THEME =
            new I18nString("kitchen.status.theme", "theme: {0}");
    static final I18nString NOTIFICATIONS =
            new I18nString("kitchen.form.notifications", "Notifications");
    static final I18nString AIRPLANE_MODE =
            new I18nString("kitchen.form.airplaneMode", "Airplane mode");
    static final I18nString TEXT_ELLIPSIS =
            new I18nString("kitchen.text.ellipsis", "Measured ellipsis at 3 widths");
    static final I18nString TEXT_MULTILINE =
            new I18nString("kitchen.text.multiline", "Multiline TextArea");
    static final I18nString TEXT_SOFT_WRAP =
            new I18nString("kitchen.text.softWrap", "Soft wrap");
    static final I18nString SAVE =
            new I18nString("kitchen.action.save", "Save");
    static final I18nString SAVED =
            new I18nString("kitchen.status.saved", "Saved!");
    static final I18nString DOWNLOAD =
            new I18nString("kitchen.action.download", "Download");
    static final I18nString DISABLED =
            new I18nString("kitchen.action.disabled", "Disabled");
    static final I18nString WINDOW_MODAL =
            new I18nString("kitchen.dialog.windowModal", "Window-modal");
    static final I18nString APP_MODAL =
            new I18nString("kitchen.dialog.appModal", "App-modal");
    static final I18nString NON_MODAL =
            new I18nString("kitchen.dialog.nonModal", "Non-modal");
    static final I18nString STACKED =
            new I18nString("kitchen.dialog.stacked", "Stacked (native → internal)");
    static final I18nString INTERNAL =
            new I18nString("kitchen.dialog.internal", "Internal (in-scene)");
    static final I18nString ALWAYS_ON_TOP =
            new I18nString("kitchen.dialog.alwaysOnTop", "Always on top");
    static final I18nString DECORATED =
            new I18nString("kitchen.dialog.decorated", "Decorated (OS frame)");
    static final I18nString PLAY_SOUND =
            new I18nString("kitchen.action.playSound", "Play sound (synthesis)");
    static final I18nString FULLSCREEN_NATIVE =
            new I18nString("kitchen.action.fullscreenNative", "Fullscreen (native)");
    static final I18nString FULLSCREEN_RES =
            new I18nString("kitchen.action.fullscreenRes", "Fullscreen 1280×720");
    static final I18nString ACTIONS_BUTTONS =
            new I18nString("kitchen.actions.buttons", "Buttons (primary, secondary, icon, disabled)");
    static final I18nString ACTIONS_PROGRESS =
            new I18nString("kitchen.actions.progress", "ProgressBar 40% / 80% / indeterminate");
    static final I18nString ACTIONS_DIALOGS =
            new I18nString("kitchen.actions.dialogs", "Dialogs: 3 scopes (window / app / non-modal), plus two stacked");
    static final I18nString ACTIONS_REPORTS =
            new I18nString("kitchen.actions.reports", "Every dialog reports its result below, including one closed by the OS frame's own button (turn on Decorated), which reports as cancelled.");
    static final I18nString ACTIONS_AUDIO =
            new I18nString("kitchen.actions.audio", "Audio (limn.sound) and window");
    static final I18nString ABOUT_NAME =
            new I18nString("kitchen.about.name", "Limn UI");
    static final I18nString ABOUT_BLURB =
            new I18nString("kitchen.about.blurb", "A Java GUI toolkit built from scratch on LWJGL, without AWT, Swing or SWT.");
    static final I18nString ABOUT_ICONS =
            new I18nString("kitchen.about.icons", "Icons tinted by the theme");
    static final I18nString MENUS_BUTTON =
            new I18nString("kitchen.menus.button", "Actions menu");
    static final I18nString MENUS_MODAL =
            new I18nString("kitchen.menus.modal", "Modal (blocks the window)");
    static final I18nString MENUS_DROPDOWN =
            new I18nString("kitchen.menus.dropdown", "Dropdown from a button (submenus, checkable items, separators, disabled entries):");
    static final I18nString MENUS_CONTEXT =
            new I18nString("kitchen.menus.context", "Context menu (right-click the area below):");
    static final I18nString MENUS_MENUBAR =
            new I18nString("kitchen.menus.menubar", "The MenuBar at the top (File / Edit / View) also opens native menus with submenus.");
    static final I18nString TAB_FORM =
            new I18nString("kitchen.tab.form", "Form");
    static final I18nString TAB_TEXT =
            new I18nString("kitchen.tab.text", "Text");
    static final I18nString TAB_ACTIONS =
            new I18nString("kitchen.tab.actions", "Actions");
    static final I18nString TAB_MENUS =
            new I18nString("kitchen.tab.menus", "Menus");
    static final I18nString TAB_LIST =
            new I18nString("kitchen.tab.list", "List");
    static final I18nString TAB_CONTROLS =
            new I18nString("kitchen.tab.controls", "Controls");
    static final I18nString TAB_FILES =
            new I18nString("kitchen.tab.files", "Files");
    static final I18nString TAB_ABOUT =
            new I18nString("kitchen.tab.about", "About");
    static final I18nString RENDER_PARTIAL =
            new I18nString("kitchen.render.partial", "Partial rendering");
    static final I18nString RENDER_PARTIAL_TIP =
            new I18nString("kitchen.render.partialTip", "Repaint only the damaged region instead of the whole window");
    static final I18nString RENDER_DAMAGE =
            new I18nString("kitchen.render.damage", "Damage debug");
    static final I18nString RENDER_DAMAGE_TIP =
            new I18nString("kitchen.render.damageTip", "Highlight each frame's damage region (magenta)");
    static final I18nString LAUNCH_CUBE =
            new I18nString("kitchen.render.launchCube", "Launch cube");
    static final I18nString LAUNCH_CUBE_TIP =
            new I18nString("kitchen.render.launchCubeTip", "Adds a transparent 3D cube bouncing around the desktop (drag to grab, right-click to dismiss, P toggles the hit-test mode)");
    static final I18nString CLEAR_CUBES =
            new I18nString("kitchen.render.clearCubes", "Clear cubes");
    static final I18nString CLEAR_CUBES_TIP =
            new I18nString("kitchen.render.clearCubesTip", "Closes the desktop cube overlay, all cubes at once");
    static final I18nString DIALOG_TITLE =
            new I18nString("kitchen.dialog.title", "Dialog: {0}");
    static final I18nString DIALOG_MESSAGE =
            new I18nString("kitchen.dialog.message", "This is a {0} dialog. Choose an option.");
    static final I18nString CANCEL =
            new I18nString("kitchen.action.cancel", "Cancel");
    static final I18nString OK =
            new I18nString("kitchen.action.ok", "OK");
    static final I18nString STATUS_WINDOW_MODAL =
            new I18nString("kitchen.status.windowModal", "Window-modal dialog: {0}");
    static final I18nString STATUS_APP_MODAL =
            new I18nString("kitchen.status.appModal", "App-modal dialog: {0}");
    static final I18nString STATUS_NON_MODAL =
            new I18nString("kitchen.status.nonModal", "Non-modal dialog: {0}");
    static final I18nString STATUS_NEEDS_WINDOW =
            new I18nString("kitchen.status.needsWindow", "Non-modal requires a native window. Turn off 'Internal'");
    static final I18nString DIALOG_LOWER =
            new I18nString("kitchen.dialog.lower", "Lower dialog");
    static final I18nString DIALOG_LOWER_BODY =
            new I18nString("kitchen.dialog.lowerBody", "Raised first, and always-on-top.");
    static final I18nString CLOSE =
            new I18nString("kitchen.action.close", "Close");
    static final I18nString STATUS_LOWER =
            new I18nString("kitchen.status.lower", "Lower dialog: {0}");
    static final I18nString DIALOG_UPPER =
            new I18nString("kitchen.dialog.upper", "Upper dialog");
    static final I18nString DIALOG_UPPER_BODY =
            new I18nString("kitchen.dialog.upperBody", "Raised second, over a window the first one had locked. This one must be answerable.");
    static final I18nString STATUS_UPPER =
            new I18nString("kitchen.status.upper", "Upper dialog: {0}");
    static final I18nString STATUS_UPPER_PROMOTED =
            new I18nString("kitchen.status.upperPromoted", "Upper dialog asked for IN_SCENE and was promoted to a window");
    static final I18nString STATUS_UPPER_IN_SCENE =
            new I18nString("kitchen.status.upperInScene", "Upper dialog stayed in-scene");
    static final I18nString STATUS_SOUND =
            new I18nString("kitchen.status.soundPlayed", "Sound played via limn.sound");
    static final I18nString STATUS_SOUND_NO_DEVICE =
            new I18nString("kitchen.status.soundNoDevice", "Sound played via limn.sound (no audio device)");
    static final I18nString STATUS_WINDOW_RESTORED =
            new I18nString("kitchen.status.windowRestored", "Window restored");
    static final I18nString STATUS_FS_CURRENT =
            new I18nString("kitchen.status.fullscreenCurrent", "Exclusive fullscreen (current resolution)");
    static final I18nString STATUS_FS_AT =
            new I18nString("kitchen.status.fullscreenAt", "Exclusive fullscreen {0}×{1}");
    static final I18nString STATUS_THEME_CHANGED =
            new I18nString("kitchen.status.themeChanged", "Theme: {0}");
    static final I18nString MENU_FILE =
            new I18nString("kitchen.menu.file", "File");
    static final I18nString MENU_OPEN_RECENT =
            new I18nString("kitchen.menu.openRecent", "Open recent");
    static final I18nString MENU_FULLSCREEN =
            new I18nString("kitchen.menu.fullscreen", "Fullscreen");
    static final I18nString MENU_CURRENT_RES =
            new I18nString("kitchen.menu.currentResolution", "Current resolution");
    static final I18nString MENU_QUIT =
            new I18nString("kitchen.menu.quit", "Quit");
    static final I18nString STATUS_QUIT =
            new I18nString("kitchen.status.quit", "File → Quit (demo)");
    static final I18nString MENU_EDIT =
            new I18nString("kitchen.menu.edit", "Edit");
    static final I18nString MENU_UNDO =
            new I18nString("kitchen.menu.undo", "Undo");
    static final I18nString STATUS_UNDO =
            new I18nString("kitchen.status.undo", "Edit → Undo");
    static final I18nString MENU_REDO =
            new I18nString("kitchen.menu.redo", "Redo");
    static final I18nString STATUS_NOTIFICATIONS =
            new I18nString("kitchen.status.notifications", "Notifications: {0}");
    static final I18nString STATUS_AIRPLANE =
            new I18nString("kitchen.status.airplane", "Airplane mode: {0}");
    static final I18nString MENU_VIEW =
            new I18nString("kitchen.menu.view", "View");
    static final I18nString MENU_GO_TO_TAB =
            new I18nString("kitchen.menu.goToTab", "Go to tab");
    static final I18nString MENU_TOGGLE_THEME =
            new I18nString("kitchen.menu.toggleTheme", "Toggle theme");
    static final I18nString MENU_CLEAR =
            new I18nString("kitchen.menu.clearMenu", "Clear menu");
    static final I18nString STATUS_RECENT_CLEARED =
            new I18nString("kitchen.status.recentCleared", "Recent list cleared");
    static final I18nString MENU_COPY =
            new I18nString("kitchen.menu.copy", "Copy");
    static final I18nString STATUS_COPY =
            new I18nString("kitchen.status.copy", "Menu: Copy");
    static final I18nString MENU_PASTE =
            new I18nString("kitchen.menu.paste", "Paste");
    static final I18nString STATUS_PASTE =
            new I18nString("kitchen.status.paste", "Menu: Paste");
    static final I18nString MENU_BOLD =
            new I18nString("kitchen.menu.bold", "Bold");
    static final I18nString STATUS_BOLD =
            new I18nString("kitchen.status.bold", "Bold: {0}");
    static final I18nString MENU_ITALIC =
            new I18nString("kitchen.menu.italic", "Italic");
    static final I18nString STATUS_ITALIC =
            new I18nString("kitchen.status.italic", "Italic: {0}");
    static final I18nString MENU_EXPORT =
            new I18nString("kitchen.menu.export", "Export");
    static final I18nString STATUS_EXPORT_PDF =
            new I18nString("kitchen.status.exportPdf", "Export PDF");
    static final I18nString STATUS_EXPORT_PNG =
            new I18nString("kitchen.status.exportPng", "Export PNG");
    static final I18nString STATUS_EXPORT_SVG =
            new I18nString("kitchen.status.exportSvg", "Export SVG");
    static final I18nString MENU_PROPERTIES =
            new I18nString("kitchen.menu.properties", "Properties…");

    private KitchenStrings() {
    }
}
