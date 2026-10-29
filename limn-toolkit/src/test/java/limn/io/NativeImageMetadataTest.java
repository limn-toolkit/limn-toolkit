package limn.io;

import limn.testfixtures.NativeImageMetadata;
import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What limn-toolkit's jar tells GraalVM's native image it needs. */
class NativeImageMetadataTest {

    private static final Path MODULE = RepositoryRoot.find().resolve("limn-toolkit");

    /** Every file the toolkit ships is in an image built from it. */
    @Test
    void everyResourceIsNamed() {
        assertEquals(Set.of(), NativeImageMetadata.uncoveredResources(MODULE, "limn-toolkit", Set.of()),
                "resources a native image would leave out");
    }

    /** The classes and the field it names for reflection exist under those names. */
    @Test
    void theReflectionItNamesExists() {
        assertDoesNotThrow(() -> Class.forName("limn.accessibility.Accessibility"));
        assertDoesNotThrow(() -> Class.forName("limn.scene.Scene"));
        assertDoesNotThrow(() -> limn.video.VideoFrame.class.getDeclaredField("generation"));
    }

    /** The glob reading the test relies on: one level for a star, any number for two. */
    @Test
    void globsAreReadAsTheImageReadsThem() {
        assertTrue(NativeImageMetadata.matches("limn/i18n/*.properties", "limn/i18n/components.properties"));
        assertFalse(NativeImageMetadata.matches("limn/i18n/*.properties", "limn/i18n/internal/x.properties"));
        assertTrue(NativeImageMetadata.matches("limn/**/*.txt", "limn/i18n/internal/x.txt"));
        assertFalse(NativeImageMetadata.matches("limn/i18n/*.txt", "limn/i18n/internal/x.txt"));
    }
}
