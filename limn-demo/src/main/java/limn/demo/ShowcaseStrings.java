package limn.demo;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * Every string of the demo's own window that is translated: the frame, the orders, the
 * dialogs and the menus.
 *
 * <p>One domain for one program, which is how an application is meant to use this: the
 * static block registers the family, so touching any constant here is what makes the
 * translations available. The pages of the gallery keep their specimens in English.
 */
final class ShowcaseStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/i18n/showcase"));
    }

    static final I18nString NAV_ORDERS =
            new I18nString("showcase.nav.orders", "Orders");
    static final I18nString NAV_COMPONENTS =
            new I18nString("showcase.nav.components", "Components");
    static final I18nString NAV_MEDIA =
            new I18nString("showcase.nav.media", "Media and 3D");
    static final I18nString NAV_INPUTS =
            new I18nString("showcase.nav.inputs", "Buttons and inputs");
    static final I18nString NAV_COMBOS =
            new I18nString("showcase.nav.combos", "Combo boxes");
    static final I18nString NAV_SLIDERS =
            new I18nString("showcase.nav.sliders", "Sliders and spinners");
    static final I18nString NAV_LISTS =
            new I18nString("showcase.nav.lists", "Lists");
    static final I18nString NAV_TABLES =
            new I18nString("showcase.nav.tables", "Tables");
    static final I18nString NAV_TREES =
            new I18nString("showcase.nav.trees", "Trees");
    static final I18nString NAV_DRAG_DROP =
            new I18nString("showcase.nav.dragDrop", "Drag and drop");
    static final I18nString NAV_CLIPBOARD =
            new I18nString("showcase.nav.clipboard", "Clipboard");
    static final I18nString NAV_TRAY =
            new I18nString("showcase.nav.tray", "Tray icon");
    static final I18nString NAV_CHARTS =
            new I18nString("showcase.nav.charts", "Charts");
    static final I18nString NAV_TEXT =
            new I18nString("showcase.nav.text", "Text and scripts");
    static final I18nString NAV_ICONS =
            new I18nString("showcase.nav.icons", "Icons");
    static final I18nString NAV_COLOURS =
            new I18nString("showcase.nav.colours", "Colours");
    static final I18nString NAV_SPLIT =
            new I18nString("showcase.nav.split", "Split panes");
    static final I18nString NAV_SECTIONS =
            new I18nString("showcase.nav.sections", "Tabs and sections");
    static final I18nString NAV_MOTION =
            new I18nString("showcase.nav.motion", "Motion");
    static final I18nString NAV_DIALOGS =
            new I18nString("showcase.nav.dialogs", "Dialogs");
    static final I18nString NAV_VIDEO =
            new I18nString("showcase.nav.video", "Video");
    static final I18nString NAV_VIEWPORT =
            new I18nString("showcase.nav.viewport", "3D viewport");
    static final I18nString NAV_SOUND =
            new I18nString("showcase.nav.sound", "Sound");
    static final I18nString NAV_SPRITES =
            new I18nString("showcase.nav.sprites", "Sprites");
    static final I18nString NAV_GLASS =
            new I18nString("showcase.nav.glass", "Backdrop panel");
    static final I18nString NAV_PAGES =
            new I18nString("showcase.nav.pages", "Pages");
    static final I18nString BAND_LANGUAGE =
            new I18nString("showcase.band.language", "Language");
    static final I18nString BAND_DIRECTION =
            new I18nString("showcase.band.direction", "Direction");
    static final I18nString BAND_SIZE =
            new I18nString("showcase.band.size", "Size");
    static final I18nString BAND_THEME =
            new I18nString("showcase.band.theme", "Theme");
    static final I18nString THEME_SYSTEM =
            new I18nString("showcase.theme.system", "System");
    static final I18nString MENU_GO =
            new I18nString("showcase.menu.go", "Go");
    static final I18nString MENU_HELP =
            new I18nString("showcase.menu.help", "Help");
    static final I18nString MENU_PERFORMANCE =
            new I18nString("showcase.menu.performance", "Performance");
    static final I18nString MENU_DOCUMENTATION =
            new I18nString("showcase.menu.documentation", "Documentation");
    static final I18nString MENU_NEW_ORDER =
            new I18nString("showcase.menu.newOrder", "New order");
    static final I18nString ORDERS_ORDER =
            new I18nString("showcase.orders.order", "Order");
    static final I18nString ORDERS_CUSTOMER =
            new I18nString("showcase.orders.customer", "Customer");
    static final I18nString ORDERS_PLACED =
            new I18nString("showcase.orders.placed", "Placed");
    static final I18nString ORDERS_STATUS =
            new I18nString("showcase.orders.status", "Status");
    static final I18nString ORDERS_TOTAL =
            new I18nString("showcase.orders.total", "Total");
    static final I18nString ORDERS_DUE =
            new I18nString("showcase.orders.due", "Due");
    static final I18nString ORDERS_NOTES =
            new I18nString("showcase.orders.notes", "Notes");
    static final I18nString ORDERS_OPEN =
            new I18nString("showcase.orders.open", "Open");
    static final I18nString ORDERS_SHIPPED =
            new I18nString("showcase.orders.shipped", "Shipped");
    static final I18nString ORDERS_PAID =
            new I18nString("showcase.orders.paid", "Paid");
    static final I18nString ORDERS_OVERDUE =
            new I18nString("showcase.orders.overdue", "Overdue");
    static final I18nString ORDERS_CANCELLED =
            new I18nString("showcase.orders.cancelled", "Cancelled");
    static final I18nString ORDERS_SEARCH_HINT =
            new I18nString("showcase.orders.searchHint", "Customer or number");
    static final I18nString ORDERS_ANY_STATUS =
            new I18nString("showcase.orders.anyStatus", "Any status");
    static final I18nString ORDERS_COUNT =
            new I18nString("showcase.orders.count", "{0} of {1}");
    static final I18nString ORDERS_HEADING =
            new I18nString("showcase.orders.heading", "Order #{0}");
    static final I18nString ORDERS_NONE =
            new I18nString("showcase.orders.none", "No order selected");
    static final I18nString ORDERS_REVERT =
            new I18nString("showcase.orders.revert", "Revert");
    static final I18nString ORDERS_SAVED =
            new I18nString("showcase.orders.saved", "Order #{0} saved.");
    static final I18nString ORDERS_CREATED =
            new I18nString("showcase.orders.created", "Order #{0} created.");
    static final I18nString ORDERS_CREATE =
            new I18nString("showcase.orders.create", "Create");
    static final I18nString ORDERS_REFRESH =
            new I18nString("showcase.orders.refresh", "Refresh");
    static final I18nString MENUS_RIGHT_CLICK =
            new I18nString("showcase.menus.rightClick", "Right-click here");
    static final I18nString STATUS_READY =
            new I18nString("showcase.status.ready", "Ready.");
    static final I18nString STATUS_LOADING =
            new I18nString("showcase.status.loading", "Loading…");
    static final I18nString STATUS_LOADED =
            new I18nString("showcase.status.loaded", "Loaded: {0} records");
    static final I18nString ACTION_SAVE =
            new I18nString("showcase.action.save", "Save");
    static final I18nString DIALOG_WINDOW_MODAL =
            new I18nString("showcase.dialog.windowModal", "Window-modal");
    static final I18nString DIALOG_APP_MODAL =
            new I18nString("showcase.dialog.appModal", "App-modal");
    static final I18nString DIALOG_NON_MODAL =
            new I18nString("showcase.dialog.nonModal", "Non-modal");
    static final I18nString DIALOG_STACKED =
            new I18nString("showcase.dialog.stacked", "Stacked (native → internal)");
    static final I18nString DIALOG_INTERNAL =
            new I18nString("showcase.dialog.internal", "Internal (in-scene)");
    static final I18nString DIALOG_ALWAYS_ON_TOP =
            new I18nString("showcase.dialog.alwaysOnTop", "Always on top");
    static final I18nString DIALOG_DECORATED =
            new I18nString("showcase.dialog.decorated", "Decorated (OS frame)");
    static final I18nString ABOUT_BLURB =
            new I18nString("showcase.about.blurb", "A Java GUI toolkit built from scratch on LWJGL, without AWT, Swing or SWT.");
    static final I18nString MENUS_BUTTON =
            new I18nString("showcase.menus.button", "Actions menu");
    static final I18nString MENUS_MODAL =
            new I18nString("showcase.menus.modal", "Modal (blocks the window)");
    static final I18nString TAB_MENUS =
            new I18nString("showcase.tab.menus", "Menus");
    static final I18nString TAB_DATES =
            new I18nString("showcase.tab.dates", "Dates");
    static final I18nString TAB_FILES =
            new I18nString("showcase.tab.files", "Files");
    static final I18nString TAB_ABOUT =
            new I18nString("showcase.tab.about", "About");
    static final I18nString RENDER_PARTIAL =
            new I18nString("showcase.render.partial", "Partial rendering");
    static final I18nString RENDER_DAMAGE =
            new I18nString("showcase.render.damage", "Damage debug");
    static final I18nString DIALOG_TITLE =
            new I18nString("showcase.dialog.title", "Dialog: {0}");
    static final I18nString DIALOG_MESSAGE =
            new I18nString("showcase.dialog.message", "This is a {0} dialog. Choose an option.");
    static final I18nString ACTION_CANCEL =
            new I18nString("showcase.action.cancel", "Cancel");
    static final I18nString ACTION_OK =
            new I18nString("showcase.action.ok", "OK");
    static final I18nString STATUS_WINDOW_MODAL =
            new I18nString("showcase.status.windowModal", "Window-modal dialog: {0}");
    static final I18nString STATUS_APP_MODAL =
            new I18nString("showcase.status.appModal", "App-modal dialog: {0}");
    static final I18nString STATUS_NON_MODAL =
            new I18nString("showcase.status.nonModal", "Non-modal dialog: {0}");
    static final I18nString STATUS_NEEDS_WINDOW =
            new I18nString("showcase.status.needsWindow", "Non-modal requires a native window. Turn off 'Internal'");
    static final I18nString DIALOG_LOWER =
            new I18nString("showcase.dialog.lower", "Lower dialog");
    static final I18nString DIALOG_LOWER_BODY =
            new I18nString("showcase.dialog.lowerBody", "Raised first, and always-on-top.");
    static final I18nString ACTION_CLOSE =
            new I18nString("showcase.action.close", "Close");
    static final I18nString STATUS_LOWER =
            new I18nString("showcase.status.lower", "Lower dialog: {0}");
    static final I18nString DIALOG_UPPER =
            new I18nString("showcase.dialog.upper", "Upper dialog");
    static final I18nString DIALOG_UPPER_BODY =
            new I18nString("showcase.dialog.upperBody", "Raised second, over a window the first one had locked. This one must be answerable.");
    static final I18nString STATUS_UPPER =
            new I18nString("showcase.status.upper", "Upper dialog: {0}");
    static final I18nString STATUS_UPPER_PROMOTED =
            new I18nString("showcase.status.upperPromoted", "Upper dialog asked for IN_SCENE and was promoted to a window");
    static final I18nString STATUS_UPPER_IN_SCENE =
            new I18nString("showcase.status.upperInScene", "Upper dialog stayed in-scene");
    static final I18nString STATUS_WINDOW_RESTORED =
            new I18nString("showcase.status.windowRestored", "Window restored");
    static final I18nString STATUS_FULLSCREEN_CURRENT =
            new I18nString("showcase.status.fullscreenCurrent", "Fullscreen (current resolution)");
    static final I18nString STATUS_FULLSCREEN_AT =
            new I18nString("showcase.status.fullscreenAt", "Exclusive fullscreen {0}×{1}");
    static final I18nString MENU_FILE =
            new I18nString("showcase.menu.file", "File");
    static final I18nString MENU_EDIT =
            new I18nString("showcase.menu.edit", "Edit");
    static final I18nString MENU_FULLSCREEN =
            new I18nString("showcase.menu.fullscreen", "Fullscreen");
    static final I18nString MENU_CURRENT_RESOLUTION =
            new I18nString("showcase.menu.currentResolution", "Current resolution");
    static final I18nString MENU_QUIT =
            new I18nString("showcase.menu.quit", "Quit");
    static final I18nString MENU_VIEW =
            new I18nString("showcase.menu.view", "View");
    static final I18nString MENU_COPY =
            new I18nString("showcase.menu.copy", "Copy");
    static final I18nString STATUS_COPY =
            new I18nString("showcase.status.copy", "Menu: Copy");
    static final I18nString MENU_PASTE =
            new I18nString("showcase.menu.paste", "Paste");
    static final I18nString STATUS_PASTE =
            new I18nString("showcase.status.paste", "Menu: Paste");
    static final I18nString MENU_BOLD =
            new I18nString("showcase.menu.bold", "Bold");
    static final I18nString STATUS_BOLD =
            new I18nString("showcase.status.bold", "Bold: {0}");
    static final I18nString MENU_ITALIC =
            new I18nString("showcase.menu.italic", "Italic");
    static final I18nString STATUS_ITALIC =
            new I18nString("showcase.status.italic", "Italic: {0}");
    static final I18nString MENU_EXPORT =
            new I18nString("showcase.menu.export", "Export");
    static final I18nString STATUS_EXPORT_PDF =
            new I18nString("showcase.status.exportPdf", "Export PDF");
    static final I18nString STATUS_EXPORT_PNG =
            new I18nString("showcase.status.exportPng", "Export PNG");
    static final I18nString STATUS_EXPORT_SVG =
            new I18nString("showcase.status.exportSvg", "Export SVG");
    static final I18nString MENU_PROPERTIES =
            new I18nString("showcase.menu.properties", "Properties…");

    private ShowcaseStrings() {
    }
}
