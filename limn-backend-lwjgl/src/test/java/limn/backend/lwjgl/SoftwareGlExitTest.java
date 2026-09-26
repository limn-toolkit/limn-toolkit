package limn.backend.lwjgl;

import limn.backend.NativeWindow;
import limn.backend.Platform;
import limn.backend.WindowConfig;
import limn.concurrent.Ui;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Mac on the software OpenGL fallback ({@link MacSoftwareGl}) ends its process cleanly after its
 * windows close.
 *
 * <p>It aborted on every exit before 2026-09-25, on the macOS guest (which has no accelerated
 * renderer) and on a host forced onto software: the software context holds the content view past
 * the window's destruction, an autorelease pool releases it at the end of the process, AppKit asks it
 * for its {@code -inputContext}, and the view still had the input gate's class, whose method is a
 * closure calling into a Java runtime that is shutting down. {@link MacInputContextGate#forget} now
 * gives the view its own class back.
 *
 * <p>It also checks that the forced path runs on {@code Apple Software Renderer}, which it did not
 * on a Mac with a GPU until the same day ({@link MacSoftwareGl#PROPERTY}).
 *
 * <p>In a JVM of its own, as {@link ClosedWindowTest} explains: the defect is the process ending,
 * which is a thing a test has to watch from outside. macOS only; the fallback exists nowhere else.
 */
class SoftwareGlExitTest {

    private static final int NO_DISPLAY = 3;

    @TempDir
    Path directory;

    @Test
    void aSoftwareRenderedMacEndsItsProcessCleanly() throws Exception {
        Assumptions.assumeTrue(Platform.current().isMacOs(), "the software OpenGL fallback is macOS's");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-XstartOnFirstThread");
        command.add("-D" + MacSoftwareGl.PROPERTY + "=true");
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
        assertTrue(output.contains("software fallback"), "the child was not on software GL:\n" + output);
        // The renderer, not the log line: on a Mac with a GPU the forced path used to build its
        // context on the GPU while saying "software fallback".
        assertTrue(output.contains("renderer=Apple Software Renderer"),
                "the forced path is not on Apple's software renderer:\n" + output);
        assertTrue(output.contains("loop ended"), output);
        assertEquals(0, child.exitValue(), "the process did not end cleanly:\n" + output);
    }

    /** Two software-rendered windows: one closed while the loop runs, as a popup is, one at the end. */
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
                NativeWindow main = backend.createWindow(WindowConfig.of("main", 200, 120));
                NativeWindow popup = backend.createWindow(WindowConfig.popup(120, 80));
                System.out.println("renderer=" + backend.graphicsInfo().renderer());
                main.show();
                popup.show();
                Ui.postDelayed(popup::requestClose, 100);
                Ui.postDelayed(main::requestClose, 300);
                backend.runEventLoop();
            }
            System.out.println("loop ended");
        }
    }
}
