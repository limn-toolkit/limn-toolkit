package limn.backend.lwjgl;

import limn.backend.CloseRequest;
import limn.backend.LoopEnd;
import limn.backend.LoopLifetime;
import limn.backend.NativeWindow;
import limn.backend.Platform;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.internal.ObjC;
import limn.concurrent.Ui;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWWindowCloseCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application ends by asking once, on the real backend (ADR 048): the loop returns why it ended,
 * lives with no window when asked to, a user's close reaches the handler after the poll, and on
 * macOS the system's Quit command asks every window and closes none unless all agree, where GLFW's
 * own answer closed the windows that agreed before one refused.
 *
 * <p>In a JVM of its own, as {@link ClosedWindowTest} explains: a backend is process-wide, and the
 * ending of a process is the thing watched. The child prints what happened; the test reads it.
 */
class ApplicationEndingProcessTest {

    private static final int NO_DISPLAY = 3;

    @TempDir
    Path directory;

    @Test
    void anAgreedQuitClosesEveryWindowAndTheLoopSaysSo() throws Exception {
        String output = run("quit");
        assertTrue(output.contains("asked A APPLICATION_END"), output);
        assertTrue(output.contains("asked B APPLICATION_END"), output);
        assertTrue(output.contains("asked C APPLICATION_END"), output);
        assertFalse(output.contains("overlap"), "a window was asked before the one before it answered:\n" + output);
        assertTrue(output.contains("closed while asking: 0"), output);
        assertTrue(output.contains("loop ended QUIT"), output);
    }

    @Test
    void aRefusedQuitClosesNothing() throws Exception {
        String output = run("refuse");
        assertTrue(output.contains("closed after the refusal: 0"), output);
        assertTrue(output.contains("loop ended STOPPED"), output);
    }

    @Test
    void theLastWindowEndsTheLoopByDefault() throws Exception {
        String output = run("last");
        assertTrue(output.contains("loop ended LAST_WINDOW_CLOSED"), output);
    }

    @Test
    void untilQuitTheLoopLivesWithNoWindow() throws Exception {
        String output = run("lifetime");
        assertTrue(output.contains("alive with 0 windows"), output);
        assertTrue(output.contains("loop ended QUIT"), output);
    }

    @Test
    void aUsersCloseIsAnsweredLaterAndFollowsThePolicy() throws Exception {
        String output = run("user");
        assertTrue(output.contains("asked once while the question was open: 1"), output);
        assertTrue(output.contains("open before the answer: true"), output);
        assertTrue(output.contains("hidden, not closed: true"), output);
        assertTrue(output.contains("loop ended STOPPED"), output);
    }

    @Test
    void macosQuitCommandAsksEveryWindowAndClosesNoneOnARefusal() throws Exception {
        Assumptions.assumeTrue(Platform.current().isMacOs(), "the Quit command is macOS's");
        String output = run("terminate");
        assertTrue(output.contains("terminate: returned"), output);
        assertTrue(output.contains("asked B APPLICATION_END"), output);
        assertTrue(output.contains("closed after the refusal: 0"),
                "GLFW's answer closed the windows that agreed before B refused:\n" + output);
        assertTrue(output.contains("loop ended QUIT"), "the second Quit, all agreeing:\n" + output);
    }

    private String run(String mode) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (Platform.current().isMacOs()) {
            command.add("-XstartOnFirstThread");
        }
        command.add("-XX:ErrorFile=" + directory.resolve("hs_err_pid%p.log"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Child.class.getName());
        command.add(mode);
        Path log = directory.resolve(mode + ".log");
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
        assertEquals(0, child.exitValue(), "the process did not end cleanly:\n" + output);
        return output;
    }

    /** Opens three windows, A, B and C, and ends the way the mode says. */
    static final class Child {

        private static final List<NativeWindow> windows = new ArrayList<>();
        private static int asking;
        private static int askedWhileOpen;
        /** The most windows found closed when a window answered: GLFW's partial quit if not 0. */
        private static int closedWhileAsking;

