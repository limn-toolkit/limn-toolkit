package limn.components;

import limn.graphics.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A palette an application builds for itself, and the token vocabulary that makes it
 * possible to treat every tone alike.
 *
 * <p>{@link #everyColourFieldHasAToken()} is the load-bearing one. Three consumers
 * (the serializer, the editor, the contrast report) iterate {@link Theme.Token} instead
 * of naming the tones, and every one of them silently drops a tone the enum forgets. A
 * new colour on {@link Theme} therefore fails here, at the one place that can say so.
 */
class ThemeBuilderTest {

    /** The four palettes whose ramps are hand-solved rather than derived. */
    private static final Set<String> HAND_TUNED =
            Set.of("Light", "Dark", "Limn", "Limn Light");

    static List<Theme> builtins() {
        return Theme.builtins();
    }

    static List<Theme> derivedBuiltins() {
        List<Theme> derived = new ArrayList<>();
        for (Theme theme : Theme.builtins()) {
            if (!HAND_TUNED.contains(theme.name)) {
                derived.add(theme);
            }
        }
        return derived;
    }

    // ------------------------------------------------------------- the vocabulary

    @Test
    void everyColourFieldHasAToken() {
        Set<String> fields = new LinkedHashSet<>();
        for (Field field : Theme.class.getFields()) {
            if (field.getType() == Color.class) {
                fields.add(field.getName());
            }
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (Theme.Token token : Theme.Token.values()) {
            tokens.add(token.key());
        }
        assertEquals(fields, tokens,
                "Theme.Token must name every public Color field, and only those");
    }

    @Test
    void aTokenReadsTheFieldItIsNamedAfter() throws Exception {
        Theme theme = Theme.limn();
        for (Theme.Token token : Theme.Token.values()) {
            Field field = Theme.class.getField(token.key());
            assertEquals(field.get(theme), token.read(theme), token.key());
        }
    }

    @Test
    void tokensAreFoundByTheirKeyAndNotByAnythingElse() {
        for (Theme.Token token : Theme.Token.values()) {
            assertSame(token, Theme.Token.byKey(token.key()));
        }
        assertNull(Theme.Token.byKey("BACKGROUND"), "the enum name is not the key");
        assertNull(Theme.Token.byKey("nope"));
    }

    // ------------------------------------------------------------- the round trip

    @ParameterizedTest(name = "{0}")
    @MethodSource("builtins")
    void aBuiltinRebuildsIntoItself(Theme theme) {
        assertEquals(theme, theme.toBuilder().build());
        assertEquals(theme.hashCode(), theme.toBuilder().build().hashCode());
    }

    @Test
    void aThemeIsToldApartByAnySingleTone() {
        Theme base = Theme.dark();
        for (Theme.Token token : Theme.Token.values()) {
            Color moved = token.read(base).lerp(Color.rgb(0xFF00FF), 0.5f);
            assertNotEquals(base, base.toBuilder().set(token, moved).build(), token.key());
        }
        assertNotEquals(base, base.toBuilder().name("Other").build());
        assertNotEquals(base, base.toBuilder().dark(false).build());
    }

    @Test
    void theBuilderIsReusableAndBuildsFreshPalettes() {
        Theme.Builder builder = Theme.builder("Mine", true);
        Theme first = builder.build();
        Theme second = builder.primary(Color.rgb(0x4FD1C5)).build();
        assertEquals(Theme.dark().primary, first.primary, "the first build must not see later edits");
        assertEquals(Color.rgb(0x4FD1C5), second.primary);
    }

    // -------------------------------------------------------------- the seeding

    @Test
    void aNewPaletteStartsFromAWorkingOne() {
        Theme mine = Theme.builder("Mine", true).build();
        assertEquals(Theme.dark().toBuilder().name("Mine").build(), mine);

        Theme light = Theme.builder("Mine", false).build();
        assertEquals(Theme.light().toBuilder().name("Mine").build(), light);
        assertFalse(light.dark);
    }

    @Test
    void aTokenSetOnTheBuilderReadsBackBeforeAnyBuild() {
        Theme.Builder builder = Theme.builder("Mine", true);
        for (Theme.Token token : Theme.Token.values()) {
            builder.set(token, Color.rgb(0x123456));
            assertEquals(Color.rgb(0x123456), builder.get(token), token.key());
        }
        assertEquals("Mine", builder.name());
        assertTrue(builder.isDark());
    }

    @Test
    void nothingAcceptsNull() {
        Theme.Builder builder = Theme.builder("Mine", true);
        assertThrows(NullPointerException.class, () -> builder.background(null));
        assertThrows(NullPointerException.class, () -> builder.name(null));
        assertThrows(NullPointerException.class, () -> builder.set(null, Color.WHITE));
        assertThrows(NullPointerException.class,
                () -> builder.set(Theme.Token.PRIMARY, null));
    }

    // ------------------------------------------------------------ the derivations

    /**
     * The derived palettes' accent ramp and disabled pair must be reproducible from the public
     * derivations, or an application following the documented recipe would get a different ramp
     * from the one the toolkit ships.
     *
     * <p>The semantic four are deliberately excluded. {@code deriveSemanticStates} supplies one
     * generic set per mode and its own Javadoc invites a palette to override any tone that cannot
     * clear 4.5:1 on the surface it lands on, which several do, because a popover sits further
     * from the canvas than the generic tones were solved against. Asserting them here would make
     * that documented override an error.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("derivedBuiltins")
    void theShippedRampsAreTheDocumentedDerivations(Theme theme) {
        Theme derived = theme.toBuilder().deriveAccentStates().deriveDisabled().build();
        assertEquals(theme.primaryHover, derived.primaryHover, theme.name + " hovered accent");
        assertEquals(theme.primaryPressed, derived.primaryPressed, theme.name + " pressed accent");
        assertEquals(theme.disabledFill, derived.disabledFill, theme.name + " disabled fill");
        assertEquals(theme.disabledText, derived.disabledText, theme.name + " disabled ink");
    }

    /**
     * The counterpart, and the reason the toolkit's own palette spells its ramp out: a
     * light accent carrying dark ink has no room for the generic 20% darkening.
     */
    @Test
    void theHandSolvedRampsAreNotWhatTheDerivationWouldProduce() {
        Theme limn = Theme.limn();
        Theme derived = limn.toBuilder().deriveAccentStates().build();
        assertNotEquals(limn, derived);
        assertTrue(Color.contrastRatio(limn.onPrimary, limn.primaryPressed)
                        > Color.contrastRatio(limn.onPrimary, derived.primaryPressed),
                "the hand-solved pressed tone must be the more legible one");
    }

    @Test
    void deriveReadsWhatWasSetBeforeItAndNotAfter() {
        Color accent = Color.rgb(0x808080);
        Theme.Builder builder = Theme.builder("Mine", true).primary(accent).deriveAccentStates();
        Color derivedHover = builder.get(Theme.Token.PRIMARY_HOVER);

        // Moving the input afterwards leaves the derived tone where it was: the trap the
        // builder's documentation names, and the reason an editor re-derives on every edit.
        builder.primary(Color.rgb(0x101010));
        assertEquals(derivedHover, builder.get(Theme.Token.PRIMARY_HOVER));
        assertEquals(Color.rgb(0x666666), builder.get(Theme.Token.PRIMARY_PRESSED));
    }

    /**
     * A palette has to be exactly writable, or saving and reloading one would move it and
     * two palettes that render identically would compare unequal. Eight bits per channel
     * is what a hex value, a colour field and a monitor share.
     */
    @Test
    void everyToneLandsOnTheEightBitGrid() {
        List<Theme> all = new ArrayList<>(Theme.builtins());
        all.add(Theme.builder("Interpolated", true)
                .primary(Color.rgb(0x808080).lerp(Color.WHITE, 0.137f))
                .deriveAccentStates()
                .deriveDisabled()
                .build());
        for (Theme theme : all) {
            for (Theme.Token token : Theme.Token.values()) {
                Color tone = token.read(theme);
                for (float channel : new float[]{tone.r(), tone.g(), tone.b(), tone.a()}) {
                    assertEquals(Math.round(channel * 255f) / 255f, channel, 0f,
                            theme.name + '.' + token.key());
                }
            }
        }
    }

    @Test
    void theSemanticFourFollowTheModeAndNotTheAccent() {
        Theme dark = Theme.builder("Mine", true).primary(Color.rgb(0x00FF00))
                .deriveSemanticStates().build();
        Theme light = Theme.builder("Mine", false).primary(Color.rgb(0x00FF00))
                .deriveSemanticStates().build();
        assertEquals(Theme.dark().danger, dark.danger);
        assertEquals(Theme.light().danger, light.danger);
        assertNotEquals(dark.danger, light.danger);
    }

    // --------------------------------------------------------------- the metrics

    /**
     * A palette carries colour and nothing else. Metrics come from the process-wide
     * {@link SizeTokens} table, so a palette an application builds measures identically
     * to a built-in, which is what lets a theme switch skip a rebuild.
     */
    @Test
    void aCustomPaletteMeasuresLikeEveryOther() {
        Theme mine = Theme.builder("Mine", true).background(Color.BLACK).build();
        assertEquals(Theme.dark().spacingLarge, mine.spacingLarge);
        assertSame(Theme.dark().body, mine.body);
        assertSame(SizeTokens.MEDIUM, mine.tokens(limn.scene.ControlSize.MEDIUM));
    }

    @Test
    void anUnknownNameStandsAsWrittenInThePicker() {
        Theme mine = Theme.builder("Ocean", true).build();
        assertEquals("Ocean", mine.displayName().get());
        assertEquals("Ocean", mine.toString());
    }
}
