package limn.print;

import limn.backend.NativeWindow;
import limn.backend.PrintResult;
import limn.backend.spi.PrintJob;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.concurrent.Work;
import limn.print.internal.PdfWriter;
import limn.print.internal.Recording;
import limn.scene.Scene;
import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * What a business application prints or saves as PDF: widgets the toolkit cuts into pages.
 *
 * <pre>{@code
 * Document order = Document.of(Paper.A4)
 *         .marginsMm(18, 15, 18, 15)
 *         .title("Order 4512")
 *         .header(page -> new Label("ACME Distribution"))
 *         .footer(page -> new Label(page.number() + " / " + page.count()))
 *         .add(customerCard)
 *         .add(itemsTable)                  // cut between rows, its header on every page
 *         .keepTogether(totalsBox)          // moved whole to the next page if it does not fit
 *         .pageBreak()
 *         .add(new DonutChart(byCategory));
 *
 * order.writePdf(file).onSuccess(saved -> window.launcher().openFile(saved)).start();
 * order.print(window);
 * }</pre>
 *
 * <p><b>The widgets are the document's.</b> An application builds them for the document, the same
 * components it shows on screen, over the same data, and does not lend it widgets that are in a
 * window: a widget belongs to one scene, and a document paints each block in a scene of its own that
 * no window shows. In that scene nothing has focus, nothing is hovered, nothing scrolls, every
 * animation is at its end, and the palette is the document's.
 *
 * <p><b>The page.</b> A document unit is 1/96 inch, the convention a browser prints with: a body
 * label of 13 units prints at 9.75 points. Every block takes the width of the content box (the paper
 * less its margins) and as much height as it measures to; a block that does not fit in what is left
 * of a page is cut where it can be cut (between the lines of a label, between the children of a
 * column or the rows of a grid, between the rows of a list, a table or a tree), or moved whole to
 * the next page, or, taller than a page and with nowhere to cut, sliced at the page's height.
 * A list, a table or a tree is cut between rows only as a block of its own: every row is printed,
 * a page's worth realized at a time, and a table's header is on every page. Inside another block it
 * prints the box it measures to.
 *
 * <p><b>The smallest content box.</b> What the margins, the header and the footer leave of a page
 * is at least a quarter inch (24 units, 18 points) each way, room for a line of body text: a
 * document that leaves less, a letterhead and a disclaimer filling a small label, or the default
 * three-quarter-inch margins on paper less than an inch and three quarters across or down, is
 * refused with an
 * {@link IllegalStateException} that names the paper, the margins and the header's and the
 * footer's heights, when its pages are recorded. The header and the footer are measured then,
 * since they are made per page.
 *
 * <p><b>What a page holds that a PDF cannot.</b> A soft shadow is a picture at the document's raster
 * resolution ({@link #rasterDpi}, 300 by default), as is an icon; a backdrop effect is its tint; a
 * video or a 3D view is left out, with a warning.
 *
 * <p>UI thread only: a document holds widgets. Its pages are recorded on the UI thread when
 * {@link #writePdf} or {@link #print} is called or a {@link PrintPreview} asks for them, and again
 * once a face the text asked for has loaded, or after ten seconds without it, with a warning; the
 * PDF's bytes are written on a worker. A document
 * can be written, printed and previewed any number of times; each
 * records its pages afresh, so what the widgets show at that moment is what is printed.
 */
public final class Document {

    private static final System.Logger LOG = System.getLogger(Document.class.getName());

    /**
     * How long a PDF, a print or a preview waits for a face its text asked for that is still
     * loading, before it records the pages with the faces it has: ten seconds. A script's fallback
     * faces load in the background in well under that from the toolkit's own font files; a load
     * still running after it is stuck (a worker pool kept busy by something else, a font on a drive
     * that stopped answering), and a print that waited on it for ever neither showed its dialog nor
     * completed, and a PDF's work held a worker until the process ended. When the wait runs out the
     * pages are recorded once more, so a face that has loaded meanwhile is used, a warning says so,
     * and text in a face still missing prints as boxes.
     */
    static final long FACE_WAIT_MILLIS = 10_000;

    /** One entry of the flow: a widget, or a page break alone. */
    private record Entry(@Nullable Widget<?> widget, boolean keepTogether, boolean breakBefore) {
    }

    private final Paper paper;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Widget<?>, Pages.Block> blocks = new IdentityHashMap<>();
    private float[] margins = {54, 54, 54, 54};
    private String title = "Document";
    private @Nullable Function<PageInfo, Widget<?>> header;
    private @Nullable Function<PageInfo, Widget<?>> footer;
    private @Nullable Theme theme;
    private @Nullable Locale locale;
    private int rasterDpi = 300;
    private boolean pendingBreak;

    private Document(Paper paper) {
        this.paper = Objects.requireNonNull(paper, "paper");
    }

    /**
     * @param paper the paper every page is laid out on
     * @return an empty document, with margins of three quarters of an inch
     */
    public static Document of(Paper paper) {
        Ui.checkUiThread();
        return new Document(paper);
    }

    /** @return an empty document on the paper of the default locale's region ({@link Paper#forLocale}) */
    public static Document of() {
        return of(Paper.forLocale(Locale.getDefault()));
    }

    // ------------------------------------------------------------------ the page

    /**
     * Sets the margins, in millimetres.
     *
     * @return this document
     */
    public Document marginsMm(float top, float right, float bottom, float left) {
        float mm = 72f / 25.4f;
        return marginsPt(top * mm, right * mm, bottom * mm, left * mm);
    }

    /**
     * Sets the margins, in points (1/72 inch).
     *
     * @return this document
     */
    public Document marginsPt(float top, float right, float bottom, float left) {
        Ui.checkUiThread();
        float[] asked = {top, right, bottom, left};
        for (float margin : asked) {
            if (!(margin >= 0) || !Float.isFinite(margin)) {
                throw new IllegalArgumentException("a margin is zero or more: " + margin);
            }
        }
        if (top + bottom >= paper.height() || left + right >= paper.width()) {
            throw new IllegalArgumentException("the margins leave no room on " + paper.name());
        }
        margins = asked;
        return this;
    }

    /**
     * Sets the title: the PDF's title and the name of the print job.
     *
     * @param title never blank
     * @return this document
     */
    public Document title(String title) {
        Ui.checkUiThread();
        if (Objects.requireNonNull(title, "title").isBlank()) {
            throw new IllegalArgumentException("a document's title is not blank");
        }
        this.title = title;
        return this;
    }

    /**
     * Sets what is drawn at the top of every page: a new widget for each page, made when the page is
     * drawn, since a page's number is known only once the document is cut, and its count only once it
     * is cut to the end. The tallest of them is the room every page keeps for it: the document is cut
     * again each time the count makes one taller, up to three times, and one taller still after that
     * is drawn in the room the last cut kept, cut at its foot, with a warning; the content never runs
     * under it.
     *
     * @param header makes a page's header, or null for none
     * @return this document
     */
    public Document header(@Nullable Function<PageInfo, Widget<?>> header) {
        Ui.checkUiThread();
        this.header = header;
        return this;
    }

    /**
     * Sets what is drawn at the foot of every page, as {@link #header} does at its top.
     *
     * @param footer makes a page's footer, or null for none
     * @return this document
     */
    public Document footer(@Nullable Function<PageInfo, Widget<?>> footer) {
        Ui.checkUiThread();
        this.footer = footer;
        return this;
    }

    /**
     * Sets the palette the pages are painted with. By default it is the light palette of the
     * process's palette family ({@link Theme#light()} for {@link Theme#dark()},
     * {@link Theme#limnLight()} for {@link Theme#limn()}, and {@link Theme#light()} for any other dark
     * palette): paper is white, and a dark page spends its toner on the background.
     *
     * @param theme the palette, or null for the default
     * @return this document
     */
    public Document theme(@Nullable Theme theme) {
        Ui.checkUiThread();
        this.theme = theme;
        return this;
    }

    /**
     * Sets the locale the pages are laid out in, which also sets their direction and the PDF's
     * language; by default the process's.
     *
     * @param locale the locale, or null for the process's
     * @return this document
     */
    public Document locale(@Nullable Locale locale) {
        Ui.checkUiThread();
        this.locale = locale;
        return this;
    }

    /**
     * Sets the resolution of what is drawn as a picture: icons, soft shadows.
     *
     * @param dpi dots per inch, from 72 to 1200; 300 by default
     * @return this document
     */
    public Document rasterDpi(int dpi) {
        Ui.checkUiThread();
        if (dpi < 72 || dpi > 1200) {
            throw new IllegalArgumentException("a raster resolution from 72 to 1200 dpi: " + dpi);
        }
        this.rasterDpi = dpi;
        return this;
    }

    // ------------------------------------------------------------------ the flow

    /**
     * Adds a block: it takes the content box's width and the height it measures to, and is cut
     * where it can be cut when it does not fit.
     *
     * @param widget a widget made for the document, in no window and in no other document
     * @return this document
     */
    public Document add(Widget<?> widget) {
        return entry(widget, false);
    }

    /**
     * Adds a block that is never cut: moved whole to the next page when it does not fit in what is
     * left of a page, and sliced only when it is taller than a whole page.
     *
     * @param widget a widget made for the document
     * @return this document
     */
    public Document keepTogether(Widget<?> widget) {
        return entry(widget, true);
    }

    /**
     * Ends the page: the next block begins a new one.
     *
     * @return this document
     */
    public Document pageBreak() {
        Ui.checkUiThread();
        pendingBreak = true;
        return this;
    }

    private Document entry(Widget<?> widget, boolean keep) {
        Ui.checkUiThread();
        Objects.requireNonNull(widget, "widget");
        if (blocks.containsKey(widget)) {
            throw new IllegalArgumentException("a widget is in a document once");
        }
        if (widget.parent() != null || widget.scene() != null) {
            throw new IllegalArgumentException("a document's widget is its own: this one is already in a "
                    + (widget.parent() != null ? "container" : "scene"));
        }
        long[] now = {0};
        Scene scene = new Scene(widget, () -> now[0]);
        Pages.configure(scene, palette(), locale);
        blocks.put(widget, new Pages.Block(widget, keep, pendingBreak, scene, now));
        entries.add(new Entry(widget, keep, pendingBreak));
        pendingBreak = false;
        return this;
    }

    // ------------------------------------------------------------------ reading

    /** @return the paper */
    public Paper paper() {
        return paper;
    }

    /** @return the title */
    public String title() {
        return title;
    }

    /** @return the locale, or null for the process's */
    public @Nullable Locale locale() {
        return locale;
    }

    /** @return the raster resolution, in dots per inch */
    public int rasterDpi() {
        return rasterDpi;
    }

    /** @return the margins in points: top, right, bottom, left */
    float[] marginsPoints() {
        return margins.clone();
    }

    @Nullable Function<PageInfo, Widget<?>> header() {
        return header;
    }

    @Nullable Function<PageInfo, Widget<?>> footer() {
        return footer;
    }

    /**
     * Cuts the document into pages now and says how many there are.
     *
     * @return the number of pages, at least 1
     * @throws IllegalStateException if the margins, the header and the footer leave a content box
     *         smaller than a quarter inch either way (the class says why)
     */
    public int pageCount() {
        return record().pages().size();
    }

    /** The palette the pages are painted with: the one set, or the light one of the process's family. */
    Theme palette() {
        if (theme != null) {
            return theme;
        }
        Theme current = Theme.current();
        if (!current.isDark()) {
            return current;
        }
        return current == Theme.limn() ? Theme.limnLight() : Theme.light();
    }

    /**
     * Records the pages, and records them again once no face the text asked for is still loading,
     * or once {@code waitMillis} have passed and one still is ({@link #FACE_WAIT_MILLIS} says why
     * it is bounded): a script's fallback face arrives in the background on its first need, and a
     * page recorded before it lands holds boxes where that script's text is. UI thread; the UI
     * thread never waits, and {@code then} runs on it, at once when nothing was loading.
     *
     * <p>{@code failed} hears what a recording threw, with the recording made before the face
     * loaded when it is the second that threw, or null when it is the first, which it hears inside
     * this call: the second can fail where the first did not, a header measuring taller in the face
     * that loaded than in the one it was first measured in.
     */
    void recordSettled(long waitMillis, java.util.function.Consumer<Pages.Result> then,
                       java.util.function.BiConsumer<Throwable, Pages.@Nullable Result> failed) {
        Pages.Result first;
        try {
            first = record();
        } catch (RuntimeException | Error error) {
            failed.accept(error, null);
            return;
        }
        CompletableFuture<Void> settled = limn.print.internal.Outlines.current().settled().toCompletableFuture();
        if (settled.isDone()) {
            then.accept(first);
            return;
        }
        // A copy carries the deadline, so the stage the backend shares with every other waiter is
        // left as it is.
        settled.copy().orTimeout(waitMillis, TimeUnit.MILLISECONDS).whenComplete((ignored, error) -> Ui.post(() -> {
            if (unwrap(error) instanceof TimeoutException) {
                warnUnsettled(waitMillis);
            }
            try {
                then.accept(record());
            } catch (RuntimeException | Error again) {
                failed.accept(again, first);
            }
        }));
    }

    /**
     * The pages to write, from a worker: those recorded when the work was asked for, or, when a face
     * the text asked for was still loading then, recorded again on the UI thread once it has loaded
     * or once the worker has waited {@code waitMillis} for it. The worker waits only for the load,
     * which was asked for before the work began, and for a recording that asks no worker for
     * anything, so it never waits on a worker it is keeping busy.
     */
    private Pages.Result settledFromWorker(Pages.Result first, CompletableFuture<Void> settled, long waitMillis)
            throws Exception {
        if (settled.isDone()) {
            return first;
        }
        try {
            settled.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            warnUnsettled(waitMillis);
        }
        CompletableFuture<Pages.Result> recorded = new CompletableFuture<>();
        Ui.post(() -> {
            try {
                recorded.complete(record());
            } catch (RuntimeException | Error failed) {
                recorded.completeExceptionally(failed);
            }
        });
        try {
            return recorded.get();
        } catch (java.util.concurrent.ExecutionException failed) {
            throw failed.getCause() instanceof Exception cause ? cause : failed;
        }
    }

    /** Says once, for the output that waited, that it goes on with the faces it has. */
    private void warnUnsettled(long waitMillis) {
        LOG.log(System.Logger.Level.WARNING, "\"{0}\": a face its text asked for was still loading after {1} ms;"
                + " the pages are recorded with the faces that have loaded, and text in that face prints as"
                + " boxes", title, Long.toString(waitMillis));
    }

    private static @Nullable Throwable unwrap(@Nullable Throwable error) {
        return error instanceof java.util.concurrent.CompletionException wrapped && wrapped.getCause() != null
                ? wrapped.getCause() : error;
    }

    /** Cuts and records every page, on the UI thread. */
    Pages.Result record() {
        Ui.checkUiThread();
        List<Pages.Block> flow = new ArrayList<>(entries.size() + 1);
        for (Entry entry : entries) {
            flow.add(blocks.get(entry.widget()));
        }
        if (pendingBreak) {
            flow.add(new Pages.Block(null, false, true, null, new long[1]));
        }
        Theme palette = palette();
        for (Pages.Block block : blocks.values()) {
            Pages.configure(Objects.requireNonNull(block.scene), palette, locale);
        }
        return new Pages(this, palette).run(flow);
    }

    // ------------------------------------------------------------------ output

    /**
     * Writes the document as a PDF file. The pages are recorded now, on the UI thread, and recorded
     * again once the work runs if a face the text asked for was still loading (a script's fallback
     * face, loaded in the background on its first need), as soon as it has loaded or after ten
     * seconds without it, with a warning; the file is written by the work on a worker, first to a
     * temporary file beside {@code file} and then moved over it, so a failed or cancelled write
     * leaves no half file.
     *
     * <pre>{@code
     * document.writePdf(file)
     *         .onProgress(fraction -> bar.setProgress((float) fraction))
     *         .onSuccess(saved -> window.launcher().showInFolder(saved))
     *         .onFailure(error -> status.setText(error.getMessage()))
     *         .start();
     * }</pre>
     *
     * @param file where to write it
     * @return the work, not started; its value is {@code file}
     * @throws IllegalStateException if the margins, the header and the footer leave a content box
     *         smaller than a quarter inch either way (the class says why); thrown here, as the
     *         pages are recorded, before any work exists
     */
    public Work<Path> writePdf(Path file) {
        return writePdf(file, FACE_WAIT_MILLIS);
    }

    /** {@link #writePdf(Path)} with how long the work waits for a face still loading; for the tests. */
    Work<Path> writePdf(Path file, long faceWaitMillis) {
        Objects.requireNonNull(file, "file");
        Pages.Result first = record();
        CompletableFuture<Void> settled = limn.print.internal.Outlines.current().settled().toCompletableFuture();
        Path target = file.toAbsolutePath().normalize();
        return Ui.work(progress -> {
            Pages.Result pages = settledFromWorker(first, settled, faceWaitMillis);
            Path parent = target.getParent();
            Path temporary = Files.createTempFile(parent == null ? Path.of(".") : parent,
                    target.getFileName().toString(), ".part");
            boolean done = false;
            try {
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    write(pages, out, progress);
                }
                if (progress.isCancelled()) {
                    return target;
                }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                done = true;
                return target;
            } finally {
                if (!done) {
                    Files.deleteIfExists(temporary);
                }
            }
        });
    }

    /**
     * Writes the document as a PDF to a stream, which is not closed. The pages are recorded now, and
     * again once the work runs if a face was still loading, as {@link #writePdf(Path)} says; the
     * bytes are written by the work on a worker.
     *
     * @param out where to write it
     * @return the work, not started
     * @throws IllegalStateException as {@link #writePdf(Path)} does
     */
    public Work<OutputStream> writePdf(OutputStream out) {
        return writePdf(out, FACE_WAIT_MILLIS);
    }

    /** {@link #writePdf(OutputStream)} with how long the work waits for a face still loading; for the tests. */
    Work<OutputStream> writePdf(OutputStream out, long faceWaitMillis) {
        Objects.requireNonNull(out, "out");
        Pages.Result first = record();
        CompletableFuture<Void> settled = limn.print.internal.Outlines.current().settled().toCompletableFuture();
        return Ui.work(progress -> {
            write(settledFromWorker(first, settled, faceWaitMillis), out, progress);
            return out;
        });
    }

    /**
     * Prints the document through the system's own print dialog, owned by {@code window}: the pages
     * are recorded on the UI thread (again once a face the text asked for has loaded, or after ten
     * seconds without it, with a warning), the PDF is written on a worker, and the window's
     * {@link limn.backend.Printer}
     * hands it to the system. The dialog chooses the printer and may save the job as PDF
     * where the system offers it; it scales a page to other paper, never re-flowing it.
     *
     * <p>Pages that cannot be recorded, a content box smaller than a quarter inch among them (the
     * class says why), show no dialog: the answer is {@link PrintResult#FAILED}, and the
     * {@link IllegalStateException} is logged as a warning.
     *
     * @param window the window that owns the dialog
     * @return what became of it, completed on the UI thread
     */
    public CompletionStage<PrintResult> print(NativeWindow window) {
        return print(window, FACE_WAIT_MILLIS);
    }

    /** {@link #print(NativeWindow)} with how long it waits for a face still loading; for the tests. */
    CompletionStage<PrintResult> print(NativeWindow window, long faceWaitMillis) {
        Objects.requireNonNull(window, "window");
        Ui.checkUiThread();
        if (!window.printer().isAvailable()) {
            return CompletableFuture.completedFuture(PrintResult.UNAVAILABLE);
        }
        CompletableFuture<PrintResult> answer = new CompletableFuture<>();
        recordSettled(faceWaitMillis, pages -> Ui.async(() -> {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
                write(pages, bytes, null);
                return bytes.toByteArray();
            } catch (IOException | RuntimeException failed) {
                LOG.log(System.Logger.Level.WARNING, "the document's PDF could not be written", failed);
                return null;
            }
        }).whenComplete((pdf, unwritten) -> {
            if (unwritten != null) {
                // An Error writing it (memory, for a document too large to hold as bytes) failed the
                // stage, and the one the caller holds was never completed.
                LOG.log(System.Logger.Level.WARNING, "the document's PDF could not be written", unwritten);
                answer.complete(PrintResult.FAILED);
                return;
            }
            if (pdf == null) {
                answer.complete(PrintResult.FAILED);
                return;
            }
            limn.backend.internal.PrintAnswers.answerWith(() -> window.printer().print(
                    new PrintJob(title, pdf, paper.width(), paper.height(), pages.pages().size())), answer, title);
        }), (error, before) -> {
            LOG.log(System.Logger.Level.WARNING, "the document's pages could not be recorded", error);
            answer.complete(PrintResult.FAILED);
        });
        return answer;
    }

    /** The pages as recorded, for the preview. */
    List<Recording> pages() {
        return record().pages();
    }

    private void write(Pages.Result pages, OutputStream out, limn.concurrent.@Nullable Progress progress)
            throws IOException {
        Locale language = locale != null ? locale : limn.i18n.I18n.locale();
        PdfWriter writer = new PdfWriter(out, pages.glyphs(), title, language, rasterDpi / 96f);
        List<Recording> recorded = pages.pages();
        for (int i = 0; i < recorded.size(); i++) {
            if (progress != null && progress.isCancelled()) {
                return;
            }
            writer.page(recorded.get(i), pages.widthPt(), pages.heightPt());
            if (progress != null) {
                progress.report((i + 1) / (double) recorded.size());
            }
        }
        writer.finish();
    }
}
