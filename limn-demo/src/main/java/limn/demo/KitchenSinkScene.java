package limn.demo;

import limn.backend.NativeWindow;
import limn.backend.WindowStyle;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.ContextMenus;
import limn.components.Dialog;
import limn.components.DisplayMode;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.Menu;
import limn.components.MenuBar;
import limn.components.MenuItem;
import limn.components.PasswordField;
import limn.components.PopupMenu;
import limn.components.ProgressBar;
import limn.components.ScrollGutters;
import limn.components.ScrollView;
import limn.components.TabbedPane;
import limn.components.TextArea;
import limn.components.TextField;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.BitmapIcon;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.graphics.TextMetrics;
import limn.scene.Constraints;
import limn.scene.ControlSize;
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
import limn.sound.AudioClip;
import limn.sound.Sounds;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * "Kitchen sink": one screen an application could plausibly be, organized in a
 * {@link TabbedPane}, with a "Load data" button that runs a ~2 s {@link Ui#async}
 * behind an animated indeterminate spinner, proving the UI stays fluid
 * (animations run, fields stay editable) while heavy work happens on a background
 * thread.
 *
 * <p><b>Twelve tabs, and that is a ceiling rather than a coincidence.</b> The
 * strip crops and scrolls past about that many at this window size, and a demo
 * whose first act is to hide half of itself behind a chevron is a demo of the
 * overflow behaviour. What is here is what a reader has to see; every widget set
 * that is not (animations, cursors, sprites, audio, the colour picker, the split
 * pane, the font specimens, PNG export, the glass panels) has a {@code --scene}
 * of its own, listed by {@code --scene ?}, where it gets a window rather than a
 * tab body. Adding a tab here means arguing one out.
 */
final class KitchenSinkScene {

    /**
     * @param useInSceneDialogs turns the "Internal" switch on, so {@code openDialog}
     *                          presents an overlay instead of a window
     * @param dialogCombo       the dropdown inside the dialog {@code openDialog} built
     *                          last, or {@code null} before the first one is opened
     */
    record Built(Scene scene, java.util.function.Supplier<Dialog> openDialog,
                 Runnable triggerLoad, Runnable toggleTheme, Runnable openThreeDTab,
                 Runnable openVideoTab, Runnable openFilesTab,
                 Runnable openIconsTab, Runnable useInSceneDialogs,
                 Supplier<ComboBox> dialogCombo) {
    }

    private KitchenSinkScene() {
    }

    static Built create(boolean lightTheme) {
        Theme.setCurrent(lightTheme ? Theme.light() : Theme.dark());

        // Monochrome PNG masks → tintable icons that follow the theme ink.
        Icon check = BitmapIcon.mask(Images.fromResource("/limn/demo/images/icon-check.png"));
        Icon download = BitmapIcon.mask(Images.fromResource("/limn/demo/images/icon-download.png"));
        Icon gear = BitmapIcon.mask(Images.fromResource("/limn/demo/images/icon-gear.png"));
        Image star = Images.fromResource("/limn/demo/images/icon-star.png");
        Image heart = Images.fromResource("/limn/demo/images/icon-heart.png");
        Image logo = Images.fromResource("/limn/demo/images/logo.png");

        Label status = new Label(KitchenStrings.READY).setMuted(true);

        // --- Async loading demo (top bar) --------------------------------
        Button load = new Button(KitchenStrings.LOAD_DATA).setIcon(download);
        ProgressBar spinner = new ProgressBar().setThickness(6).setPreferredWidth(120);
        spinner.setVisible(false);
        Label loadResult = new Label("").setMuted(true);
        Runnable doLoad = () -> {
            load.setEnabled(false);
            spinner.setVisible(true);
            spinner.setIndeterminate(true);
            loadResult.setText(KitchenStrings.LOADING);
            Ui.async(() -> {
                try {
                    Thread.sleep(2000); // heavy work on a worker thread
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return 42;
            }).thenAccept(count -> {
                load.setEnabled(true);
                spinner.setVisible(false);
                spinner.setIndeterminate(false);
                loadResult.setText(KitchenStrings.LOADED.format(String.valueOf(count)));
            });
        };
        load.onAction(doLoad);

        // --- Tab: Form ----------------------------------------------------
        TextField name = new TextField().setPlaceholder(KitchenStrings.NAME_PLACEHOLDER)
                .onChange(t -> status.setText(KitchenStrings.STATUS_NAME.format(t)));
        PasswordField password = new PasswordField();
        password.setPlaceholder(KitchenStrings.PASSWORD).setText("secret123");
        Checkbox reveal = new Checkbox(Checkbox.Variant.SWITCH, KitchenStrings.REVEAL);
        reveal.onChange(password::setRevealed);
        Row passwordRow = new Row();
        passwordRow.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        passwordRow.add(Expanded.of(password, 1));
        passwordRow.add(reveal);
        ComboBox combo = new ComboBox(List.of("Limn dark", "Limn light", "High contrast", "Sepia"));
        combo.onSelect(i -> status.setText(KitchenStrings.STATUS_THEME.format(combo.selectedItem())));
        Row checks = new Row();
        checks.gap(20).crossAlignment(Flex.CrossAlignment.CENTER);
        checks.add(new Checkbox(Checkbox.Variant.BOX, KitchenStrings.NOTIFICATIONS).setChecked(true));
        checks.add(new Checkbox(Checkbox.Variant.SWITCH, KitchenStrings.AIRPLANE_MODE));

        Column form = tabColumn();
        form.add(new Label(KitchenStrings.NAME).setMuted(true));
        form.add(name);
        form.add(new Label(KitchenStrings.PASSWORD).setMuted(true));
        form.add(passwordRow);
        form.add(new Label(KitchenStrings.FAVORITE_THEME).setMuted(true));
        form.add(combo);
        form.add(checks);

        // --- Tab: Text ----------------------------------------------------
        String longText = "A long text demonstrating ellipsis truncation measured with real glyphs";
        Column ellipsis = new Column();
        ellipsis.gap(6).crossAlignment(Flex.CrossAlignment.START);
        for (float w : new float[] {420, 260, 140}) {
            ellipsis.add(new SizedBox(w, 20, new Label(longText).setMuted(true)));
        }
        TextArea notes = new TextArea();
        notes.setText("""
                The TextArea scrolls on both axes with draggable scrollbars, navigates \
                across lines with the arrow keys (sticky column), selects with Shift+arrows and mouse.
                Line 2
                Line 3: scroll down with the wheel or by dragging the thumb.
                Line 4
                Line 5
                Line 6
                Line 7
                Line 8
                Line 9, the end of the text.""");
        Column textTab = tabColumn();
        textTab.add(new Label(KitchenStrings.TEXT_ELLIPSIS).setMuted(true));
        textTab.add(ellipsis);
        textTab.add(new Label(KitchenStrings.TEXT_MULTILINE).setMuted(true));
        textTab.add(Expanded.of(notes, 1));

        // --- Tab: Actions -------------------------------------------------
        Button save = new Button(KitchenStrings.SAVE).setIcon(check);
        save.onAction(() -> status.setText(KitchenStrings.SAVED));
        Button secondary = new Button(KitchenStrings.DOWNLOAD).setSecondary(true).setIcon(download);
        Button disabled = new Button(KitchenStrings.DISABLED);
        disabled.setEnabled(false);
        Row buttons = new Row();
        buttons.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        buttons.add(save);
        buttons.add(secondary);
        buttons.add(disabled);
        // Three dialog shortcuts, one per modality scope.
        Button dialogWindowModal = new Button(KitchenStrings.WINDOW_MODAL).setIcon(gear);
        Button dialogAppModal = new Button(KitchenStrings.APP_MODAL).setSecondary(true).setIcon(gear);
        Button dialogNonModal = new Button(KitchenStrings.NON_MODAL).setSecondary(true).setIcon(gear);
        // The modality regression: a native dialog, then an in-scene one raised on the
        // window the first is blocking. Both must stay reachable, newest on top.
        Button dialogStacked = new Button(KitchenStrings.STACKED).setSecondary(true);
        Row dialogButtons = new Row();
        dialogButtons.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        dialogButtons.add(dialogWindowModal);
        dialogButtons.add(dialogAppModal);
        dialogButtons.add(dialogNonModal);
        dialogButtons.add(dialogStacked);
        // Dialog options: internal (in-scene), always-on-top and OS decoration.
        Checkbox dialogInScene = new Checkbox(Checkbox.Variant.SWITCH, KitchenStrings.INTERNAL);
        Checkbox dialogOnTop = new Checkbox(Checkbox.Variant.SWITCH, KitchenStrings.ALWAYS_ON_TOP).setChecked(true);
        Checkbox dialogDecorated = new Checkbox(Checkbox.Variant.SWITCH, KitchenStrings.DECORATED);
        Row dialogOptions = new Row();
        dialogOptions.gap(20).crossAlignment(Flex.CrossAlignment.CENTER);
        dialogOptions.add(dialogInScene);
        dialogOptions.add(dialogOnTop);
        dialogOptions.add(dialogDecorated);
        // Sound-package demo: a synthesized tone (same primitive as the beep).
        Button playTone = new Button(KitchenStrings.PLAY_SOUND).setSecondary(true);
        // Exclusive fullscreen: current resolution and a mode-switch (1280×720).
        Button fullscreenNative = new Button(KitchenStrings.FULLSCREEN_NATIVE).setSecondary(true);
        Button fullscreenRes = new Button(KitchenStrings.FULLSCREEN_RES).setSecondary(true);
        Row windowRow = new Row();
        windowRow.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        windowRow.add(playTone);
        windowRow.add(fullscreenNative);
        windowRow.add(fullscreenRes);
        Column actions = tabColumn();
        actions.add(new Label(KitchenStrings.ACTIONS_BUTTONS).setMuted(true));
        actions.add(buttons);
        actions.add(new Label(KitchenStrings.ACTIONS_PROGRESS).setMuted(true));
        actions.add(new SizedBox(360, SizedBox.UNSET, new ProgressBar().setProgress(0.4f)));
        actions.add(new SizedBox(360, SizedBox.UNSET, new ProgressBar().setProgress(0.8f)));
        ProgressBar barAnim = new ProgressBar();
        actions.add(new SizedBox(360, SizedBox.UNSET, barAnim));
        actions.add(new Label(KitchenStrings.ACTIONS_DIALOGS).setMuted(true));
        actions.add(dialogButtons);
        actions.add(dialogOptions);
        // The status line is the regression net for the "died unanswered" path, which
        // no unit test reaches: turn on Decorated, open one, close it with the OS
        // button. Every way out of a dialog has to report, or a caller doing its
        // cleanup in thenAccept is left hanging with nothing on screen to say so.
        actions.add(new Label(KitchenStrings.ACTIONS_REPORTS).setMuted(true).setWrap(true));
        actions.add(new Label(KitchenStrings.ACTIONS_AUDIO).setMuted(true));
        actions.add(windowRow);

        // --- Tab: About ---------------------------------------------------
        Column about = tabColumn();
        Row aboutHead = new Row();
        aboutHead.gap(14).crossAlignment(Flex.CrossAlignment.CENTER);
        aboutHead.add(new SizedBox(56, 56, new ImageView(logo).setFit(ImageView.Fit.CONTAIN)));
        aboutHead.add(new Label(KitchenStrings.ABOUT_NAME).setFont(Theme.current().title));
        about.add(aboutHead);
        about.add(new Label(KitchenStrings.ABOUT_BLURB).setWrap(true).setMuted(true));
        Row aboutIcons = new Row();
        aboutIcons.gap(16).crossAlignment(Flex.CrossAlignment.CENTER);
        aboutIcons.add(new SizedBox(24, 24, new ImageView(heart).setTint(Theme.current().primary)));
        aboutIcons.add(new SizedBox(24, 24, new ImageView(star).setTint(Theme.current().primary)));
        aboutIcons.add(new Label(KitchenStrings.ABOUT_ICONS).setMuted(true));
        about.add(aboutIcons);

        // --- Tab: Menus (PopupMenu dropdown + right-click context menu) --
        Button menuButton = new Button(KitchenStrings.MENUS_BUTTON).setSecondary(true).setIcon(gear);
        Checkbox modalCheck = new Checkbox(Checkbox.Variant.SWITCH, KitchenStrings.MENUS_MODAL);
        ContextArea contextArea = new ContextArea();
        Row menuButtonRow = new Row();
        menuButtonRow.gap(16).crossAlignment(Flex.CrossAlignment.CENTER);
        menuButtonRow.add(menuButton);
        menuButtonRow.add(modalCheck);
        Column menusTab = tabColumn();
        menusTab.add(new Label(KitchenStrings.MENUS_DROPDOWN).setMuted(true));
        menusTab.add(menuButtonRow);
        menusTab.add(new Label(KitchenStrings.MENUS_CONTEXT)
                .setMuted(true));
        menusTab.add(new SizedBox(SizedBox.UNSET, 150, contextArea));
        menusTab.add(new Label(KitchenStrings.MENUS_MENUBAR).setMuted(true).setWrap(true));

        // The bar lives at the very top (window chrome); its menus are wired below,
        // once the actions they trigger (load, theme, tabs) exist.
        MenuBar menuBar = new MenuBar();

        TabbedPane tabs = new TabbedPane();
        tabs.setAlignment(TabbedPane.TabAlignment.LEFT);
        // Tabs with tall, fixed-height content scroll so nothing overflows the
        // panel and bleeds over the footer below (which would otherwise steal
        // the click on the bottom rows when the window is short). The Text/List
        // tabs already fill-and-self-scroll (TextArea/ListView), and the other
        // scene tabs are ScrollViews themselves.
        //
        // The padding goes INSIDE the ScrollView (scroll(pad(...))), not outside:
        // the viewport clips at its own edge, and focus rings are drawn 2px OUTSIDE
        // their widget (Button: -2..w+2), so an edge widget flush against the clip
        // would have its ring chopped. The 16pt inset keeps that overshoot clear.
        //
        // The panels made of controls go through `controlPanel`, which adds the reserved
        // bar strip to that padding; the media and prose tabs keep the overlay default.
        //
        // Every index is read off the pane just before the tab lands on it, never written
        // down: a literal is a fact this method can invalidate silently; insert one tab
        // and every index below it addresses another real tab, so nothing fails and the
        // wrong panel opens.
        int tabForm = tabs.tabCount();
        tabs.addTab(KitchenStrings.TAB_FORM, controlPanel(form));
        tabs.addTab(KitchenStrings.TAB_TEXT, pad(textTab));
        int tabActions = tabs.tabCount();
        tabs.addTab(KitchenStrings.TAB_ACTIONS, gear, controlPanel(actions));
        int tabMenus = tabs.tabCount();
        tabs.addTab(KitchenStrings.TAB_MENUS, controlPanel(menusTab));
        tabs.addTab(KitchenStrings.TAB_LIST, pad(ListScene.buildList(status::setText)));
        tabs.addTab(KitchenStrings.TAB_CONTROLS, pad(ControlsScene.content()));
        int tab3D = tabs.tabCount();
        tabs.addTab("3D", new ScrollView(pad(Viewport3DScene.tabContent())));
        int tabVideo = tabs.tabCount();
        tabs.addTab("Video", new ScrollView(pad(VideoScene.tabContent())));
        tabs.addTab("Charts", new ScrollView(pad(ChartsScene.content())));
        // Untranslated like its neighbours: the specimens are the scripts themselves, and their
        // captions name shaping properties rather than say anything a user of this window needs
        // in their own language. Switching the picker to العربية is the other half of the demo —
        // that translates the chrome around this tab, which is where a shaper either reaches or
        // does not.
        tabs.addTab("Scripts", new ScrollView(pad(BidiScene.tabContent())));
        int tabIcons = tabs.tabCount();
        tabs.addTab("Icons", pad(IconsScene.content()));
        int tabFiles = tabs.tabCount();
        tabs.addTab(KitchenStrings.TAB_FILES, pad(FilesScene.content()));
        int tabAbout = tabs.tabCount();
        tabs.addTab(KitchenStrings.TAB_ABOUT, new ScrollView(pad(about)));
        Runnable openThreeDTab = () -> tabs.setSelectedIndex(tab3D);
        Runnable openVideoTab = () -> tabs.setSelectedIndex(tabVideo);
        Runnable openFilesTab = () -> tabs.setSelectedIndex(tabFiles);
        Runnable openIconsTab = () -> tabs.setSelectedIndex(tabIcons);

        // --- The toolbar: title, the async load, and the three process-wide switches ---
        // One band where there were three. The theme picker lists every built-in, light
        // and dark among them, so the separate light/dark button was a second control for
        // a subset of this one's job; the toggle survives as View ▸ Toggle theme, which is
        // where a shortcut for "the other one" belongs.
        ComboBox themePicker = ComboBox.localized(
                Theme.builtins().stream().map(Theme::displayName).toList());
        themePicker.setSelectedIndex(Math.max(0, Theme.builtins().indexOf(Theme.current())));

        // Beside the theme picker, and process-wide for the same reason it is: this drives
        // ControlSize.setProcessDefault, the ROOT of the inheritance chain, so it reaches
        // popups and menus in their own windows, which a per-scene default would not. Any
        // widget that declares its own step keeps it, which is exactly the point of the axis.
        ComboBox sizePicker = new ComboBox(
                java.util.Arrays.stream(ControlSize.values()).map(Enum::name).toList());
        sizePicker.setSelectedIndex(ControlSize.processDefault().ordinal());

        // The language picker, beside the other two process-wide switches. Nothing here
        // re-sets any label: the widgets hold I18nStrings, and I18n.setLocale re-lays-out
        // every scene, which is the claim this control exists to make visible.
        ComboBox languagePicker = new ComboBox(Languages.NAMES);
        languagePicker.setSelectedIndex(Languages.indexOf(limn.i18n.I18n.locale()));
        languagePicker.onSelect(i -> limn.i18n.I18n.setLocale(Languages.LOCALES.get(i)));

        Row toolbar = new Row();
        toolbar.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        // The title at its natural width with a SPACER taking the slack, not the title
        // itself flexed: a flexed child is pinned to its share, so on a narrow window
        // (or simply in a language whose words are longer) the title was the first thing
        // to be squeezed and ellipsised, which is the one label on this screen that
        // should be the last.
        toolbar.add(new Label(KitchenStrings.TITLE).setRole(Label.Role.TITLE));
        toolbar.add(Expanded.spacer(1));
        toolbar.add(load);
        toolbar.add(spinner);
        toolbar.add(languagePicker);
        toolbar.add(sizePicker);
        toolbar.add(themePicker);

        // The load's own result reads as status, so it is status: one line above the
        // footer says what the screen last did, instead of two lines in two places
        // competing to.
        Row statusRow = new Row();
        statusRow.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        statusRow.add(Expanded.of(status, 1));
        statusRow.add(loadResult);

        Column page = new Column();
        page.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(menuBar); // window menu bar at the very top
        page.add(toolbar);
        page.add(Expanded.of(tabs, 1));
        page.add(new SizedBox(SizedBox.UNSET, 18, statusRow));
        page.add(new PerfFooter());

        limn.scene.Widget root = new Padding(limn.scene.Insets.all(20), page);
        Scene scene = new Scene(root);
        scene.setBackground(Theme.current().background);
        barAnim.setIndeterminate(true); // now that it is in a scene

        // The dropdown of the dialog built last, for the capture that opens it.
        AtomicReference<ComboBox> lastDialogCombo = new AtomicReference<>();

        // Builds a fresh dialog labelled with its scope, honoring the option switches.
        Function<String, Dialog> makeDialog = scope -> {
            // A dropdown and a menu opened from a dialog's own content are native windows
            // of their own, owned by the window the dialog is drawn in. Turn 'Internal'
            // on and that owner is the window the overlay deliberately keeps interactive:
            // both must still open and still take a choice. They used to be created and
            // then locked, so the control reported itself open with nothing on screen.
            ComboBox dialogCombo = new ComboBox(
                    List.of("Limn dark", "Limn light", "High contrast", "Sepia"));
            dialogCombo.onSelect(i -> status.setText(KitchenStrings.STATUS_THEME.format(dialogCombo.selectedItem())));
            lastDialogCombo.set(dialogCombo);
            Button dialogMenu = new Button(KitchenStrings.MENUS_BUTTON).setSecondary(true).setIcon(gear);
            dialogMenu.onAction(() -> openBelow(dialogMenu, demoMenu(status), false));
            Row dialogPopups = new Row();
            dialogPopups.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
            dialogPopups.add(dialogCombo);
            dialogPopups.add(dialogMenu);
            Dialog dialog = new Dialog(KitchenStrings.DIALOG_TITLE.format(scope),
                    KitchenStrings.DIALOG_MESSAGE.format(scope))
                    .setContent(dialogPopups)
                    .addButton(KitchenStrings.CANCEL, "cancel")
                    .addPrimaryButton(KitchenStrings.OK, "ok")
                    .setCancelResult("cancel")
                    .setAlwaysOnTop(dialogOnTop.isChecked());
            if (dialogInScene.isChecked()) {
                dialog.setDisplayMode(DisplayMode.IN_SCENE);
            }
            if (dialogDecorated.isChecked()) {
                dialog.setStyle(WindowStyle.DECORATED); // OS title bar (natively draggable)
            }
            return dialog;
        };

        // The window-modal opener is also what the --screenshot capture drives.
        Supplier<Dialog> openDialog = () -> {
            Dialog dialog = makeDialog.apply("window-modal");
            dialog.show(scene).thenAccept(r -> status.setText(KitchenStrings.STATUS_WINDOW_MODAL.format(String.valueOf(r))));
            return dialog;
        };
        dialogWindowModal.onAction(openDialog::get);
        dialogAppModal.onAction(() -> makeDialog.apply("app-modal")
                .showToolkitModal(scene).thenAccept(r -> status.setText(KitchenStrings.STATUS_APP_MODAL.format(String.valueOf(r)))));
        dialogNonModal.onAction(() -> {
            if (dialogInScene.isChecked()) {
                status.setText(KitchenStrings.STATUS_NEEDS_WINDOW);
                return;
            }
            makeDialog.apply("non-modal")
                    .showNonModal(scene).thenAccept(r -> status.setText(KitchenStrings.STATUS_NON_MODAL.format(String.valueOf(r))));
        });
        // Two modals at once: the shape that used to deadlock the toolkit. A native
        // dialog locks this window; a beat later the app raises a second one ON that
        // locked window (an unsaved-changes prompt on the way out is how a real app
        // gets here). Answer the upper one first: it must be on top and clickable,
        // and the lower one frozen until it closes, then live again.
        //
        // Both are floating windows, so the lower dialog stays on screen, dimmed,
        // underneath the upper one. Turn 'Internal' on and the upper one ASKS for an
        // overlay and gets a window anyway: an overlay is drawn inside this window
        // and could only be fronted by raising it, which would swallow the dialog
        // already floating there. The status line reports the promotion; keepInScene()
        // is how an app declines it.
        dialogStacked.onAction(() -> {
            new Dialog(KitchenStrings.DIALOG_LOWER, KitchenStrings.DIALOG_LOWER_BODY)
                    .addPrimaryButton(KitchenStrings.CLOSE, "ok")
                    .setCancelResult("ok")
                    .show(scene)
                    .thenAccept(r -> status.setText(KitchenStrings.STATUS_LOWER.format(String.valueOf(r))));
            boolean upperInScene = dialogInScene.isChecked();
            Ui.postDelayed(() -> {
                Dialog upper = new Dialog(KitchenStrings.DIALOG_UPPER, KitchenStrings.DIALOG_UPPER_BODY)
                        .addButton(KitchenStrings.CANCEL, "cancel")
                        .addPrimaryButton(KitchenStrings.OK, "ok")
                        .setCancelResult("cancel");
                if (upperInScene) {
                    upper.setDisplayMode(DisplayMode.IN_SCENE);
                }
                upper.show(scene).thenAccept(r -> status.setText(KitchenStrings.STATUS_UPPER.format(String.valueOf(r))));
                if (upperInScene) {
                    status.setText(upper.modalWindow() != null
                            ? KitchenStrings.STATUS_UPPER_PROMOTED.get()
                            : KitchenStrings.STATUS_UPPER_IN_SCENE.get());
                }
            }, 700);
        });
        playTone.onAction(() -> {
            Sounds.play(AudioClip.tone(880f, 0.14f, 0.5f));
            status.setText(Sounds.isAvailable()
                    ? KitchenStrings.STATUS_SOUND.get() : KitchenStrings.STATUS_SOUND_NO_DEVICE.get());
        });
        // Both fullscreen buttons toggle: enter (at the given resolution, or the
        // current one for w/h <= 0) when windowed, exit when already fullscreen.
        BiConsumer<Integer, Integer> toggleFullscreen = (w, h) -> {
            NativeWindow win = scene.window();
            if (win == null) {
                return;
            }
            if (win.isFullscreen()) {
                win.exitFullscreen();
                status.setText(KitchenStrings.STATUS_WINDOW_RESTORED);
            } else {
                win.enterFullscreen(w, h, 0);
                status.setText(w <= 0 ? KitchenStrings.STATUS_FS_CURRENT.get()
                        : KitchenStrings.STATUS_FS_AT.format(String.valueOf(w), String.valueOf(h)));
            }
        };
        fullscreenNative.onAction(() -> toggleFullscreen.accept(0, 0));
        fullscreenRes.onAction(() -> toggleFullscreen.accept(1280, 720));

        // Applies any theme process-wide: refresh the scene background + re-layout
        // (every component reads its colors from Theme.current() at paint time) and
        // keep the header controls in sync.
        Consumer<Theme> applyTheme = t -> {
            Theme.setCurrent(t);
            // indexOf answers -1 for a palette that is not one of the built-ins, and the picker
            // refuses an index that is not an item, so the picker only follows a theme it can
            // show. Setting it echoes straight back through onSelect into this lambda with the
            // same theme; the setter's unchanged-value early return is what ends that there.
            int builtin = Theme.builtins().indexOf(t);
            if (builtin >= 0) {
                themePicker.setSelectedIndex(builtin);
            }
            scene.setBackground(t.background);
            root.markNeedsLayout();
            status.setText(KitchenStrings.STATUS_THEME_CHANGED.format(t.displayName().get()));
        };
        themePicker.onSelect(i -> applyTheme.accept(Theme.builtins().get(i)));
        // No relayout call here: Scene subscribes a listener to ControlSize in its CONSTRUCTOR,
        // so every live scene (this window, any open popup, any unbound one) re-measures
        // itself. Doing it by hand would miss the popups, which is the bug the subscription
        // point was chosen to avoid.
        sizePicker.onSelect(i -> ControlSize.setProcessDefault(ControlSize.values()[i]));
        // The "Toggle theme" menu item flips between light and dark.
        Runnable toggleTheme = () ->
                applyTheme.accept(Theme.current() == Theme.dark() ? Theme.light() : Theme.dark());

        // --- MenuBar (top): wired now that load/theme/tabs/fullscreen exist ---
        // "Open recent" is a menu whose contents are DATA: every load pushes an
        // entry and the submenu is rebuilt in place with Menu.clear(). In place,
        // because the MenuItem below holds this instance; handing it a fresh Menu
        // would leave it showing the list that is no longer current. Load twice and
        // reopen the menu to watch it grow, even with the menu already on screen.
        List<String> recentLoads = new java.util.ArrayList<>();
        Menu recents = new Menu();
        fillRecents(recents, recentLoads, status);
        menuBar.addMenu(KitchenStrings.MENU_FILE, new Menu()
                .addItem(KitchenStrings.LOAD_DATA, () -> {
                    doLoad.run();
                    recentLoads.add(0, "dataset-" + (recentLoads.size() + 1) + ".csv");
                    fillRecents(recents, recentLoads, status);
                })
                .addSubmenu(KitchenStrings.MENU_OPEN_RECENT, recents)
                .addSeparator()
                .addSubmenu(KitchenStrings.MENU_FULLSCREEN, new Menu()
                        .addItem(KitchenStrings.MENU_CURRENT_RES, () -> toggleFullscreen.accept(0, 0))
                        .addItem("1280×720", () -> toggleFullscreen.accept(1280, 720)))
                .addSeparator()
                .addItem(KitchenStrings.MENU_QUIT, () -> status.setText(KitchenStrings.STATUS_QUIT)));
        menuBar.addMenu(KitchenStrings.MENU_EDIT, new Menu()
                .addItem(KitchenStrings.MENU_UNDO, () -> status.setText(KitchenStrings.STATUS_UNDO))
                .add(MenuItem.of(KitchenStrings.MENU_REDO, () -> { }).setEnabled(false))
                .addSeparator()
                .addCheck(KitchenStrings.NOTIFICATIONS, true,
                        on -> status.setText(KitchenStrings.STATUS_NOTIFICATIONS.format(String.valueOf(on))))
                .addCheck(KitchenStrings.AIRPLANE_MODE, false,
                        on -> status.setText(KitchenStrings.STATUS_AIRPLANE.format(String.valueOf(on)))));
        // The rendering experiment lives here rather than on a row of its own under the
        // tabs. It is a developer's switch, not the application's: what it changes is
        // visible in the footer's Painted gauge and in the damage flashes, neither of
        // which is where the switch was, and the row it used to occupy is the one that
        // was clipping the bottom of every tab.
        menuBar.addMenu(KitchenStrings.MENU_VIEW, new Menu()
                .addSubmenu(KitchenStrings.MENU_GO_TO_TAB, new Menu()
                        .addItem(KitchenStrings.TAB_FORM, () -> tabs.setSelectedIndex(tabForm))
                        .addItem(KitchenStrings.TAB_ACTIONS, () -> tabs.setSelectedIndex(tabActions))
                        .addItem(KitchenStrings.TAB_MENUS, () -> tabs.setSelectedIndex(tabMenus))
                        .addItem("3D", () -> tabs.setSelectedIndex(tab3D))
                        .addItem("Video", () -> tabs.setSelectedIndex(tabVideo))
                        .addItem(KitchenStrings.TAB_ABOUT, () -> tabs.setSelectedIndex(tabAbout)))
                .addSeparator()
                .addItem(KitchenStrings.MENU_TOGGLE_THEME, toggleTheme)
                .addSeparator()
                // What the switches used to say in a tooltip, said in the status line
                // instead: a menu item cannot carry one, and an explanation that arrives
                // when the thing is switched on is read, where one hidden behind a hover
                // on a row of toggles was not.
                .addCheck(KitchenStrings.RENDER_PARTIAL, false, on -> {
                    scene.setPartialRendering(on);
                    status.setText(KitchenStrings.RENDER_PARTIAL_TIP);
                })
                .addCheck(KitchenStrings.RENDER_DAMAGE, false, on -> {
                    scene.setDamageDebug(on);
                    status.setText(KitchenStrings.RENDER_DAMAGE_TIP);
                })
                .addSeparator()
                .addItem(KitchenStrings.LAUNCH_CUBE, () -> {
                    if (scene.window() != null) { // each pick spawns another cube
                        CubeGadget.spawn(scene.window().backend(), scene);
                        status.setText(KitchenStrings.LAUNCH_CUBE_TIP);
                    }
                })
                .addItem(KitchenStrings.CLEAR_CUBES, () -> {
                    CubeGadget.closeOverlay();
                    status.setText(KitchenStrings.CLEAR_CUBES_TIP);
                }));

        // --- Menus tab: dropdown from the button + right-click context menu ---
        menuButton.onAction(() -> openBelow(menuButton, demoMenu(status), modalCheck.isChecked()));
        contextArea.onContext((x, y) -> new PopupMenu(demoMenu(status))
                .setModal(modalCheck.isChecked()).showAt(scene, x, y));

        return new Built(scene, openDialog, doLoad, toggleTheme, openThreeDTab, openVideoTab,
                openFilesTab, openIconsTab, () -> dialogInScene.setChecked(true),
                lastDialogCombo::get);
    }

    private static Column tabColumn() {
        Column column = new Column();
        column.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
        return column;
    }

    private static Padding pad(limn.scene.Widget content) {
        return new Padding(limn.scene.Insets.all(16), content);
    }

    /**
     * A padded panel of controls in a scroll view that keeps a strip for its bar.
     *
     * <p>The padding and the strip solve two different halves of the same complaint. The
     * padding keeps a focus ring off the clip edge, which is why it goes inside; the strip
     * keeps the bar off the controls, which the padding cannot do, because an overlay bar
     * is drawn over the viewport and does not know the content has a margin. A panel whose
     * rows all end in something clickable wants both.
     *
     * <p>Media and prose do not: see the tabs that stay on the {@link
     * ScrollGutters.Layout#OVERLAY} default below.
     */
    private static ScrollView controlPanel(limn.scene.Widget content) {
        return new ScrollView(pad(content)).setBarLayout(ScrollGutters.Layout.RESERVED);
    }

    /**
     * Rebuilds the "Open recent" submenu from its list: the data-driven menu
     * pattern {@link Menu#clear()} exists for. Empty is a disabled row rather than
     * an empty column, so the parent item still reads as a place things go.
     */
    private static void fillRecents(Menu recents, List<String> entries, Label status) {
        recents.clear();
        if (entries.isEmpty()) {
            recents.add(MenuItem.of("Nothing loaded yet", () -> { }).setEnabled(false));
            return;
        }
        for (String entry : entries) {
            recents.addItem(entry, () -> status.setText("Reopen " + entry));
        }
        recents.addSeparator();
        recents.addItem(KitchenStrings.MENU_CLEAR, () -> {
            entries.clear();
            fillRecents(recents, entries, status);
            status.setText(KitchenStrings.STATUS_RECENT_CLEARED);
        });
    }

    /** A demo menu with commands, checkable items, a separator, a submenu and a disabled entry. */
    private static Menu demoMenu(Label status) {
        return new Menu()
                .addItem(KitchenStrings.MENU_COPY, () -> status.setText(KitchenStrings.STATUS_COPY))
                .addItem(KitchenStrings.MENU_PASTE, () -> status.setText(KitchenStrings.STATUS_PASTE))
                .addSeparator()
                .addCheck(KitchenStrings.MENU_BOLD, true,
                        on -> status.setText(KitchenStrings.STATUS_BOLD.format(String.valueOf(on))))
                .addCheck(KitchenStrings.MENU_ITALIC, false,
                        on -> status.setText(KitchenStrings.STATUS_ITALIC.format(String.valueOf(on))))
                .addSeparator()
                .addSubmenu(KitchenStrings.MENU_EXPORT, new Menu()
                        .addItem("PDF", () -> status.setText(KitchenStrings.STATUS_EXPORT_PDF))
                        .addItem("PNG", () -> status.setText(KitchenStrings.STATUS_EXPORT_PNG))
                        .addItem("SVG", () -> status.setText(KitchenStrings.STATUS_EXPORT_SVG)))
                .add(MenuItem.of(KitchenStrings.MENU_PROPERTIES, () -> { }).setEnabled(false));
    }

    /**
     * Opens {@code menu} as a dropdown directly below {@code anchor} (its scene rect).
     * Anchored on the widget, not on a scene handed in: a dialog's content lives in the
     * dialog's own scene when it is a native window, and in the host's when it is an
     * overlay; passing the wrong one puts the menu in the wrong window.
     */
    private static void openBelow(Widget anchor, Menu menu, boolean modal) {
        float sx = 0;
        float sy = 0;
        for (Widget w = anchor; w != null; w = w.parent()) {
            sx += w.x();
            sy += w.y();
        }
        new PopupMenu(menu).setModal(modal).showAnchored(anchor, sx, sy, anchor.width(), anchor.height());
    }

    /**
     * A bordered panel that raises a context menu.
     *
     * <p>It asks {@code ContextMenus} what the gesture is and then opens the popup itself,
     * rather than going through {@code ContextMenus.attach}: this scene switches the popup
     * between modal and not from a checkbox, and configuring the popup is exactly what the
     * attach form does not hand over. The gesture is still asked about in one place, which is
     * the part that was worth sharing.
     */
    private static final class ContextArea extends Widget {

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
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), theme.surface);
            canvas.drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, theme.tokensFor(this).radiusMedium(), 1, theme.outline);
            String hint = "right-click here";
            Font font = theme.body;
            TextMetrics m = textRuler().measure(hint, font);
            canvas.drawText(hint, (width() - m.width()) / 2,
                    (height() - m.height()) / 2 + m.ascent(), font, theme.textMuted);
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
