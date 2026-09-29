package limn.backend.lwjgl.a11y.linux;

import limn.scene.SystemPreferences;
import limn.scene.SystemPreferences.ColorScheme;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the desktop portal's settings say, with the values read on the two Linux guests on
 * 2026-09-29: Ubuntu 24.04's portal has no reduced-motion key and GNOME's {@code enable-animations}
 * stands in for it; Fedora 44's KDE portal carries {@code reduced-motion} itself, and its answer wins
 * over GNOME's mirror of it.
 */
class PortalAppearanceWatchTest {

    private static final String APPEARANCE = PortalAppearanceWatch.APPEARANCE;
    private static final String GNOME = PortalAppearanceWatch.GNOME_INTERFACE;

    @Test
    void ubuntusDefaultIsNoPreference() {
        assertEquals(SystemPreferences.NONE, PortalAppearanceWatch.preferencesOf(Map.of(
                APPEARANCE, Map.of("color-scheme", 0, "contrast", 0),
                GNOME, Map.of("enable-animations", true))));
    }

    @Test
    void oneIsDarkAndTwoIsLight() {
        assertEquals(ColorScheme.DARK, PortalAppearanceWatch.preferencesOf(Map.of(
                APPEARANCE, Map.of("color-scheme", 1))).colorScheme());
        assertEquals(ColorScheme.LIGHT, PortalAppearanceWatch.preferencesOf(Map.of(
                APPEARANCE, Map.of("color-scheme", 2))).colorScheme());
    }

    @Test
    void gnomesAnimationSwitchStandsInWhereThePortalHasNoKey() {
        assertEquals(true, PortalAppearanceWatch.preferencesOf(Map.of(
                APPEARANCE, Map.of("color-scheme", 0, "contrast", 0),
                GNOME, Map.of("enable-animations", false))).reduceMotion());
    }

    @Test
    void thePortalsOwnKeyWinsWhereItHasOne() {
        assertEquals(false, PortalAppearanceWatch.preferencesOf(Map.of(
                APPEARANCE, Map.of("color-scheme", 2, "reduced-motion", 0),
                GNOME, Map.of("enable-animations", false))).reduceMotion());
        assertEquals(true, PortalAppearanceWatch.preferencesOf(Map.of(
                APPEARANCE, Map.of("color-scheme", 2, "reduced-motion", 1),
                GNOME, Map.of("enable-animations", true))).reduceMotion());
    }

    @Test
    void contrastOneIsIncreasedContrast() {
        assertEquals(true, PortalAppearanceWatch.preferencesOf(Map.of(
                APPEARANCE, Map.of("contrast", 1))).increaseContrast());
    }

    @Test
    void noPortalSaysNothing() {
        assertEquals(SystemPreferences.NONE, PortalAppearanceWatch.preferencesOf(Map.of()));
    }
}
