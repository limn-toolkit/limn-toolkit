package limn.backend.lwjgl;

import limn.backend.NativeWindow;
import limn.backend.Platform;
import limn.backend.WindowConfig;
import limn.concurrent.Ui;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWAllocator;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window the user closed, asked to do things by an application that was not told: a title set
 * when a background load completes, a resize, a show. Every one of them used to reach GLFW with the
 * freed handle, and GLFW checks nothing, so the first of them aborted the process with "pointer
 * being freed was not allocated".
 *
 * <p><b>Run in a JVM of its own</b>, which is the only way this suite can hold a real window: a
 * backend installs the toolkit's services process-wide and terminates GLFW when it closes, which
 * would take {@link HeadlessGl}'s context from under every GL test after this one. And a crash is
 * the failure being tested for, which a test has to survive to report. The child prints what it
 * did; a line missing from that output, or an exit status that is a signal, is the defect.
 */
class ClosedWindowTest {

    /** What the child exits with when this machine cannot open a window at all. */
    private static final int NO_DISPLAY = 3;

    @TempDir
    Path directory;

    @Test
    void everyCallOnAClosedWindowIsAnsweredWithoutReachingGlfw() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (Platform.current().isMacOs()) {
            command.add("-XstartOnFirstThread"); // Cocoa windows exist on the first thread only
        }
        // A crash report written where the crash happened would land in the module directory.
        command.add("-XX:ErrorFile=" + directory.resolve("hs_err_pid%p.log"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Child.class.getName());
        // To a file rather than a pipe, so a child that hangs cannot hang the read as well.
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
        assertEquals(0, child.exitValue(),
                "a call on the closed window took the process down:\n" + output);
        assertTrue(output.contains("closed window answered every call"), output);
        assertTrue(output.contains("title=after close"), output);
        assertTrue(output.contains("loop ended"), output);
    }

    /**
     * The child: a window closed the way the loop closes a window the user closed, another opened
     * straight afterwards, and then every call an application can still make on the closed one.
     *
     * <p>GLFW's memory comes from {@link #QUARANTINE} here, which never hands a block back and
     * fills every freed one with a pattern no pointer can have. Without it the outcome of reaching
     * into a freed window is whatever the allocator did with the block meanwhile, and the same calls
     * came through unharmed about one run in four before the fix; with it, the first call that
     * reads the freed window dereferences the pattern and the process dies on the spot.
     */
    static final class Child {

        /** What freed memory is filled with: not a tagged pointer on arm64, and far above any address. */
        private static final int POISON = 0x5A;

        /** Room in front of each block for its size, which the free needs and GLFW does not pass. */
        private static final long HEADER = 16;

        private static final GLFWAllocator QUARANTINE = GLFWAllocator.calloc()
                .allocate((size, user) -> allocate(size))
                .reallocate((block, size, user) -> {
                    long moved = allocate(size);
                    MemoryUtil.memCopy(block, moved,
                            Math.min(size, MemoryUtil.memGetLong(block - HEADER)));
                    free(block);
                    return moved;
                })
                .deallocate((block, user) -> free(block));

        private static long allocate(long size) {
            long base = MemoryUtil.nmemCalloc(1, size + HEADER);
            MemoryUtil.memPutLong(base, size);
            return base + HEADER;
        }

        private static void free(long block) {
            if (block != MemoryUtil.NULL) {
                MemoryUtil.memSet(block, POISON, MemoryUtil.memGetLong(block - HEADER));
            }
        }

        public static void main(String[] args) {
            GLFW.glfwInitAllocator(QUARANTINE); // before the backend's glfwInit, which is when it counts
            LwjglBackend backend;
            try {
                backend = new LwjglBackend();
            } catch (RuntimeException | LinkageError noDisplay) {
                System.out.println("no backend: " + noDisplay);
                System.exit(NO_DISPLAY);
                return;
            }
            try (backend) {
                NativeWindow keeper = backend.createWindow(WindowConfig.of("keeper", 160, 90));
                NativeWindow gone = backend.createWindow(WindowConfig.of("gone", 160, 90));
                keeper.show();
                gone.show();
                gone.requestClose();
                Ui.postDelayed(() -> afterClose(backend, keeper, gone), 20);
                backend.runEventLoop();
            }
            System.out.println("loop ended");
        }

        private static void afterClose(LwjglBackend backend, NativeWindow keeper, NativeWindow gone) {
            if (!gone.isClosed()) {
                Ui.postDelayed(() -> afterClose(backend, keeper, gone), 20);
                return;
            }
            NativeWindow fresh = backend.createWindow(WindowConfig.of("fresh", 160, 90));
            fresh.show();

            gone.setTitle("after close");
            gone.setSize(320, 180);
            gone.setSizeLimits(100, 100, 400, 400);
            gone.show();
            gone.hide();
            gone.setScreenPosition(40, 40);
            gone.setMousePassthrough(true);
            gone.setAboveSystemChrome(true);
            gone.setOpacity(0.5f);
            gone.focus();
            gone.clipboard().get();
            gone.publishAccessibilityElsewhere();
            System.out.println("read back: visible=" + gone.isVisible()
                    + " at=" + gone.screenX() + "," + gone.screenY()
                    + " cursor=" + gone.cursorX() + "," + gone.cursorY()
                    + " display=" + gone.display()
                    + " native=" + gone.nativeHandle()
                    + " title=" + gone.title());
            System.out.println("closed window answered every call");

            fresh.requestClose();
            keeper.requestClose();
        }
    }
}
