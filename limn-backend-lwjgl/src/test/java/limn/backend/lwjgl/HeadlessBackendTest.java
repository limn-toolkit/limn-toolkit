package limn.backend.lwjgl;

import limn.backend.Platform;
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

/**
 * A backend with no window: started without a display it refuses a window and still draws a picture
 * and a document, a layer under a Porter-Duff rule included, with a context of its own; started with
 * one, it draws them all the same before any window is open. Each in a JVM of its own
 * ({@link HeadlessDrawing}), as a server's process would be, since the windowing library starts once
 * a process. And which machines start without a display by themselves.
 */
class HeadlessBackendTest {

    @TempDir
    Path directory;

    @Test
    void withoutADisplayItRefusesAWindowAndDrawsPicturesAndDocuments() throws Exception {
        String output = run(List.of("-D" + LwjglBackend.HEADLESS_PROPERTY + "=true"), List.of());
        assertTrue(output.contains("window refused"), output);
        assertTrue(output.contains("\nOK"), output);
    }

    @Test
    void withADisplayItDrawsThemWithNoWindowOpen() throws Exception {
        Assumptions.assumeFalse(Platform.current().isLinux() && System.getenv("DISPLAY") == null
                && System.getenv("WAYLAND_DISPLAY") == null, "no display here");
        String output = run(List.of(), List.of("display"));
        assertFalse(output.contains("window refused"), output);
        assertTrue(output.contains("\nOK"), output);
    }

    @Test
    void aMachineStartsWithoutADisplayWhereItHasNone() {
        assertTrue(LwjglBackend.headlessByDefault(Platform.Os.LINUX, null, null, false), "Linux with neither");
        assertTrue(LwjglBackend.headlessByDefault(Platform.Os.LINUX, " ", "", false), "blank is unset");
        assertFalse(LwjglBackend.headlessByDefault(Platform.Os.LINUX, ":0", null, false), "X11");
        assertFalse(LwjglBackend.headlessByDefault(Platform.Os.LINUX, null, "wayland-0", false), "Wayland");
        assertTrue(LwjglBackend.headlessByDefault(Platform.Os.WINDOWS, null, null, true), "a service's session");
        assertFalse(LwjglBackend.headlessByDefault(Platform.Os.WINDOWS, null, null, false), "a user's session");
        assertFalse(LwjglBackend.headlessByDefault(Platform.Os.MACOS, null, null, false),
                "macOS starts with one, and without one only where the windowing library finds none");
    }

    /** Runs {@link HeadlessDrawing} in a JVM of its own; skips the test where no context can be had. */
    private String run(List<String> properties, List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (Platform.current().isMacOs()) {
            command.add("-XstartOnFirstThread");
        }
        command.add("-Dlimn.macos.agent=true");
        // Fewer processors than the machine has, as a container allows: a software rasterizer's
        // threads must follow the process's count (HeadlessDrawing counts them).
        command.add("-XX:ActiveProcessorCount=2");
        command.addAll(properties);
        command.add("-XX:ErrorFile=" + directory.resolve("hs_err_pid%p.log"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(HeadlessDrawing.class.getName());
        command.addAll(args);
        Path log = directory.resolve("child.log");
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = child.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly().waitFor();
        }
        String output = Files.readString(log);
        assertTrue(finished, "the child never finished:\n" + output);
        Assumptions.assumeFalse(output.contains("no picture can be drawn off screen"),
                () -> "no context can be had here:\n" + output);
        assertEquals(0, child.exitValue(), output);
        return output;
    }
}
