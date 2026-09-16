package limn.demo.a11y;

import limn.components.DisplayMode;
import limn.demo.a11y.AccessibilityGallery.Step;
import limn.input.Keys;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader driver's command line, which the guest recipes are written against, held without a
 * window (the run itself needs a desktop; its steps are {@code ReaderStepsTest}'s).
 */
class ReaderDriverTest {

    @Test
    void aRunSpeaksBrazilianPortugueseUnlessToldOtherwise() {
        ReaderDriver.Options options = ReaderDriver.Options.parse(new String[]{"--reader", "table"});
        assertEquals(Locale.forLanguageTag("pt-BR"), options.locale(),
                "the guests' reader language (decision 65)");
        assertNull(options.presentation(), "the entry's own presentation stands");
        assertEquals(0, options.exitAfterMillis());
        assertEquals(Locale.forLanguageTag("en"), ReaderDriver.Options.parse(
                new String[]{"--reader", "table", "--locale", "en"}).locale());
    }

    @Test
    void thePresentationIsAskedByName() {
        assertEquals(DisplayMode.IN_SCENE, ReaderDriver.Options.parse(new String[]{
                "--presentation", "in-scene", "--reader", "date-picker"}).presentation());
        assertEquals(DisplayMode.NATIVE_WINDOW, ReaderDriver.Options.parse(new String[]{
                "--reader", "date-picker", "--presentation", "native"}).presentation());
        assertThrows(IllegalArgumentException.class, () -> ReaderDriver.Options.parse(
                new String[]{"--reader", "date-picker", "--presentation", "popup"}));
    }

    @Test
    void anUnknownScriptOrOptionIsRefusedWithTheScriptsThereAre() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> ReaderDriver.Options.parse(new String[]{"--reader", "tree-reader"}));
        assertTrue(unknown.getMessage().contains("tree-loading"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ReaderDriver.Options.parse(
                new String[]{"--reader", "table", "--scene", "table"}));
        assertThrows(IllegalArgumentException.class, () -> ReaderDriver.Options.parse(
                new String[]{"--exit-after", "100"}));
    }

    /** The spelling the 2026-09-13 recipes launch is the tree run, every other option kept. */
    @Test
    void theTreeReaderSceneIsTheTreeLoadingRun() {
        assertArrayEquals(new String[]{"--reader", "tree-loading", "--exit-after", "62000"},
                ReaderDriver.readerArguments(new String[]{"--scene", "tree-reader",
                        "--exit-after", "62000"}));
        assertArrayEquals(new String[]{"--reader", "calendar"},
                ReaderDriver.readerArguments(new String[]{"--reader", "calendar"}));
        assertNull(ReaderDriver.readerArguments(new String[]{"--scene", "tree"}));
        assertNull(ReaderDriver.readerArguments(new String[]{}));
    }

    /** A recipe waits for "--- step N " and labels its snapshot with what the step does. */
    @Test
    void aStepLineStartsTheWayTheRecipesWaitForIt() {
        Step step = AccessibilityGallery.readerEntry("tree-loading").reader().steps().get(13);
        String line = ReaderDriver.stepLine(14, step, null);
        assertTrue(line.startsWith("--- step 14 RIGHT - "), line);
        assertTrue(line.endsWith(" focus=none"), line);
        assertEquals("SHIFT+TAB", Step.chord(Keys.TAB, Keys.MOD_SHIFT, "x").keys());
        assertEquals("CMD+A", Step.chord(Keys.A, Step.COMMAND, "x").keys());
        assertEquals("'5'", Step.type('5', "x").keys());
    }
}
