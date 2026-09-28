package limn.backend.lwjgl;

import limn.backend.Platform;
import limn.backend.WindowConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_AUTO_ICONIFY;
import static org.lwjgl.glfw.GLFW.glfwGetWindowAttrib;

/**
 * What a window fullscreen on a monitor does when it loses the focus, as GLFW is asked for it on
 * each entry: minimized at a named mode, the fullscreen a game asks for, and left fullscreen behind
 * the window that took the focus at the mode the display is in, the fullscreen an application asks
 * for. GLFW reads the attribute only while it holds the window fullscreen, and a test never makes a
 * window fullscreen, so what is read here is what each request sets on a hidden window, through the
 * same call {@code enterFullscreen} makes before GLFW takes the monitor; a live run reads the
 * minimizing itself.
 *
 * <p><b>Run in a JVM of its own</b>, as {@link ClosedWindowTest} explains: a backend installs the
 * toolkit's services process-wide and terminates GLFW when it closes. The child prints what GLFW
 * answered; a missing line is the defect.
 */
class FullscreenFocusLossTest {

    /** What the child exits with when this machine cannot open a window at all. */
    private static final int NO_DISPLAY = 3;

    @TempDir
    Path directory;

    @Test
    void aRequestThatNamesNoModeLeavesTheWindowFullscreenWhenItLosesTheFocus() throws Exception {
        String output = runChild();

        assertTrue(output.contains("a new window: 1"), "sanity: GLFW's default:\n" + output);
        assertTrue(output.contains("no mode named: 0"), output);
        assertTrue(output.contains("a size named: 1"), output);
        assertTrue(output.contains("a rate alone named: 1"), output);
        assertTrue(output.contains("no mode named after a named one: 0"), output);
        assertTrue(output.contains("done"), output);
    }

    /** Which requests name a mode, the one question the attribute follows. */
    @Test
    void onlyASizeOrARefreshRateNamesAMode() {
        assertFalse(LwjglWindow.namesMode(0, 0, 0));
        assertFalse(LwjglWindow.namesMode(-1, -1, -1), "the dimensions <= 0 name none");
        assertTrue(LwjglWindow.namesMode(1280, 720, 0));
        assertTrue(LwjglWindow.namesMode(0, 0, 60));
        assertTrue(LwjglWindow.namesMode(1280, 0, 0), "one dimension is enough");
    }

    private String runChild() throws Exception {
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
        return output;
    }

    /** A hidden window whose attribute is read back after each kind of request. */
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
                        WindowConfig.of("focus loss", 160, 90).visible(false));
                long handle = window.handle();
                System.out.println("a new window: " + glfwGetWindowAttrib(handle, GLFW_AUTO_ICONIFY));

                window.minimizeOnFocusLoss(LwjglWindow.namesMode(0, 0, 0));
                System.out.println("no mode named: " + glfwGetWindowAttrib(handle, GLFW_AUTO_ICONIFY));

                window.minimizeOnFocusLoss(LwjglWindow.namesMode(1280, 720, 60));
                System.out.println("a size named: " + glfwGetWindowAttrib(handle, GLFW_AUTO_ICONIFY));

                window.minimizeOnFocusLoss(false);
                window.minimizeOnFocusLoss(LwjglWindow.namesMode(0, 0, 60));
                System.out.println("a rate alone named: "
                        + glfwGetWindowAttrib(handle, GLFW_AUTO_ICONIFY));

                window.minimizeOnFocusLoss(LwjglWindow.namesMode(0, 0, 0));
                System.out.println("no mode named after a named one: "
                        + glfwGetWindowAttrib(handle, GLFW_AUTO_ICONIFY));
            }
            System.out.println("done");
        }
    }
}
