package limn.demo;

import limn.backend.Backend;
import limn.backend.Clipboard;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.components.Label;
import limn.concurrent.Ui;
import limn.graphics.Image;
import limn.graphics.ImageFormat;
import limn.graphics.Images;
import limn.input.Keys;
import limn.scene.Scene;
import limn.transfer.DataFormat;
import limn.transfer.Offer;
import limn.transfer.Transfer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * A lab tool, not a demo: a window that copies one item in every format on C and reads whatever
 * the clipboard holds on V, saying on standard output what it offered and what it got. It is how
 * the native clipboards are measured against another program on each system.
 *
 * <p>{@code java -cp limn-demo-all.jar limn.demo.ClipboardProbe <name> [png]}; it leaves after
 * {@code -Dprobe.ms} (30000). A picture read from the clipboard is saved as {@code <name>-read.png}.
 */
public final class ClipboardProbe {

    private static final DataFormat<String> NOTE = DataFormat.of("application/x-limn-probe-note",
            note -> note.getBytes(StandardCharsets.UTF_8), bytes -> new String(bytes, StandardCharsets.UTF_8));

    private ClipboardProbe() {
    }

    private static void say(String name, String line) {
        System.out.printf("%tT.%<tL [%s] %s%n", System.currentTimeMillis(), name, line);
        System.out.flush();
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .substring(0, 12);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * @param args the probe's name, and optionally a picture to copy
     * @throws Exception when the picture cannot be read
     */
    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "probe";
        try (Backend backend = new LwjglBackend()) {
            Image picture = args.length > 1 ? Images.decode(Files.readAllBytes(Path.of(args[1])))
                    : new Image(2, 2, new byte[] {-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -1, -1, -1, -1});
            NativeWindow window = backend.createWindow(WindowConfig.of(name, 420, 160));
            Scene scene = new Scene(new Label("C copies every format, V reads the clipboard"));
            scene.bind(window);
            Clipboard clipboard = scene.clipboard();
            say(name, "clipboard is " + clipboard.getClass().getSimpleName()
                    + ", picture " + picture.width() + "x" + picture.height()
                    + " pixels sha=" + hash(picture.pixels()));
            clipboard.onChange(() -> say(name, "CHANGED formats=" + clipboard.content().formats()));
            scene.addShortcutHandler(event -> {
                if (!event.isPressed()) {
                    return false;
                }
                if (event.key() == Keys.C) {
                    clipboard.set(Transfer.of(DataFormat.TEXT, "text from " + name)
                            .with(DataFormat.HTML, "<b>html from " + name + "</b>")
                            .withLazy(DataFormat.IMAGE, () -> {
                                say(name, "the picture was asked for");
                                return picture;
                            })
                            .with(DataFormat.FILES, List.of(Path.of("/etc/hostname"), Path.of("/tmp/a b ç.txt")))
                            .with(NOTE, "note from " + name));
                    say(name, "SET");
                    return true;
                }
                if (event.key() == Keys.V) {
                    String now = clipboard.text();
                    say(name, "text()=" + (now.length() > 200 ? now.length() + " chars" : "[" + now + "]"));
                    Offer offer = clipboard.content();
                    say(name, "formats=" + offer.formats());
                    for (DataFormat<?> format : offer.formats()) {
                        offer.read(format).whenComplete((value, failed) -> {
                            if (failed != null) {
                                say(name, "READ " + format + " FAILED " + failed);
                            } else if (value instanceof Image image) {
                                say(name, "READ " + format + " " + image.width() + "x" + image.height()
                                        + " pixels sha=" + hash(image.pixels()));
                                Images.save(image, ImageFormat.PNG, Path.of(name + "-read.png"));
                            } else if (value instanceof String text && text.length() > 200) {
                                say(name, "READ " + format + " " + text.length() + " chars sha="
                                        + hash(text.getBytes(StandardCharsets.UTF_8)));
                            } else {
                                say(name, "READ " + format + " [" + value + "]");
                            }
                        });
                    }
                    return true;
                }
                return false;
            });
            Ui.postDelayed(window::requestClose, Long.getLong("probe.ms", 30000));
            backend.runEventLoop();
            say(name, "exit");
        }
    }
}
