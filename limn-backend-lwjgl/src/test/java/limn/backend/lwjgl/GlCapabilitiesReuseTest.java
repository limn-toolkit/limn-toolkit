package limn.backend.lwjgl;

import limn.backend.NativeWindow;
import limn.backend.Platform;
import limn.backend.WindowConfig;
import limn.backend.spi.WindowPeer;
import limn.concurrent.Ui;
import limn.graphics.Color;
import limn.graphics.Image;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Popups whose contexts are of one kind draw with the GL capabilities the first of them resolved,
 * and draw what they are asked to with them: a clear and a filled rectangle, which between them
 * reach the canvas's shaders, buffers and draw calls through the shared function table.
 *
 * <p>Run in a JVM of its own, for the reasons {@link ClosedWindowTest} gives. The child is also
 * the measurement: run alone, with {@code -Dlimn.backend.reuseGlCapabilities=true} or
 * {@code false} and {@code -Dpopups=20}, it prints what each popup's window cost to create, which
 * is how the Windows guest was read before the default there was chosen.
 */
class GlCapabilitiesReuseTest {

    /** What the child exits with when this machine cannot open a window at all. */
    private static final int NO_DISPLAY = 3;

    @TempDir
    Path directory;

    @Test
    void popupsOfOneKindShareCapabilitiesAndDrawCorrectlyWithThem() throws Exception {
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
        assertEquals(0, child.exitValue(), output);
        boolean reuses = GlCapabilitiesCache.reusesByDefault(Platform.current().os(), null);
        assertTrue(output.contains("popups share capabilities: " + reuses), output);
        assertTrue(output.contains("every popup drew what it was asked to"), output);
        assertTrue(output.contains("kept after the popups closed: " + (reuses ? 1 : 0)), output);
    }

    /**
     * The child: an opaque owner, then popups opened one after another, each painted a colour of
     * its own and read back before the next is made, and their capabilities compared.
     */
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
            int count = Integer.getInteger("popups", 4);
            boolean[] failed = {false};
            try (backend) {
                NativeWindow owner = backend.createWindow(
                        WindowConfig.of("owner", 160, 120).focusOnShow(false));
                owner.setOpacity(0f); // shown, as an owner is, but not over the developer's work
                owner.show();
                List<LwjglWindow> popups = new ArrayList<>();
                List<Double> millis = new ArrayList<>();
                Ui.postDelayed(() -> next(backend, owner, popups, millis, count, failed), 50);
                backend.runEventLoop();
            }
            System.out.println("loop ended");
            System.exit(failed[0] ? 1 : 0);
        }

        private static void next(LwjglBackend backend, NativeWindow owner, List<LwjglWindow> popups,
                List<Double> millis, int count, boolean[] failed) {
            if (popups.size() == count) {
                finish(backend, owner, popups, millis, failed);
                return;
            }
            int index = popups.size();
            long start = System.nanoTime();
            LwjglWindow popup = (LwjglWindow) backend.createWindow(
                    WindowConfig.popup(owner, 80, 60).atInOwner(10, 10));
            millis.add((System.nanoTime() - start) / 1e6);
            popups.add(popup);
            // A colour per popup, so a frame that reached another window's buffer cannot pass.
            Color background = Color.rgb(0x204060 + index * 0x101010);
            Color square = Color.rgb(0xC03020 + index * 0x000810);
            WindowPeer.of(popup).setFrameCallback((renderer, frame) -> {
                renderer.canvas().clear(background);
                renderer.canvas().fillRect(frame.logicalWidth() / 4, frame.logicalHeight() / 4,
                        frame.logicalWidth() / 2, frame.logicalHeight() / 2, square);
            });
            popup.setOpacity(0f);
            popup.show();
            popup.captureNextFrame(image -> {
                String corner = pixel(image, 1, 1);
                String centre = pixel(image, image.width() / 2, image.height() / 2);
                boolean right = corner.equals(hex(background)) && centre.equals(hex(square));
                System.out.println("popup " + index + ": corner " + corner + " centre " + centre
                        + (right ? "" : " WRONG, asked for " + hex(background) + " and " + hex(square)));
                failed[0] |= !right;
                Ui.post(() -> next(backend, owner, popups, millis, count, failed));
            });
        }

        private static void finish(LwjglBackend backend, NativeWindow owner, List<LwjglWindow> popups,
                List<Double> millis, boolean[] failed) {
            boolean shared = true;
            for (LwjglWindow popup : popups) {
                shared &= popup.glCapabilities() == popups.get(0).glCapabilities();
            }
            System.out.println("popups share capabilities: " + shared);
            System.out.println("the owner shares them: "
                    + (((LwjglWindow) owner).glCapabilities() == popups.get(0).glCapabilities()));
            System.out.println("createWindow ms: " + millis);
            List<Double> afterFirst = millis.subList(1, millis.size());
            System.out.printf(Locale.ROOT, "createWindow after the first, mean ms: %.2f%n",
                    afterFirst.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            if (!failed[0]) {
                System.out.println("every popup drew what it was asked to");
            }
            popups.forEach(NativeWindow::requestClose);
            Ui.postDelayed(() -> {
                // The owner's opaque context is of another kind and holds an entry of its own; the
                // popups' entry went with the last of them.
                System.out.println("kept after the popups closed: " + backend.glCapabilities().size());
                owner.requestClose();
            }, 100);
        }

        private static String pixel(Image image, int x, int y) {
            byte[] rgba = image.pixels();
            int at = (y * image.width() + x) * 4;
            return String.format(Locale.ROOT, "%02x%02x%02x%02x", rgba[at] & 0xFF, rgba[at + 1] & 0xFF,
                    rgba[at + 2] & 0xFF, rgba[at + 3] & 0xFF);
        }

        private static String hex(Color color) {
            return color.toHex().substring(1).toLowerCase(Locale.ROOT) + "ff";
        }
    }
}
