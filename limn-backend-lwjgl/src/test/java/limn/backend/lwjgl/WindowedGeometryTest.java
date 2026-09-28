package limn.backend.lwjgl;

import limn.backend.Platform;
import limn.backend.WindowConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.backend.lwjgl.WindowedGeometry.Step.APPLY;
import static limn.backend.lwjgl.WindowedGeometry.Step.NONE;
import static limn.backend.lwjgl.WindowedGeometry.Step.RESTORE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwWaitEventsTimeout;
import static org.lwjgl.glfw.GLFW.glfwSetWindowSize;

/**
 * What a window is given back when it leaves a fullscreen on the monitor, played against the
 * answers GNOME over X11 gave a 900 &times; 600 window in the live runs: back maximized at
 * 958 &times; 699 in most, back at 900 &times; 563 and 37 pixels lower in the rest. Those answers
 * come only from a real window manager to a window that really went fullscreen, which a test never
 * makes; what is read here is what the window asks of GLFW for each of them, and, on a hidden
 * window, that the loop's settling reads the window and asks GLFW again. A live run reads the rest.
 */
class WindowedGeometryTest {

    private static final long MS = 1_000_000L;

    /** The case GNOME answered 4 times in 19: the size and position given to the frame. */
    @Test
    void aWindowGivenBackSmallerAndLowerIsAskedOnceMoreForWhereItWas() {
        WindowedGeometry geometry = new WindowedGeometry();
        geometry.save(66, 69, 900, 600, false);

        assertFalse(geometry.left(false, 0), "not maximized: nothing to undo");
        assertEquals(NONE, geometry.settle(10 * MS, 66, 106, 900, 563, false, true),
                "no answer yet: the window reads as it is before the window manager has acted");
        geometry.answered();
        assertEquals(APPLY, geometry.settle(30 * MS, 66, 106, 900, 563, false, true));
        assertEquals(66, geometry.x());
        assertEquals(69, geometry.y());
        assertEquals(900, geometry.width());
        assertEquals(600, geometry.height());

        geometry.answered(); // the answer to the correction, whatever it is
        assertEquals(NONE, geometry.settle(50 * MS, 66, 106, 900, 563, false, true),
                "corrected once, never again: a window manager that refuses cannot make a loop");
        assertFalse(geometry.watching());
    }

    /** The case GNOME answered 14 times in 19: back maximized, a state added while fullscreen. */
    @Test
    void aWindowThatComesBackMaximizedIsUnmaximizedAndThenAskedForWhereItWas() {
        WindowedGeometry geometry = new WindowedGeometry();
        geometry.save(94, 101, 900, 600, false);

        assertTrue(geometry.left(true, 0), "maximized now and not before: unmaximized at once");
        geometry.answered();
        assertEquals(APPLY, geometry.settle(20 * MS, 66, 69, 958, 699, true, true),
                "asked after the unmaximize, so the window manager handles it unmaximized");
        geometry.answered();
        assertEquals(NONE, geometry.settle(40 * MS, 94, 101, 900, 600, false, true));
    }

    /** A window that reports the maximized state only in the answer is unmaximized then. */
    @Test
    void aWindowMaximizedOnlyInTheAnswerIsUnmaximizedThenAndCorrectedAfter() {
        WindowedGeometry geometry = new WindowedGeometry();
        geometry.save(94, 101, 900, 600, false);

        assertFalse(geometry.left(false, 0));
        geometry.answered();
        assertEquals(RESTORE, geometry.settle(20 * MS, 66, 69, 958, 699, true, true));
        geometry.answered();
        assertEquals(APPLY, geometry.settle(40 * MS, 66, 69, 958, 699, true, true),
                "unmaximized once, then only corrected");
        geometry.answered();
        assertEquals(NONE, geometry.settle(60 * MS, 94, 101, 900, 600, false, true));
    }

    /** A right answer changes nothing, and a wrong one after it is still caught. */
    @Test
    void aRightAnswerIsLeftAloneAndAWrongOneAfterItIsStillCaught() {
        WindowedGeometry geometry = new WindowedGeometry();
        geometry.save(66, 69, 900, 600, false);
        geometry.left(false, 0);

        geometry.answered();
        assertEquals(NONE, geometry.settle(20 * MS, 66, 69, 900, 600, false, true));
        assertTrue(geometry.watching(), "a first answer can be one of several");
        geometry.answered();
        assertEquals(APPLY, geometry.settle(40 * MS, 66, 106, 900, 563, false, true));
    }

    /** Past the window of answers nothing is corrected: a resize then is the person's. */
    @Test
    void nothingIsCorrectedOnceTheAnswersAreNoLongerWatched() {
        WindowedGeometry geometry = new WindowedGeometry();
        geometry.save(66, 69, 900, 600, false);
        geometry.left(false, 0);

        geometry.answered();
        assertEquals(NONE, geometry.settle(WindowedGeometry.ANSWER_WINDOW_NANOS + 1, 66, 69, 700,
                500, false, true));
        assertFalse(geometry.watching());
        geometry.answered();
        assertEquals(NONE, geometry.settle(WindowedGeometry.ANSWER_WINDOW_NANOS + 2, 66, 69, 700,
                500, false, true));
    }

