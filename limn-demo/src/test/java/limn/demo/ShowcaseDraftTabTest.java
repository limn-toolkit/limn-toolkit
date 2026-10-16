package limn.demo;

import limn.components.TabbedPane;
import limn.components.Theme;
import limn.concurrent.UiRuntime;
import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.components.Accelerator;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The Showcase's draft tab asks before it closes and every other tab closes at once. The draft
 * once closed unasked: the page wrapped it in a card and the handler still compared the request
 * with the bare text area.
 */
class ShowcaseDraftTabTest {

    private static final ImageDecoder DECODER = bytes -> new Image(16, 16, new byte[16 * 16 * 4]);

    private HeadlessUi ui;
    private HeadlessBackend backend;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> 0L);
        UiRuntime runtime = ui.runtime();
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
        ui.close();
    }

    private static TabbedPane firstPane(Widget<?> at) {
        if (at instanceof TabbedPane pane) {
            return pane;
        }
        for (Widget<?> child : at.children()) {
            TabbedPane found = firstPane(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The selected tab's header, the strip's one tab stop. */
    private static Widget<?> tabStop(Widget<?> at) {
        if (at.isFocusable() && at.getClass().getSimpleName().equals("TabHeader")) {
            return at;
        }
        for (Widget<?> child : at.children()) {
            Widget<?> found = tabStop(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void closeSelected(Scene scene, HeadlessWindow window, TabbedPane pane) {
        scene.requestFocus(tabStop(pane));
        drive(scene).press(Keys.W, Accelerator.commandModifier());
        drive(scene).inputBatchEnded();
    }

    @Test
    void theDraftAsksAndAnyOtherTabClosesAtOnce() {
        Scene scene = new Scene(SectionsScene.content());
        HeadlessWindow window = backend.open("Sections", 1100, 760);
        scene.bind(window);
        window.frame();
        TabbedPane documents = firstPane(scene.root());
        assertNotNull(documents);
        int tabs = documents.tabCount();

        documents.setSelectedIndex(1);
        window.frame();
        closeSelected(scene, window, documents);
        assertEquals(tabs - 1, documents.tabCount(), "a saved document closes at once");

        int draft = documents.tabCount() - 1;
        documents.setSelectedIndex(draft);
        window.frame();
        closeSelected(scene, window, documents);
        assertEquals(tabs - 1, documents.tabCount(), "the draft waits for the dialog's answer");
    }
}
