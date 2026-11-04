package limn.backend.lwjgl;

import limn.backend.WindowConfig;
import limn.graphics.BlendMode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Gradient;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.print.Document;
import limn.print.Paper;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A program, not a test: what a server does with the toolkit, run in a process of its own by
 * {@link HeadlessBackendTest} and on the guests by hand. It starts the backend and opens no window,
 * draws a picture, the first text it draws being in scripts whose faces load in the background, and
 * writes a document whose layer an eraser crosses, then prints one line per
 * finding, and {@code OK} when every one held. With no argument the backend is expected to have
 * started without a display, and to refuse a window; with {@code display}, to have one, and to draw
 * all the same with no window open.
 */
public final class HeadlessDrawing {

    private HeadlessDrawing() {
    }

    /** A red card with a round hole erased out of it, inside a layer of its own. */
    private static final class Erased extends Widget<Erased> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(80, 60);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.saveLayer(0, 0, 80, 60, 1);
            canvas.fillRect(0, 0, 80, 60, Color.rgb(0xCC2020));
            canvas.setBlendMode(BlendMode.DESTINATION_OUT);
            canvas.fillCircle(40, 30, 20, Color.BLACK);
            canvas.restore();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("idle")) {
            idle();
            return;
        }
        boolean ok = true;
        boolean display = args.length > 0 && args[0].equals("display");
        LwjglBackend backend = new LwjglBackend("Headless drawing");
        try {
            System.out.println("canOpenWindows " + backend.canOpenWindows());
            ok &= backend.canOpenWindows() == display;
            if (!display) {
                try {
                    backend.createWindow(WindowConfig.of("never", 100, 100));
                    System.out.println("a window was opened");
                    ok = false;
                } catch (IllegalStateException refused) {
                    System.out.println("window refused: " + refused.getMessage());
                }
            }
            // The first text of the process, in scripts the default face lacks: their faces start
            // loading only now, and the picture must not keep the boxes drawn while they load.
            limn.graphics.Font font = limn.graphics.Font.of(20);
            java.util.function.Consumer<Canvas> words = c -> c.drawText("\u0645\u0631\u062D\u0628\u0627 \u65E5\u672C",
                    4, 24, font, Color.BLACK);
            Image first = Images.render(140, 32, 1, words);
            Image image = Images.render(40, 30, 2, c -> {
                c.fillCircle(20, 15, 10, Gradient.linear(10, 0, 30, 0, Color.rgb(0xCC2020), Color.rgb(0xCC2020)));
                c.setBlendMode(BlendMode.DESTINATION_OUT);
                c.fillCircle(20, 15, 4, Color.BLACK);
            });
            byte[] p = image.pixels();
            int disc = (30 * 80 + 26) * 4;
            int hole = (30 * 80 + 40) * 4;
            System.out.println("image " + image.width() + "x" + image.height() + ", disc " + (p[disc] & 0xff) + "/"
                    + (p[disc + 3] & 0xff) + ", hole alpha " + (p[hole + 3] & 0xff));
            ok &= image.width() == 80 && (p[disc] & 0xff) == 0xCC && (p[disc + 3] & 0xff) == 255
                    && (p[hole + 3] & 0xff) == 0;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            AtomicReference<Object> done = new AtomicReference<>();
            backend.setLoopLifetime(limn.backend.LoopLifetime.UNTIL_QUIT);
            Document.of(Paper.A4).title("Headless").add(new Erased()).writePdf(bytes)
                    .onSuccess(v -> {
                        done.set(v);
                        backend.stop();
                    })
                    .onFailure(e -> {
                        done.set(e);
                        backend.stop();
                    })
                    .start();
            backend.runEventLoop();
            String pdf = bytes.toString(StandardCharsets.ISO_8859_1);
            boolean picture = pdf.contains("/Subtype/Image");
            System.out.println("pdf " + bytes.size() + " bytes, " + (done.get() instanceof Throwable t ? "failed: " + t
                    : "written") + ", the erased layer " + (picture ? "as a picture" : "NOT as a picture"));
            ok &= !(done.get() instanceof Throwable) && picture;
            // The loop has run since, and the document waited for every face still loading: the
            // same words drawn now are drawn with the faces in place.
            Image settled = Images.render(140, 32, 1, words);
            boolean same = Arrays.equals(first.pixels(), settled.pixels());
            System.out.println("the first text picture " + (same ? "matches" : "DIFFERS FROM")
                    + " one drawn after the fonts settled");
            ok &= same;
        } finally {
            backend.close();
        }
        System.out.println(ok ? "OK" : "FAILED");
        // A success returns from main, as a server's or a batch job's process ends: the UI thread
        // then ends before the process does, and anything a native library left with it runs.
        // System.exit would end the process first, and once hid a crash at that thread's end.
        if (!ok) {
            System.exit(1);
        }
    }

    /**
     * The loop with nothing to do, as a server's between requests: it must wait, not turn, and
     * still wake for a task posted from another thread and for a delayed one.
     */
    private static void idle() throws Exception {
        LwjglBackend backend = new LwjglBackend("Headless idle");
        java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
        long[] woken = new long[2];
        long[] posted = new long[2];
        try {
            backend.setLoopLifetime(limn.backend.LoopLifetime.UNTIL_QUIT);
            Thread other = new Thread(() -> {
                try {
                    Thread.sleep(1500);
                    posted[0] = System.nanoTime();
                    limn.concurrent.Ui.post(() -> {
                        woken[0] = System.nanoTime();
                        posted[1] = System.nanoTime();
                        limn.concurrent.Ui.postDelayed(() -> {
                            woken[1] = System.nanoTime();
                            backend.stop();
                        }, 300);
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "poster");
            other.start();
            long cpuBefore = threads.getCurrentThreadCpuTime();
            long wallBefore = System.nanoTime();
            backend.runEventLoop();
            long cpu = threads.getCurrentThreadCpuTime() - cpuBefore;
            long wall = System.nanoTime() - wallBefore;
            other.join();
            long postLatency = (woken[0] - posted[0]) / 1_000_000;
            long delayed = (woken[1] - posted[1]) / 1_000_000;
            System.out.println("idle loop: " + cpu / 1_000_000 + " ms of processor in " + wall / 1_000_000
                    + " ms; a post from another thread ran after " + postLatency + " ms, a task delayed 300 ms after "
                    + delayed + " ms");
            boolean ok = cpu < wall / 10 && woken[0] != 0 && postLatency < 250 && delayed >= 290 && delayed < 800;
            System.out.println(ok ? "OK" : "FAILED");
            if (!ok) {
                System.exit(1);
            }
        } finally {
            backend.close();
        }
    }
}