    /** A window maximized before it entered comes back as the desktop sizes it. */
    @Test
    void aWindowThatWasMaximizedIsLeftAsItComesBack() {
        WindowedGeometry geometry = new WindowedGeometry();
        geometry.save(66, 69, 958, 699, true);

        assertFalse(geometry.left(true, 0));
        geometry.answered();
        assertEquals(NONE, geometry.settle(20 * MS, 70, 80, 900, 600, true, true));
        assertFalse(geometry.watching());
    }

    /** Where windows are not placed, as on Wayland, only the size counts. */
    @Test
    void aPositionCountsOnlyWhereThePlatformPlacesWindows() {
        WindowedGeometry geometry = new WindowedGeometry();
        geometry.save(0, 0, 900, 600, false);
        geometry.left(false, 0);

        geometry.answered();
        assertEquals(NONE, geometry.settle(20 * MS, 40, 40, 900, 600, false, false));
        geometry.answered();
        assertEquals(APPLY, geometry.settle(40 * MS, 40, 40, 900, 563, false, false));
    }

    /** Going fullscreen again stops the watching of the answers to the last leaving. */
    @Test
    void enteringAgainStopsTheWatching() {
        WindowedGeometry geometry = new WindowedGeometry();
        geometry.save(66, 69, 900, 600, false);
        geometry.left(false, 0);

        geometry.entered();
        geometry.answered();
        assertEquals(NONE, geometry.settle(20 * MS, 0, 0, 1024, 768, false, true));
    }

    // ------------------------------------------------------------------ on a real window

    /** What the child exits with when this machine cannot open a window at all. */
    private static final int NO_DISPLAY = 3;

    @TempDir
    Path directory;

    /**
     * The window's side, on a hidden window that never goes fullscreen: after it has left, a size
     * the window manager answered wrong, read back through the size callback on the next poll, is
     * asked again once by the loop's settling, and a second wrong size is not.
     */
    @Test
    void aHiddenWindowGivenTheWrongSizeAfterLeavingIsGivenItsOwnBackOnce() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (Platform.current().isMacOs()) {
            command.add("-XstartOnFirstThread"); // Cocoa windows exist on the first thread only
        }
        command.add("-XX:ErrorFile=" + directory.resolve("hs_err_pid%p.log"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Child.class.getName());
        Path log = directory.resolve("child.log");
        Process child = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        boolean finished = child.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly().waitFor();
        }
        String output = Files.readString(log);
        assertTrue(finished, "the child never finished:\n" + output);
        Assumptions.assumeFalse(child.exitValue() == NO_DISPLAY,
                () -> "no window can be opened here:\n" + output);
        assertEquals(0, child.exitValue(), "the child failed:\n" + output);

        assertTrue(output.contains("before: 320x200"), output);
        assertTrue(output.contains("answered wrong: 320x180"), output);
        assertTrue(output.contains("after the settling: 320x200"), output);
        assertTrue(output.contains("answered wrong again: 320x180"), output);
        assertTrue(output.contains("after a second settling: 320x180"), output);
        assertTrue(output.contains("done"), output);
    }

    /** A hidden window that plays one leaving, this process standing in for the window manager. */
    static final class Child {

        public static void main(String[] args) {
            LwjglBackend backend;
            try {
                backend = new LwjglBackend();
            } catch (RuntimeException | LinkageError noDisplay) {
                System.out.println("no backend: " + noDisplay);
                System.exit(NO_DISPLAY);
                return;
            }
            try (backend) {
                LwjglWindow window = (LwjglWindow) backend.createWindow(
                        WindowConfig.of("leaving", 320, 200).visible(false));
                System.out.println("before: " + size(window));

                window.leftMonitorFullscreenForTest();
                glfwSetWindowSize(window.handle(), 320, 180);
                awaitAnswers();
                System.out.println("answered wrong: " + size(window));
                window.settleWindowedGeometry();
                awaitAnswers();
                System.out.println("after the settling: " + size(window));

                glfwSetWindowSize(window.handle(), 320, 180);
                awaitAnswers();
                System.out.println("answered wrong again: " + size(window));
                window.settleWindowedGeometry();
                awaitAnswers();
                System.out.println("after a second settling: " + size(window));
            }
            System.out.println("done");
        }

        /**
         * Lets the display server's answers arrive and the size callback run. One poll is enough on
         * macOS, where a resize is answered in the call; X11 answers with a ConfigureNotify that can
         * land a poll later (seen under Xvfb, CI's display), and a test that read the window before
         * it would be reading the question rather than the answer.
         */
        private static void awaitAnswers() {
            for (int i = 0; i < 10; i++) {
                glfwWaitEventsTimeout(0.02);
            }
        }

        private static String size(LwjglWindow window) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                glfwGetWindowSize(window.handle(), w, h);
                return w.get(0) + "x" + h.get(0);
            }
        }
    }
}
