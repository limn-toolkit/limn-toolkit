package limn.demo;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.Menu;
import limn.components.MenuItem;
import limn.components.TextField;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.desktop.Tray;
import limn.graphics.BitmapIcon;
import limn.graphics.Icon;
import limn.graphics.Images;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.Set;

/**
 * The tray icon, to try by hand: an icon with a menu the system draws, and switches for each
 * thing it can show. Every click the system reports is written in the status line and, for a
 * run nobody watches, on standard output.
 *
 * <p>{@code -Dlimn.demo.tray=icon,mono,attention,activate} turns switches on from the start, and
 * {@code -Dlimn.demo.tray.script=true} then changes the tooltip, the menu and the attention on a
 * timer, which is how a change made while the icon is shown is looked at.
 */
final class TrayScene {

    private TrayScene() {
    }

    /** Standalone {@code --scene tray}. */
    static Scene create(Backend backend) {
        Label status = new Label("").setMuted(true);
        Column page = new Column();
        page.gap(16).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(content(backend, status));
        page.add(status);
        Scene scene = new Scene(new Padding(Insets.all(24), page));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    private static void say(Label status, String text) {
        status.setText(text);
        System.out.println("TRAY " + text);
        System.out.flush();
    }

    static Widget<?> content(Backend backend, Label status) {
        Icon picture = BitmapIcon.picture(Images.fromResource("/limn/demo/images/logo.png"));
        Icon shape = BitmapIcon.mask(Images.fromResource("/limn/demo/images/icon-star.png"));
        Set<String> on = Set.of(System.getProperty("limn.demo.tray", "").split(","));

        Tray tray = Tray.create(backend, "limn-showcase");
        tray.setTitle("Limn Showcase").setTooltip("Limn Showcase");

        Checkbox shown = new Checkbox(Checkbox.Variant.SWITCH, "Icon in the tray");
        Checkbox monochrome = new Checkbox(Checkbox.Variant.SWITCH, "Monochrome icon");
        Checkbox attention = new Checkbox(Checkbox.Variant.SWITCH, "Asks for attention");
        Checkbox activates = new Checkbox(Checkbox.Variant.SWITCH, "A click brings this window");
        TextField tooltip = new TextField().setText("Limn Showcase");
        Label available = new Label("").setMuted(true);

        int[] opened = {0};
        MenuItem bold = MenuItem.check("Bold", true, value -> say(status, "check Bold " + value));
        Menu export = new Menu()
                .addItem("PDF", () -> say(status, "chose Export > PDF"))
                .addItem("report_final.png", () -> say(status, "chose Export > report_final.png"));
        Menu menu = new Menu()
                .add(MenuItem.of("Open the window", () -> {
                    say(status, "chose Open the window");
                    bring(shown);
                }).setMnemonic('O'))
                .add(MenuItem.of("Copy", () -> say(status, "chose Copy")).setEnabled(false))
                .addSeparator()
                .add(bold)
                .addSubmenu("Export", export)
                .addSeparator()
                .addItem("Count this opening", () -> say(status, "chose Count, " + ++opened[0]))
                .addItem("Quit", () -> {
                    say(status, "chose Quit");
                    backend.requestQuit();
                });
        tray.setMenu(menu);

        Runnable tell = () -> available.setText(tray.isAvailable()
                ? "The system took the icon." : "No tray took the icon.");
        tray.onAvailabilityChange(() -> {
            tell.run();
            say(status, "available " + tray.isAvailable());
        });

        shown.onChange(value -> {
            tray.setIcon(value ? picture : null);
            if (!value) {
                tray.setMonochromeIcon(null);
                monochrome.setChecked(false);
            }
            tell.run();
        });
        monochrome.onChange(value -> tray.setMonochromeIcon(value ? shape : null));
        attention.onChange(tray::setAttention);
        activates.onChange(value -> tray.onActivate(value ? () -> {
            say(status, "activated");
            bring(shown);
        } : null));
        tooltip.onChange(tray::setTooltip);

        if (on.contains("icon")) {
            shown.setChecked(true);
            tray.setIcon(picture);
        }
        if (on.contains("mono")) {
            monochrome.setChecked(true);
            tray.setMonochromeIcon(shape);
        }
        if (on.contains("attention")) {
            attention.setChecked(true);
            tray.setAttention(true);
        }
        if (on.contains("activate")) {
            activates.setChecked(true);
            tray.onActivate(() -> {
                say(status, "activated");
                bring(shown);
            });
        }
        if (Boolean.getBoolean("limn.demo.tray.script")) {
            // What a panel shows of a change made while the icon is there, with nobody at the keys.
            Ui.postDelayed(() -> {
                tray.setTooltip("Changed while shown");
                bold.setChecked(false);
                menu.addItem("Added while shown", () -> say(status, "chose Added while shown"));
                say(status, "script: tooltip and menu changed");
            }, 8000);
            Ui.postDelayed(() -> {
                tray.setAttention(true);
                say(status, "script: attention on");
            }, 16000);
            Ui.postDelayed(() -> {
                tray.setAttention(false);
                say(status, "script: attention off");
            }, 24000);
        }
        if (Boolean.getBoolean("limn.demo.tray.second")) {
            // A second icon of the same application, taken away after a while.
            Tray second = Tray.create(backend, "limn-showcase-second");
            second.setTitle("Limn, second icon").setIcon(BitmapIcon.picture(
                    Images.fromResource("/limn/demo/images/icon-heart.png")));
            second.setMenu(new Menu().addItem("Second icon's item", () -> say(status, "chose the second icon's item")));
            Ui.postDelayed(() -> {
                second.dispose();
                say(status, "script: second icon disposed");
            }, 14000);
        }
        Ui.postDelayed(tell, 1500);

        Row tip = new Row();
        tip.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        tip.add(new Label("Tooltip"));
        tip.add(new SizedBox(280, SizedBox.UNSET, tooltip));

        Column page = new Column();
        page.gap(12).crossAlignment(Flex.CrossAlignment.START);
        page.add(shown);
        page.add(monochrome);
        page.add(attention);
        page.add(activates);
        page.add(tip);
        page.add(available);
        return page;
    }

    /** Shows and focuses the window the switch is in. */
    private static void bring(Widget<?> inside) {
        if (inside.scene() != null && inside.scene().window() != null) {
            NativeWindow window = inside.scene().window();
            window.show();
            window.focus();
        }
    }
}
