package limn.backend.lwjgl;

import limn.backend.Platform;
import limn.backend.WindowConfig;
import limn.backend.WindowInput;
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
 * What a real window tells its input about fullscreen, read without making anything fullscreen or
 * visible, which a test must never do: a request that changes nothing tells nothing, and on macOS
 * AppKit's four notifications for the person's green button tell one change each way, through the
 * same method AppKit's observer calls, a transition AppKit never finishes is told undone when the
 * loop's settling gives it up, and the application's {@code enterFullscreen} and
 * {@code exitFullscreen} tell theirs, on a window the observer has forgotten so that the toggle
 * they ask for reaches nothing.
 *
 * <p>What it cannot read is the change those two calls make on Windows and Linux, which is GLFW
 * taking the monitor fullscreen at once, nor AppKit's own transition: a live run reads those.
 *
 * <p><b>Run in a JVM of its own</b>, as {@link ClosedWindowTest} explains: a backend installs the
 * toolkit's services process-wide and terminates GLFW when it closes, which would take
 * {@link HeadlessGl}'s context from under every GL test after this one. The window is hidden
 * throughout and the loop never runs. The child prints what its input was told; a missing line is
 * the defect.
 */
class FullscreenReportTest {

    /** What the child exits with when this machine cannot open a window at all. */
    private static final int NO_DISPLAY = 3;

    @TempDir
    Path directory;

    @Test
    void aWindowTellsItsInputEachChangeOfFullscreenAndNothingElse() throws Exception {
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

        assertTrue(output.contains("leaving a windowed window told []"), output);
        if (Platform.current().isMacOs()) {
            assertTrue(output.contains("the person starting to enter told [true]"), output);
            assertTrue(output.contains("AppKit finishing told [true]"), output);
            assertTrue(output.contains("the person starting to leave told [true, false]"), output);
            assertTrue(output.contains("AppKit finishing again told [true, false]"), output);
            assertTrue(output.contains("a transition begun told [true]"), output);
            assertTrue(output.contains("the same transition settled early told [true]"), output);
            assertTrue(output.contains("the transition given up told [true, false]"), output);
            assertTrue(output.contains("fullscreen at the end: false"), output);
            assertTrue(output.contains("done"), output);
            Assumptions.assumeFalse(output.contains("toggles are sent at once"),
                    () -> "the fullscreen observer could not be made here:\n" + output);
            assertTrue(output.contains("enterFullscreen told [true]"), output);
            assertTrue(output.contains("exitFullscreen told [true, false]"), output);
        }
        assertTrue(output.contains("done"), output);
    }

    /** A hidden window whose input records what it is told of fullscreen, and nothing else. */
    static final class Child {

        public static void main(String[] args) throws InterruptedException {
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
                        WindowConfig.of("fullscreen report", 160, 90).visible(false));
                List<Boolean> told = new ArrayList<>();
                window.setInput(new Recorder(told));

                window.exitFullscreen();
                System.out.println("leaving a windowed window told " + told);

                if (Platform.current().isMacOs()) {
                    // What AppKit's observer delivers for the green button: nothing is sent back,
                    // because a transition the person began is one the record only follows.
                    window.nativeFullscreenChanged(MacFullscreen.Event.WILL_ENTER);
                    System.out.println("the person starting to enter told " + told);
                    window.nativeFullscreenChanged(MacFullscreen.Event.DID_ENTER);
                    System.out.println("AppKit finishing told " + told);
                    window.nativeFullscreenChanged(MacFullscreen.Event.WILL_EXIT);
                    System.out.println("the person starting to leave told " + told);
                    window.nativeFullscreenChanged(MacFullscreen.Event.DID_EXIT);
                    System.out.println("AppKit finishing again told " + told);

                    // A transition that is begun and never finished, as AppKit leaves a toggle it
                    // refuses part way: the loop's settling gives it up at the deadline and
                    // believes the window, which never left the desktop.
                    told.clear();
                    window.nativeFullscreenChanged(MacFullscreen.Event.WILL_ENTER);
                    System.out.println("a transition begun told " + told);
                    window.settleNativeFullscreen();
                    System.out.println("the same transition settled early told " + told);
                    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(NativeFullscreen.DEADLINE_NANOS) + 250);
                    window.settleNativeFullscreen();
                    System.out.println("the transition given up told " + told);

                    // The application's own two calls, on a window the observer has forgotten
                    // first: the toggle enterFullscreen asks for waits for the run loop and is then
                    // sent only to a window still observed, so it reaches nothing, whenever the
                    // loop turns. Where the observer could not be made a toggle is sent at once,
                    // and the calls are not made at all.
                    long nsWindow = window.nativeHandle();
                    if (MacFullscreen.togglesOnlyObservedWindows()) {
                        MacFullscreen.forget(nsWindow);
                        told.clear();
                        window.enterFullscreen();
                        System.out.println("enterFullscreen told " + told);
                        window.exitFullscreen();
                        System.out.println("exitFullscreen told " + told);
                    } else {
                        System.out.println("toggles are sent at once here: no enter or exit made");
                    }
                    System.out.println("fullscreen at the end: " + MacFullscreen.isFullscreen(nsWindow));
                }
            }
            System.out.println("done");
        }
    }

    /** Records each fullscreen change it is told; every other event is ignored. */
    private record Recorder(List<Boolean> told) implements WindowInput {
        @Override public void windowFullscreenChanged(boolean fullscreen) { told.add(fullscreen); }
        @Override public void mouseMoved(float x, float y) { }
        @Override public void mouseButton(int button, boolean pressed, int modifiers, float x, float y,
                                          int clickCount) { }
        @Override public void scrolled(float deltaX, float deltaY, float x, float y) { }
        @Override public void keyEvent(int key, boolean pressed, boolean repeat, int modifiers) { }
        @Override public void charTyped(int codepoint) { }
        @Override public void pointerEntered(boolean entered) { }
        @Override public void windowResized(float logicalWidth, float logicalHeight) { }
    }
}
