package limn.demo;

import limn.backend.GraphicsInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code --gl-info} report, whose whole deliverable is the text: it exists so that "it will not
 * start on that machine" can be answered from a log rather than from a stack trace.
 *
 * <p>Two failures reach it and the mode used to survive only one. The backend catches what
 * {@code createWindow} throws, but a machine with no display server fails a step earlier, when the
 * window library refuses to start &mdash; that throw came out of the try-with-resources' own
 * resource expression, where no catch of the mode's could see it, so the diagnostic documented to
 * print a report printed an uncaught exception and an LWJGL stack trace instead. A plain SSH shell
 * with no {@code DISPLAY} and no Wayland socket is exactly that machine, which is how CI and half
 * the verification lab are reached.
 *
 * <p>Nothing here opens a window: the report is a function of what the backend said, and the two
 * failures differ only in what that is.
 */
class GlInfoTest {

    private static GraphicsInfo working() {
        return new GraphicsInfo("Cocoa", "3.4.0", "OpenGL 3.3 core", "native", "Apple", "M1",
                "3.3", "3.30", 4, true, true, true, List.of("GL_ARB_one"), null);
    }

    /**
     * The defect itself: the backend's constructor is the try-with-resources' resource
     * expression, which no catch inside the block can see, so the throw left the mode entirely
     * and the process died with a stack trace where it documents a report and an exit status.
     */
    @Test
    void aLibraryThatRefusesToStartIsContainedAndReportedRatherThanThrown() {
        PrintStream out = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
        boolean available;
        try {
            available = assertDoesNotThrow(() -> GlInfo.run(() -> {
                throw new IllegalStateException("glfwInit() failed: no supported platform");
            }, GlInfo::startFailure));
        } finally {
            System.setOut(out);
        }

        assertFalse(available, "and the documented exit status, not a stack trace");
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("No graphics context."),
                printed.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aLibraryThatNeverStartedIsReportedAndNotThrown() {
        String text = GlInfo.report(
                GlInfo.startFailure(new IllegalStateException(
                        "glfwInit() failed: Failed to detect any supported platform")), null);

        assertTrue(text.contains("No graphics context."), text);
        assertTrue(text.contains(GlInfo.NO_GLFW),
                "a reader has to be able to tell this machine from one whose driver refused a "
                        + "window, and the exit status is the same for both: " + text);
        assertTrue(text.contains("Failed to detect any supported platform"),
                "carrying what the library itself said: " + text);
    }

    /** A throw with nothing to say still names itself, rather than reporting "null". */
    @Test
    void aStartFailureWithNoMessageNamesItsType() {
        String text = GlInfo.report(GlInfo.startFailure(new IllegalStateException()), null);

        assertTrue(text.contains(IllegalStateException.class.getName()), text);
    }

    /** The other failure, unchanged: the window's own throw is what the report carries. */
    @Test
    void aWindowThatCouldNotBeCreatedStillReportsItsOwnFailure() {
        String text = GlInfo.report(
                GraphicsInfo.unavailable("X11", "3.4.0", "no context"), "GLX: no GLXFBConfig");

        assertTrue(text.contains("No graphics context."), text);
        assertTrue(text.contains("GLX: no GLXFBConfig"),
                "the window's own failure wins over the backend's summary: " + text);
    }

    @Test
    void aMachineWithAContextReportsTheStackAndNoFailureLine() {
        String text = GlInfo.report(working(), null);

        assertTrue(text.contains("OpenGL 3.3 core"), text);
        assertTrue(text.contains("Extensions (1)"), text);
        assertEquals(-1, text.indexOf("No graphics context"), text);
    }
}
