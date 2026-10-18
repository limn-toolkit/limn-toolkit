package limn.demo;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.components.Checkbox;
import limn.components.Field;
import limn.components.Label;
import limn.components.Menu;
import limn.components.MenuItem;
import limn.components.TextField;
import limn.concurrent.Ui;
import limn.desktop.Tray;
import limn.graphics.BitmapIcon;
import limn.graphics.Icon;
import limn.graphics.Images;
import limn.i18n.I18nString;
import limn.icons.tabler.Tabler;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;

import java.util.Set;

/**
 * The tray icon, to try by hand: an icon with a menu the system draws, and switches for each
 * thing it can show. A choice from the icon's menu is said in the status line; every click the
 * system reports is written on standard output, for a run nobody watches.
 *
 * <p>{@code -Dlimn.demo.tray=icon,mono,attention,activate} turns switches on from the start, and
 * {@code -Dlimn.demo.tray.script=true} then changes the tooltip, the menu and the attention on a
 * timer, which is how a change made while the icon is shown is looked at.
 */
final class TrayScene {

    /** Every string the page and the icon's menu show. */
    static final class Text {
        static final I18nString ICON = new I18nString("showcase.tray.icon", "Icon");
        static final I18nString SHOWN = new I18nString("showcase.tray.shown", "Show in the tray");
        static final I18nString MONOCHROME = new I18nString("showcase.tray.monochrome", "Monochrome");
        static final I18nString ATTENTION = new I18nString("showcase.tray.attention", "Ask for attention");
        static final I18nString ACTIVATES = new I18nString("showcase.tray.activates", "Click brings the window");
        static final I18nString TOOLTIP = new I18nString("showcase.tray.tooltip", "Tooltip");
        static final I18nString TAKEN = new I18nString("showcase.tray.taken", "The system took the icon.");
        static final I18nString NOT_TAKEN = new I18nString("showcase.tray.notTaken", "No tray took the icon.");
        static final I18nString NO_DESKTOP = new I18nString("showcase.tray.noDesktop",
                "The tray icon needs a desktop to be shown on.");
        // The menu's access letters are in the words, so each language gives its own (ADR 070).
        static final I18nString OPEN_WINDOW = new I18nString("showcase.tray.openWindow", "&Open the window");
        static final I18nString NEW_ORDER = new I18nString("showcase.tray.newOrder", "&New order");
        static final I18nString PREFERENCES = new I18nString("showcase.tray.preferences", "&Preferences…");
        static final I18nString PAUSE_SYNC = new I18nString("showcase.tray.pauseSync", "Pause &syncing");
        static final I18nString RECENT = new I18nString("showcase.tray.recent", "&Recent orders");
        static final I18nString QUIT = new I18nString("showcase.tray.quit", "&Quit");
        static final I18nString CHOSE = new I18nString("showcase.tray.chose", "Tray menu: {0}");
        static final I18nString SYNC_PAUSED = new I18nString("showcase.tray.syncPaused", "Syncing paused.");
        static final I18nString SYNC_RESUMED = new I18nString("showcase.tray.syncResumed", "Syncing resumed.");

        private Text() {
        }
    }

    private TrayScene() {
    }

    private static final long STARTED = System.currentTimeMillis();

    /** The backend the icon is made from; none in a capture or a test, which have no desktop. */
    private static Backend backend;
    /** One icon for the life of the process, whichever page or scene shows its switches. */
    private static Tray shared;
    private static limn.concurrent.Subscription availability;
    private static boolean iconOn;
    private static boolean shapeOn;
    private static boolean attentionOn;
    private static boolean activatesOn;
    private static String tooltipNow = "Limn Showcase";
    private static boolean started;

    /** Says which backend the icon is made from. Called once, before a scene is built. */
    static void use(Backend running) {
        backend = running;
    }

