package limn.demo;

import limn.backend.NativeWindow;
import limn.backend.WindowStyle;
import limn.components.Accelerator;
import limn.components.Button;
import limn.components.Card;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.ContextMenus;
import limn.components.Dialog;
import limn.components.DisplayMode;
import limn.components.Field;
import limn.components.Label;
import limn.components.ListView;
import limn.components.Menu;
import limn.components.MenuItem;
import limn.components.PopupMenu;
import limn.components.SelectionMode;
import limn.components.TextField;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.i18n.I18nString;
import limn.icons.tabler.Tabler;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The two pages of the demo that open something over the window: the dialogs an order desk
 * raises, in each scope of modality, and the menus of a folder of files, from a button, a right
 * click and the keyboard.
 *
 * <p>Every way out of a dialog reports in the status line, the one closed by the system's own
 * button included: a caller that tidies up when the answer comes is left waiting by a dialog
 * that ends without one, and nothing else on screen would say so.
 */
final class DialogsScene {

    /** Every string the two pages show. */
    static final class Text {
        static final I18nString ACTIONS = new I18nString("showcase.dialogs.actions", "Order desk");
        static final I18nString DELETE_ORDER = new I18nString("showcase.dialogs.deleteOrder", "Delete order…");
        static final I18nString PREFERENCES = new I18nString("showcase.dialogs.preferences", "Preferences…");
        static final I18nString EDIT_CUSTOMER = new I18nString("showcase.dialogs.editCustomer", "Edit customer…");
        static final I18nString RESTORE = new I18nString("showcase.dialogs.restore", "Restore backup…");
        static final I18nString IN_WINDOW = new I18nString("showcase.dialogs.inWindow", "Inside the window");
        static final I18nString ON_TOP = new I18nString("showcase.dialogs.onTop", "Always on top");
        static final I18nString SYSTEM_FRAME = new I18nString("showcase.dialogs.systemFrame", "System frame");
        static final I18nString COMPACT = new I18nString("showcase.dialogs.compact", "Compact");

        static final I18nString DELETE_TITLE = new I18nString("showcase.dialogs.deleteTitle", "Delete order #1042?");
        static final I18nString DELETE_BODY = new I18nString("showcase.dialogs.deleteBody",
                "The order and its invoices are removed for good. This cannot be undone.");
        static final I18nString DELETE = new I18nString("showcase.dialogs.delete", "Delete");
        static final I18nString DELETED = new I18nString("showcase.dialogs.deleted", "Order #1042 deleted.");
        static final I18nString KEPT = new I18nString("showcase.dialogs.kept", "Order #1042 kept.");

        static final I18nString PREFERENCES_TITLE = new I18nString("showcase.dialogs.preferencesTitle", "Preferences");
        static final I18nString CURRENCY = new I18nString("showcase.dialogs.currency", "Currency");
        static final I18nString WITH_TAX = new I18nString("showcase.dialogs.withTax", "Prices include tax");
        static final I18nString REOPEN = new I18nString("showcase.dialogs.reopen", "Reopen the last order on start");
        static final I18nString CURRENCY_SET = new I18nString("showcase.dialogs.currencySet", "Prices shown in {0}.");
        static final I18nString TAX_ON = new I18nString("showcase.dialogs.taxOn", "Prices now include tax.");
        static final I18nString TAX_OFF = new I18nString("showcase.dialogs.taxOff", "Prices now exclude tax.");
        static final I18nString PREFERENCES_CLOSED = new I18nString("showcase.dialogs.preferencesClosed", "Preferences closed.");

        static final I18nString CUSTOMER_TITLE = new I18nString("showcase.dialogs.customerTitle", "Edit customer");
        static final I18nString NAME = new I18nString("showcase.dialogs.name", "Name");
        static final I18nString EMAIL = new I18nString("showcase.dialogs.email", "Email");
        static final I18nString UNSAVED_TITLE = new I18nString("showcase.dialogs.unsavedTitle",
                "Save changes to {0}?");
        static final I18nString UNSAVED_BODY = new I18nString("showcase.dialogs.unsavedBody",
                "Your changes are lost if you don't save them.");
        static final I18nString DONT_SAVE = new I18nString("showcase.dialogs.dontSave", "Don't save");
        static final I18nString SAVED = new I18nString("showcase.dialogs.saved", "{0} saved.");
        static final I18nString DISCARDED = new I18nString("showcase.dialogs.discarded", "Changes to {0} discarded.");
        static final I18nString UNCHANGED = new I18nString("showcase.dialogs.unchanged", "{0} not changed.");
        static final I18nString STILL_EDITING = new I18nString("showcase.dialogs.stillEditing", "Still editing {0}.");

