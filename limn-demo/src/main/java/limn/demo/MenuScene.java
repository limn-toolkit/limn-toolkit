package limn.demo;

import limn.components.Accelerator;
import limn.components.ContextMenus;
import limn.components.Label;
import limn.components.Menu;
import limn.components.MenuBar;
import limn.components.MenuItem;
import limn.components.PopupMenu;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.input.Keys;
import limn.graphics.Font;
import limn.graphics.TextMetrics;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;

import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Demo of the menu system: an internal {@link MenuBar} (File/Edit/View) with
 * submenus and checkable items, plus a right-click {@link PopupMenu} whose render
 * mode (internal soft, internal modal, or a native window) is chosen from a
 * {@link ComboBox}. Both flip/clamp so they never overflow the visible area.
 */
final class MenuScene {

    private MenuScene() {
    }

    /** The scene plus a hook to open a context menu at a scene point (screenshots/tests). */
    record Built(Scene scene, BiFunction<Float, Float, PopupMenu> openContext) {
    }

    static Built create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Label status = new Label("Ready. Pick a menu or right-click.").setMuted(true);
        Consumer<String> say = status::setText;

        MenuBar bar = new MenuBar()
                .addMenu("File", 'F', fileMenu(say))
                .addMenu("Edit", 'E', editMenu(say))
                .addMenu("View", 'V', viewMenu(say));

        Widget area = ContextMenus.attach(new ContextArea(), () -> contextMenu(say));

        Column page = new Column();
        page.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(bar);
        page.add(Expanded.of(area, 1));
        page.add(new SizedBox(SizedBox.UNSET, 18, status));

        Scene scene = new Scene(new Padding(Insets.all(20), page));
        scene.setBackground(Theme.current().background);

        // The pointer and keyboard routes are ContextMenus' business; this one exists only so a
        // screenshot can open the menu without a pointer, and it hands the popup back because a
        // screenshot has to be able to find the window it just raised.
        BiFunction<Float, Float, PopupMenu> openContext = (x, y) -> {
            PopupMenu popup = new PopupMenu(contextMenu(say));
            popup.showAt(scene, x, y);
            return popup;
        };
        return new Built(scene, openContext);
    }

    // --------------------------------------------------------------- menus

    private static Menu fileMenu(Consumer<String> say) {
        Menu recents = new Menu()
                .addItem("report.txt", () -> say.accept("Open report.txt"))
                .addItem("notes.md", () -> say.accept("Open notes.md"))
                .addItem("budget.xlsx", () -> say.accept("Open budget.xlsx"));
        return new Menu()
                .add(MenuItem.of("New", () -> say.accept("File → New"))
                        .setAccelerator(Accelerator.command(Keys.N)).setMnemonic('N'))
                .add(MenuItem.of("Open…", () -> say.accept("File → Open"))
                        .setAccelerator(Accelerator.command(Keys.O)).setMnemonic('O'))
                .addSubmenu("Open recent", recents)
                .addSeparator()
                .add(MenuItem.of("Save", () -> say.accept("File → Save"))
                        .setAccelerator(Accelerator.command(Keys.S)).setMnemonic('S'))
                // Disabled AND accelerated on purpose: the chord must not run it, and the row
                // has to show the hint anyway or the shortcut looks like it does not exist.
                .add(MenuItem.of("Save as…", () -> say.accept("Save as"))
                        .setAccelerator(Accelerator.command(Keys.S, Keys.MOD_SHIFT))
                        .setEnabled(false))
                .addSeparator()
                .add(MenuItem.of("Quit", () -> say.accept("File → Quit"))
                        .setAccelerator(Accelerator.command(Keys.Q)).setMnemonic('Q'));
    }

    private static Menu editMenu(Consumer<String> say) {
        return new Menu()
                .add(MenuItem.of("Undo", () -> say.accept("Edit → Undo"))
                        .setAccelerator(Accelerator.command(Keys.Z)).setMnemonic('U'))
                .add(MenuItem.of("Redo", () -> { }).setEnabled(false)
                        .setAccelerator(Accelerator.command(Keys.Z, Keys.MOD_SHIFT)))
                .addSeparator()
                // A check row carries both a mark and a hint, which is the row where the two
                // compete for width, the reason the demo has one.
                .add(MenuItem.check("Word wrap", true, on -> say.accept("Word wrap: " + on))
                        .setAccelerator(Accelerator.command(Keys.W, Keys.MOD_ALT))
                        .setMnemonic('W'))
                .addCheck("Line numbers", false, on -> say.accept("Line numbers: " + on));
    }

    private static Menu viewMenu(Consumer<String> say) {
        Menu zoom = new Menu()
                .add(MenuItem.of("Zoom in", () -> say.accept("Zoom +"))
                        .setAccelerator(Accelerator.command(Keys.EQUAL)))
                .add(MenuItem.of("Zoom out", () -> say.accept("Zoom −"))
                        .setAccelerator(Accelerator.command(Keys.MINUS)))
                .add(MenuItem.of("Reset", () -> say.accept("Zoom 100%"))
                        .setAccelerator(Accelerator.command(Keys.NUM_0)));
        return new Menu()
                .addCheck("Sidebar", true, on -> say.accept("Sidebar: " + on))
                .addCheck("Status bar", true, on -> say.accept("Status bar: " + on))
                .addSeparator()
                .addSubmenu("Zoom", zoom);
    }

    private static Menu contextMenu(Consumer<String> say) {
        Menu export = new Menu()
                .addItem("PDF", () -> say.accept("Export PDF"))
                .addItem("PNG", () -> say.accept("Export PNG"))
                .addItem("SVG", () -> say.accept("Export SVG"));
        return new Menu()
                .add(MenuItem.of("Cut", () -> { }).setEnabled(false)
                        .setAccelerator(Accelerator.command(Keys.X)))
                .add(MenuItem.of("Copy", () -> say.accept("Copy"))
                        .setAccelerator(Accelerator.command(Keys.C)).setMnemonic('C'))
                .add(MenuItem.of("Paste", () -> say.accept("Paste"))
                        .setAccelerator(Accelerator.command(Keys.V)).setMnemonic('P'))
                .addSeparator()
                .addCheck("Bold", true, on -> say.accept("Bold: " + on))
                .addCheck("Italic", false, on -> say.accept("Italic: " + on))
                .addSeparator()
                .addSubmenu("Export", export)
                .addSeparator()
                .addItem("Properties…", () -> say.accept("Properties"));
    }

    /**
     * The panel the menu is attached to: a hint and a border, and no input handling at all.
     * {@code ContextMenus.attach} carries the gesture, which is what makes the keyboard route
     * work here without this widget knowing about it.
     */
    private static final class ContextArea extends Widget {

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusLarge(), theme.surface);
            canvas.drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, theme.tokensFor(this).radiusLarge(), 1, theme.outline);
            String hint = "Right-click, or press the Menu key, to open the context menu";
            Font font = theme.body;
            TextMetrics m = textRuler().measure(hint, font);
            canvas.drawText(hint, (width() - m.width()) / 2,
                    (height() - m.height()) / 2 + m.ascent(), font, theme.textMuted);
        }
    }
}
