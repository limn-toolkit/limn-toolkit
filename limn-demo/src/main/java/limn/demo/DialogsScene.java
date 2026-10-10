package limn.demo;

import limn.backend.WindowStyle;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.ContextMenus;
import limn.components.Dialog;
import limn.components.DisplayMode;
import limn.components.Label;
import limn.components.Menu;
import limn.components.MenuItem;
import limn.components.PopupMenu;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.TextMetrics;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The two pages of the demo that open something over the window: dialogs in each scope of
 * modality, and menus from a button and from a right click.
 *
 * <p>Every way out of a dialog reports in the status line, the one closed by the system's own
 * button included: a caller that tidies up when the answer comes is left waiting by a dialog
 * that ends without one, and nothing else on screen would say so.
 */
final class DialogsScene {

    private DialogsScene() {
    }

    /** The dialogs page: a dialog per scope, two at once, and the three ways one can be shown. */
    static Widget<?> dialogs(Label status) {
        Button windowModal = new Button(ShowcaseStrings.DIALOG_WINDOW_MODAL);
        Button appModal = new Button(ShowcaseStrings.DIALOG_APP_MODAL).setSecondary(true);
        Button nonModal = new Button(ShowcaseStrings.DIALOG_NON_MODAL).setSecondary(true);
        Button stacked = new Button(ShowcaseStrings.DIALOG_STACKED).setSecondary(true);
        Row buttons = new Row();
        buttons.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        buttons.add(windowModal);
        buttons.add(appModal);
        buttons.add(nonModal);
        buttons.add(stacked);

        Checkbox inScene = new Checkbox(Checkbox.Variant.SWITCH, ShowcaseStrings.DIALOG_INTERNAL);
        Checkbox onTop = new Checkbox(Checkbox.Variant.SWITCH, ShowcaseStrings.DIALOG_ALWAYS_ON_TOP)
                .setChecked(true);
        Checkbox decorated = new Checkbox(Checkbox.Variant.SWITCH, ShowcaseStrings.DIALOG_DECORATED);
        Row options = new Row();
        options.gap(20).crossAlignment(Flex.CrossAlignment.CENTER);
        options.add(inScene);
        options.add(onTop);
        options.add(decorated);

        // A dropdown and a menu inside the dialog: each is a window of its own, owned by the
        // window the dialog is drawn in, and has to open and take a choice from there.
        Function<String, Dialog> make = scope -> {
            ComboBox<String> combo = new ComboBox<>(List.of("Limn dark", "Limn light", "High contrast", "Sepia"));
            Button menu = new Button(ShowcaseStrings.MENUS_BUTTON).setSecondary(true);
            menu.onAction(() -> openBelow(menu, sampleMenu(status), false));
            Row popups = new Row();
            popups.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
            popups.add(combo);
            popups.add(menu);
            Dialog dialog = new Dialog(ShowcaseStrings.DIALOG_TITLE.format(scope),
                    ShowcaseStrings.DIALOG_MESSAGE.format(scope))
                    .setContent(popups)
                    .addButton(ShowcaseStrings.ACTION_CANCEL, "cancel")
                    .addPrimaryButton(ShowcaseStrings.ACTION_OK, "ok")
                    .setCancelResult("cancel")
                    .setAlwaysOnTop(onTop.isChecked());
            if (inScene.isChecked()) {
                dialog.setDisplayMode(DisplayMode.IN_SCENE);
            }
            if (decorated.isChecked()) {
                dialog.setStyle(WindowStyle.DECORATED);
            }
            return dialog;
        };
        windowModal.onAction(() -> make.apply("window-modal").show(windowModal).thenAccept(
                r -> status.setText(ShowcaseStrings.STATUS_WINDOW_MODAL.format(String.valueOf(r)))));
        appModal.onAction(() -> make.apply("app-modal").showToolkitModal(appModal).thenAccept(
                r -> status.setText(ShowcaseStrings.STATUS_APP_MODAL.format(String.valueOf(r)))));
        nonModal.onAction(() -> {
            if (inScene.isChecked()) {
                status.setText(ShowcaseStrings.STATUS_NEEDS_WINDOW);
                return;
            }
            make.apply("non-modal").showNonModal(nonModal).thenAccept(
                    r -> status.setText(ShowcaseStrings.STATUS_NON_MODAL.format(String.valueOf(r))));
        });
        // Two modals at once: a dialog locks this window, and a beat later the application
        // raises a second one on the window the first is blocking, as a question about unsaved
        // changes does. The upper one must be on top and answerable, and the lower one frozen
        // until it closes.
        stacked.onAction(() -> {
            new Dialog(ShowcaseStrings.DIALOG_LOWER, ShowcaseStrings.DIALOG_LOWER_BODY)
                    .addPrimaryButton(ShowcaseStrings.ACTION_CLOSE, "ok")
                    .setCancelResult("ok")
                    .show(stacked)
                    .thenAccept(r -> status.setText(ShowcaseStrings.STATUS_LOWER.format(String.valueOf(r))));
            boolean upperInScene = inScene.isChecked();
            Ui.postDelayed(() -> {
                if (stacked.scene() == null) {
                    return; // the page was left meanwhile
                }
                Dialog upper = new Dialog(ShowcaseStrings.DIALOG_UPPER, ShowcaseStrings.DIALOG_UPPER_BODY)
                        .addButton(ShowcaseStrings.ACTION_CANCEL, "cancel")
                        .addPrimaryButton(ShowcaseStrings.ACTION_OK, "ok")
                        .setCancelResult("cancel");
                if (upperInScene) {
                    upper.setDisplayMode(DisplayMode.IN_SCENE);
                }
                upper.show(stacked).thenAccept(
                        r -> status.setText(ShowcaseStrings.STATUS_UPPER.format(String.valueOf(r))));
                if (upperInScene) {
                    status.setText(upper.modalWindow() != null
                            ? ShowcaseStrings.STATUS_UPPER_PROMOTED.get()
                            : ShowcaseStrings.STATUS_UPPER_IN_SCENE.get());
                }
            }, 700);
        });

        Column page = new Column();
        page.gap(14).crossAlignment(Flex.CrossAlignment.START);
        page.add(buttons);
        page.add(options);
        return page;
    }