        static final I18nString RESTORE_TITLE = new I18nString("showcase.dialogs.restoreTitle", "Restore last night's backup?");
        static final I18nString RESTORE_BODY = new I18nString("showcase.dialogs.restoreBody",
                "Every window waits until the restore is done, so nothing is edited while the data is replaced.");
        static final I18nString RESTORE_ACTION = new I18nString("showcase.dialogs.restoreAction", "Restore");
        static final I18nString RESTORED = new I18nString("showcase.dialogs.restored", "Backup restored.");
        static final I18nString NOT_RESTORED = new I18nString("showcase.dialogs.notRestored", "Backup not restored.");

        static final I18nString ALERTS = new I18nString("showcase.dialogs.alerts", "Alerts");
        static final I18nString INFORMATION = new I18nString("showcase.dialogs.information", "Information");
        static final I18nString WARNING = new I18nString("showcase.dialogs.warning", "Warning");
        static final I18nString ERROR = new I18nString("showcase.dialogs.error", "Error");
        static final I18nString ALERTS_HINT = new I18nString("showcase.dialogs.alertsHint",
                "Click outside an alert to close it.");
        static final I18nString INFO_TITLE = new I18nString("showcase.dialogs.infoTitle", "Price list updated");
        static final I18nString INFO_BODY = new I18nString("showcase.dialogs.infoBody",
                "The new prices apply to orders placed from tomorrow.");
        static final I18nString WARNING_TITLE = new I18nString("showcase.dialogs.warningTitle", "Stock running low");
        static final I18nString WARNING_BODY = new I18nString("showcase.dialogs.warningBody",
                "Three products are below their minimum stock. Reorder them before Friday.");
        static final I18nString ERROR_TITLE = new I18nString("showcase.dialogs.errorTitle", "Invoice not sent");
        static final I18nString ERROR_BODY = new I18nString("showcase.dialogs.errorBody",
                "The mail server did not answer. Invoice 1042 stays in the outbox.");
        static final I18nString ALERT_CLOSED = new I18nString("showcase.dialogs.alertClosed", "Alert closed.");

        static final I18nString WINDOW = new I18nString("showcase.dialogs.window", "Window");
        static final I18nString ASK_BEFORE_CLOSING = new I18nString("showcase.dialogs.askBeforeClosing",
                "Ask before closing");
        static final I18nString CLOSE_HINT = new I18nString("showcase.dialogs.closeHint",
                "Try the window's close button.");
        static final I18nString CLOSE_TITLE = new I18nString("showcase.dialogs.closeTitle", "Close the Showcase?");
        static final I18nString KEEP_OPEN = new I18nString("showcase.dialogs.keepOpen", "Keep open");

        static final I18nString FILES = new I18nString("showcase.menus.files", "Shared documents");
        static final I18nString ACTIONS_MENU = new I18nString("showcase.menus.actions", "Actions");
        static final I18nString MODAL = new I18nString("showcase.menus.modalShort", "Modal");
        static final I18nString FILES_HINT = new I18nString("showcase.menus.filesHint",
                "Right-click a file, or select one and press the Menu key or Shift+F10.");
        static final I18nString NEW_FOLDER = new I18nString("showcase.menus.newFolder", "&New folder");
        static final I18nString UPLOAD = new I18nString("showcase.menus.upload", "&Upload files…");
        static final I18nString SORT_BY = new I18nString("showcase.menus.sortBy", "Sort &by");
        static final I18nString BY_NAME = new I18nString("showcase.menus.byName", "&Name");
        static final I18nString BY_SIZE = new I18nString("showcase.menus.bySize", "&Size");
        static final I18nString BY_KIND = new I18nString("showcase.menus.byKind", "&Kind");
        static final I18nString HIDDEN = new I18nString("showcase.menus.hidden", "Show &hidden files");
        static final I18nString PROPERTIES = new I18nString("showcase.menus.properties", "&Properties…");
        static final I18nString OPEN = new I18nString("showcase.menus.open", "&Open");
        static final I18nString OPEN_WITH = new I18nString("showcase.menus.openWith", "Open &with");
        static final I18nString VIEWER = new I18nString("showcase.menus.viewer", "&Document viewer");
        static final I18nString SPREADSHEET = new I18nString("showcase.menus.spreadsheet", "&Spreadsheet");
        static final I18nString TEXT_EDITOR = new I18nString("showcase.menus.textEditor", "&Text editor");
        static final I18nString CUT = new I18nString("showcase.menus.cut", "Cu&t");
        static final I18nString COPY = new I18nString("showcase.menus.copy", "&Copy");
        static final I18nString PASTE = new I18nString("showcase.menus.paste", "&Paste");
        static final I18nString RENAME = new I18nString("showcase.menus.rename", "&Rename…");
        static final I18nString DELETE_FILE = new I18nString("showcase.menus.delete", "&Delete");

