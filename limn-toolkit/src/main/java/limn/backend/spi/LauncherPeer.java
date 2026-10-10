package limn.backend.spi;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * What a backend does for {@link limn.backend.Launcher}: hand an address or a file to the system
 * its window is on.
 *
 * <p>A backend receives only what {@code Launcher} let through: an absolute address whose scheme
 * the application allows, and an absolute path that existed when it was asked about. It owes
 * three things in return. Each method <b>returns at once</b>: the systems' own calls can wait
 * seconds on the program they start, so they are made off the UI thread. Each stage <b>completes
 * on the UI thread</b>, after the event that asked and never inside it. And the answer is
 * {@code true} when the system took what it was handed and {@code false} when it did not, with
 * the reason logged, since nothing else says it; a method here does not throw for a refusal.
 */
public interface LauncherPeer {

    /**
     * @param address an absolute address of an allowed scheme
     * @return whether the system took it
     */
    CompletionStage<Boolean> openAddress(URI address);

    /**
     * @param file an absolute path that exists
     * @return whether the system took it
     */
    CompletionStage<Boolean> openFile(Path file);

    /**
     * @param file an absolute path that exists
     * @return whether the file manager was asked to show it; {@code true} as well where the
     *         system can open the folder but not select the file in it
     */
    CompletionStage<Boolean> showInFolder(Path file);

    /** The peer of a window with no desktop under it: nothing is handed over. */
    LauncherPeer NONE = new LauncherPeer() {
        @Override
        public CompletionStage<Boolean> openAddress(URI address) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletionStage<Boolean> openFile(Path file) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletionStage<Boolean> showInFolder(Path file) {
            return CompletableFuture.completedFuture(false);
        }
    };
}
