package limn.backend.lwjgl;

import limn.backend.Resolution;
import limn.backend.ScreenRect;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A display whose monitor was unplugged. GLFW frees the monitor then, and a display an application
 * kept (the one a window sat on, the one a dialog was centred against) still holds the handle.
 *
 * <p>What no test here can do is unplug a monitor: the platform's own disconnect, and GLFW
 * dropping the monitor from its list on it, is exercised only by hand. What is tested is what the
 * display does once GLFW no longer lists its handle, which is the only thing this class decides.
 */
class LwjglDisplayTest {

    private HeadlessUi ui;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void uninstallRuntime() {
        ui.close();
    }

    /**
     * NULL is the handle here because LWJGL refuses it before calling GLFW, with an exception
     * where a freed monitor would be a read of whatever memory is there now: a display that asked
     * GLFW anything fails this test instead of crashing the suite.
     */
    @Test
    void aMonitorGlfwNoLongerListsIsNeverAskedAnything() {
        LwjglDisplay gone = new LwjglDisplay(0L, 1, ui.runtime(), monitor -> false);

        assertEquals("display-1", gone.id());
        assertFalse(gone.name().isBlank(), "named by its position, as a nameless monitor is");
        assertFalse(gone.isPrimary(), "a display that is gone is nobody's primary");
        assertEquals(new Resolution(1, 1, 0), gone.currentResolution());
        assertEquals(List.of(), gone.availableResolutions());
        assertEquals(new ScreenRect(0, 0, 0, 0), gone.bounds());
        assertEquals(new ScreenRect(0, 0, 0, 0), gone.workArea());
        assertEquals(1f, gone.contentScale());
    }

    @Test
    void anUnpluggedMonitorAnswersWhatItLastRead() {
        HeadlessGl.assumeAvailable(); // a real GLFW, and so a real monitor to read
        long primary = GLFW.glfwGetPrimaryMonitor();
        // Not only on a machine with no screen: macOS leaves a sleeping display out of the list.
        assumeTrue(primary != 0L, "GLFW lists no monitor here");
        assertTrue(LwjglDisplay.isListed(primary));

        boolean[] plugged = {true};
        LwjglDisplay display = new LwjglDisplay(primary, 0, ui.runtime(), monitor -> plugged[0]);
        String name = display.name();
        Resolution resolution = display.currentResolution();
        List<Resolution> resolutions = display.availableResolutions();
        ScreenRect bounds = display.bounds();
        ScreenRect workArea = display.workArea();
        float scale = display.contentScale();
        assertTrue(display.isPrimary());

        plugged[0] = false;

        assertEquals(name, display.name());
        assertEquals(resolution, display.currentResolution());
        assertEquals(resolutions, display.availableResolutions());
        assertEquals(bounds, display.bounds());
        assertEquals(workArea, display.workArea());
        assertEquals(scale, display.contentScale());
        assertFalse(display.isPrimary());
    }
}