        static final I18nString KIND_PDF = new I18nString("showcase.menus.kindPdf", "PDF document");
        static final I18nString KIND_SHEET = new I18nString("showcase.menus.kindSheet", "Spreadsheet");
        static final I18nString KIND_PICTURE = new I18nString("showcase.menus.kindPicture", "Picture");
        static final I18nString KIND_ARCHIVE = new I18nString("showcase.menus.kindArchive", "Archive");
        static final I18nString KIND_TEXT = new I18nString("showcase.menus.kindText", "Text");

        static final I18nString DID_OPEN = new I18nString("showcase.menus.didOpen", "Opened {0}.");
        static final I18nString DID_OPEN_WITH = new I18nString("showcase.menus.didOpenWith", "Opened {0} with: {1}.");
        static final I18nString DID_CUT = new I18nString("showcase.menus.didCut", "{0} cut.");
        static final I18nString DID_COPY = new I18nString("showcase.menus.didCopy", "{0} copied.");
        static final I18nString DID_PASTE = new I18nString("showcase.menus.didPaste", "{0} pasted.");
        static final I18nString DID_RENAME = new I18nString("showcase.menus.didRename", "Renaming {0}.");
        static final I18nString DID_DELETE = new I18nString("showcase.menus.didDelete", "{0} deleted.");
        static final I18nString DID_NEW_FOLDER = new I18nString("showcase.menus.didNewFolder", "New folder made.");
        static final I18nString DID_UPLOAD = new I18nString("showcase.menus.didUpload", "Choosing files to upload.");
        static final I18nString DID_SORT = new I18nString("showcase.menus.didSort", "Sorted by: {0}.");
        static final I18nString HIDDEN_ON = new I18nString("showcase.menus.hiddenOn", "Hidden files shown.");
        static final I18nString HIDDEN_OFF = new I18nString("showcase.menus.hiddenOff", "Hidden files not shown.");

        private Text() {
        }
    }

    private DialogsScene() {
    }

    // ================================================================ dialogs

    /** What the four presentation switches say, read when a dialog is made. */
    private record Presentation(Checkbox inWindow, Checkbox onTop, Checkbox systemFrame, Checkbox compact) {

        /** Applies the switches to a modal dialog. */
        Dialog dress(Dialog dialog) {
            dialog.setAlwaysOnTop(onTop.isChecked());
            if (inWindow.isChecked()) {
                dialog.setDisplayMode(DisplayMode.IN_SCENE);
            }
            if (systemFrame.isChecked()) {
                dialog.setStyle(WindowStyle.DECORATED);
            }
            if (compact.isChecked()) {
                dialog.setControlSize(ControlSize.SMALL);
            }
            return dialog;
        }
    }

    /**
     * The dialogs page: the questions an order desk asks, one per scope of modality, a form that
     * asks before its changes are lost, three alerts, and the question before the window closes.
     *
     * @param status unused: the page reports through {@link Page#report}
     */
    static Widget<?> dialogs(Label status) {
        Checkbox askBeforeClosing = new Checkbox(Checkbox.Variant.SWITCH, Text.ASK_BEFORE_CLOSING).setChecked(true);
        Column page = new WindowWiring(askBeforeClosing);
        page.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.STRETCH);

