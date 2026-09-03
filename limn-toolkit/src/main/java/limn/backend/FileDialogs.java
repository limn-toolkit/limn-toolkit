package limn.backend;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Native system file/folder dialogs, obtained via {@link Backend#fileDialogs()}.
 * Each call opens the <em>platform's</em> chooser and blocks the UI thread until
 * the user picks or cancels (system-modal, like every native app). UI thread
 * only. Headless/embedded backends return {@link #NONE}, whose dialogs resolve
 * empty; callers must treat "empty" as "cancelled" and need no headless branch.
 *
 * <p><b>The application stops while the panel is up, and the first call is
 * dearer than the rest.</b> On macOS and Linux the chooser is not drawn by this
 * process at all: a command goes to a helper program ({@code osascript} on
 * macOS, zenity or kdialog on Linux) and one line of its output is the answer.
 * So while the panel is open, this process runs no frames: its windows keep
 * showing the frame presented just before the call, animations do not advance,
 * and no timer or posted task runs until the user is done. That last frame is
 * drawn immediately before blocking, so what stays on screen reflects whatever
 * the calling handler already changed: a button that renders released, not one
 * frozen mid-press. Starting the helper costs tens of milliseconds on every
 * call, and the first call in a process pays a further one-off setup (on the
 * order of a quarter of a second) before any panel appears. A caller that
 * cannot afford that pause on a click, such as a game loop or a video that must
 * keep playing, should not open a file dialog from one. On Windows the chooser
 * runs inside this process instead, on the calling thread, so its own message
 * loop can still service window repaints there; the thread is unavailable to
 * the toolkit either way.
 *
 * <p><b>No asynchronous form, and none would be right.</b> A system-modal
 * panel's whole contract is that the application is unusable while it is up, so
 * a chooser opened from a worker would leave this process drawing frames and
 * taking clicks behind a panel the platform believes is blocking them. What is
 * being waited for is the user, not a disk, so there is no work here to move
 * off the thread, only a pause to keep off a click that cannot afford one.
 *
 * <p><b>One at a time.</b> The chooser is not reentrant, and a second one opened
 * before the first returns is undefined. This is reachable only through code
 * that runs while the call is on the stack, which is why the Windows difference
 * above is worth knowing.
 *
 * <p>These are the platform's own panels, so how they look, where they appear,
 * and which shortcuts and sidebar places they offer are the platform's to
 * decide, not the toolkit theme's, and not this API's.
 *
 * <p><b>Title and initial path are bounded on macOS and Linux.</b> There the
 * helper's command line has a fixed size, so a title and an initial path that
 * would not fit it together are shortened before the call: the path is dropped
 * in favour of the panel's own default folder first, and only a title too long
 * on its own is cut. A few hundred bytes between them always fit.
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
     * @param initial initial path (a directory, or a file to preselect); null for the platform default
     * @param filter  name filter, or null for all files
     * @return the chosen file, or empty if cancelled
     */
    Optional<Path> openFile(String title, Path initial, Filter filter);

    /** Like {@link #openFile} allowing multiple selection; empty list if cancelled. */
    List<Path> openFiles(String title, Path initial, Filter filter);

    /**
     * Asks for a destination file to save to (the platform dialog handles the
     * overwrite confirmation).
     *
     * @return the chosen destination, or empty if cancelled
     */
    Optional<Path> saveFile(String title, Path initial, Filter filter);

    /** Asks for an existing directory. @return the chosen folder, or empty if cancelled */
    Optional<Path> chooseFolder(String title, Path initial);

    /** Headless/embedded implementation: every dialog resolves empty (as if cancelled). */
    FileDialogs NONE = new FileDialogs() {
        @Override
        public Optional<Path> openFile(String title, Path initial, Filter filter) {
            return Optional.empty();
        }

        @Override
        public List<Path> openFiles(String title, Path initial, Filter filter) {
            return List.of();
        }

        @Override
        public Optional<Path> saveFile(String title, Path initial, Filter filter) {
            return Optional.empty();
        }

        @Override
        public Optional<Path> chooseFolder(String title, Path initial) {
            return Optional.empty();
        }
    };
}
