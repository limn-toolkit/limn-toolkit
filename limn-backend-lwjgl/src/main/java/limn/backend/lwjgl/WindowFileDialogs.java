package limn.backend.lwjgl;

import limn.backend.FileDialogs;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A window's file and folder choosers: one at a time for the window, each answer delivered on the
 * UI thread after the event that asked, never inside it.
 *
 * <p>Where the platform's own chooser is not written yet, the blocking one ({@link TinyFdDialogs})
 * runs from a posted task, so the handler that asked has returned and drawn its frame first.
 */
final class WindowFileDialogs implements FileDialogs {

    /** What is asked for. */
    enum Kind { OPEN, OPEN_MANY, SAVE, FOLDER }

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
        if (NATIVE) {
            return first(nativePanel(Kind.OPEN, title, initial, filter));
        }
        return ask(Optional.empty(), () -> backend.tinyFd().openFile(title, initial, filter));
    }

    @Override
    public CompletionStage<List<Path>> openFiles(String title, @Nullable Path initial,
                                                 @Nullable Filter filter) {
        if (NATIVE) {
            return nativePanel(Kind.OPEN_MANY, title, initial, filter);
        }
        return ask(List.of(), () -> backend.tinyFd().openFiles(title, initial, filter));
    }

    @Override
    public CompletionStage<Optional<Path>> saveFile(String title, @Nullable Path initial,
                                                    @Nullable Filter filter) {
        if (NATIVE) {
            return first(nativePanel(Kind.SAVE, title, initial, filter));
        }
        return ask(Optional.empty(), () -> backend.tinyFd().saveFile(title, initial, filter));
    }

    @Override
    public CompletionStage<Optional<Path>> chooseFolder(String title, @Nullable Path initial) {
        if (NATIVE) {
            return first(nativePanel(Kind.FOLDER, title, initial, null));
        }
        return ask(Optional.empty(), () -> backend.tinyFd().chooseFolder(title, initial));
    }

    private static final boolean MACOS = limn.backend.Platform.current().isMacOs();
    private static final boolean WINDOWS = limn.backend.Platform.current().isWindows();
    private static final boolean LINUX = limn.backend.Platform.current().isLinux();
    /** Whether the platform's own chooser is written here; tinyfd answers where it is not. */
    private static final boolean NATIVE = MACOS || WINDOWS || LINUX;

    private static CompletionStage<Optional<Path>> first(CompletionStage<List<Path>> paths) {
        return paths.thenApply(list -> list.isEmpty() ? Optional.<Path>empty() : Optional.of(list.get(0)));
    }

    /**
     * The platform's own chooser, owned by this window: a sheet on macOS, a dialog on a thread of
     * its own on Windows, the desktop portal's chooser on Linux. The application keeps running while
     * it is up.
     */
    private CompletionStage<List<Path>> nativePanel(Kind kind, String title,
                                                    @Nullable Path initial, @Nullable Filter filter) {
        backend.uiRuntime().checkUiThread();
        CompletableFuture<List<Path>> answer = new CompletableFuture<>();
        if (open || window.isClosed()) {
            answer.complete(List.of());
            return answer;
        }
        open = true;
        Consumer<List<Path>> done = chosen -> {
            open = false;
            answer.complete(chosen);
        };
        boolean opened = true;
        if (MACOS) {
            opened = MacFileDialogs.open(window::cocoaWindow, window.isVisible(), kind, title, initial,
                    filter, done);
        } else if (WINDOWS) {
            WindowsFileDialogs.open(window.nativeHandle(), kind, title, initial, filter, done);
        } else {
            portal(kind, title, initial, filter, done);
        }
        if (!opened) {
            open = false;
            answer.complete(List.of());
        }
        return answer;
    }

    /**
     * The desktop portal's chooser, asked on a thread of its own and parented to this window where
     * the portal can name it: {@code x11:} and the window's id. A Wayland window has no name GLFW
     * can export, so the chooser opens unparented there. Where no portal answers, as on a desktop
     * that runs none, the blocking tinyfd chooser answers instead, so "Open…" still opens something.
     */
    private void portal(Kind kind, String title, @Nullable Path initial, @Nullable Filter filter,
                        Consumer<List<Path>> done) {
        String address = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        String parent = window.portalParent();
        boolean save = kind == Kind.SAVE;
        boolean multiple = kind == Kind.OPEN_MANY;
        boolean directory = kind == Kind.FOLDER;
        String filterName = filter == null ? null : filter.description();
        List<String> patterns = filter == null ? List.of() : filter.patterns();
        limn.concurrent.internal.Threads.daemon("limn-file-dialog", () -> {
            List<Path> chosen;
            try {
                if (!limn.backend.lwjgl.a11y.linux.PortalFileChooser.canAsk(address)) {
                    throw new java.io.IOException("no session bus");
                }
                chosen = limn.backend.lwjgl.a11y.linux.PortalFileChooser.ask(address, parent, save, multiple,
                        directory, title, initial, filterName, patterns);
            } catch (java.io.IOException | RuntimeException unreachable) {
                System.getLogger(WindowFileDialogs.class.getName()).log(System.Logger.Level.INFO,
                        "the file chooser of the desktop portal is unavailable; using the fallback",
                        unreachable);
                limn.concurrent.Ui.post(() -> done.accept(fallback(kind, title, initial, filter)));
                return;
            }
            List<Path> result = chosen;
            limn.concurrent.Ui.post(() -> done.accept(result));
        });
    }

    private List<Path> fallback(Kind kind, String title, @Nullable Path initial, @Nullable Filter filter) {
        if (window.isClosed()) {
            return List.of();
        }
        return switch (kind) {
            case OPEN -> backend.tinyFd().openFile(title, initial, filter).map(List::of).orElse(List.of());
            case OPEN_MANY -> backend.tinyFd().openFiles(title, initial, filter);
            case SAVE -> backend.tinyFd().saveFile(title, initial, filter).map(List::of).orElse(List.of());
            case FOLDER -> backend.tinyFd().chooseFolder(title, initial).map(List::of).orElse(List.of());
        };
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
