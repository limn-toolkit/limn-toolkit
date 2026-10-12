package limn.demo;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.components.Accelerator;
import limn.components.Button;
import limn.components.Dialog;
import limn.components.DisplayMode;
import limn.components.Label;
import limn.components.ListView;
import limn.components.Menu;
import limn.components.MenuBar;
import limn.components.MenuItem;
import limn.components.PasswordField;
import limn.components.SelectionMode;
import limn.components.TextArea;
import limn.components.TextField;
import limn.components.date.DateField;
import limn.concurrent.Ui;
import limn.graphics.BitmapIcon;
import limn.graphics.Images;
import limn.input.Keys;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.scene.layout.Wrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A menu bar to try by hand and by a script: every kind of item, the three roles a system has
 * a place of its own for, the six editing commands, and the windows that decide which bar a
 * system shows (a second window with another bar, one with none, an owned one, a dialog as a
 * window and one drawn in the scene, a file panel).
 *
 * <p>Each thing the page can do has a name and a button. {@code -Dlimn.demo.menubar.commands}
 * names a file the page reads a few times a second: a name written there is done once, which is
 * how a run with nobody at the keys drives it. Every choice and every step is printed as a line
 * that starts with {@code MENUBAR}.
 */
final class MenuBarLabScene {

    private MenuBarLabScene() {
    }

    private static void say(Label status, String text) {
        status.setText(text);
        System.out.println("MENUBAR " + text);
        System.out.flush();
    }