        Button delete = new Button(Text.DELETE_ORDER).setStyle(Button.Style.SECONDARY).setDestructive(true);
        delete.setIcon(Tabler.outline("trash"));
        Button preferences = new Button(Text.PREFERENCES).setStyle(Button.Style.SECONDARY);
        preferences.setIcon(Tabler.outline("settings"));
        Button customer = new Button(Text.EDIT_CUSTOMER).setStyle(Button.Style.SECONDARY);
        customer.setIcon(Tabler.outline("user"));
        Button restore = new Button(Text.RESTORE).setStyle(Button.Style.SECONDARY);
        restore.setIcon(Tabler.outline("database-import"));

        Presentation look = new Presentation(
                new Checkbox(Checkbox.Variant.SWITCH, Text.IN_WINDOW),
                new Checkbox(Checkbox.Variant.SWITCH, Text.ON_TOP).setChecked(true),
                new Checkbox(Checkbox.Variant.SWITCH, Text.SYSTEM_FRAME),
                new Checkbox(Checkbox.Variant.SWITCH, Text.COMPACT));

        // Window-modal: a destructive question about one order, which locks this window only.
        delete.onAction(() -> look.dress(alert(Text.DELETE_TITLE, Text.DELETE_BODY, "alert-triangle",
                        Theme.current().danger()))
                // The safe answer is the default, so Return keeps the order; Delete is the
                // destructive button, which never answers Return.
                .addPrimaryButton(ShowcaseStrings.ACTION_CANCEL, "cancel")
                .addDestructiveButton(Text.DELETE, "delete")
                .setCancelResult("cancel")
                .show(delete)
                .thenAccept(r -> Page.report("delete".equals(r) ? Text.DELETED.get() : Text.KEPT.get())));

