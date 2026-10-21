package limn.demo;

import limn.components.ComboBox;
import limn.components.SegmentedControl;
import limn.components.Theme;
import limn.components.Viewport3D;
import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Showcase's 3D page: one toolbar over several scenes, of which one shows at a time. What the
 * toolbar sets is the page's, not the first scene's: the render scale was wired to the materials
 * scene alone, so the shadows scene went on rendering at full resolution under "50%".
 */
class Viewport3DPageTest {

    private static final ImageDecoder DECODER = bytes -> new Image(16, 16, new byte[16 * 16 * 4]);

    private HeadlessUi ui;
    private HeadlessBackend backend;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> 0L);
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
        Images.installDecoder(DECODER);
        backend = new HeadlessBackend(ui.runtime());
    }

    @AfterEach
    void uninstallRuntime() {
        Images.uninstallDecoder(DECODER);
        TextRulers.uninstall(HeadlessWindow.RULER);
        ui.close();
    }

    /** The page, laid out in a window of the Showcase's size. */
    private Scene page(HeadlessWindow[] window) {
        Scene scene = new Scene(Viewport3DScene.tabContent());
        window[0] = backend.open("Viewport 3D", 1100, 760);
        scene.bind(window[0]);
        window[0].frame();
        return scene;
    }

    static <T extends Widget<?>> List<T> all(Widget<?> at, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(at, type, found);
        return found;
    }

    private static <T extends Widget<?>> void collect(Widget<?> at, Class<T> type, List<T> out) {
        if (type.isInstance(at)) {
            out.add(type.cast(at));
        }
        for (Widget<?> child : at.children()) {
            collect(child, type, out);
        }
    }

    /** The user picking the render scale's third choice, 50%, from the keyboard. */
    private static void chooseHalfScale(Scene scene, HeadlessWindow window) {
        ComboBox<?> renderScale = all(scene.root(), ComboBox.class).get(0);
        scene.requestFocus(renderScale);
        drive(scene).press(Keys.DOWN).press(Keys.DOWN).press(Keys.DOWN).press(Keys.ENTER);
        window.frame();
        assertEquals(2, renderScale.selectedIndex(), "the keys chose 50%");
    }

    @Test
    void theRenderScaleReachesEverySceneAndStaysAcrossASwitch() {
        HeadlessWindow[] window = new HeadlessWindow[1];
        Scene scene = page(window);
        List<Viewport3D> views = all(scene.root(), Viewport3D.class);
        assertFalse(views.isEmpty());

        chooseHalfScale(scene, window[0]);
        for (int i = 0; i < views.size(); i++) {
            assertEquals(0.5f, views.get(i).renderScale(), 1e-6f,
                    "every scene of the page renders at the scale its toolbar shows, view " + i);
        }

        SegmentedControl picker = all(scene.root(), SegmentedControl.class).get(0);
        for (int each = 0; each < picker.segmentCount(); each++) {
            show(scene, window[0], picker, each);
            int showing = 0;
            for (Viewport3D view : views) {
                if (view.isShowing()) {
                    showing++;
                    assertEquals(0.5f, view.renderScale(), 1e-6f,
                            "the scene shown after a switch keeps the scale: " + picker.segment(each));
                }
            }
            assertEquals(1, showing, "one scene shows at a time: " + picker.segment(each));
        }
    }

    /**
     * The user choosing the picker's segment {@code index} from the keyboard: the page answers a
     * user's choice, and {@code setSelectedIndex} is a caller's write that runs no handler.
     */
    static void show(Scene scene, HeadlessWindow window, SegmentedControl picker, int index) {
        scene.requestFocus(picker);
        while (picker.selectedIndex() != index) {
            drive(scene).press(picker.selectedIndex() < index ? Keys.RIGHT : Keys.LEFT);
        }
        window.frame();
    }

    /** The picker's segment that says {@code name}, as the reader of the picker hears it. */
    static int segmentOf(SegmentedControl picker, limn.i18n.I18nString name) {
        for (int i = 0; i < picker.segmentCount(); i++) {
            if (picker.segmentSource(i).equals(name)) {
                return i;
            }
        }
        throw new AssertionError("no segment " + name.get());
    }

    @Test
    void theEnvironmentAndTheModelAreScenesOfThePage() {
        HeadlessWindow[] window = new HeadlessWindow[1];
        Scene scene = page(window);
        SegmentedControl picker = all(scene.root(), SegmentedControl.class).get(0);
        segmentOf(picker, Viewport3DScene.Text.ENVIRONMENT);
        segmentOf(picker, Viewport3DScene.Text.MODEL);
    }

    @Test
    void theModelSaysWhatItLoadedOnceItIsInView() {
        limn.components.Label status = new limn.components.Label("");
        Page.reportTo(status);
        try {
            HeadlessWindow[] window = new HeadlessWindow[1];
            Scene scene = page(window);
            assertEquals("", status.text(), "nothing is said of a scene that is not in view");
            SegmentedControl picker = all(scene.root(), SegmentedControl.class).get(0);
            show(scene, window[0], picker, segmentOf(picker, Viewport3DScene.Text.MODEL));
            ui.pumpUntil(() -> status.text().contains("nodes"));
            assertEquals("toy.gltf · nodes: 3 · materials: 3 · textures: 1", status.text());
        } finally {
            Page.reportTo(null);
        }
    }
}
