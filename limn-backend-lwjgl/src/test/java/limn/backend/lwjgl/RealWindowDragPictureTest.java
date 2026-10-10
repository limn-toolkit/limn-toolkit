package limn.backend.lwjgl;

import limn.backend.NativeWindow;
import limn.backend.Platform;
import limn.backend.WindowConfig;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.DragStartEvent;
import limn.transfer.DataFormat;
import limn.transfer.Drag;
import limn.transfer.Transfer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picture a real window hands the system for a drag: the dragged thing, painted, and not an
 * empty picture or none. {@code DragPictureTest} reads the same path against a bare renderer;
 * this one goes through the window, its frames and its own framebuffer, in a process of its own
 * since a backend cannot share one with the other GL tests' context.
 */
class RealWindowDragPictureTest {

    private static final int NO_DISPLAY = 77;
    /**
     * What the child exits with when its window was never painted after the drag began. A window
     * another program's full-screen window covers is given no frames (the loop does not paint what
     * nobody can see), and the picture of a drag is taken from the next frame: on a desktop where
     * the developer has something full screen over it, there is nothing here to test.
     */
    private static final int NO_FRAME = 78;

    @TempDir
    Path directory;

    @Test
    void aRealWindowHandsTheSystemAPictureWithTheDraggedThingInIt() throws Exception {
        Assumptions.assumeTrue(Platform.current().isMacOs(), "the system's drag is built on macOS so far");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-XstartOnFirstThread");
        command.add("-Dlimn.drag.trace=true");
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
        Assumptions.assumeFalse(child.exitValue() == NO_DISPLAY, () -> "no window can be opened here:\n" + output);
        Assumptions.assumeFalse(child.exitValue() == NO_FRAME,
                () -> "the window was given no frame after the drag began (is it covered?):\n" + output);
        java.util.regex.Matcher asked = java.util.regex.Pattern
                .compile("limn\\.drag: asked, picture=(\\d+)x(\\d+) px, (\\d+) not transparent").matcher(output);
        assertTrue(asked.find(), "the window was asked to begin a drag with a picture:\n" + output);
        int total = Integer.parseInt(asked.group(1)) * Integer.parseInt(asked.group(2));
        int seen = Integer.parseInt(asked.group(3));
        assertTrue(seen > 0, "the dragged thing is in the picture:\n" + output);
        assertTrue(seen < total, "and what it did not paint is see-through:\n" + output);
    }

    /** A window whose one widget drags a square painted on nothing. */
    static final class Child {

        private static final class Source extends Widget<Source> {
            @Override
            protected Size onMeasure(Constraints constraints) {
                return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
            }

            @Override
            protected void onPaint(Canvas canvas) {
                canvas.fillRect(0, 0, width(), height(), Color.rgb(0x22CC44));
            }

            @Override
            protected Drag onDragStart(DragStartEvent event) {
                return Drag.of(Transfer.of(DataFormat.TEXT, "square"))
                        // A card as a list paints one: a rounded panel with a border, and text on it.
                        .preview(120, 28, 20, 14, canvas -> {
                            canvas.fillRoundRectWithBorder(0, 0, 120, 28, 6, Color.rgb(0xFFFFFF), 1,
                                    Color.rgb(0x808080));
                            canvas.drawText("Invoice 1042", 10, 18, limn.components.Theme.current().body(),
                                    Color.rgb(0x000000));
                        });
            }
        }

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
                // Shown but not seen: transparent and taking no focus.
                NativeWindow window = backend.createWindow(WindowConfig.of("drag", 200, 120).focusOnShow(false));
                window.setOpacity(0f);
                Scene scene = new Scene(new Source());
                scene.bind(window);
                // Painted from a frame callback of the application's own, as the demo paints its
                // scenes: the scene is handed a canvas and nothing else. The picture was missing
                // exactly there while it was read through the renderer of the scene's own callback.
                int[] framesAfterTheDrag = {-1};
                limn.backend.spi.WindowPeer.of(window).setFrameCallback((renderer, frame) -> {
                    if (framesAfterTheDrag[0] >= 0) {
                        framesAfterTheDrag[0]++;
                    }
                    scene.renderFrame(renderer.canvas(), frame);
                });
                window.show();
                Ui.postDelayed(() -> {
                    drive(scene).mouseMoved(50, 50);
                    drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 50, 50, 1);
                    drive(scene).inputBatchEnded();
                    framesAfterTheDrag[0] = 0;
                    drive(scene).mouseMoved(80, 50);
                    drive(scene).inputBatchEnded();
                }, 400);
                Ui.postDelayed(window::requestClose, 1200);
                backend.runEventLoop();
                if (framesAfterTheDrag[0] <= 0) {
                    System.out.println("no frame after the drag began");
                    System.exit(NO_FRAME);
                }
            }
            System.out.println("loop ended");
        }
    }
}
