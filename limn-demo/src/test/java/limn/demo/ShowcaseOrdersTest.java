package limn.demo;

import limn.components.Button;
import limn.scene.ControlSize;
import limn.components.SearchField;
import limn.components.Theme;
import limn.concurrent.UiRuntime;
import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Showcase's orders page under its own filter. */
class ShowcaseOrdersTest {

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

    private static <W> List<W> all(Widget<?> root, Class<W> kind, List<W> into) {
        if (kind.isInstance(root)) {
            into.add(kind.cast(root));
        }
        for (Widget<?> child : root.children()) {
            all(child, kind, into);
        }
        return into;
    }

    @Test
    void aFilterThatLeavesNoOrderEmptiesTheFormAndDisablesIt() {
        Scene scene = AppScene.create().scene();
        HeadlessWindow window = backend.open(AppScene.TITLE, 1100, 760);
        scene.bind(window);
        window.frame();
        SearchField search = all(scene.root(), SearchField.class, new ArrayList<>()).get(0);
        Button save = all(scene.root(), Button.class, new ArrayList<>()).stream()
                .filter(button -> ShowcaseStrings.ACTION_SAVE.get().equals(button.text())).findFirst().orElse(null);
        assertNotNull(save, "the order's form has its Save");
        assertTrue(save.isEnabled(), "an order is selected as the page opens");

        // The filter listens to the user's typing, so it is typed: nothing is called that.
        scene.requestFocus(search);
        "no such customer".codePoints().forEach(drive(scene)::charTyped);
        drive(scene).inputBatchEnded();
        window.frame();

        assertFalse(save.isEnabled(), "with no order left the form is disabled, every field of it");
    }
}
