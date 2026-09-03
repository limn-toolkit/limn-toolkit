package limn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository's NOTICE, checked against the tree it describes.
 *
 * <p>NOTICE is the file a redistributor reads to learn where each licence text is, and it names
 * those places as paths. Three times over, a module moved out of this repository (ADRs 036, 037
 * and 038) and the paths it named stayed behind: four font licence files that no longer existed,
 * two scripts that had gone with the FFmpeg build. Nothing failed, because nothing read the file.
 * This does: every repository-relative path NOTICE names has to exist, so the next move breaks a
 * test rather than a promise. URLs and paths inside a dependency's jar are somebody else's tree
 * and are left alone.
 */
class RepositoryNoticeTest {

    /**
     * A path counts as repository-relative when its first segment is an entry at the root:
     * {@code scripts/fetch-fonts.sh} is checked, {@code limn/fonts/Roboto-LICENSE.txt} (inside a
     * jar) is not, and neither is anything with a scheme or a placeholder in angle brackets.
     */
    @Test
    void everyPathTheNoticeNamesExists() throws IOException {
        Path root = repositoryRoot();
        String notice = Files.readString(root.resolve("NOTICE"), StandardCharsets.UTF_8);

        Set<String> named = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        for (String token : notice.split("[\\s,;()]+")) {
            String candidate = token.replaceAll("[.:]+$", "");
            if (!candidate.contains("/") || candidate.contains("://") || candidate.contains("<")) {
                continue;
            }
            String first = candidate.substring(0, candidate.indexOf('/'));
            if (first.isEmpty() || !Files.exists(root.resolve(first))) {
                continue;
            }
            named.add(candidate);
            if (!Files.exists(root.resolve(candidate))) {
                missing.add(candidate);
            }
        }

        // The two files this repository itself is responsible for, so an extraction bug that
        // finds nothing cannot pass as a NOTICE that names nothing.
        assertTrue(named.contains("media/LICENSE-CC-BY-3.0.txt"), "found: " + named);
        assertTrue(named.stream().anyMatch(path -> path.endsWith("LimnMenuSymbols-LICENSE.txt")),
                "the one font this repository ships must be named with its licence: " + named);
        assertTrue(Files.exists(root.resolve("LICENSE")), "NOTICE points at LICENSE");
        assertFalse(notice.contains("build-ffmpeg.sh") || notice.contains("fetch-ffmpeg.sh"),
                "the FFmpeg scripts left with ADR 037; NOTICE must not send a reader to them");

        assertTrue(missing.isEmpty(), "NOTICE names paths that do not exist: " + missing);
    }

    /** The tests run with the module as the working directory; the root is one level up. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.exists(directory.resolve("NOTICE"))
                    && Files.exists(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
    }
}
