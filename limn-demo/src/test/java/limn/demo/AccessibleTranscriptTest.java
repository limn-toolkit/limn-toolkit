package limn.demo;

import limn.components.Dialog;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.demo.a11y.Goldens;
import limn.demo.a11y.HeadlessBackend;
import limn.demo.a11y.HeadlessWindow;
import limn.demo.a11y.Transcript;
import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What three of the demo's scenes sound like, compared against a transcript somebody has read
 * aloud.
 *
 * <p>The gallery-wide invariants — no unknown role, every focusable node named — pass on a tree
 * that is uniformly wrong in a way no invariant names, because nobody had said what the demo
 * <em>should</em> sound like. These transcripts are that reference answer, for the three scenes
 * a reviewer chose: the form, the component sheet, and the kitchen sink with its dialog open as
 * a window of its own, which is two trees. They are built the way {@code Main --scene} builds
 * them, bound to a window with no platform behind it, settled over the same number of frames
 * the gallery capture settles over, and read in tree order.
 *
 * <p>A transcript that changes fails here, on purpose, until somebody rewrites it with
 * {@code -Dlimn.a11y.transcripts.update=true} and reads the difference. That is the whole of the
 * mechanism: a widget that starts describing itself differently is a change to what a user
 * hears, and it is reviewed like one.
 */
class AccessibleTranscriptTest {

    /** The demo window, in logical points: what {@code Main} opens. */
    private static final int WIDTH = 800;
    private static final int HEIGHT = 640;

    /**
     * Frames a scene renders before it is read, at a fixed step of scene time each. The same
     * number the gallery capture warms up with, for the same reason: the longest transition in
     * the theme is shorter than this, so what is read is what has settled.
     */
    private static final int SETTLE_FRAMES = 24;
    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    /**
     * Decodes every image into the same sixteen transparent points. The kitchen sink loads its
     * icons and logo while it builds, decoding is the backend's, and nothing in a transcript
     * depends on a pixel: what an image is called is what a reader hears.
     */
    private static final ImageDecoder DECODER = bytes -> new Image(16, 16, new byte[16 * 16 * 4]);

    private ExecutorService workers;
    private UiRuntime runtime;
    private long nanos;
    private HeadlessBackend backend;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(() -> nanos, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        // What the gallery capture does before every shot: the transcript is in the language the
        // reference was read in, whatever the machine running this happens to speak.
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
        Images.installDecoder(DECODER);
        backend = new HeadlessBackend(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        Images.uninstallDecoder(DECODER);
        TextRulers.uninstall(HeadlessWindow.RULER);
        Theme.setCurrent(Theme.dark());
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    @Test
    void theFormSoundsLikeItsTranscript() {
        FormsScene.Built built = FormsScene.create(false);
        HeadlessWindow window = show("Limn UI: forms", built.scene());
        Goldens.check("forms", transcript(window));
    }

    @Test
    void theComponentSheetSoundsLikeItsTranscript() {
        Scene scene = ComponentsScene.create(false);
        HeadlessWindow window = show("Limn UI: components", scene);
        Goldens.check("components", transcript(window));
    }

    /**
     * The kitchen sink with its window-modal dialog open: the dialog is a window of its own with
     * a tree of its own, and the host is the window it blocks, so both are read, host first.
     */
    @Test
    void theKitchenSinkWithItsDialogOpenSoundsLikeItsTranscript() {
        KitchenSinkScene.Built built = KitchenSinkScene.create(false);
        HeadlessWindow host = show("Limn kitchen sink", built.scene());

        Dialog dialog = built.openDialog().get();
        assertNotNull(dialog.modalWindow(), "the demo's default dialog is a window of its own");
        assertEquals(2, backend.windows().size(), "the host and the dialog, and nothing else");
        HeadlessWindow modal = backend.windows().get(1);
        // The dialog asks for the keyboard when it shows, so the desktop moves it: the host is
        // no longer the active window, and the dialog is.
        host.desktopFocus(false);
        modal.desktopFocus(true);
        settle(host, modal);

        Goldens.check("kitchen-dialog", transcript(host) + transcript(modal));
    }

    private HeadlessWindow show(String title, Scene scene) {
        HeadlessWindow window = backend.open(title, WIDTH, HEIGHT);
        scene.bind(window);
        window.frame();
        window.desktopFocus(true);
        settle(window);
        return window;
    }

    /** Renders every window in turn, one frame of scene time apart, until everything settled. */
    private void settle(HeadlessWindow... windows) {
        for (int i = 0; i < SETTLE_FRAMES; i++) {
            nanos += FRAME_NANOS;
            runtime.drain();
            for (HeadlessWindow window : windows) {
                window.frame();
            }
        }
    }

    private static String transcript(HeadlessWindow window) {
        StringBuilder out = new StringBuilder("== window \"").append(window.title()).append('"');
        if (window.isModal()) {
            out.append(" (modal)");
        }
        if (window.isModalBlocked()) {
            out.append(" (blocked by a modal)");
        }
        out.append(" ==\n");
        return out.append(Transcript.of(window.bridge().tree())).toString();
    }
}
