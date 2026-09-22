package limn.demo.a11y;

import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.components.ComboBox;
import limn.components.DisplayMode;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A combo box whose list is a window of its own publishes no verb on its options once it is
 * closed, through the frames the window takes to fade out (semantics 5, 2026-09-15).
 *
 * <p>The options' verbs are published and performed on one condition, the list being open
 * (fdd9533), but a window's fade moves only the window's opacity and damages nothing in the panel,
 * so nothing asked the popup's scene to walk again and its tree kept publishing {@code SELECT},
 * {@code PRESS} and {@code FOCUS} for the whole fade. Here rather than in the toolkit's own tests
 * for {@link NativePopupCursorTest}'s reason: the toolkit's stub window cannot open a second window.
 *
 * <p>Deterministic although the popup's scene runs on the wall clock: a fade registered on a scene
 * with no ticker running starts from {@code dt == 0}, so the first frame after the close always
 * draws, and publishes, the list.
 */
class NativePopupFadeOutTest {

    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

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
    void aClosedListInAWindowOfItsOwnOffersNoVerbWhileItFadesOut() {
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three"));
        Column root = new Column();
        root.add(combo);
        HeadlessWindow host = backend.open("Limn popups", 400, 300);
        new Scene(root).bind(host);
        host.frame();
        host.desktopFocus(true);
        settle(host);
        assertEquals(DisplayMode.NATIVE_WINDOW, combo.displayMode(),
                "the demo's default presentation is the native one");

        combo.open();
        runtime.drain();
        assertEquals(2, backend.windows().size(), "the host and the popup");
        HeadlessWindow popup = backend.windows().get(1);
        settle(host, popup);
        for (AccessibleNode option : options(popup.bridge().tree())) {
            assertTrue(option.actions() != null
                            && option.actions().has(Accessible.Action.SELECT),
                    "open, an option offers its verbs: " + Transcript.of(popup.bridge().tree()));
        }

        combo.close();
        popup.frame();
        host.frame();

        assertFalse(combo.isOpen());
        assertEquals(2, backend.windows().size(), "the popup is still fading out");
        List<AccessibleNode> fading = options(popup.bridge().tree());
        assertEquals(3, fading.size(), "and its list is still drawn and published: "
                + Transcript.of(popup.bridge().tree()));
        for (AccessibleNode option : fading) {
            assertNull(option.actions(), "a fading option offers no verb, because the hook "
                    + "performs none of them: " + Transcript.of(popup.bridge().tree()));
        }
    }

    private void settle(HeadlessWindow... windows) {
        for (int i = 0; i < 24; i++) {
            nanos += FRAME_NANOS;
            runtime.drain();
            for (HeadlessWindow window : windows) {
                window.frame();
            }
        }
    }

    private static List<AccessibleNode> options(AccessibleTree tree) {
        List<AccessibleNode> found = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == Accessible.Role.LIST_ITEM) {
                found.add(tree.node(i));
            }
        }
        return found;
    }
}