    /** The page of the Showcase's gallery. */
    static Widget<?> page(Label status) {
        if (backend == null) {
            return Page.hint(Text.NO_DESKTOP);
        }
        return content(backend, status);
    }

    /** Standalone {@code --scene tray}. */
    static Scene create(Backend backend) {
        use(backend);
        Label status = new Label("").setMuted(true);
        Page.reportTo(status); // the only window: its line is the status line
        Column page = new Column();
        page.gap(16).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(content(backend, status));
        page.add(status);
        Scene scene = new Scene(new Padding(Insets.all(24), page));
        return scene;
    }

    /** For a run nobody watches: what the system reported, on standard output only. */
    private static void say(Label status, String text) {
        System.out.println("TRAY " + text + " @" + (System.currentTimeMillis() - STARTED));
        System.out.flush();
    }

    static Widget<?> content(Backend backend, Label status) {
        Icon picture = BitmapIcon.picture(Images.fromResource("/limn/demo/images/logo.png"));
        Icon shape = BitmapIcon.mask(Images.fromResource("/limn/demo/images/icon-star.png"));
        Set<String> on = Set.of(System.getProperty("limn.demo.tray", "").split(","));

        if (shared == null) {
            shared = Tray.create(backend, "limn-showcase");
            shared.setTitle("Limn Showcase").setTooltip(tooltipNow);
        }
        Tray tray = shared;

        Checkbox shown = new Checkbox(Checkbox.Variant.SWITCH, Text.SHOWN);
        Checkbox monochrome = new Checkbox(Checkbox.Variant.SWITCH, Text.MONOCHROME);
        Checkbox attention = new Checkbox(Checkbox.Variant.SWITCH, Text.ATTENTION);
        Checkbox activates = new Checkbox(Checkbox.Variant.SWITCH, Text.ACTIVATES);
        TextField tooltip = new TextField().setText(tooltipNow);
        shown.setChecked(iconOn);
        monochrome.setChecked(shapeOn);
        attention.setChecked(attentionOn);
        activates.setChecked(activatesOn);
        Label available = new Label(Text.NOT_TAKEN).setMuted(true);

        MenuItem pauseSync = MenuItem.check(Text.PAUSE_SYNC, false, value -> {
            say(status, "check Pause syncing " + value);
            Page.report(value ? Text.SYNC_PAUSED.get() : Text.SYNC_RESUMED.get());
        });
        Menu recent = new Menu()
                .addItem("#1042 Harbor Supply", () -> chose(status, I18nString.literal("#1042 Harbor Supply")))
                .addItem("#1041 Bayside Freight", () -> chose(status, I18nString.literal("#1041 Bayside Freight")));
        Menu menu = new Menu()
                .add(MenuItem.of(Text.OPEN_WINDOW, () -> {
                    chose(status, Text.OPEN_WINDOW);
                    bring(shown);
                }).setIcon(picture))
                .add(MenuItem.of(Text.NEW_ORDER, () -> chose(status, Text.NEW_ORDER))
                        .setIcon(Tabler.outline("file-plus"))
                        .setAccelerator(limn.components.Accelerator.command(limn.input.Keys.N)))
                .add(MenuItem.of(Text.PREFERENCES, () -> chose(status, Text.PREFERENCES))
                        .setIcon(Tabler.outline("settings"))
                        .setAccelerator(limn.components.Accelerator.command(limn.input.Keys.COMMA)))
                .addSeparator()
                .add(pauseSync)
                .add(MenuItem.submenu(Text.RECENT, recent))
                .addSeparator()
                .add(MenuItem.of(Text.QUIT, () -> {
                    chose(status, Text.QUIT);
                    backend.requestQuit();
                }).setAccelerator(limn.components.Accelerator.command(limn.input.Keys.Q)));
        tray.setMenu(menu);

        Runnable tell = () -> available.setText(tray.isAvailable() ? Text.TAKEN : Text.NOT_TAKEN);
        if (availability != null) {
            availability.cancel();   // the page was built before; its label is gone
        }
        availability = tray.onAvailabilityChange(() -> {
            tell.run();
            say(status, "available " + tray.isAvailable());
        });

        // The first switch is whether there is an icon at all, and the second which one: a tray
        // shows an icon while it has either, so a shape left set with the first switch off kept
        // the icon in the tray, and the switch that says "icon in the tray" took nothing out.
        Runnable icons = () -> {
            tray.setIcon(iconOn ? picture : null);
            tray.setMonochromeIcon(iconOn && shapeOn ? shape : null);
            tell.run();
        };
        shown.onChange(value -> {
            iconOn = value;
            icons.run();
        });
        monochrome.onChange(value -> {
            shapeOn = value;
            icons.run();
        });
        attention.onChange(value -> {
            attentionOn = value;
            tray.setAttention(value);
        });
        Runnable activation = () -> {
            say(status, "activated");
            bring(shown);
            // For a run nobody watches: whether the window that was asked in front has the keyboard.
            Ui.postDelayed(() -> say(status, "focused " + (shown.scene() != null && shown.scene().isWindowFocused())), 900);
        };
        activates.onChange(value -> {
            activatesOn = value;
            tray.onActivate(value ? activation : null);
        });
        if (activatesOn) {
            tray.onActivate(activation);   // to this page's widgets, not to those of a page left
        }
        tooltip.onChange(text -> {
            tooltipNow = text;
            tray.setTooltip(text);
        });

        if (!started && on.contains("icon")) {
            iconOn = true;
            shown.setChecked(true);
        }
        if (!started && on.contains("mono")) {
            shapeOn = true;
            monochrome.setChecked(true);
        }
        if (!started) {
            icons.run();
        }
        if (!started && on.contains("toggle")) {
            // The icon taken out and put back with nobody at the switch.
            for (int step = 1; step <= 2; step++) {
                boolean there = step == 2;
                Ui.postDelayed(() -> {
                    iconOn = there;
                    shown.setChecked(there);
                    icons.run();
                }, 5000L * step);
            }
        }
        if (!started && on.contains("attention")) {
            attentionOn = true;
            attention.setChecked(true);
            tray.setAttention(true);
        }
        if (!started && on.contains("activate")) {
            activatesOn = true;
            activates.setChecked(true);
            tray.onActivate(activation);
        }
        boolean first = !started;
        started = true;
        if (first && Boolean.getBoolean("limn.demo.tray.script")) {
            // What a panel shows of a change made while the icon is there, with nobody at the keys.
            Ui.postDelayed(() -> {
                tray.setTooltip("Changed while shown");
                pauseSync.setChecked(true);
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
        if (first && Boolean.getBoolean("limn.demo.tray.second")) {
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
        Ui.postDelayed(() -> {
            // For a run nobody watches: that the window lost the keyboard before it is asked back.
            if (shown.scene() != null) {
                shown.scene().observeWindowBlur(() -> say(status, "blurred"));
            }
        }, 1500);

        Column page = Page.column();
        page.add(Page.firstSection(Text.ICON));
        Column switches = new Column();
        switches.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.START);
        switches.add(shown);
        switches.add(monochrome);
        switches.add(attention);
        switches.add(activates);
        page.add(switches);
        page.add(Page.narrow(320, new Field(Text.TOOLTIP, tooltip)));
        page.add(available);
        return page;
    }

    /** A choice from the icon's menu: said in the status line, and on standard output. */
    private static void chose(Label status, I18nString item) {
        say(status, "chose " + item.withoutAccessLetter().english());
        Page.report(Text.CHOSE.format(Page.plain(item)));
    }

    /** Shows and focuses the window the switch is in. */
    private static void bring(Widget<?> inside) {
        if (inside.scene() != null && inside.scene().window() != null) {
            NativeWindow window = inside.scene().window();
            window.toFront();
        }
    }
}
