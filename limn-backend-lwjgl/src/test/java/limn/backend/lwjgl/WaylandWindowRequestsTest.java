package limn.backend.lwjgl;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_COCOA;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_NULL;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WIN32;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11;

/**
 * What a program asks of its windows that Wayland gives no client: a window's whole opacity, its
 * icon, the always-on-top band, the pointer moved. GLFW answers each call there with
 * {@code GLFW_FEATURE_UNAVAILABLE} on every compositor, and the backend's error callback prints it
 * with a stack trace; on the Fedora KDE guest (2026-10-25) a run that opened one dialog printed
 * twelve, one at each step of the dialog's fades, under a comment that said GLFW did nothing there.
 * The calls are not made where the platform cannot carry them out, as the window-position calls
 * already were not. Neither half needs a display: the decision is a platform constant, and the rule
 * that every such call asks it first is read from the sources.
 */
class WaylandWindowRequestsTest {

    /** The calls GLFW's Wayland platform refuses with GLFW_FEATURE_UNAVAILABLE whatever the compositor. */
    private static final Pattern REFUSED_ON_WAYLAND = Pattern.compile(
            "\\bglfwSetWindowOpacity\\s*\\(|\\bglfwSetWindowIcon\\s*\\(|\\bglfwSetCursorPos\\s*\\("
                    + "|\\bglfwSetWindowAttrib\\s*\\([^;]*GLFW_FLOATING");

    /** A member of a top-level class: a line indented by exactly four spaces that opens a declaration. */
    private static final Pattern MEMBER = Pattern.compile("\n {4}[A-Za-z@][^\n]*");

    @Test
    void onlyWaylandIsToldNothing() {
        assertFalse(LwjglBackend.honoursWindowRequests(GLFW_PLATFORM_WAYLAND));
        assertTrue(LwjglBackend.honoursWindowRequests(GLFW_PLATFORM_X11),
                "X11 carries each out through the window manager's hints and its own pointer warp");
        assertTrue(LwjglBackend.honoursWindowRequests(GLFW_PLATFORM_WIN32));
        assertTrue(LwjglBackend.honoursWindowRequests(GLFW_PLATFORM_COCOA));
        assertTrue(LwjglBackend.honoursWindowRequests(GLFW_PLATFORM_NULL),
                "the null platform takes every call quietly");
    }

    @Test
    void everyCallWaylandRefusesAsksFirst() throws IOException {
        List<String> unguarded = new ArrayList<>();
        int calls = 0;
        for (Path source : sources()) {
            String text = Files.readString(source);
            Matcher call = REFUSED_ON_WAYLAND.matcher(text);
            while (call.find()) {
                if (isImport(text, call.start())) {
                    continue;
                }
                calls++;
                // From the declaration of the method the call is in, up to the call.
                int from = 0;
                Matcher member = MEMBER.matcher(text);
                while (member.find() && member.start() < call.start()) {
                    from = member.start();
                }
                if (!text.substring(from, call.start()).contains("honoursWindowRequests()")) {
                    int line = (int) text.substring(0, call.start()).lines().count();
                    unguarded.add(source.getFileName() + ":" + line + " " + call.group().strip());
                }
            }
        }
        assertTrue(calls >= 4, "the four calls are where they were looked for: " + calls);
        assertEquals(List.of(), unguarded,
                "ask LwjglBackend.honoursWindowRequests() before the call, or Wayland prints a stack trace");
    }

    private static boolean isImport(String text, int at) {
        int lineStart = text.lastIndexOf('\n', at) + 1;
        return text.startsWith("import", lineStart);
    }

    private static List<Path> sources() throws IOException {
        Path root = RepositoryRoot.find().resolve("limn-backend-lwjgl/src/main/java/limn/backend/lwjgl");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }
}