    /** The menus page: a dropdown from a button, and a context menu over an area. */
    static Widget<?> menus(Label status) {
        Button button = new Button(ShowcaseStrings.MENUS_BUTTON).setSecondary(true);
        Checkbox modal = new Checkbox(Checkbox.Variant.SWITCH, ShowcaseStrings.MENUS_MODAL);
        Row top = new Row();
        top.gap(16).crossAlignment(Flex.CrossAlignment.CENTER);
        top.add(button);
        top.add(modal);
        ContextArea area = new ContextArea();
        button.onAction(() -> openBelow(button, sampleMenu(status), modal.isChecked()));
        area.onContext((x, y) -> {
            if (area.scene() != null) {
                new PopupMenu(sampleMenu(status)).setModal(modal.isChecked()).showAt(area.scene(), x, y);
            }
        });

        Column page = new Column();
        page.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(top);
        page.add(new SizedBox(SizedBox.UNSET, 180, area));
        return page;
    }

    /** A menu with commands, checkable items, a separator, a submenu and a disabled entry. */
    private static Menu sampleMenu(Label status) {
        return new Menu()
                .addItem(ShowcaseStrings.MENU_COPY, () -> status.setText(ShowcaseStrings.STATUS_COPY))
                .addItem(ShowcaseStrings.MENU_PASTE, () -> status.setText(ShowcaseStrings.STATUS_PASTE))
                .addSeparator()
                .addCheck(ShowcaseStrings.MENU_BOLD, true,
                        on -> status.setText(ShowcaseStrings.STATUS_BOLD.format(String.valueOf(on))))
                .addCheck(ShowcaseStrings.MENU_ITALIC, false,
                        on -> status.setText(ShowcaseStrings.STATUS_ITALIC.format(String.valueOf(on))))
                .addSeparator()
                .addSubmenu(ShowcaseStrings.MENU_EXPORT, new Menu()
                        .addItem("PDF", () -> status.setText(ShowcaseStrings.STATUS_EXPORT_PDF))
                        .addItem("PNG", () -> status.setText(ShowcaseStrings.STATUS_EXPORT_PNG))
                        .addItem("SVG", () -> status.setText(ShowcaseStrings.STATUS_EXPORT_SVG)))
                .add(MenuItem.of(ShowcaseStrings.MENU_PROPERTIES, () -> { }).setEnabled(false));
    }

    /**
     * Opens {@code menu} as a dropdown under {@code anchor}. Anchored on the widget and not on a
     * scene handed in: a dialog's content is in the dialog's own scene when the dialog is a
     * window, and in the host's when it is an overlay.
     */
    private static void openBelow(Widget<?> anchor, Menu menu, boolean modal) {
        float sx = 0;
        float sy = 0;
        for (Widget<?> w = anchor; w != null; w = w.parent()) {
            sx += w.x();
            sy += w.y();
        }
        new PopupMenu(menu).setModal(modal).showAnchored(anchor, sx, sy, anchor.width(), anchor.height());
    }

    /**
     * A bordered area that raises a context menu. It asks {@code ContextMenus} what the gesture
     * is and opens the popup itself, since the page switches the popup between modal and not.
     */
    private static final class ContextArea extends Widget<ContextArea> {

        private BiConsumer<Float, Float> onContext = (x, y) -> { };

        void onContext(BiConsumer<Float, Float> listener) {
            this.onContext = listener;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(),
                    theme.surface(), 1, theme.outline());
            String hint = ShowcaseStrings.MENUS_RIGHT_CLICK.get();
            Font font = theme.body();
            TextMetrics m = textRuler().measure(hint, font);
            canvas.drawText(hint, (width() - m.width()) / 2,
                    (height() - m.height()) / 2 + m.ascent(), font, theme.textMuted());
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (ContextMenus.isRequest(event)) {
                onContext.accept(event.x(), event.y());
                event.consume();
            }
        }
    }
}