        public static void main(String[] args) {
            String mode = args[0];
            LwjglBackend backend;
            try {
                backend = new LwjglBackend();
            } catch (RuntimeException | LinkageError noDisplay) {
                System.out.println("no backend: " + noDisplay);
                System.exit(NO_DISPLAY);
                return;
            }
            LoopEnd end;
            try (backend) {
                for (String name : List.of("A", "B", "C")) {
                    NativeWindow window = backend.createWindow(WindowConfig.of(name, 160, 90));
                    window.show();
                    windows.add(window);
                }
                switch (mode) {
                    case "quit" -> {
                        answerLater(null);
                        Ui.postDelayed(backend::requestQuit, 100);
                    }
                    case "refuse" -> {
                        answerLater("B");
                        Ui.postDelayed(backend::requestQuit, 100);
                        Ui.postDelayed(() -> {
                            System.out.println("closed after the refusal: " + closedCount());
                            backend.stop();
                        }, 1000);
                    }
                    case "last" -> Ui.postDelayed(() -> windows.forEach(NativeWindow::requestClose), 100);
                    case "lifetime" -> {
                        backend.setLoopLifetime(LoopLifetime.UNTIL_QUIT);
                        Ui.postDelayed(() -> windows.forEach(NativeWindow::requestClose), 100);
                        Ui.postDelayed(() -> {
                            System.out.println("alive with " + (3 - closedCount()) + " windows");
                            backend.requestQuit();
                        }, 600);
                    }
                    case "user" -> user(backend);
                    case "terminate" -> terminate(backend);
                    default -> throw new IllegalArgumentException(mode);
                }
                end = backend.runEventLoop();
            }
            System.out.println("closed while asking: " + closedWhileAsking);
            System.out.println("loop ended " + end);
        }

        /** Every window answers 150 ms after it is asked; {@code refusing} refuses. */
        private static void answerLater(String refusing) {
            for (NativeWindow window : windows) {
                window.setCloseRequestHandler(request -> {
                    System.out.println("asked " + window.title() + " " + request.reason());
                    if (asking++ != 0) {
                        System.out.println("overlap");
                    }
                    Ui.postDelayed(() -> {
                        asking--;
                        if (window.title().equals(refusing)) {
                            request.refuse();
                        } else {
                            closedWhileAsking = Math.max(closedWhileAsking, closedCount());
                            request.allow();
                        }
                    }, 150);
                });
            }
        }

        /** A user's close, as GLFW delivers it: its close callback, which the backend installed. */
        private static void user(LwjglBackend backend) {
            LwjglWindow a = (LwjglWindow) windows.get(0);
            a.setClosePolicy(limn.backend.ClosePolicy.HIDE);
            List<CloseRequest> kept = new ArrayList<>();
            a.setCloseRequestHandler(request -> {
                askedWhileOpen++;
                kept.add(request);
            });
            Ui.postDelayed(() -> {
                clickClose(a);
                clickClose(a); // while the first question is open
            }, 100);
            Ui.postDelayed(() -> {
                System.out.println("asked once while the question was open: " + askedWhileOpen);
                System.out.println("open before the answer: " + (!a.isClosed() && a.isVisible()));
                kept.get(0).allow();
            }, 400);
            Ui.postDelayed(() -> {
                System.out.println("hidden, not closed: " + (!a.isVisible() && !a.isClosed()));
                backend.stop();
            }, 700);
        }

        private static void clickClose(LwjglWindow window) {
            long handle = window.handle();
            GLFWWindowCloseCallback installed = GLFW.glfwSetWindowCloseCallback(handle, null);
            GLFW.glfwSetWindowShouldClose(handle, true); // what GLFW does before it calls back
            installed.invoke(handle);
            GLFW.glfwSetWindowCloseCallback(handle, installed);
        }

        /** macOS's Quit command, as its menu item sends it: {@code -[NSApp terminate:]}. */
        private static void terminate(LwjglBackend backend) {
            answerLater("B");
            Ui.postDelayed(Child::sendTerminate, 200);
            Ui.postDelayed(() -> {
                System.out.println("closed after the refusal: " + closedCount());
                answerLater(null);
                sendTerminate();
            }, 1200);
        }

        private static void sendTerminate() {
            long app = ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication");
            ObjC.msgVoid(app, "terminate:", 0L);
            System.out.println("terminate: returned");
        }

        private static int closedCount() {
            int closed = 0;
            for (NativeWindow window : windows) {
                if (window.isClosed()) {
                    closed++;
                }
            }
            return closed;
        }
    }
}
