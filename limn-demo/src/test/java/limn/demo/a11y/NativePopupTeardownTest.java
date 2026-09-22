package limn.demo.a11y;

import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.components.Button;
import limn.components.ComboBox;
import limn.components.Dialog;
import limn.components.Menu;
import limn.components.PopupMenu;
import limn.components.Theme;
import limn.concurrent.UiRuntime;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A surface closed in a window of its own goes, in the time its opener's clock says the fade takes
 * (2026-09-15).
 *
 * <p>What was wrong. Every such surface leaves through {@code Scene#fadeWindowOut}, whose last
 * frame is what destroys the window, and that fade is a <b>real-time</b> ticker: it must be, because
 * a paused application would otherwise be left with a half-closed popup nobody can dismiss. The
 * scene that owns it, though, was built by the toolkit with no clock — the wall clock — while the
 * scene that opened it ran on whatever a test injected. So a headless harness that steps its clock
 * by 20 ms a frame and renders as fast as the machine can advanced that fade by the microseconds
 * the frames really took: {@code VerbPolicyRatchetTest} pressed a menu row and cancelled a cascade,
 * both reached the widget, and four hundred frames later — eight seconds of scene time, fifty fades
 * — the popup window was still open with the same tree. It was carried as two
 * {@code PERFORMED_UNSEEN} exemptions until the clock was passed along ({@code Scene#clock}).
 *
 * <p>What is held here. One frame after the close the cascade is still there, which is what makes
 * the rest a fade and not an instant teardown; by the ninth it is gone — {@code Theme.animWindow}
 * (0.16 s) over a 20 ms step is eight frames, and the destroy runs on the frame after the last —
 * and the window is closed, unregistered from its parent and publishing nothing. In this module
 * rather than the toolkit's own tests for the reason the other native-popup cases are: the
 * toolkit's stub window cannot open a second window.
 */
class NativePopupTeardownTest {

    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    /** {@code Theme.animWindow} (0.16 s) over a 20 ms frame, and one more for the destroy. */
    private static final int FRAMES_OF_THE_FADE = 9;

    private HeadlessUi ui;
    private UiRuntime runtime;
    private long nanos;
    private HeadlessBackend backend;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> nanos);
        runtime = ui.runtime();
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
        backend = new HeadlessBackend(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        TextRulers.uninstall(HeadlessWindow.RULER);
        ui.close();
    }

    @Test
    void aCascadeClosedInAWindowOfItsOwnIsGoneWhenItsOpenersClockSaysTheFadeIsOver() {
        Column root = new Column();
        Button anchor = new Button("Options");
        root.add(anchor);
        HeadlessWindow host = backend.open("Limn popups", 400, 300);
        new Scene(root, () -> nanos).bind(host);
        host.frame();
        host.desktopFocus(true);
        settle(host);

        Menu menu = new Menu();
        menu.addItem("Cut", () -> { });
        menu.addItem("Copy", () -> { });
        PopupMenu popupMenu = new PopupMenu(menu);
        popupMenu.showAnchored(anchor, anchor.localToSceneX(), anchor.localToSceneY(),
                anchor.width(), anchor.height());
        runtime.drain(); // the window is created from a posted task
        assertEquals(2, backend.windows().size(), "the host and the cascade");
        HeadlessWindow popup = backend.windows().get(1);
        settle(host, popup);
        assertTrue(popupMenu.isOpen());
        assertTrue(popup.bridge().tree().nodeCount() > 1, "the cascade publishes its rows: "
                + Transcript.of(popup.bridge().tree()));

        popupMenu.close();
        frame(host, popup);
        assertFalse(popup.isClosed(), "one frame in, the window is still there and fading: "
                + "without a fade to sample there would be no defect to find, and no fade-out "
                + "frame for the verb ratchet to hold either");

        settle(host, popup);
        assertTrue(popup.isClosed(), "the fade ended and its last frame destroyed the window; "
                + "before the opener's clock was passed to the cascade's own scene, the fade ran "
                + "on the wall clock and this window stayed open for ever");
        assertFalse(host.isClosed(), "and only that window went");
    }

    /**
     * The same, frame by frame, so the number of frames it takes is measured and not settled into:
     * the window is there through the fade and gone on the frame after it.
     */
    @Test
    void theCascadeGoesOnTheFrameAfterTheFade() {
        Column root = new Column();
        Button anchor = new Button("Options");
        root.add(anchor);
        HeadlessWindow host = backend.open("Limn popups", 400, 300);
        new Scene(root, () -> nanos).bind(host);
        host.frame();
        host.desktopFocus(true);
        settle(host);

        Menu menu = new Menu();
        menu.addItem("Cut", () -> { });
        PopupMenu popupMenu = new PopupMenu(menu);
        popupMenu.showAnchored(anchor, anchor.localToSceneX(), anchor.localToSceneY(),
                anchor.width(), anchor.height());
        runtime.drain();
        HeadlessWindow popup = backend.windows().get(1);
        settle(host, popup);

        popupMenu.close();
        int frames = 0;
        while (!popup.isClosed() && frames < 4 * FRAMES_OF_THE_FADE) {
            frame(host, popup);
            frames++;
        }
        assertEquals(FRAMES_OF_THE_FADE, frames, "Theme.animWindow is "
                + Theme.current().animWindow + " s and a frame is 20 ms, so the fade is eight "
                + "frames and the ninth is the one that destroys the window");
    }

    /**
     * The same for a combo's list, which is the second of the four surfaces that open in a window
     * of their own, so the rule is held on more than the one place it was found in.
     */
    @Test
    void aClosedListInAWindowOfItsOwnGoesToo() {
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three"));
        Column root = new Column();
        root.add(combo);
        HeadlessWindow host = backend.open("Limn popups", 400, 300);
        new Scene(root, () -> nanos).bind(host);
        host.frame();
        host.desktopFocus(true);
        settle(host);

        combo.open();
        runtime.drain();
        assertEquals(2, backend.windows().size(), "the host and the list");
        HeadlessWindow popup = backend.windows().get(1);
        settle(host, popup);

        combo.close();
        frame(host, popup);
        assertFalse(popup.isClosed(), "one frame in, the list is still there and fading");
        settle(host, popup);
        assertTrue(popup.isClosed(), Transcript.of(popup.bridge().tree()));
    }

    /**
     * And for a dialog's card, the third: there the fade's last frame does more than destroy a
     * window — it completes the future the caller is waiting on — so a fade that never ends leaves
     * an application waiting for ever, which is the case {@code Scene#addRealTimeTicker}'s javadoc
     * names as the hard reason those tickers exist.
     */
    @Test
    void aDismissedDialogInAWindowOfItsOwnGoesAndItsResultArrives() {
        Column root = new Column();
        Button opener = new Button("Discard draft…");
        root.add(opener);
        HeadlessWindow host = backend.open("Limn dialogs", 400, 300);
        Scene scene = new Scene(root, () -> nanos);
        scene.bind(host);
        host.frame();
        host.desktopFocus(true);
        settle(host);

        Dialog dialog = new Dialog("Discard the draft?", "This cannot be undone.")
                .addButton("Keep", "keep")
                .addPrimaryButton("Discard", "discard");
        List<String> answered = new ArrayList<>();
        dialog.show(scene).thenAccept(answered::add);
        runtime.drain();
        assertEquals(2, backend.windows().size(), "the host and the dialog");
        HeadlessWindow modal = backend.windows().get(1);
        settle(host, modal);

        dialog.dismiss("discard");
        frame(host, modal);
        assertFalse(modal.isClosed(), "one frame in, the card is still there and fading");
        settle(host, modal);
        assertTrue(modal.isClosed(), Transcript.of(modal.bridge().tree()));
        assertEquals(List.of("discard"), answered, "and the caller was told, which is what the "
                + "fade's last frame does here besides destroying the window");
    }

    private void frame(HeadlessWindow... windows) {
        nanos += FRAME_NANOS;
        runtime.drain();
        for (HeadlessWindow window : windows) {
            window.frame();
        }
    }

    private void settle(HeadlessWindow... windows) {
        for (int i = 0; i < 24; i++) {
            frame(windows);
        }
    }
}
