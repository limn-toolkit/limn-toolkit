package limn.testing;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the checkout is, for a test that reads a file of the repository's rather than of its
 * module: the NOTICE, a design record, a guide page.
 */
public final class RepositoryRoot {

    private RepositoryRoot() {
    }

    /**
     * Walks up from the working directory to the directory holding both {@code NOTICE} and
     * {@code settings.gradle.kts}, which only the root of this repository does.
     *
     * @return the repository root
     * @throws IllegalStateException if the working directory is not inside the checkout
     */
    public static Path find() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.exists(directory.resolve("NOTICE"))
                    && Files.exists(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("not running inside the limn-toolkit checkout: "
                + Path.of("").toAbsolutePath());
    }
}
