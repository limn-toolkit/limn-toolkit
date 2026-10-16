package limn.demo;

import limn.i18n.I18n;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A page of the Showcase built on its own, as {@code --scene controls} builds it, reads in the
 * language asked for: its strings are in the demo's family of files, which was added only when
 * something touched {@link ShowcaseStrings}, and a page alone touches nothing of it.
 *
 * <p>In a JVM of its own, because the family is added once per process and every other test of
 * this module may already have added it.
 */
class PageAloneLanguageTest {

    @TempDir
    Path directory;

    @Test
    void aPageBuiltAloneReadsInTheLanguageAskedFor() throws Exception {
        Path log = directory.resolve("child.log");
        Process child = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName()))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = child.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly().waitFor();
        }
        String output = Files.readString(log);
        assertTrue(finished, "the child never finished:\n" + output);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("first section: Ligações"), output);
    }

    /** Builds the sliders page in Brazilian Portuguese, touching nothing else of the demo. */
    static final class Child {

        public static void main(String[] args) {
            try (HeadlessUi ui = new HeadlessUi()) {
                I18n.setLocale(Locale.forLanguageTag("pt-BR"));
                ControlsScene.content();
                System.out.println("first section: " + ControlsScene.Text.CALLS.get());
            }
        }
    }
}
