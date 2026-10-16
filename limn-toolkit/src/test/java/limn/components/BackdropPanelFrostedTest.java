package limn.components;

import limn.graphics.BackdropEffect;
import limn.graphics.Color;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frosted glass is worked out from the theme in effect, and leans toward a tint when given one. */
class BackdropPanelFrostedTest extends ComponentTestBase {

    /** An amount or a cover that is not a number is refused, where one past an end is clamped. */
    @Test
    void aTintOrACoverThatIsNotANumberIsRefused() {
        BackdropPanel panel = BackdropPanel.frosted(new Label("On glass"));
        assertThrows(IllegalArgumentException.class, () -> panel.setTint(Color.WHITE, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> panel.setCover(Float.NaN));
        assertEquals(BackdropPanel.FROST_COVER, panel.cover());
        assertEquals(0, panel.tintAmount());
        panel.setCover(2);
        assertEquals(1, panel.cover(), "past the end, clamped");
    }

    private static BackdropEffect.Wash toneOf(BackdropPanel panel) {
        List<BackdropEffect> stack = panel.effects();
        assertEquals(3, stack.size(), "blurred across, blurred down, and the tone over it");
        assertInstanceOf(BackdropEffect.Blur.class, stack.get(0));
        assertInstanceOf(BackdropEffect.Blur.class, stack.get(1));
        return assertInstanceOf(BackdropEffect.Wash.class, stack.get(2));
    }

    @Test
    void frostedGlassTakesTheToneOfThePaletteInEffectAndChangesWithIt() {
        BackdropPanel panel = BackdropPanel.frosted(new Label("On glass"));
        assertTrue(panel.isFrosted());

        Theme.setCurrent(Theme.light());
        BackdropEffect.Wash light = toneOf(panel);
        Color surface = Theme.light().surface();
        assertEquals(surface.r(), light.tint().r(), 1e-6f);
        assertTrue(light.tint().a() > 0.4f && light.tint().a() < 0.8f, "over what is behind, and not hiding it");
        assertTrue(light.lift() > 0, "what is behind light glass is moved toward white");

        Theme.setCurrent(Theme.dark());
        BackdropEffect.Wash dark = toneOf(panel);
        assertEquals(Theme.dark().surface().r(), dark.tint().r(), 1e-6f,
                "the palette changed and nobody told the panel: it reads the theme when it is asked");
        assertTrue(dark.lift() < 0, "and what is behind dark glass is moved toward black");
        assertTrue(dark.tint().relativeLuminance() < light.tint().relativeLuminance());
    }

    @Test
    void aTintLeansTheToneTowardAColourByTheAmountGiven() {
        Theme.setCurrent(Theme.light());
        Color surface = Theme.light().surface();
        Color brand = new Color(0f, 0.5f, 1f, 1f);
        BackdropPanel panel = BackdropPanel.frosted(new Label("On glass")).setTint(brand, 0.25f);

        Color tone = toneOf(panel).tint();
        assertEquals(surface.r() + (brand.r() - surface.r()) * 0.25f, tone.r(), 1e-5f);
        assertEquals(surface.b() + (brand.b() - surface.b()) * 0.25f, tone.b(), 1e-5f);

        panel.setTint(brand, 7f);
        assertEquals(1f, panel.tintAmount(), "an amount is a share, held to 0..1");
        assertEquals(brand.g(), toneOf(panel).tint().g(), 1e-5f, "at 1 the tone is the colour");

        panel.setTint(null, 0.5f);
        assertEquals(surface.g(), toneOf(panel).tint().g(), 1e-6f, "and with none, the theme's alone");
    }

    @Test
    void theCoverIsHowMuchOfTheToneIsLaidOverWhatIsBehind() {
        Theme.setCurrent(Theme.light());
        BackdropPanel panel = BackdropPanel.frosted(new Label("On glass"));
        assertEquals(0.6f, panel.cover());
        assertEquals(0.6f, toneOf(panel).tint().a(), 1e-6f);

        panel.setCover(0.3f);
        assertEquals(0.3f, toneOf(panel).tint().a(), 1e-6f, "less of the tone, more of what is behind");
        assertEquals(Theme.light().surface().r(), toneOf(panel).tint().r(), 1e-6f, "and the tone itself as it was");

        panel.setCover(4f);
        assertEquals(1f, panel.cover(), "a share, held to 0..1");
        BackdropPanel byHand = new BackdropPanel(new BackdropEffect.Pixelate(Color.TRANSPARENT, 9f),
                limn.scene.Insets.all(4), new Label("By hand"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> byHand.setCover(0.5f));
    }

    @Test
    void aPanelGivenItsEffectsByHandIsNoLongerWorkedOutAndTakesNoTint() {
        BackdropEffect.Pixelate redaction = new BackdropEffect.Pixelate(Color.TRANSPARENT, 9f);
        BackdropPanel panel = BackdropPanel.frosted(new Label("On glass"));
        panel.setEffect(redaction);
        assertFalse(panel.isFrosted());
        assertEquals(List.of(redaction), panel.effects());
        Theme.setCurrent(Theme.light());
        assertEquals(List.of(redaction), panel.effects(), "an effect given by hand does not follow the theme");
        assertThrows(IllegalStateException.class, () -> panel.setTint(Color.WHITE, 0.2f));

        BackdropPanel byHand = new BackdropPanel(redaction, new Label("Covered"));
        assertThrows(IllegalStateException.class, () -> byHand.setTint(Color.WHITE, 0.2f));
    }
}
