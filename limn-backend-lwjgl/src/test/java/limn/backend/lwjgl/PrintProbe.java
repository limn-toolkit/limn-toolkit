package limn.backend.lwjgl;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.spi.PrintJob;
import limn.backend.spi.WindowPeer;
import limn.components.Button;
import limn.components.Label;
import limn.concurrent.Ui;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;

/**
 * A window that prints through its own printer (ADR 078 §12, items 1 to 3): the system's print
 * dialog, owned by a Limn window, over a small PDF of {@link ProbePdf}. It is run on a guest, where a
 * person or an injected key answers the dialog, and it prints what each answer was.
 *
 * <p>Not a test: it asserts nothing and waits for the dialog. System properties:
 * {@code probe.pages} (1), {@code probe.rows} (0, rows of a table on each page),
 * {@code probe.paper} ({@code a4} or {@code letter}), {@code probe.file} (print that PDF instead,
 * paper unknown), {@code probe.prints} (how many prints one after the other, 1),
 * {@code probe.delayMs} (before the first, 3000), {@code probe.exitMs} (after the last answer, 3000),
 * {@code probe.write} (also write the PDF to that path), {@code probe.hidden} (never show the window,
 * which on macOS takes the panel that is not a sheet).
 *
 * <pre>
 * java -jar limn-print-probe.jar
 * </pre>
 */
public final class PrintProbe {

    private PrintProbe() {
    }

    /**
     * @param args unused
     * @throws IOException when {@code probe.file} or {@code probe.write} cannot be used
     */
    public static void main(String[] args) throws IOException {
        int pages = Integer.getInteger("probe.pages", 1);
        int rows = Integer.getInteger("probe.rows", 0);
        boolean letter = "letter".equals(System.getProperty("probe.paper", "a4"));
        float width = letter ? 612 : 595.28f;
        float height = letter ? 792 : 841.89f;
        String file = System.getProperty("probe.file");
        byte[] pdf;
        PrintJob job;
        if (file != null) {
            pdf = Files.readAllBytes(Path.of(file));
            job = new PrintJob("Limn print probe", pdf, 0, 0, -1);
        } else {
            pdf = ProbePdf.write(pages, width, height, rows);
            job = new PrintJob("Limn print probe", pdf, width, height, pages);
        }
        String write = System.getProperty("probe.write");
        if (write != null) {
            Files.write(Path.of(write), pdf);
        }
        int prints = Integer.getInteger("probe.prints", 1);
        long delay = Long.getLong("probe.delayMs", 3_000);
        long exit = Long.getLong("probe.exitMs", 3_000);
        log("pdf " + pdf.length + " bytes, " + (file != null ? file : pages + " page(s) " + width + "x" + height));

        try (Backend backend = new LwjglBackend()) {
            boolean hidden = Boolean.getBoolean("probe.hidden");
            NativeWindow window = backend.createWindow(WindowConfig.of("Limn print probe", 520, 300).visible(!hidden));
            int[] asked = {0};
            Runnable[] print = new Runnable[1];
            Label status = new Label("Waiting to print.");
            print[0] = () -> {
                int n = ++asked[0];
                log("print " + n + " asked; available=" + window.printer().isAvailable());
                status.setText("Print " + n + " asked.");
                window.printer().print(job).thenAccept(result -> {
                    log("print " + n + " -> " + result + " (on the UI thread: " + Ui.isUiThread() + ")");
                    status.setText("Print " + n + ": " + result);
                    if (n < prints) {
                        Ui.postDelayed(print[0], 1_500);
                    } else {
                        Ui.postDelayed(window::close, exit);
                    }
                });
            };
            Column page = new Column();
            page.gap(12);
            page.add(new Label("Limn print probe: " + pdf.length + " bytes of PDF"));
            page.add(status);
            page.add(new Button("Print").onAction(print[0]));
            Scene scene = new Scene(new Padding(Insets.all(24), page));
            scene.bind(window);
            WindowPeer.of(window).setFrameCallback((renderer, frame) -> scene.renderFrame(renderer.canvas(), frame));
            if (!hidden) {
                window.show();
            }
            Ui.postDelayed(() -> {
                if (!hidden) {
                    window.focus();
                }
                print[0].run();
            }, delay);
            backend.runEventLoop();
            log("DONE");
        }
    }

    private static void log(String line) {
        System.out.println(LocalTime.now() + " " + line);
        System.out.flush();
    }
}
