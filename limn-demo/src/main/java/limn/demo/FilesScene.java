package limn.demo;

import limn.backend.FileDialogs;
import limn.backend.NativeWindow;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Dialog;
import limn.components.DisplayMode;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.TextMetrics;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.DragEvent;
import limn.scene.event.DropEvent;
import limn.transfer.DataFormat;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * OS file integration showcase ({@code --scene files}): the native open/save/
 * folder dialogs ({@link FileDialogs}, tinyfd, no AWT), OS drag-and-drop onto a
 * {@link Widget#onDrop} target, and the window extras: icon
 * ({@link NativeWindow#setIcon}, Windows/Linux), a minimum size
 * ({@link NativeWindow#setSizeLimits}) and a close request answered from a confirm
 * dialog ({@link NativeWindow#setCloseRequestHandler}).
 */
final class FilesScene {

    private FilesScene() {
    }

    /** Standalone {@code --scene files}. */
    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    /** The subtree, reusable as a kitchen-sink tab; wires the window extras on attach. */
    static Widget<?> content() {
        // No Theme local here on purpose: the only reads it served were the four title
        // fonts, and those are now roles resolved per widget at measure time.
        Checkbox confirmClose = new Checkbox(Checkbox.Variant.SWITCH, "Confirm before closing");
        confirmClose.setChecked(true);

        // The window binds to the scene only after the whole tree is built, so
        // the column wires the window extras one loop turn after it attaches.
        Column col = new Column() {
            private boolean wired;

            @Override
            protected void onAttached() {
                Ui.post(this::wire);
            }

            private void wire() {
                Scene scene = scene();
                NativeWindow window = scene != null ? scene.window() : null;
                if (wired || window == null) {
                    return; // headless/embedded, or detached before the post ran
                }
                wired = true;
                window.setIcon(appIcon(32), appIcon(16));
                window.setSizeLimits(480, 360, 0, 0);
                // Asked on the close button and when the application ends; answered from the
                // dialog, later. A second click while it is open asks nothing new.
                window.setCloseRequestHandler(request -> {
                    // Asked only while this page is the one in the window: once the page has
                    // left it there is no scene to show the question in, and a window that
                    // could not ask could not be closed either.
                    if (!confirmClose.isChecked() || scene() == null) {
                        request.allow();
                        return;
                    }
                    new Dialog("Close window?", "The close button was intercepted by "
                            + "setCloseRequestHandler; confirm to really close.")
                            .setDisplayMode(DisplayMode.IN_SCENE)
                            .addButton("Keep open", "stay")
                            .addPrimaryButton("Close", "close")
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
        };
        col.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);

        // Role + strong, not setFont(theme.title): setStrong derives bold from whatever
        // the role resolves to, so the pair still tracks the step; setFont would pin
        // MEDIUM's 20 pt and only the weight would survive.
        col.add(new Label("OS files: dialogs, drop, window extras")
                .setRole(Label.Role.TITLE).setStrong(true));

        col.add(new Label("Native file dialogs").setRole(Label.Role.TITLE));
        col.add(new Label("The system's own chooser, owned by this window.")
                .setMuted(true).setWrap(true));

        Label status = new Label("No dialog shown yet.").setMuted(true).setWrap(true);
        FileDialogs.Filter images = FileDialogs.Filter.of("Images", "*.png", "*.jpg", "*.jpeg");

        Row buttons = new Row();
        buttons.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        buttons.add(new Button("Open file…").onAction(() -> dialogs(status).ifPresent(d ->
                d.openFile("Open image", null, images).thenAccept(picked -> status.setText(
                        picked.map(p -> "Open: " + p).orElse("Open: cancelled"))))));
        buttons.add(new Button("Open files…").onAction(() -> dialogs(status).ifPresent(d ->
                d.openFiles("Open images", null, images).thenAccept(picked -> status.setText(
                        picked.isEmpty() ? "Open multiple: cancelled"
                                : "Open multiple: " + picked.size() + " file(s) " + picked)))));
        buttons.add(new Button("Save file…").onAction(() -> dialogs(status).ifPresent(d ->
                d.saveFile("Save as", Path.of("untitled.png"), images).thenAccept(picked -> status.setText(
                        picked.map(p -> "Save: " + p).orElse("Save: cancelled"))))));
        buttons.add(new Button("Choose folder…").onAction(() -> dialogs(status).ifPresent(d ->
                d.chooseFolder("Choose a folder", null).thenAccept(picked -> status.setText(
                        picked.map(p -> "Folder: " + p).orElse("Folder: cancelled"))))));
        col.add(buttons);

        col.add(new Label("Opens on a sample folder, filtered to one made-up extension.")
                .setMuted(true).setWrap(true));
        FileDialogs.Filter sketches = FileDialogs.Filter.of("Limn sketch", "*.limn");
        Row sketchRow = new Row();
        sketchRow.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        sketchRow.add(new Button("Open sketch (*.limn)…").onAction(() -> dialogs(status).ifPresent(d -> {
            Path samples = sampleSketchFolder(status);
            if (samples != null) {
                d.openFile("Open sketch", samples, sketches).thenAccept(picked -> status.setText(
                        picked.map(p -> "Sketch: " + p).orElse("Sketch: cancelled")));
            }
        })));
        col.add(sketchRow);
        col.add(status);

        col.add(new Label("Drag & drop from the OS").setRole(Label.Role.TITLE));
        col.add(new Label("Drop files from the file manager on the area below.")
                .setMuted(true).setWrap(true));
        col.add(new DropArea());

        col.add(new Label("Window extras").setRole(Label.Role.TITLE));
        col.add(new Label("This window has an icon and a minimum size, and can ask before it closes.")
                .setMuted(true).setWrap(true));
        col.add(confirmClose);

        return new ScrollView(col);
    }

    /** The backend dialogs, or empty (with a status note) when running headless. */
    private static Optional<FileDialogs> dialogs(Label status) {
        Scene scene = status.scene();
        if (scene == null || scene.window() == null) {
            status.setText("No native window (headless): dialogs unavailable.");
            return Optional.empty();
        }
        return Optional.of(scene.window().fileDialogs());
    }

    /**
     * A folder of sample {@code .limn} files plus decoys, (re)written on demand
     * under the system temp dir so the sketch dialog opens somewhere the filter
     * is visible: samples selectable, decoys greyed out. Returns null (with a
     * status note) if the folder cannot be written.
     */
    private static Path sampleSketchFolder(Label status) {
        try {
            // A fresh directory per run, not a fixed name under the shared temp dir: on a
            // multi-user machine a fixed name is anyone's to pre-create, with entries that are
            // symbolic links to files this user owns, and writeString follows them.
            if (sketchFolder == null) {
                sketchFolder = Files.createTempDirectory("limn-demo-sketches-");
                sketchFolder.toFile().deleteOnExit();
            }
            Path dir = sketchFolder;
            Files.writeString(dir.resolve("doodle.limn"), "limn sample sketch\n");
            Files.writeString(dir.resolve("shapes.limn"), "limn sample sketch\n");
            Files.writeString(dir.resolve("not-a-sketch.txt"), "decoy\n");
            Files.writeString(dir.resolve("also-not.dat"), "decoy\n");
            return dir;
        } catch (IOException e) {
            status.setText("Could not create the sample folder: " + e);
            return null;
        }
    }

    /** The sample folder of this run, once made; see {@link #sampleSketchFolder}. */
    private static Path sketchFolder;

    /** A generated two-tone "L" tile, enough to show the icon slot without assets. */
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
                rgba[i] = (byte) (inL ? 0xFF : 0x3D);     // R
                rgba[i + 1] = (byte) (inL ? 0xFF : 0x6B); // G
                rgba[i + 2] = (byte) (inL ? 0xFF : 0xF2); // B
                rgba[i + 3] = (byte) 0xFF;
            }
        }
        return new Image(size, size, rgba);
    }

    /** A drop target: lists the names of the last files dropped onto it. */
    private static final class DropArea extends Widget<DropArea> {

        private final List<String> dropped = new ArrayList<>();

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 140);
        }

        @Override
        protected void onDragOver(DragEvent event) {
            if (event.offer().has(DataFormat.FILES)) {
                event.accept();
            }
        }

        @Override
        protected void onDrop(DropEvent event) {
            event.offer().read(DataFormat.FILES).thenAccept(files -> {
                dropped.clear();
                for (Path path : files) {
                    dropped.add(path.getFileName() != null ? path.getFileName().toString() : path.toString());
                }
                invalidate();
            });
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            float radius = theme.tokensFor(this).radiusMedium();
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), radius, theme.surfaceRaised(),
                    1, theme.outline());
            Font font = theme.body();
            if (dropped.isEmpty()) {
                drawCentered(canvas, "Drop files here", font, 0, 1);
            } else {
                int shown = Math.min(dropped.size(), 4);
                for (int i = 0; i < shown; i++) {
                    drawCentered(canvas, dropped.get(i), font, i, shown);
                }
                if (dropped.size() > shown) {
                    drawCentered(canvas, "… +" + (dropped.size() - shown) + " more",
                            font, shown, shown + 1);
                }
            }
        }

        private void drawCentered(Canvas canvas, String text, Font font, int line, int lines) {
            Theme theme = Theme.current();
            TextMetrics m = textRuler().measure(text, font);
            float lineHeight = m.height() + 4;
            float top = (height() - lines * lineHeight) / 2;
            canvas.drawText(text, (width() - m.width()) / 2,
                    top + line * lineHeight + m.ascent(), font,
                    dropped.isEmpty() ? theme.textMuted() : theme.text());
        }
    }
}
