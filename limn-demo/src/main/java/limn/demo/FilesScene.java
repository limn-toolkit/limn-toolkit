package limn.demo;

import limn.backend.FileDialogs;
import limn.components.Button;
import limn.components.ScrollView;
import limn.components.TextField;
import limn.components.Theme;
import limn.i18n.I18nString;
import limn.icons.tabler.Tabler;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The system's own file choosers, owned by this window ({@code --scene files}): a form whose
 * fields are filled by the open, open-several, save and folder dialogs ({@link FileDialogs}),
 * each answer written into the field it was asked for.
 */
final class FilesScene {

    /** Every string the page shows. */
    static final class Text {
        static final I18nString ORDER = new I18nString("showcase.files.order", "Order #1042");
        static final I18nString ATTACHMENT = new I18nString("showcase.files.attachment", "Attachment");
        static final I18nString PHOTOS = new I18nString("showcase.files.photos", "Delivery photos");
        static final I18nString EXPORT_AS = new I18nString("showcase.files.exportAs", "Export as");
        static final I18nString FOLDER = new I18nString("showcase.files.folder", "Backup folder");
        static final I18nString SKETCH = new I18nString("showcase.files.sketch", "Sketch");
        static final I18nString BROWSE = new I18nString("showcase.files.browse", "Browse…");
        static final I18nString ADD = new I18nString("showcase.files.add", "Add…");
        static final I18nString SAVE_AS = new I18nString("showcase.files.saveAs", "Save as…");
        static final I18nString CHOOSE = new I18nString("showcase.files.choose", "Choose…");
        static final I18nString NONE_CHOSEN = new I18nString("showcase.files.noneChosen", "No file chosen");
        static final I18nString NO_FOLDER = new I18nString("showcase.files.noFolder", "No folder chosen");

        static final I18nString OPEN_TITLE = new I18nString("showcase.files.openTitle", "Attach a document");
        static final I18nString PHOTOS_TITLE = new I18nString("showcase.files.photosTitle", "Add delivery photos");
        static final I18nString SAVE_TITLE = new I18nString("showcase.files.saveTitle", "Export the orders");
        static final I18nString FOLDER_TITLE = new I18nString("showcase.files.folderTitle", "Choose the backup folder");
        static final I18nString SKETCH_TITLE = new I18nString("showcase.files.sketchTitle", "Open a sketch");
        static final I18nString DOCUMENTS = new I18nString("showcase.files.documents", "Documents");
        static final I18nString PICTURES = new I18nString("showcase.files.pictures", "Pictures");
        static final I18nString SPREADSHEETS = new I18nString("showcase.files.spreadsheets", "Spreadsheets");
        static final I18nString SKETCHES = new I18nString("showcase.files.sketches", "Sketches");
        static final I18nString SUGGESTED_NAME = new I18nString("showcase.files.suggestedName", "Orders October 2026.csv");

        static final I18nString CANCELLED = new I18nString("showcase.files.cancelled", "Nothing chosen.");
        static final I18nString CHOSEN = new I18nString("showcase.files.chosen", "{0} chosen.");
        static final I18nString NO_WINDOW = new I18nString("showcase.files.noWindow",
                "File choosers need a window of their own.");
        static final I18nString NO_SAMPLES = new I18nString("showcase.files.noSamples",
                "The sample sketches could not be written.");

        private Text() {
        }
    }

    private FilesScene() {
    }

