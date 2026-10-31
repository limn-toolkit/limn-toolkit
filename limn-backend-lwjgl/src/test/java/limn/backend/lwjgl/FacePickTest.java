package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which face of a many-faced family draws a request for regular or bold: by the weight each face
 * declares, never by the order its collection lists it in.
 */
class FacePickTest {

    private static final Path FILE = Path.of("many.ttc");

    /** The twelve faces of a system family, in the order its collection lists them. */
    private static final List<SystemFonts.Face> MANY_WEIGHTS = List.of(
            face("Bold", 700, false, 5), face("Bold Italic", 700, true, 5),
            face("Demi Bold", 600, false, 5), face("Demi Bold Italic", 600, true, 5),
            face("Italic", 400, true, 5), face("Medium", 500, false, 5),
            face("Medium Italic", 500, true, 5), face("Regular", 400, false, 5),
            face("Heavy", 800, false, 5), face("Heavy Italic", 800, true, 5),
            face("Ultra Light", 200, false, 5), face("Ultra Light Italic", 200, true, 5));

    private static SystemFonts.Face face(String style, int weight, boolean italic, int width) {
        return new SystemFonts.Face("Many", style, FILE, 0, weight >= 600, italic, weight, width);
    }

    private static String pick(List<SystemFonts.Face> faces, boolean bold, boolean italic) {
        return FontStore.pickFace(faces, bold, italic).style();
    }

    @Test
    void theRegularOfAManyWeightFamilyIsItsRegularNotTheFirstFaceThatIsNotBold() {
        // Before: "Medium", the first face listed that is neither bold nor italic.
        assertEquals("Regular", pick(MANY_WEIGHTS, false, false));
        assertEquals("Italic", pick(MANY_WEIGHTS, false, true));
    }

    @Test
    void theBoldIsTheSevenHundredNotTheFirstFaceWhoseNameSaysBold() {
        List<SystemFonts.Face> demiFirst = List.of(face("Demi Bold", 600, false, 5),
                face("Regular", 400, false, 5), face("Bold", 700, false, 5));
        assertEquals("Bold", pick(demiFirst, true, false));
        assertEquals("Bold Italic", pick(MANY_WEIGHTS, true, true));
    }

    @Test
    void aMissingWeightIsSearchedInTheStyleSheetOrder() {
        // Regular asked of a family with no 400: 500 comes before anything lighter.
        List<SystemFonts.Face> noRegular = List.of(face("Light", 300, false, 5),
                face("Medium", 500, false, 5), face("Bold", 700, false, 5));
        assertEquals("Medium", pick(noRegular, false, false));
        // Bold asked of a family with no 700: heavier before lighter.
        List<SystemFonts.Face> noBold = List.of(face("Semibold", 600, false, 5),
                face("Regular", 400, false, 5), face("Black", 900, false, 5));
        assertEquals("Black", pick(noBold, true, false));
    }

    @Test
    void aBoldItalicTheFamilyLacksKeepsTheWeightBeforeTheSlant() {
        List<SystemFonts.Face> noBoldItalic = List.of(face("Regular", 400, false, 5),
                face("Italic", 400, true, 5), face("Bold", 700, false, 5));
        assertEquals("Bold", pick(noBoldItalic, true, true));
        assertEquals("Italic", pick(noBoldItalic, false, true));
    }

    @Test
    void aCondensedFaceNeverStandsInForTheNormalWidthOne() {
        List<SystemFonts.Face> widths = List.of(face("Condensed Bold", 700, false, 3),
                face("Bold", 700, false, 5), face("Condensed Regular", 400, false, 3),
                face("Regular", 400, false, 5));
        assertEquals("Bold", pick(widths, true, false));
        assertEquals("Regular", pick(widths, false, false));
    }

    @Test
    void twoFacesOfOneWeightGoToThePlainName() {
        // A family whose "Book" and "Roman" both declare 400, Book listed first.
        List<SystemFonts.Face> sameWeight = List.of(face("Book", 400, false, 5),
                face("Book Oblique", 400, true, 5), face("Oblique", 400, true, 5),
                face("Roman", 400, false, 5));
        assertEquals("Roman", pick(sameWeight, false, false));
        assertEquals("Oblique", pick(sameWeight, false, true));
    }

    @Test
    void theWeightIsReadFromTheFacesOwnTable() throws Exception {
        for (String style : List.of("Regular", "Bold", "Italic", "BoldItalic")) {
            Path file = Files.createTempFile("roboto", ".ttf");
            try (InputStream in = FacePickTest.class.getResourceAsStream("/limn/fonts/Roboto-" + style + ".ttf")) {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                SystemFonts.Face face = SystemFonts.facesIn(file).get(0);
                boolean bold = style.startsWith("Bold");
                assertEquals(bold ? 700 : 400, face.weight(), style + " weight");
                assertEquals(SystemFonts.NORMAL_WIDTH, face.width(), style + " width");
                assertEquals(bold, face.bold(), style + " bold");
                assertEquals(style.endsWith("Italic"), face.italic(), style + " italic");
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void aStyleNameStandsInForAMissingTable() {
        assertEquals(600, SystemFonts.weightFromStyle("demi bold italic"));
        assertEquals(600, SystemFonts.weightFromStyle("semibold"));
        assertEquals(800, SystemFonts.weightFromStyle("extrabold"));
        assertEquals(900, SystemFonts.weightFromStyle("heavy"));
        assertEquals(200, SystemFonts.weightFromStyle("ultra light"));
        assertEquals(300, SystemFonts.weightFromStyle("light"));
        assertEquals(500, SystemFonts.weightFromStyle("medium"));
        assertEquals(700, SystemFonts.weightFromStyle("bold"));
        assertEquals(400, SystemFonts.weightFromStyle("regular"));
        assertTrue(SystemFonts.weightFromStyle("thin") < 200);
        assertFalse(SystemFonts.weightFromStyle("book") != 400);
    }

    @Test
    void theWidthNearestNormalIsTakenBeforeTheWeight() {
        // A family listing its condensed faces beside its normal ones: 900 asked, and the
        // condensed Black, nearer in weight, loses to the normal-width Bold.
        List<SystemFonts.Face> condensed = List.of(face("Condensed Black", 900, false, 3),
                face("Regular", 400, false, 5), face("Bold", 700, false, 5),
                face("Condensed Bold", 700, false, 3));
        assertEquals("Bold", FontStore.pickFace(condensed, 900, false).style());
        assertEquals("Bold", FontStore.pickFace(condensed, 800, false).style());
    }
}
