package limn.components;

import limn.graphics.Color;
import limn.scene.SystemPreferences;
import limn.scene.SystemPreferences.ColorScheme;
import limn.scene.internal.SystemPreferencesAccess;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette a widget paints with while the system asks for more contrast (ADR 081 §1.2, §1.3):
 * the user's own colours where the platform gives a palette, the application's palette
 * strengthened otherwise, and the application's own palette when it refused.
 */
class ContrastPaletteTest {

    /**
     * The four contrast themes Windows 11 ships, as their system colours read on 2026-10-23 (ADR
     * 081 §0.4): window, window text, link, disabled text, highlight, highlight text, button face,
     * button text.
     */
    static final Map<String, SystemPreferences.ContrastColors> WINDOWS_THEMES = Map.of(
            "hc1", colours(0x2D3236, 0xFFFFFF, 0x70EBDE, 0xA6A6A6, 0xA1BFDE, 0x212D3B, 0x2D3236, 0xB6F6F0),
            "hc2", colours(0x000000, 0xFFFFFF, 0x8080FF, 0xA6A6A6, 0xD6B4FD, 0x2B2B2B, 0x000000, 0xFFEE32),
            "hcblack", colours(0x202020, 0xFFFFFF, 0x75E9FC, 0xA6A6A6, 0x8EE3F0, 0x263B50, 0x202020, 0xFFFFFF),
            "hcwhite", colours(0xFFFAEF, 0x3D3D3D, 0x1C5E75, 0x676767, 0x903909, 0xFFF5E3, 0xFFFAEF, 0x202020));

    static SystemPreferences.ContrastColors colours(int... rgb) {
        return new SystemPreferences.ContrastColors(Color.rgb(rgb[0]), Color.rgb(rgb[1]), Color.rgb(rgb[2]),
                Color.rgb(rgb[3]), Color.rgb(rgb[4]), Color.rgb(rgb[5]), Color.rgb(rgb[6]), Color.rgb(rgb[7]));
    }

    private HeadlessUi ui;

    @AfterEach
    void putBack() {
        if (ui != null) {
            SystemPreferences.report(SystemPreferences.NONE);
            Theme.followSystemContrast(true);
            ui.close();
        }
    }

    @Test
    void theStrengthenedCopyOfEveryShippedPaletteMeetsItsBars() {
        List<String> short_ = new ArrayList<>();
        for (Theme base : Theme.builtins()) {
            Theme t = Theme.strengthened(base);
            assertTrue(t.contrast(), base + ": a contrast palette");
            assertEquals(base.isDark(), t.isDark(), base + ": the same light or dark");
            for (Color ground : List.of(t.background(), t.surface(), t.surfaceRaised())) {
                bar(short_, base, "text", t.text(), ground, 7.0);
                bar(short_, base, "muted text", t.textMuted(), ground, 7.0);
                bar(short_, base, "outline", t.outline(), ground, 4.5);
                bar(short_, base, "control edge", t.controlBorder(), ground, 4.5);
                bar(short_, base, "focus ring", t.focusRing(), ground, 4.5);
                bar(short_, base, "accent", t.primary(), ground, 4.5);
                bar(short_, base, "danger", t.danger(), ground, 4.5);
            }
            bar(short_, base, "text on the accent", t.onPrimary(), t.primary(), 4.5);
        }
        assertEquals(List.of(), short_);
    }

    private static void bar(List<String> short_, Theme base, String what, Color ink, Color ground, double bar) {
        double ratio = Color.contrastRatio(ink, ground);
        if (ratio < bar) {
            short_.add(base + " " + what + ": " + String.format("%.2f", ratio));
        }
    }

    @Test
    void onWindowsThePaletteIsTheUsersColoursAsGiven() {
        for (var entry : WINDOWS_THEMES.entrySet()) {
            SystemPreferences.ContrastColors c = entry.getValue();
            Theme t = Theme.fromSystemColours(Theme.limn(), c);
            String name = entry.getKey();
            assertTrue(t.contrast(), name);
            assertEquals(c.window(), t.background(), name);
            assertEquals(c.window(), t.surface(), name);
            assertEquals(c.window(), t.surfaceRaised(), name);
            assertEquals(c.windowText(), t.text(), name);
            assertEquals(c.windowText(), t.textMuted(), name);
            assertEquals(c.windowText(), t.outline(), name);
            assertEquals(c.windowText(), t.controlBorder(), name + ": already 3:1, not moved");
            assertEquals(c.link(), t.focusRing(), name);
            assertEquals(c.highlight(), t.primary(), name);
            assertEquals(c.highlightText(), t.onPrimary(), name);
            assertEquals(c.disabledText(), t.disabledText(), name);
            assertEquals(c.windowText(), t.danger(), name + ": the semantic tones have no system colour");
            assertEquals(name.equals("hcwhite") ? false : true, t.isDark(), name);
        }
    }

    @Test
    void aWidgetPaintsWithTheContrastPaletteWhileTheSystemAsksAndCurrentIsUnchanged() {
        ui = new HeadlessUi();
        Theme before = Theme.current();
        Label widget = new Label("x");
        assertSame(before, Theme.of(widget), "no request: the application's palette");

        SystemPreferences.report(SystemPreferencesAccess.of(ColorScheme.LIGHT, false, true, false));
        Theme strengthened = Theme.of(widget);
        assertTrue(strengthened.contrast(), "more contrast and no palette of the system's: strengthened");
        assertSame(strengthened, Theme.of(widget), "made once");
        assertSame(before, Theme.current(), "the application's choice is kept");

        SystemPreferences.report(SystemPreferencesAccess.of(ColorScheme.LIGHT, false, true, false,
                WINDOWS_THEMES.get("hc2")));
        assertEquals(Color.rgb(0x000000), Theme.of(widget).background(), "a Windows contrast theme: its colours");

        Theme.followSystemContrast(false);
        assertSame(before, Theme.of(widget), "refused: the application's palette");
        assertFalse(Theme.followsSystemContrast());

        Theme.followSystemContrast(true);
        SystemPreferences.report(SystemPreferences.NONE);
        assertSame(before, Theme.of(widget), "the request withdrawn: the application's palette again");
    }
}