    /** Standalone {@code --scene files}. */
    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        return scene;
    }

    /** The page, scrolling on its own since the frame sets it beside rather than in a scroller. */
    static Widget<?> content() {
        Column page = Page.column();
        page.add(Page.firstSection(Text.ORDER));

        // Open one file: a document attached to the order.
        TextField attachment = pathField(Text.NONE_CHOSEN);
        Button browse = chooser(Text.BROWSE, "paperclip");
        browse.onAction(() -> dialogs(browse).ifPresent(d -> d.openFile(Text.OPEN_TITLE.get(), null,
                FileDialogs.Filter.of(Text.DOCUMENTS.get(), "*.pdf", "*.png", "*.jpg", "*.jpeg"))
                .thenAccept(picked -> show(attachment, picked))));

        // Open several: photos of the delivery.
        TextField photos = pathField(Text.NONE_CHOSEN);
        Button add = chooser(Text.ADD, "photo-up");
        add.onAction(() -> dialogs(add).ifPresent(d -> d.openFiles(Text.PHOTOS_TITLE.get(), null,
                FileDialogs.Filter.of(Text.PICTURES.get(), "*.png", "*.jpg", "*.jpeg", "*.heic"))
                .thenAccept(picked -> {
                    if (picked.isEmpty()) {
                        Page.report(Text.CANCELLED.get());
                        return;
                    }
                    photos.setText(picked.stream().map(FilesScene::nameOf).collect(Collectors.joining(", ")));
                    Page.report(Text.CHOSEN.format(photos.text()));
                })));

        // Save: a suggested name, and the kinds of spreadsheet the export writes.
        TextField export = pathField(Text.NONE_CHOSEN);
        Button saveAs = chooser(Text.SAVE_AS, "file-export");
        saveAs.onAction(() -> dialogs(saveAs).ifPresent(d -> d.saveFile(Text.SAVE_TITLE.get(),
                Path.of(Text.SUGGESTED_NAME.get()),
                FileDialogs.Filter.of(Text.SPREADSHEETS.get(), "*.csv", "*.xlsx", "*.ods"))
                .thenAccept(picked -> show(export, picked))));

        // A folder.
        TextField folder = pathField(Text.NO_FOLDER);
        Button choose = chooser(Text.CHOOSE, "folder-search");
        choose.onAction(() -> dialogs(choose).ifPresent(d -> d.chooseFolder(Text.FOLDER_TITLE.get(), null)
                .thenAccept(picked -> show(folder, picked))));

        // Opened on a folder of samples, filtered to a type no installed program registers.
        TextField sketch = pathField(Text.NONE_CHOSEN);
        Button open = chooser(Text.BROWSE, "file-search");
        open.onAction(() -> dialogs(open).ifPresent(d -> {
            Path samples = sampleSketchFolder();
            if (samples == null) {
                Page.report(Text.NO_SAMPLES.get());
                return;
            }
            d.openFile(Text.SKETCH_TITLE.get(), samples, FileDialogs.Filter.of(Text.SKETCHES.get(), "*.limn"))
                    .thenAccept(picked -> show(sketch, picked));
        }));

        Column form = new Column();
        form.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.STRETCH);
        form.add(formRow(Text.ATTACHMENT, attachment, browse));
        form.add(formRow(Text.PHOTOS, photos, add));
        form.add(formRow(Text.EXPORT_AS, export, saveAs));
        form.add(formRow(Text.FOLDER, folder, choose));
        form.add(formRow(Text.SKETCH, sketch, open));
        page.add(Page.narrow(620, form));
        return new ScrollView(new Padding(Insets.symmetric(12, 0), page));
    }

    /** A field the dialog answers into; the user may also type a path. */
    private static TextField pathField(I18nString placeholder) {
        return new TextField().setPlaceholder(placeholder);
    }

    /** The button beside a field that opens the chooser: secondary, there being several. */
    private static Button chooser(I18nString text, String icon) {
        Button button = new Button(text).setSecondary(true);
        button.setIcon(Tabler.outline(icon));
        return button;
    }

    /** "Caption: [field] Button…", the caption naming the field. */
    private static Widget<?> formRow(I18nString caption, TextField field, Button button) {
        Row row = new Row();
        row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(Expanded.of(field, 1));
        // One width for every button, so the fields end in one line.
        row.add(new SizedBox(140, SizedBox.UNSET, button));
        return Page.field(caption, field, row);
    }

    /** Writes the chosen path into its field, or says nothing was chosen. */
    private static void show(TextField field, Optional<Path> picked) {
        if (picked.isEmpty()) {
            Page.report(Text.CANCELLED.get());
            return;
        }
        field.setText(picked.get().toString());
        Page.report(Text.CHOSEN.format(nameOf(picked.get())));
    }

    private static String nameOf(Path path) {
        return path.getFileName() != null ? path.getFileName().toString() : path.toString();
    }

    /** The window's dialogs, or none (said in the status line) where there is no window. */
    private static Optional<FileDialogs> dialogs(Widget<?> from) {
        Scene scene = from.scene();
        if (scene == null || scene.window() == null) {
            Page.report(Text.NO_WINDOW.get());
            return Optional.empty();
        }
        return Optional.of(scene.window().fileDialogs());
    }

    /**
     * A folder of sample {@code .limn} files plus decoys, (re)written on demand under the system
     * temp dir so the sketch dialog opens somewhere the filter is visible: samples selectable,
     * decoys greyed out. Null if the folder cannot be written.
     */
    private static Path sampleSketchFolder() {
        try {
            // A fresh directory per run, not a fixed name under the shared temp dir: on a
            // multi-user machine a fixed name is anyone's to pre-create, with entries that are
            // symbolic links to files this user owns, and writeString follows them.
            if (sketchFolder == null) {
                sketchFolder = Files.createTempDirectory("limn-demo-sketches-");
                sketchFolder.toFile().deleteOnExit();
            }
            Path dir = sketchFolder;
            for (String name : List.of("floor plan.limn", "shelf layout.limn")) {
                Files.writeString(dir.resolve(name), "limn sample sketch\n");
            }
            Files.writeString(dir.resolve("not-a-sketch.txt"), "decoy\n");
            Files.writeString(dir.resolve("also-not.dat"), "decoy\n");
            return dir;
        } catch (IOException e) {
            return null;
        }
    }

    /** The sample folder of this run, once made; see {@link #sampleSketchFolder}. */
    private static Path sketchFolder;
}
