package limn.backend.lwjgl;

import limn.backend.NativeWindow;
import limn.backend.Platform;
import limn.backend.WindowConfig;
import limn.concurrent.Ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window's OpenGL context is the backend's own (ADR 082 §1.1), and it draws. On Windows and Linux
 * it is then made again as after a lost device (on Windows the driver's library unloaded and a child
 * surface drawn on, on Linux a new EGL context on the same window), and it draws again.
 *
 * <p>Run in a JVM of its own, for the reasons {@link ClosedWindowTest} gives: a backend terminates
 * GLFW when it closes, and on Windows this one unloads the OpenGL runtime.
 */
class OwnContextTest {

    private static final int NO_DISPLAY = 3;
    private static final int NOT_OWN = 4;

    @TempDir
    Path directory;

    @Test
    void theBackendMakesTheContextAndMakesItAgain() throws Exception {
        Platform.Os os = Platform.current().os();
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (Platform.current().isMacOs()) {
            command.add("-XstartOnFirstThread");
        }
        command.add("-XX:ErrorFile=" + directory.resolve("hs_err_pid%p.log"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Child.class.getName());
        Path log = directory.resolve("child.log");
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = child.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly().waitFor();
        }
        String output = Files.readString(log);
        assertTrue(finished, "the child never finished:\n" + output);
        Assumptions.assumeFalse(child.exitValue() == NO_DISPLAY, () -> "no window can be opened here:\n" + output);
        Assumptions.assumeFalse(child.exitValue() == NOT_OWN,
                () -> "this machine's windows are on another context (ANGLE or the software one):\n" + output);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("own context draws"), output);
        if (os != Platform.Os.MACOS) {
            assertTrue(output.contains("made again, draws"), output);
        }
        assertTrue(output.contains("loop ended"), output);
    }

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
                NativeWindow window = backend.createWindow(WindowConfig.of("own context", 160, 90).focusOnShow(false));
                window.setOpacity(0f); // shown, as tested, and not seen
                window.show();
                Ui.postDelayed(() -> check(backend, (LwjglWindow) window), 200);
                backend.runEventLoop();
            }
            System.out.println("loop ended");
        }

        private static void check(LwjglBackend backend, LwjglWindow window) {
            boolean own = Platform.current().isMacOs()
                    ? GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_CLIENT_API) == GLFW.GLFW_NO_API
                    : window.contextRemakeable();
            if (!own || AngleFallback.taken()) {
                System.out.println("not the backend's own context");
                System.exit(NOT_OWN);
            }
            System.out.println(window.contextDraws() ? "own context draws" : "own context does NOT draw");
            if (!Platform.current().isMacOs()) {
                backend.lossWatch().rebuildNow(List.of(window));
                System.out.println(window.contextDraws() ? "made again, draws" : "made again, does NOT draw");
            }
            window.requestClose();
        }
    }
}
