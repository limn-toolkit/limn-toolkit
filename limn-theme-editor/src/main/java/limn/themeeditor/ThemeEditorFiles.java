package limn.themeeditor;

import limn.backend.FileDialogs;
import limn.components.Button;
import limn.components.Theme;
import limn.components.ThemeFormat;
import limn.components.TokenRow;
import limn.components.Tokens;
import limn.concurrent.Ui;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Flex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Open and Save As for a {@link ThemeEditor}, through the platform's own chooser.
 *
 * <p>Separate from the editor, and optional, because <b>where a palette lives is the
 * application's decision</b>: a file is one answer, but a preferences store, a document
 * bundle and a server are all as likely, and a widget that assumed the first would have to
 * be worked around by everyone who meant one of the others. The editor itself moves a
 * palette through the clipboard and needs nothing from the platform at all.
 *
 * <p><b>Two different things block here, and only one of them is avoidable.</b> The chooser
 * is a system-modal panel (on macOS and Linux it is not even drawn by this process), so
 * the application stops while it is up, and {@link FileDialogs} explains at length why no
 * asynchronous form of that would be honest. Reading and writing the bytes is a different
 * matter: that is disk work with no user in it, so it goes through {@link Ui#work} and
 * lands back on the UI thread, which is what this repository asks of anything that touches
 * a file.
 *
 * <p>Every outcome, including every failure, is reported on the editor's own status line.
 * Nothing here throws at the caller: a chooser the user cancelled, a file that turned out
 * not to be a palette and a disk that was full are all ordinary, and none of them is a bug
 * in the application that opened the editor.
 */
public final class ThemeEditorFiles {

    private ThemeEditorFiles() {
    }

    /** A row of the two buttons, for an application that wants them in its own toolbar. */
    public static Widget buttons(ThemeEditor editor) {
        Objects.requireNonNull(editor, "editor");
        TokenRow row = new TokenRow(Tokens.Role.SMALL);
        row.crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(new Button(ThemeEditorStrings.OPEN).setSecondary(true)
                .onAction(() -> open(editor)));
        row.add(new Button(ThemeEditorStrings.SAVE).setSecondary(true)
                .onAction(() -> saveAs(editor)));
        return row;
    }

    /**
     * Asks for a palette file and loads it into {@code editor}. The chooser blocks; the
     * read does not. A cancelled chooser does nothing at all, silently, which is what
     * cancelling means.
     */
    public static void open(ThemeEditor editor) {
        Ui.checkUiThread();
        Objects.requireNonNull(editor, "editor");
        FileDialogs dialogs = dialogsFor(editor);
        if (dialogs == null) {
            return;
        }
        Optional<Path> chosen = dialogs.openFile(ThemeEditorStrings.OPEN.get(), null, filter());
        if (chosen.isEmpty()) {
            return;
        }
        Path path = chosen.get();
        Ui.<Theme>work(progress -> ThemeFormat.load(path))
                .deliverIf(editor::isShowing)
                .onSuccess(theme -> {
                    editor.load(theme);
                    editor.setStatus("");
                })
                // One handler for both halves on purpose: from the user's side "this file is
                // not a palette" and "this file could not be read" are the same sentence with
                // a different reason, and the reason is what the message carries.
                .onFailure(failure -> editor.setStatus(
                        ThemeEditorStrings.FILE_FAILED.format(reasonOf(failure))))
                .start();
    }

    /**
     * Asks where to put the palette and writes it there. The text is produced <em>here</em>,
     * on the UI thread, before any of it goes to a worker: it is read out of live widget
     * state, and a worker that read the editor while the user kept typing would be reading
     * widget state off the UI thread, which throws, and rightly.
     */
    public static void saveAs(ThemeEditor editor) {
        Ui.checkUiThread();
        Objects.requireNonNull(editor, "editor");
        FileDialogs dialogs = dialogsFor(editor);
        if (dialogs == null) {
            return;
        }
        Theme theme = editor.theme();
        Optional<Path> chosen = dialogs.saveFile(ThemeEditorStrings.SAVE.get(),
                Path.of(fileNameFor(theme)), filter());
        if (chosen.isEmpty()) {
            return;
        }
        String text = ThemeFormat.write(theme);
        Path path = chosen.get();
        Ui.<Path>work(progress -> {
            Files.writeString(path, text, StandardCharsets.UTF_8);
            return path;
        })
                .deliverIf(editor::isShowing)
                .onSuccess(written -> editor.setStatus(
                        ThemeEditorStrings.SAVED.format(written.getFileName().toString())))
                .onFailure(failure -> editor.setStatus(
                        ThemeEditorStrings.FILE_FAILED.format(reasonOf(failure))))
                .start();
    }

    /** {@code "Ocean Deep"} → {@code "ocean-deep.limntheme"}, a name a file system accepts. */
    static String fileNameFor(Theme theme) {
        String slug = theme.name.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return (slug.isEmpty() ? "palette" : slug) + '.' + ThemeFormat.EXTENSION;
    }

    private static FileDialogs.Filter filter() {
        return FileDialogs.Filter.of(ThemeEditorStrings.FILE_KIND.get(),
                "*." + ThemeFormat.EXTENSION);
    }

    /** @return the platform's chooser, or null after saying on the status line why not */
    private static FileDialogs dialogsFor(ThemeEditor editor) {
        Scene scene = editor.scene();
        if (scene == null || scene.window() == null) {
            editor.setStatus(ThemeEditorStrings.NO_FILE_DIALOGS);
            return null;
        }
        return scene.window().backend().fileDialogs();
    }

    /** The sentence a user should see, not the stack the exception came wrapped in. */
    private static String reasonOf(Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        String message = cause.getMessage();
        return message != null && !message.isBlank() ? message : cause.toString();
    }
}
