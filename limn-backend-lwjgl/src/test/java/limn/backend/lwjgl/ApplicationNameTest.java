package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the desktop calls a Limn application (decision 56): the name the application gave its
 * backend, otherwise the title of the first window the backend created.
 *
 * <p>The rule matters on Linux, where every window of the process is a frame of one AT-SPI
 * application object: before 2026-09-15 each window named its own application after its own title,
 * so a native popup — titled "popup" by {@code WindowConfig.popup} — was an application of that name.
 */
class ApplicationNameTest {

    @Test
    void aGivenNameWinsOverEveryWindowsTitle() {
        assertEquals("Kitchen Sink", LwjglBackend.applicationNameOf("Kitchen Sink", "Splash"));
    }

    @Test
    void unsetItIsTheFirstWindowsTitleAndNeverALaterOne() {
        assertEquals("Limn UI: Kitchen Sink",
                LwjglBackend.applicationNameOf(null, "Limn UI: Kitchen Sink"),
                "the default is the first window's title, recorded before any popup opens");
    }

    @Test
    void beforeAnyWindowItIsEmptyRatherThanNull() {
        assertEquals("", LwjglBackend.applicationNameOf(null, null));
    }
}
