package limn.backend;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The platform's own file and folder choosers, owned by a window: {@link NativeWindow#fileDialogs()}.
 * Each call opens the chooser tied to that window (a sheet on macOS, a dialog owned by it on
 * Windows, the desktop portal's chooser parented to it on Linux) and returns at once; the answer
 * completes the returned stage on the UI thread when the user picks or cancels. The application
 * keeps running meanwhile: its windows draw, animations advance, timers fire. UI thread only.
 *
 * <p>An empty answer means cancelled, and is also what a headless or embedded backend answers
 * ({@link #NONE}), so a caller needs no headless branch.
 *
 * <p><b>One at a time per window.</b> A window shows one chooser, as the platforms do; a request
 * made while one is open on the same window completes empty at once.
 *
 * <p>These are the platform's own panels, so how they look, where they appear, and which shortcuts
 * and sidebar places they offer are the platform's to decide, not the toolkit theme's, and not this
 * API's.
 */
public interface FileDialogs {

    /**
     * A file-name filter: a human-readable description plus glob patterns,
     * e.g. {@code Filter.of("Images", "*.png", "*.jpg")}. Dialogs may show the
     * description and restrict the listing to the patterns. Open dialogs
     * honour the patterns on macOS too, including {@code *.ext} filters for
     * extensions no installed application registers (an application's own
     * document type): the macOS panel matches by uniform type identifier, not
     * filename, and the backend translates such patterns to the system's
     * identifier. The macOS <em>save</em> panel offers no name filtering: a
     * filter passed to {@link #saveFile} is ignored there; the other platforms
     * filter save dialogs by name as usual.
     */
    record Filter(String description, List<String> patterns) {
        public Filter {
            Objects.requireNonNull(description, "description");
            patterns = List.copyOf(patterns);
        }

        /** A filter shown as {@code description}, matching glob patterns like {@code "*.png"}. */
    public static Filter of(String description, String... patterns) {
            return new Filter(description, List.of(patterns));
        }
    }

    /**
     * Asks for one existing file to open.
     *
     * @param title   dialog title
     * @param initial initial path (a directory, or a file to preselect); {@code null} for the
     *                platform's default
     * @param filter  name filter, or {@code null} for all files
     * @return the chosen file, or empty if cancelled, on the UI thread
     */
    CompletionStage<Optional<Path>> openFile(String title, @Nullable Path initial, @Nullable Filter filter);

    /**
     * Like {@link #openFile} allowing several files.
     *
     * @return the chosen files, or an empty list if cancelled, on the UI thread
     */
    CompletionStage<List<Path>> openFiles(String title, @Nullable Path initial, @Nullable Filter filter);

    /**
     * Asks for a destination file to save to; the platform's chooser asks about overwriting.
     *
     * @param initial a directory, or a file name or path to suggest; {@code null} for the default
     * @return the chosen destination, or empty if cancelled, on the UI thread
     */
    CompletionStage<Optional<Path>> saveFile(String title, @Nullable Path initial, @Nullable Filter filter);

    /**
     * Asks for an existing folder.
     *
     * @return the chosen folder, or empty if cancelled, on the UI thread
     */
    CompletionStage<Optional<Path>> chooseFolder(String title, @Nullable Path initial);

    /** Headless/embedded implementation: every dialog resolves empty at once, as if cancelled. */
    FileDialogs NONE = new FileDialogs() {
        @Override
        public CompletionStage<Optional<Path>> openFile(String title, @Nullable Path initial,
                                                        @Nullable Filter filter) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<List<Path>> openFiles(String title, @Nullable Path initial,
                                                     @Nullable Filter filter) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<Optional<Path>> saveFile(String title, @Nullable Path initial,
                                                        @Nullable Filter filter) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<Optional<Path>> chooseFolder(String title, @Nullable Path initial) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    };
}
