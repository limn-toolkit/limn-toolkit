package limn.backend.lwjgl;

import limn.backend.FileDialogs;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * A window's file and folder choosers: one at a time for the window, each answer delivered on the
 * UI thread after the event that asked, never inside it.
 *
 * <p>Where the platform's own chooser is not written yet, the blocking one ({@link TinyFdDialogs})
 * runs from a posted task, so the handler that asked has returned and drawn its frame first.
 */
final class WindowFileDialogs implements FileDialogs {

    private final LwjglWindow window;
    private final LwjglBackend backend;
    /** Whether a chooser is open on this window, which makes the next request answer empty. */
    private boolean open;

    WindowFileDialogs(LwjglWindow window, LwjglBackend backend) {
        this.window = window;
        this.backend = backend;
    }

    @Override
    public CompletionStage<Optional<Path>> openFile(String title, @Nullable Path initial,
                                                    @Nullable Filter filter) {
        if (MACOS) {
            return first(nativePanel(MacFileDialogs.Kind.OPEN, title, initial, filter));
        }
        return ask(Optional.empty(), () -> backend.tinyFd().openFile(title, initial, filter));
    }

    @Override
    public CompletionStage<List<Path>> openFiles(String title, @Nullable Path initial,
                                                 @Nullable Filter filter) {
        if (MACOS) {
            return nativePanel(MacFileDialogs.Kind.OPEN_MANY, title, initial, filter);
        }
        return ask(List.of(), () -> backend.tinyFd().openFiles(title, initial, filter));
    }

    @Override
    public CompletionStage<Optional<Path>> saveFile(String title, @Nullable Path initial,
                                                    @Nullable Filter filter) {
        if (MACOS) {
            return first(nativePanel(MacFileDialogs.Kind.SAVE, title, initial, filter));
        }
        return ask(Optional.empty(), () -> backend.tinyFd().saveFile(title, initial, filter));
    }

    @Override
    public CompletionStage<Optional<Path>> chooseFolder(String title, @Nullable Path initial) {
        if (MACOS) {
            return first(nativePanel(MacFileDialogs.Kind.FOLDER, title, initial, null));
        }
        return ask(Optional.empty(), () -> backend.tinyFd().chooseFolder(title, initial));
    }

    private static final boolean MACOS = limn.backend.Platform.current().isMacOs();

    private static CompletionStage<Optional<Path>> first(CompletionStage<List<Path>> paths) {
        return paths.thenApply(list -> list.isEmpty() ? Optional.<Path>empty() : Optional.of(list.get(0)));
    }

    /** macOS's own panel, a sheet on this window; the application keeps running while it is up. */
    private CompletionStage<List<Path>> nativePanel(MacFileDialogs.Kind kind, String title,
                                                    @Nullable Path initial, @Nullable Filter filter) {
        backend.uiRuntime().checkUiThread();
        CompletableFuture<List<Path>> answer = new CompletableFuture<>();
        if (open || window.isClosed()) {
            answer.complete(List.of());
            return answer;
        }
        open = true;
        boolean opened = MacFileDialogs.open(window.cocoaWindow(), window.isVisible(), kind, title, initial,
                filter, chosen -> {
                    open = false;
                    answer.complete(chosen);
                });
        if (!opened) {
            open = false;
            answer.complete(List.of());
        }
        return answer;
    }

    private <T> CompletionStage<T> ask(T cancelled, Supplier<T> blocking) {
        backend.uiRuntime().checkUiThread();
        CompletableFuture<T> answer = new CompletableFuture<>();
        if (open || window.isClosed()) {
            answer.complete(cancelled);
            return answer;
        }
        open = true;
        backend.uiRuntime().post(() -> {
            T chosen = cancelled;
            try {
                if (!window.isClosed()) {
                    chosen = blocking.get();
                }
            } finally {
                open = false;
                answer.complete(chosen);
            }
        });
        return answer;
    }
}