    static Scene create(Backend backend, NativeWindow window) {
        Label status = new Label("Nothing chosen yet");
        TextField field = new TextField().setPlaceholder("A text field");
        TextArea area = new TextArea();
        PasswordField password = new PasswordField();
        DateField date = new DateField();
        ListView<String> list = new ListView<String>(Label::new)
                .setItems(List.of("One", "Two", "Three")).setSelectionMode(SelectionMode.MULTI);

        MenuItem settings = MenuItem.of("Preferences…", () -> say(status, "chose Preferences"))
                .setRole(MenuItem.Role.SETTINGS);
        Menu help = new Menu()
                .addItem("Guide", () -> say(status, "chose Guide"))
                .addSeparator()
                .add(MenuItem.of("About the lab", () -> say(status, "chose About")).setRole(MenuItem.Role.ABOUT));
        Menu windows = new Menu().addItem("Bring the lab forward", () -> say(status, "chose Bring forward"));
        MenuBar bar = new MenuBar();
        bar.addMenu("File", new Menu()
                .add(MenuItem.of("New", () -> say(status, "chose New")).setAccelerator(Accelerator.command(Keys.N)))
                .add(MenuItem.of("Close this window", () -> {
                    say(status, "chose Close this window");
                    window.requestClose();
                }).setAccelerator(Accelerator.command(Keys.W)))
                .add(MenuItem.of("End the application", () -> {
                    say(status, "chose End the application");
                    backend.requestQuit();
                }))
                .addSeparator()
                .add(MenuItem.of("Exit", () -> say(status, "the Exit item's own action ran"))
                        .setRole(MenuItem.Role.QUIT).setAccelerator(Accelerator.command(Keys.Q))));
        bar.addMenu("Edit", new Menu()
                .add(MenuItem.of(MenuItem.Role.UNDO))
                .add(MenuItem.of(MenuItem.Role.REDO))
                .addSeparator()
                .add(MenuItem.of(MenuItem.Role.CUT))
                .add(MenuItem.of(MenuItem.Role.COPY))
                .add(MenuItem.of(MenuItem.Role.PASTE))
                .add(MenuItem.of(MenuItem.Role.SELECT_ALL))
                .addSeparator()
                .add(settings));
        MenuItem disabled = MenuItem.of("Disabled, on a chord", () -> say(status, "chose the disabled item"))
                .setAccelerator(Accelerator.command(Keys.D)).setEnabled(false);
        bar.addMenu("View", new Menu()
                .addCheck("Side bar", true, on -> say(status, "Side bar " + on))
                .add(MenuItem.of("With a picture", () -> say(status, "chose With a picture"))
                        .setIcon(BitmapIcon.picture(Images.fromResource("/limn/demo/images/icon-heart.png"))))
                .addSubmenu("More", new Menu().addItem("Inside", () -> say(status, "chose Inside")))
                .addSeparator()
                .add(MenuItem.of("Refresh", () -> say(status, "chose Refresh")).setAccelerator(Accelerator.of(Keys.F5)))
                .add(MenuItem.of("Control alone", () -> say(status, "chose Control alone"))
                        .setAccelerator(new Accelerator(Keys.K, Keys.MOD_CONTROL)))
                .add(MenuItem.of("Option alone", () -> say(status, "chose Option alone"))
                        .setAccelerator(new Accelerator(Keys.J, Keys.MOD_ALT)))
                .add(MenuItem.of("A bare key", () -> say(status, "chose A bare key"))
                        .setAccelerator(Accelerator.of(Keys.F9)))
                .add(MenuItem.of("Delete", () -> say(status, "chose Delete"))
                        .setAccelerator(Accelerator.command(Keys.BACKSPACE)))
                .add(MenuItem.of("Bare Delete", () -> say(status, "chose Bare Delete"))
                        .setAccelerator(Accelerator.of(Keys.DELETE)))
                .add(disabled));
        if (!Boolean.getBoolean("limn.demo.menubar.noWindowMenu")) {
            bar.addMenu("Window", windows);
            bar.setWindowMenu(windows);
        }
        bar.addMenu("Help", help);
        bar.setHelpMenu(help);
        if (Boolean.getBoolean("limn.demo.menubar.selectNone")) {
            // What the guide warns of: an ordinary item on an editing command's chord.
            bar.addMenu("Selection", new Menu().add(MenuItem.of("Select None", () -> say(status, "chose Select None"))
                    .setAccelerator(Accelerator.command(Keys.A))));
        }

        Column page = new Column();
        page.gap(8).crossAlignment(limn.scene.layout.Flex.CrossAlignment.STRETCH);
        Wrap buttons = new Wrap();
        Map<String, Runnable> steps = new LinkedHashMap<>();
        Scene[] sceneOf = new Scene[1];

        steps.put("second", () -> {
            NativeWindow second = backend.createWindow(WindowConfig.of("Second", 420, 260));
            MenuBar tools = new MenuBar().addMenu("Tools", new Menu()
                    .add(MenuItem.of("Run", () -> say(status, "chose Run, of the second window"))
                            .setAccelerator(Accelerator.command(Keys.R))));
            Column body = new Column();
            body.add(tools);
            body.add(new TextField().setPlaceholder("The second window's field"));
            new Scene(body).bind(second);
            second.show();
        });
        steps.put("nobar", () -> {
            NativeWindow plain = backend.createWindow(WindowConfig.of("Plain", 360, 200));
            Column body = new Column();
            body.add(new TextField().setPlaceholder("A window with no bar"));
            new Scene(body).bind(plain);
            plain.show();
        });
        steps.put("palette", () -> {
            NativeWindow palette = backend.createWindow(WindowConfig.of("Palette", 300, 160).ownedBy(window));
            Column body = new Column();
            TextField find = new TextField().setPlaceholder("An owned window's field");
            body.add(find);
            Scene scene = new Scene(body);
            scene.bind(palette);
            palette.show();
            scene.requestFocus(find);
        });
        steps.put("modal", () -> new Dialog("A dialog as a window", "The owner's bar is out of reach.")
                .addPrimaryButton("Close", "close").setCancelResult("close").show(sceneOf[0])
                .thenAccept(result -> say(status, "the dialog closed")));
        steps.put("inscene", () -> new Dialog("A dialog in the scene", "The owner's bar is out of reach.")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .addPrimaryButton("Close", "close").setCancelResult("close").show(sceneOf[0])
                .thenAccept(result -> say(status, "the dialog in the scene closed")));
        steps.put("panel", () -> window.fileDialogs().saveFile("Save", null, null)
                .thenAccept(path -> say(status, "the panel answered " + path.isPresent())));
        steps.put("fsmonitor", () -> window.enterFullscreen(1280, 720, 0));
        steps.put("fsnative", () -> window.enterFullscreen(0, 0, 0));
        steps.put("fsexit", window::exitFullscreen);
        steps.put("hide", () -> {
            window.hide();
            Ui.postDelayed(() -> {
                window.show();
                say(status, "shown again");
            }, 6000);
        });
        steps.put("minimize", () -> {
            window.minimize();
            Ui.postDelayed(() -> {
                window.restore();
                say(status, "restored");
            }, 6000);
        });
        steps.put("detach", () -> page.remove(bar));
        steps.put("attach", () -> page.add(0, bar));
        steps.put("inwindow", () -> bar.setPlacement(MenuBar.Placement.IN_WINDOW));
        steps.put("system", () -> bar.setPlacement(MenuBar.Placement.SYSTEM));
        steps.put("hidebar", () -> bar.setVisible(false));
        steps.put("showbar", () -> bar.setVisible(true));
        steps.put("settingsoff", () -> settings.setEnabled(false));
        steps.put("settingson", () -> settings.setEnabled(true));
        steps.put("enable", () -> disabled.setEnabled(true));
        steps.put("addmenu", () -> bar.addMenu("Added", new Menu().addItem("Late", () -> say(status, "chose Late"))));
        steps.put("rebind", () -> {
            MenuBar other = new MenuBar().addMenu("Other", new Menu().addItem("Else", () -> say(status, "chose Else")));
            Column body = new Column();
            body.add(other);
            body.add(new Label("Another scene on the same window"));
            new Scene(body).bind(window);
            window.requestFrame();
        });
        steps.put("fill", () -> {
            field.setText("limn");
            sceneOf[0].requestFocus(field);
            field.selectAll();
        });
        steps.put("unselect", () -> field.select(0, 0));
        steps.put("focus-field", () -> sceneOf[0].requestFocus(field));
        steps.put("focus-area", () -> sceneOf[0].requestFocus(area));
        steps.put("focus-password", () -> sceneOf[0].requestFocus(password));
        steps.put("focus-date", () -> sceneOf[0].requestFocus(date));
        steps.put("focus-list", () -> sceneOf[0].requestFocus(list));
        steps.put("focus-none", () -> sceneOf[0].requestFocus(null));
        steps.put("report", () -> say(status, "field=[" + field.text() + "] area=[" + area.text().replace('\n', '|')
                + "] password=" + password.text().length() + " date=[" + date.text() + "] list="
                + list.selectedIndices().length + " inSystemBar=" + bar.isInSystemBar()
                + " clipboard=[" + sceneOf[0].clipboard().text() + "]"));

        for (Map.Entry<String, Runnable> step : steps.entrySet()) {
            Button button = new Button(step.getKey());
            button.onAction(() -> run(status, step.getKey(), step.getValue()));
            buttons.add(button);
        }
        page.add(bar);
        page.add(status);
        page.add(field);
        page.add(area);
        page.add(password);
        page.add(date);
        page.add(buttons);
        page.add(list);
        Scene scene = new Scene(new Padding(Insets.all(12), page));
        sceneOf[0] = scene;

        String commands = System.getProperty("limn.demo.menubar.commands");
        if (commands != null) {
            follow(Path.of(commands), status, steps);
        }
        return scene;
    }

    private static void run(Label status, String name, Runnable step) {
        say(status, "step " + name);
        try {
            step.run();
        } catch (RuntimeException failed) {
            say(status, "step " + name + " failed: " + failed);
        }
    }

    /** Does each name written to the file, once: a line is {@code <number> <name>} and the number must grow. */
    private static void follow(Path file, Label status, Map<String, Runnable> steps) {
        long[] done = {0};
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            try {
                if (Files.exists(file)) {
                    for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
                        String[] parts = line.trim().split(" ", 2);
                        if (parts.length == 2 && Long.parseLong(parts[0]) > done[0]) {
                            done[0] = Long.parseLong(parts[0]);
                            Runnable step = steps.get(parts[1]);
                            if (step == null) {
                                say(status, "no step named " + parts[1]);
                            } else {
                                run(status, parts[1], step);
                            }
                        }
                    }
                }
            } catch (java.io.IOException | NumberFormatException unreadable) {
                // Written while read: the next turn reads it whole.
            }
            Ui.postDelayed(poll[0], 250);
        };
        Ui.postDelayed(poll[0], 500);
    }
}
