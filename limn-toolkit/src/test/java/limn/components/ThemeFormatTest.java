package limn.components;

import limn.graphics.Color;
import limn.graphics.Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A palette written out and read back.
 *
 * <p>{@link #everyBuiltinSurvivesTheRoundTrip()} is the guarantee the format exists for:
 * a palette an application saves is the palette it loads, tone for tone, for every
 * palette the toolkit knows how to build.
 */
class ThemeFormatTest {

    static List<Theme> builtins() {
        return Theme.builtins();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("builtins")
    void everyBuiltinSurvivesTheRoundTrip(Theme theme) {
        assertEquals(theme, ThemeFormat.parse(ThemeFormat.write(theme)));
    }

    @Test
    void writingIsDeterministic() {
        assertEquals(ThemeFormat.write(Theme.limn()), ThemeFormat.write(Theme.limn()));
        assertEquals(ThemeFormat.write(Theme.limn()),
                ThemeFormat.write(Theme.limn().toBuilder().build()));
    }

    @Test
    void aTranslucentToneSurvivesToo() {
        Theme ghostly = Theme.builder("Ghostly", true)
                .outline(Color.rgba(0xFFFFFF, 0.25f))
                .build();
        assertEquals(ghostly, ThemeFormat.parse(ThemeFormat.write(ghostly)));
    }

    // ------------------------------------------------------------------ the typeface

    @Test
    void aTypefacePreferenceSurvivesTheRoundTrip() {
        Theme serif = Theme.builder("Serif", false).fontFamily("Iowan Old Style").build();
        Theme back = ThemeFormat.parse(ThemeFormat.write(serif));
        assertEquals("Iowan Old Style", back.fontFamily);
        assertEquals(serif, back);
    }

    /**
     * A family with a space in it is the normal case, not the exotic one: the parser splits on
     * the first {@code =} and keeps the rest, so nothing has to be quoted.
     */
    @Test
    void aFamilyNameKeepsItsSpaces() {
        Theme theme = Theme.builder("Spaced", true).fontFamily("Helvetica Neue LT Std").build();
        assertEquals("Helvetica Neue LT Std",
                ThemeFormat.parse(ThemeFormat.write(theme)).fontFamily);
    }

    /** No key means no preference, which is what every built-in carries. */
    @Test
    void aFileWithoutTheKeyExpressesNoPreference() {
        Theme theme = ThemeFormat.parse("name = Plain\ndark = true\n");
        assertEquals(Font.DEFAULT_FAMILY, theme.fontFamily);
    }

    /**
     * A blank value is a file that meant to say something and did not. It is rejected rather than
     * shrugged at, for the same reason a misspelled tone key is: the palette would come back
     * looking right and wearing the wrong face.
     */
    @Test
    void aBlankFamilyIsAnError() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name = Blank\ndark = true\nfontFamily =\n"));
        assertTrue(failure.getMessage().contains("fontFamily"), failure.getMessage());
    }

    /**
     * A palette outlives the machine it was authored on, so a family this machine does not have
     * loads rather than failing: the fallback happens when it is applied, not when it is read.
     */
    @Test
    void aFamilyNobodyHasStillLoads() {
        Theme theme = ThemeFormat.parse(
                "name = Exotic\ndark = false\nfontFamily = No Such Face 9000\n");
        assertEquals("No Such Face 9000", theme.fontFamily);
    }

    /** Two palettes that differ only in typeface are different palettes. */
    @Test
    void theTypefaceCountsTowardsEquality() {
        Theme plain = Theme.builder("Same", true).build();
        Theme serif = Theme.builder("Same", true).fontFamily("Iowan Old Style").build();
        assertNotEquals(plain, serif);
    }

    // ------------------------------------------------------------------ from a file

    @Test
    void aSavedFileLoadsBackWithoutTheEditorAnywhere(@TempDir Path dir) throws IOException {
        Theme saved = Theme.builder("Ocean", true)
                .primary(Color.rgb(0x4FD1C5))
                .cornerScale(2.5f)
                .build();
        Path file = dir.resolve("ocean." + ThemeFormat.EXTENSION);
        Files.writeString(file, ThemeFormat.write(saved), StandardCharsets.UTF_8);

        assertEquals(saved, ThemeFormat.load(file));
    }

    /** The resource case (a theme inside a jar), which is why {@code load} exists at all. */
    @Test
    void aStreamIsReadToTheEndAndLeftForItsOwner() throws IOException {
        byte[] bytes = ThemeFormat.write(Theme.limn()).getBytes(StandardCharsets.UTF_8);
        AtomicBoolean closed = new AtomicBoolean();
        try (InputStream in = new ByteArrayInputStream(bytes) {
            @Override
            public void close() {
                closed.set(true);
            }
        }) {
            assertEquals(Theme.limn(), ThemeFormat.load(in));
            assertFalse(closed.get(),
                    "closing a stream it did not open would double the caller's close");
        }
        assertTrue(closed.get());
    }

    @Test
    void aMissingFileIsAnIoFailureAndNotAParseFailure(@TempDir Path dir) {
        assertThrows(IOException.class, () -> ThemeFormat.load(dir.resolve("absent.limntheme")));
    }

    /**
     * A palette is a file people hand-edit, and re-saving one in a text editor on Windows
     * can prepend a byte-order mark. Without stripping it the very first line stops looking
     * like a comment, and the error points at something the reader can see is fine.
     */
    @Test
    void aByteOrderMarkIsNotPartOfTheFirstKey() {
        String written = ThemeFormat.write(Theme.limn());
        assertEquals(Theme.limn(), ThemeFormat.parse('﻿' + written));

        // Only at the head: further in, U+FEFF is an ordinary character and still an error.
        assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name = Ocean\ndark = true\n﻿primary = #FFF\n"));
    }

    // ------------------------------------------------------------ what may be left out

    @Test
    void anOmittedToneComesFromTheBuiltinTheModeNames() {
        Theme parsed = ThemeFormat.parse("""
                name = Ocean
                dark = true
                primary = #4FD1C5
                """);
        assertEquals(Color.rgb(0x4FD1C5), parsed.primary);
        assertEquals(Theme.dark().background, parsed.background);
        assertEquals(Theme.dark().text, parsed.text);

        Theme light = ThemeFormat.parse("name = Ocean\ndark = false\n");
        assertEquals(Theme.light().background, light.background);
    }

    @Test
    void commentsAndBlankLinesAndCarriageReturnsAreIgnored() {
        Theme parsed = ThemeFormat.parse("\r\n# a palette\r\n\r\n  name = Ocean  \r\n"
                + "dark = true\r\n   # indented comment\r\nprimary = #4FD1C5\r\n");
        assertEquals("Ocean", parsed.name);
        assertEquals(Color.rgb(0x4FD1C5), parsed.primary);
    }

    /**
     * The trap the parser is written around: a colour value starts with the character a
     * naive parser treats as the start of a comment.
     */
    @Test
    void aHashInsideAValueIsNotAComment() {
        Theme parsed = ThemeFormat.parse("name = Ocean\ndark = true\nbackground = #0B1A24\n");
        assertEquals(Color.rgb(0x0B1A24), parsed.background);
    }

    @Test
    void hexIsAcceptedInEveryFormTheColourParserKnows() {
        Theme parsed = ThemeFormat.parse("name = Ocean\ndark = true\n"
                + "background = 0B1A24\nsurface = #abc\nprimary = #4FD1C5FF\n");
        assertEquals(Color.rgb(0x0B1A24), parsed.background);
        assertEquals(Color.rgb(0xAABBCC), parsed.surface);
        assertEquals(Color.rgb(0x4FD1C5), parsed.primary);
    }

    // ------------------------------------------------------------------ what may not

    @Test
    void aMisspelledKeyIsAnErrorAndSaysWhereItIs() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name = Ocean\ndark = true\nprimaryHovor = #FFFFFF\n"));
        assertTrue(thrown.getMessage().contains("line 3"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("primaryHovor"), thrown.getMessage());
    }

    @Test
    void aRepeatedKeyIsAnError() {
        assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name = Ocean\ndark = true\nprimary = #FFF\nprimary = #000\n"));
        assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name = Ocean\nname = Sea\ndark = true\n"));
    }

    @Test
    void theTwoRequiredKeysAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> ThemeFormat.parse("dark = true\n"));
        assertThrows(IllegalArgumentException.class, () -> ThemeFormat.parse("name = Ocean\n"));
        assertThrows(IllegalArgumentException.class, () -> ThemeFormat.parse(""));
    }

    @Test
    void aValueThatIsNotWhatItsKeyMeansIsAnError() {
        assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name = Ocean\ndark = yes\n"));
        assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name = Ocean\ndark = true\nprimary = teal\n"));
        assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name = Ocean\ndark = true\nprimary\n"));
        assertThrows(IllegalArgumentException.class,
                () -> ThemeFormat.parse("name =\ndark = true\n"));
    }
}