        // Non-modal: a panel that stays open beside the window, which stays usable. It is always
        // a window of its own, there being no form of it inside the window.
        preferences.onAction(() -> {
            ComboBox<String> currency = new ComboBox<>(List.of("USD", "EUR", "BRL", "JPY"));
            currency.onSelect(() -> Page.report(Text.CURRENCY_SET.format(currency.selectedItem())));
            Checkbox tax = new Checkbox(Text.WITH_TAX).setChecked(true);
            tax.onChange(on -> Page.report(on ? Text.TAX_ON.get() : Text.TAX_OFF.get()));
            Checkbox reopen = new Checkbox(Text.REOPEN);
            Column form = new Column();
            form.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.START);
            form.add(new Field(Text.CURRENCY, currency));
            form.add(tax);
            form.add(reopen);
            Dialog dialog = new Dialog(Text.PREFERENCES_TITLE, I18nString.EMPTY)
                    .setContent(form)
                    .addPrimaryButton(ShowcaseStrings.ACTION_CLOSE, "close")
                    .setCancelResult("close")
                    .setAlwaysOnTop(look.onTop().isChecked());
            if (look.systemFrame().isChecked()) {
                dialog.setStyle(WindowStyle.DECORATED);
            }
            if (look.compact().isChecked()) {
                dialog.setControlSize(ControlSize.SMALL);
            }
            dialog.showNonModal(preferences).thenAccept(r -> Page.report(Text.PREFERENCES_CLOSED.get()));
        });

        // Two at once: the customer's form locks this window, and when the user closes it with
        // a field changed, Cancel or Escape, the application asks about the changes over it,
        // keeping the form open until that is answered. The upper one must be on top and
        // answerable, and the lower one frozen until it closes.
        customer.onAction(() -> {
            String name = "Harbor Supply";
            String email = "orders@harborsupply.example";
            TextField nameField = new TextField().setText(name);
            TextField emailField = new TextField().setText(email);
            Column form = new Column();
            form.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.STRETCH);
            form.add(new Field(Text.NAME, nameField));
            form.add(new Field(Text.EMAIL, emailField));
            Dialog lower = look.dress(new Dialog(Text.CUSTOMER_TITLE, I18nString.EMPTY))
                    .setContent(new SizedBox(320, SizedBox.UNSET, form))
                    .addButton(ShowcaseStrings.ACTION_CANCEL, "cancel")
                    .addPrimaryButton(ShowcaseStrings.ACTION_SAVE, "save")
                    .setCancelResult("cancel");
            lower.onCancel(request -> {
                if (nameField.text().equals(name) && emailField.text().equals(email)) {
                    request.allow(); // nothing to lose
                    return;
                }
                look.dress(new Dialog(I18nString.literal(Text.UNSAVED_TITLE.format(name)), Text.UNSAVED_BODY))
                        .addButton(Text.DONT_SAVE, "discard")
                        .addButton(ShowcaseStrings.ACTION_CANCEL, "cancel")
                        .addPrimaryButton(ShowcaseStrings.ACTION_SAVE, "save")
                        .setCancelResult("cancel")
                        .show(customer)
                        .thenAccept(answer -> {
                            if ("save".equals(answer) || "discard".equals(answer)) {
                                request.dialog().dismiss(answer); // closes the form, and settles the request
                            } else {
                                request.refuse();
                                Page.report(Text.STILL_EDITING.format(name));
                            }
                        });
            });
            lower.show(customer).thenAccept(r -> Page.report(switch (String.valueOf(r)) {
                case "save" -> Text.SAVED.format(name);
                case "discard" -> Text.DISCARDED.format(name);
                default -> Text.UNCHANGED.format(name);
            }));
        });

        // Application-modal: while the data is replaced, no window of the application may edit it.
        restore.onAction(() -> look.dress(new Dialog(Text.RESTORE_TITLE, Text.RESTORE_BODY))
                .addButton(ShowcaseStrings.ACTION_CANCEL, "cancel")
                .addPrimaryButton(Text.RESTORE_ACTION, "restore")
                .setCancelResult("cancel")
                .showToolkitModal(restore)
                .thenAccept(r -> Page.report("restore".equals(r) ? Text.RESTORED.get() : Text.NOT_RESTORED.get())));

        page.add(Page.firstSection(Text.ACTIONS));
        page.add(wrapRow(delete, preferences, customer, restore));
        page.add(wrapRow(look.inWindow(), look.onTop(), look.systemFrame(), look.compact()));

        // Alerts: in the window, and a click on the dimmed window around one closes it.
        Button info = new Button(Text.INFORMATION).setStyle(Button.Style.SECONDARY);
        info.setIcon(Tabler.outline("info-circle"));
        Button warning = new Button(Text.WARNING).setStyle(Button.Style.SECONDARY);
        warning.setIcon(Tabler.outline("alert-triangle"));
        Button error = new Button(Text.ERROR).setStyle(Button.Style.SECONDARY);
        error.setIcon(Tabler.outline("alert-circle"));
        info.onAction(() -> showAlert(info, look, Text.INFO_TITLE, Text.INFO_BODY, "info-circle",
                Theme.current().info()));
        warning.onAction(() -> showAlert(warning, look, Text.WARNING_TITLE, Text.WARNING_BODY, "alert-triangle",
                Theme.current().warning()));
        error.onAction(() -> showAlert(error, look, Text.ERROR_TITLE, Text.ERROR_BODY, "alert-circle",
                Theme.current().danger()));
        page.add(Page.section(Text.ALERTS));
        page.add(wrapRow(info, warning, error));
        page.add(Page.hint(Text.ALERTS_HINT));

        page.add(Page.section(Text.WINDOW));
        page.add(askBeforeClosing);
        page.add(Page.hint(Text.CLOSE_HINT));
        return page;
    }

    /** A row of controls at the start of the line, 12 apart. */
    private static Widget<?> wrapRow(Widget<?>... children) {
        Row row = Page.row(12, children);
        row.add(Expanded.spacer(1));
        return row;
    }

    /** An alert: its icon beside its sentence, closed by OK or by a click outside it. */
    private static void showAlert(Button from, Presentation look, I18nString title, I18nString body,
                                  String icon, Color tint) {
        Dialog dialog = alert(title, body, icon, tint)
                .addPrimaryButton(ShowcaseStrings.ACTION_OK, "ok")
                .setCancelResult("ok")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .setDismissOnScrim(true);
        if (look.compact().isChecked()) {
            dialog.setControlSize(ControlSize.SMALL);
        }
        dialog.show(from).thenAccept(r -> Page.report(Text.ALERT_CLOSED.get()));
    }

    /**
     * A dialog whose sentence stands beside an icon. The card has no icon of its own, so the
     * sentence is its content rather than its message.
     */
    private static Dialog alert(I18nString title, I18nString body, String icon, Color tint) {
        Row content = new Row();
        content.gap(14).crossAlignment(Flex.CrossAlignment.START);
        content.add(new Mark(Tabler.outline(icon), tint));
        content.add(Expanded.of(new Label(body).setWrap(true), 1));
        return new Dialog(title, I18nString.EMPTY).setContent(new SizedBox(360, SizedBox.UNSET, content));
    }

    /** An icon drawn large and in a colour of its own: the kind of an alert. */
    private static final class Mark extends Widget<Mark> {

        private static final float SIZE = 32;
        private final Icon icon;
        private final Color tint;

        Mark(Icon icon, Color tint) {
            this.icon = icon;
            this.tint = tint;
            setAccessibleIgnored(true); // the title says what kind of alert it is
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(SIZE, SIZE);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            icon.paint(canvas, 0, 0, SIZE, tint, Theme.current().isDark());
        }
    }

    /**
     * The page's column, which sets the window up once it is in one: its icon, its smallest size,
     * and the question asked when it is closed, asked only while this page is the one shown.
     */
    private static final class WindowWiring extends Column {

        private final Checkbox ask;
        private boolean wired;

        WindowWiring(Checkbox ask) {
            this.ask = ask;
        }

        @Override
        protected void onAttached() {
            // The window binds to the scene only after the whole tree is built.
            Ui.post(this::wire);
        }

        private void wire() {
            Scene scene = scene();
            NativeWindow window = scene != null ? scene.window() : null;
            if (wired || window == null) {
                return; // headless or embedded, or detached before the post ran
            }
            wired = true;
            window.setIcon(appIcon(32), appIcon(16));
            window.setSizeLimits(480, 360, 0, 0);
            // Asked on the close button and when the application ends; answered from the
            // dialog, later. A second click while it is open asks nothing new.
            window.setCloseRequestHandler(request -> {
                // Once the page has left the window there is no scene to ask in, and a window
                // that could not ask could not be closed either.
                if (!ask.isChecked() || scene() == null) {
                    request.allow();
                    return;
                }
                new Dialog(Text.CLOSE_TITLE, I18nString.EMPTY)
                        .setDisplayMode(DisplayMode.IN_SCENE)
                        .addButton(Text.KEEP_OPEN, "stay")
                        .addPrimaryButton(ShowcaseStrings.ACTION_CLOSE, "close")
                        .setCancelResult("stay")
                        .show(scene())
                        .thenAccept(result -> {
                            if ("close".equals(result)) {
                                request.allow();
                            } else {
                                request.refuse();
                            }
                        });
            });
        }
    }

    /** A generated two-tone "L" tile for the window's icon. */
    private static Image appIcon(int size) {
        byte[] rgba = new byte[size * size * 4];
        int border = Math.max(1, size / 8);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inL = x >= border * 2 && x < border * 3 + size / 6
                        && y >= border * 2 && y < size - border * 2
                        || y >= size - border * 3 - size / 6 && y < size - border * 2
                        && x >= border * 2 && x < size - border * 2;
                int i = (y * size + x) * 4;
                rgba[i] = (byte) (inL ? 0xFF : 0x3D);
                rgba[i + 1] = (byte) (inL ? 0xFF : 0x6B);
                rgba[i + 2] = (byte) (inL ? 0xFF : 0xF2);
                rgba[i + 3] = (byte) 0xFF;
            }
        }
        return new Image(size, size, rgba);
    }

    // ================================================================== menus

    /** A file in the shared folder, its size in kilobytes of 1024 bytes as {@link Formats#size} counts them. */
    record Document(String name, I18nString kind, String icon, int kilobytes) {
    }

    /** How the folder is sorted, shared by both menus that sort it. */
    private enum SortKey { NAME, SIZE, KIND }

    /**
     * The menus page: a folder of shared documents with a menu of actions on a button, and a
     * menu per file on a right click and on the keyboard's menu key.
     *
     * @param status unused: the page reports through {@link Page#report}
     */
    static Widget<?> menus(Label status) {
        List<Document> files = new ArrayList<>(List.of(
                new Document("Invoice 1042.pdf", Text.KIND_PDF, "file-type-pdf", 84),
                new Document("Quarterly sales.xlsx", Text.KIND_SHEET, "file-spreadsheet", 412),
                new Document("Price list 2026.csv", Text.KIND_SHEET, "file-type-csv", 38),
                new Document("Contract Harbor Supply.pdf", Text.KIND_PDF, "file-type-pdf", 1_331),
                new Document("Warehouse photos.zip", Text.KIND_ARCHIVE, "file-type-zip", 18_842),
                new Document("Logo.png", Text.KIND_PICTURE, "photo", 26),
                new Document("Meeting notes.txt", Text.KIND_TEXT, "file-text", 4),
                new Document(".sync-state", Text.KIND_TEXT, "file", 1)));
        SortKey[] sort = {SortKey.NAME};
        boolean[] hidden = {false};
        Document[] held = {null};

        AtomicReference<ListView<Document>> holder = new AtomicReference<>();
        ListView<Document> list = new ListView<>(file -> new FileRow(file, holder));
        holder.set(list);
        list.setItemName(file -> I18nString.literal(file.name()));
        list.setAccessibleName(Text.FILES);
        list.setSelectionMode(SelectionMode.SINGLE);
        Runnable resort = () -> {
            files.sort(switch (sort[0]) {
                case NAME -> Comparator.comparing(Document::name, String.CASE_INSENSITIVE_ORDER);
                case SIZE -> Comparator.comparingInt(Document::kilobytes).reversed();
                case KIND -> Comparator.comparing((Document f) -> f.kind().get())
                        .thenComparing(Document::name, String.CASE_INSENSITIVE_ORDER);
            });
            list.setItems(files.stream().filter(f -> hidden[0] || !f.name().startsWith(".")).toList());
        };
        resort.run();

        // The sort order, a group of three of which one is chosen: there is no radio item, so
        // three checks are kept to one by hand, in both menus that show them.
        Supplier<Menu> sortMenu = () -> {
            Menu menu = new Menu();
            SortKey[] keys = SortKey.values();
            I18nString[] names = {Text.BY_NAME, Text.BY_SIZE, Text.BY_KIND};
            for (int i = 0; i < keys.length; i++) {
                SortKey key = keys[i];
                I18nString name = names[i];
                menu.add(MenuItem.check(name, sort[0] == key, on -> {
                    sort[0] = key;
                    resort.run();
                    Page.report(Text.DID_SORT.format(Page.plain(name)));
                }));
            }
            return menu;
        };

        Button actions = new Button(Text.ACTIONS_MENU).setStyle(Button.Style.SECONDARY);
        actions.setIcon(Tabler.outline("dots"));
        Checkbox modal = new Checkbox(Checkbox.Variant.SWITCH, Text.MODAL);
        actions.onAction(() -> {
            Menu menu = new Menu()
                    .add(MenuItem.of(Text.NEW_FOLDER, () -> Page.report(Text.DID_NEW_FOLDER.get()))
                            .setIcon(Tabler.outline("folder-plus"))
                            .setAccelerator(Accelerator.command(Keys.N, Keys.MOD_SHIFT)))
                    .add(MenuItem.of(Text.UPLOAD, () -> Page.report(Text.DID_UPLOAD.get()))
                            .setIcon(Tabler.outline("upload"))
                            .setAccelerator(Accelerator.command(Keys.U)))
                    .addSeparator()
                    .add(MenuItem.submenu(Text.SORT_BY, sortMenu.get()))
                    .add(MenuItem.check(Text.HIDDEN, hidden[0], on -> {
                        hidden[0] = on;
                        resort.run();
                        Page.report(on ? Text.HIDDEN_ON.get() : Text.HIDDEN_OFF.get());
                    }).setAccelerator(Accelerator.command(Keys.PERIOD, Keys.MOD_SHIFT)))
                    .addSeparator()
                    .add(MenuItem.of(Text.PROPERTIES, () -> { }).setEnabled(false));
            openBelow(actions, menu, modal.isChecked());
        });

        Supplier<Menu> fileMenu = () -> {
            Document file = list.selectedItem();
            if (file == null) {
                return null;
            }
            return new Menu()
                    .add(MenuItem.of(Text.OPEN, () -> Page.report(Text.DID_OPEN.format(file.name())))
                            .setIcon(Tabler.outline("external-link"))
                            .setAccelerator(Accelerator.command(Keys.O)))
                    .add(MenuItem.submenu(Text.OPEN_WITH, new Menu()
                            .add(openWith(file, Text.VIEWER))
                            .add(openWith(file, Text.SPREADSHEET))
                            .add(openWith(file, Text.TEXT_EDITOR))))
                    .addSeparator()
                    .add(MenuItem.of(Text.CUT, () -> {
                                held[0] = file;
                                Page.report(Text.DID_CUT.format(file.name()));
                            })
                            .setIcon(Tabler.outline("scissors"))
                            .setAccelerator(Accelerator.command(Keys.X)))
                    .add(MenuItem.of(Text.COPY, () -> {
                                held[0] = file;
                                Page.report(Text.DID_COPY.format(file.name()));
                            })
                            .setIcon(Tabler.outline("copy"))
                            .setAccelerator(Accelerator.command(Keys.C)))
                    .add(MenuItem.of(Text.PASTE, () -> Page.report(Text.DID_PASTE.format(held[0].name())))
                            .setIcon(Tabler.outline("clipboard"))
                            .setAccelerator(Accelerator.command(Keys.V))
                            .setEnabled(held[0] != null))
                    .addSeparator()
                    .add(MenuItem.of(Text.RENAME, () -> Page.report(Text.DID_RENAME.format(file.name())))
                            .setIcon(Tabler.outline("pencil"))
                            .setAccelerator(Accelerator.of(Keys.F2)))
                    .add(MenuItem.of(Text.DELETE_FILE, () -> {
                                files.remove(file);
                                resort.run();
                                Page.report(Text.DID_DELETE.format(file.name()));
                            })
                            .setIcon(Tabler.outline("trash"))
                            .setAccelerator(Accelerator.of(Keys.DELETE)))
                    .addSeparator()
                    .add(MenuItem.submenu(Text.SORT_BY, sortMenu.get()));
        };
        // The region answers the pointer, the keyboard (the Menu key, Shift+F10) and an assistive
        // technology alike, opening the menu at the pointer for the first.
        Widget<?> folder = ContextMenus.attach(list, fileMenu);
        list.setSelectedIndex(0);

        Column page = Page.column();
        page.add(Page.firstSection(Text.FILES));
        page.add(wrapRow(actions, modal));
        page.add(Page.narrow(640, new Card(new SizedBox(SizedBox.UNSET, 7 * 34 + 8, folder))));
        page.add(Page.hint(Text.FILES_HINT));
        return page;
    }

    private static MenuItem openWith(Document file, I18nString program) {
        return MenuItem.of(program, () -> Page.report(Text.DID_OPEN_WITH.format(file.name(), Page.plain(program))));
    }

    /**
     * A file's row: its icon and name, its kind and its size. A right press selects the row
     * before the menu is asked for, so the menu is about the file under the pointer, as a file
     * manager's is.
     */
    private static final class FileRow extends Padding {

        private final Document file;
        private final AtomicReference<ListView<Document>> list;

        FileRow(Document file, AtomicReference<ListView<Document>> list) {
            super(Insets.symmetric(6, 10), content(file));
            this.file = file;
            this.list = list;
        }

        private static Widget<?> content(Document file) {
            Row row = new Row();
            row.gap(16).crossAlignment(Flex.CrossAlignment.CENTER);
            row.add(Expanded.of(new Label(file.name()).setIcon(Tabler.outline(file.icon())), 1));
            row.add(new SizedBox(130, SizedBox.UNSET, new Label(file.kind()).setMuted(true)));
            // Written as the tree writes a size, and written again after a change of language,
            // since a size carries the language's decimal separator and a row can outlive it.
            Label size = Page.label(() -> Formats.size(file.kilobytes() * 1024L));
            row.add(new SizedBox(70, SizedBox.UNSET,
                    size.setMuted(true).setAlign(Label.HAlign.END, Label.VAlign.CENTER)));
            return row;
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            ListView<Document> files = list.get();
            if (ContextMenus.isRequest(event) && files != null) {
                int index = files.items().indexOf(file);
                if (index >= 0) {
                    files.setSelectedIndex(index);
                }
                // Not consumed: the press goes on to the region that opens the menu.
            }
        }
    }

    /**
     * Opens {@code menu} as a dropdown under {@code anchor}. Anchored on the widget and not on a
     * scene handed in: a dialog's content is in the dialog's own scene when the dialog is a
     * window, and in the host's when it is an overlay.
     */
    private static void openBelow(Widget<?> anchor, Menu menu, boolean modal) {
        new PopupMenu(menu).setModal(modal).showFor(anchor);
    }
}
