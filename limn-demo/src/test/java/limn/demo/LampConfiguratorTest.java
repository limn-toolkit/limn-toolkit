package limn.demo;

import limn.components.Label;
import limn.components.SegmentedControl;
import limn.components.Theme;
import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.math.Vec2;
import limn.math.Vec4;
import limn.render3d.Material;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import limn.testing.TestPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 3D page's product configurator, as its visitor uses it: the swatches and the finish change
 * the lamp's body, by a short blend that less motion turns into a cut; a click on a part opens
 * its name and price beside it; and the total follows the finish.
 */
class LampConfiguratorTest {

    private static final ImageDecoder DECODER = bytes -> new Image(16, 16, new byte[16 * 16 * 4]);

    private HeadlessUi ui;
    private HeadlessBackend backend;
    private LampConfigurator.Built lamp;
    private Scene scene;
    private HeadlessWindow window;

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

    private void open() {
        lamp = LampConfigurator.build();
        scene = new Scene(lamp.widget);
        window = backend.open("Configurator", 1000, 700);
        scene.bind(window);
        window.frame();
    }

    private static Vec4 colourOf(int index) {
        LampConfigurator.Choice choice = new LampConfigurator.Choice();
        choice.colour = index;
        return choice.body().baseColor();
    }

    private Label labelSaying(String text) {
        for (Label label : Viewport3DPageTest.all(scene.root(), Label.class)) {
            if (label.text().equals(text)) {
                return label;
            }
        }
        throw new AssertionError("no label says " + text);
    }

    @Test
    void aSwatchClickedOrArrowedToRepaintsTheBodyAndNamesItsColour() {
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true); // the blend is a cut: what is drawn is the choice at once
            open();
            List<LampConfigurator.Swatch> swatches =
                    Viewport3DPageTest.all(scene.root(), LampConfigurator.Swatch.class);
            assertEquals(4, swatches.size());

            drive(scene).click(swatches.get(3));
            window.frame();
            assertEquals(3, lamp.choice.colour);
            assertEquals(colourOf(3), lamp.bodyNow().baseColor(), "the body is terracotta");
            labelSaying("Terracotta");

            // One tab stop, the chosen swatch; the arrows move the choice and the focus with it.
            assertTrue(swatches.get(3).isFocusable());
            assertFalse(swatches.get(0).isFocusable());
            scene.requestFocus(swatches.get(3));
            drive(scene).press(Keys.LEFT);
            window.frame();
            assertEquals(2, lamp.choice.colour);
            assertTrue(swatches.get(2).isFocused(), "the focus went with the choice");
            assertEquals(colourOf(2), lamp.bodyNow().baseColor());
            labelSaying("Sage");
        }
    }

    @Test
    void aFinishBlendsTheBodyOverAThirdOfASecond() {
        open();
        SegmentedControl finish = Viewport3DPageTest.all(scene.root(), SegmentedControl.class).get(0);
        Material.Pbr matte = lamp.bodyNow();
        float glossy = LampConfigurator.Finish.GLOSSY.roughness;
        long start = System.nanoTime();
        Viewport3DPageTest.show(scene, window, finish, LampConfigurator.Finish.GLOSSY.ordinal());
        // A finish is chrome's kind of motion, on the wall clock: frame until it lands.
        int between = 0;
        while (lamp.bodyNow().roughness() != glossy && System.nanoTime() - start < 2_000_000_000L) {
            float roughness = lamp.bodyNow().roughness();
            if (roughness < matte.roughness() && roughness > glossy) {
                between++;
            }
            window.frame();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        assertEquals(glossy, lamp.bodyNow().roughness(), 0f, "it lands on glossy");
        assertTrue(between > 0, "frames drawn part of the way from matte to glossy");
        assertTrue(seconds > 0.25, "over about a third of a second, not at once: " + seconds);
        assertNotEquals(matte, lamp.bodyNow());
    }

    @Test
    void lessMotionMakesTheFinishACut() {
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true);
            open();
            SegmentedControl finish = Viewport3DPageTest.all(scene.root(), SegmentedControl.class).get(0);
            Viewport3DPageTest.show(scene, window, finish, LampConfigurator.Finish.METAL.ordinal());
            assertEquals(1f, lamp.bodyNow().metallic(), 0f, "metal on the first frame, no blend");
            assertEquals(LampConfigurator.Finish.METAL.roughness, lamp.bodyNow().roughness(), 0f);
        }
    }

    @Test
    void aClickOnThePartsOpensTheirNameAndPriceAndTheTotalFollowsTheFinish() {
        open();
        labelSaying("$192.00");
        float[] shade = lamp.onView(LampConfigurator.Part.SHADE);
        Vec2 at = lamp.view.localToScene(shade[0], shade[1]);
        drive(scene).click(at.x(), at.y());
        window.frame();
        assertTrue(lamp.popover.isOpen(), "a click on the shade opens its popover");
        assertSame(LampConfigurator.Part.SHADE, lamp.picked);
        assertEquals(List.of("Shade", "$46.00"), lamp.popoverText());

        lamp.popover.close();
        window.frame();
        SegmentedControl finish = Viewport3DPageTest.all(scene.root(), SegmentedControl.class).get(0);
        Viewport3DPageTest.show(scene, window, finish, LampConfigurator.Finish.METAL.ordinal());
        labelSaying("$240.60"); // the body's parts at 1.3 times, the joints and the bulb as they were
        labelSaying("$59.80");

        float[] base = lamp.onView(LampConfigurator.Part.BASE);
        at = lamp.view.localToScene(base[0], base[1]);
        drive(scene).click(at.x(), at.y());
        window.frame();
        assertSame(LampConfigurator.Part.BASE, lamp.picked);
        assertEquals(List.of("Base", "$83.20"), lamp.popoverText(), "the price in the finish chosen");
    }

    @Test
    void aClickOnNothingOpensNothing() {
        open();
        Vec2 corner = lamp.view.localToScene(4, 4);
        drive(scene).click(corner.x(), corner.y());
        window.frame();
        assertFalse(lamp.popover.isOpen());
        assertEquals(null, lamp.picked);
    }
}
