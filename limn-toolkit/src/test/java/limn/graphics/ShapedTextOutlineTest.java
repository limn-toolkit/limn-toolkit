package limn.graphics;

import limn.backend.spi.GlyphOutlines;
import limn.print.internal.Outlines;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shaped line's outline, read through a glyph source of squares: each glyph's outline scaled from
 * its face's units and flipped onto the line at its pen and offset, a bold face drawn by thickening
 * thickened, a glyphless cluster outlined from its fallback glyphs, and a picture left out.
 */
class ShapedTextOutlineTest {

    private static final int FACE = 7;
    private static final int BOLD_FACE = 8;
    /** Drawn bold by thickening too, its contours wound the other way, as a face may wind them. */
    private static final int REVERSED_BOLD_FACE = 9;
    private static final Font FONT = new Font("Squares", 10, false, false);

    /** Every glyph a square of 600 by 700 units at 50 from the pen; a picture for "P", glyphs for "G". */
    private static final GlyphOutlines SQUARES = new GlyphOutlines() {
        @Override
        public int unitsPerEm(int faceId) {
            return faceId == FACE || faceId == BOLD_FACE || faceId == REVERSED_BOLD_FACE ? 1000 : 0;
        }

        @Override
        public String faceName(int faceId) {
            return "Squares";
        }

        @Override
        public boolean isSyntheticBold(int faceId) {
            return faceId == BOLD_FACE || faceId == REVERSED_BOLD_FACE;
        }

        @Override
        public boolean outline(int faceId, int glyphId, Path2D.Visitor out) {
            out.moveTo(50, 0);
            if (faceId == REVERSED_BOLD_FACE) {
                out.lineTo(50, 700);
                out.lineTo(650, 700);
                out.lineTo(650, 0);
            } else {
                out.lineTo(650, 0);
                out.lineTo(650, 700);
                out.lineTo(50, 700);
            }
            out.close();
            return true;
        }

        @Override
        public List<Piece> cluster(String characters, Font font) {
            if (characters.equals("P")) {
                return List.of(new Picture(new Image(1, 1, new byte[4]), 1, 1, 0.8f, 1));
            }
            return List.of(new Glyph(FACE, 5, 0.7f), new Glyph(FACE, 6, 0.7f));
        }
    };

    @BeforeEach
    void install() {
        Outlines.install(SQUARES);
    }

    @AfterEach
    void uninstall() {
        Outlines.uninstall(SQUARES);
    }

    private static ShapedText line(int face, int... glyphs) {
        String text = "x".repeat(glyphs.length);
        ShapedText.Builder b = ShapedText.builder(text, FONT, ShapedText.Direction.LTR, glyphs.length);
        b.lineMetrics(8, 2, 12);
        b.run(face, 0, glyphs.length, 0);
        for (int i = 0; i < glyphs.length; i++) {
            b.glyph(glyphs[i], i, 8, 0, i == 1 ? 2 : 0);
        }
        return b.build();
    }

    @Test
    void eachGlyphIsScaledFlippedAndPlacedAtItsPen() {
        Path2D outline = line(FACE, 4, 4).outline(100, 50);
        Rect box = outline.bounds();
        // 10 points over 1000 units: a glyph is 6 wide and 7 tall above the baseline, 0.5 from its pen.
        assertEquals(100.5f, box.x(), 1e-4);
        assertEquals(50 - 7, box.y(), 1e-4, "above the baseline: y up turned down");
        assertEquals(108 + 6.5f - 100.5f, box.width(), 1e-4, "the second at its pen of 8");
        assertEquals(7 + 2, box.height(), 1e-4, "the second 2 below, its offset positive down");
        assertTrue(outline.contains(103, 47) && !outline.contains(107.5f, 47), "ink, then the gap between");
    }

    @Test
    void aFaceDrawnBoldByThickeningIsThickened() {
        Rect plain = line(FACE, 4).outline(0, 50).bounds();
        Rect bold = line(BOLD_FACE, 4).outline(0, 50).bounds();
        // An outline a 24th of the size wide: half of it, 10 / 48, past each edge.
        assertEquals(plain.width() + 10 / 24f, bold.width(), 1e-3);
    }

    @Test
    void aThickenedGlyphIsFilledToItsEdgeWhicheverWayItsContoursWind() {
        // The thickening, an outline a 24th of the size wide, winds one way whatever the glyph's
        // way: added to a glyph wound the other way, the band just inside each edge cancelled to
        // nothing, and the glyph came out hollow there.
        for (int face : new int[] {BOLD_FACE, REVERSED_BOLD_FACE}) {
            Path2D outline = line(face, 4).outline(0, 50);
            String which = face == BOLD_FACE ? "one way" : "the other way";
            // The glyph's left edge stands at 0.5; the thickening reaches 10 / 48 either side of it.
            assertTrue(outline.contains(0.6f, 46), which + ": just inside the edge");
            assertTrue(outline.contains(3, 46), which + ": deep inside");
            assertTrue(outline.contains(0.4f, 46), which + ": just outside, in the thickening");
            assertTrue(!outline.contains(0.2f, 46), which + ": past the thickening");
        }
    }

    @Test
    void aGlyphlessClusterIsItsFallbackGlyphsAndAPictureIsLeftOut() {
        ShapedText.Builder b = ShapedText.builder("GP", FONT, ShapedText.Direction.LTR, 2);
        b.lineMetrics(8, 2, 12);
        b.run(FACE, 0, 2, 0);
        b.glyph(ShapedText.NO_GLYPH, 0, 14, 0, 0);
        b.glyph(ShapedText.NO_GLYPH, 1, 10, 0, 0);
        Path2D outline = b.build().outline(0, 50);
        Rect box = outline.bounds();
        // Two fallback glyphs, the second 7 points on; the picture of "P" after them gives nothing.
        assertEquals(0.5f, box.x(), 1e-4);
        assertEquals(7 + 6.5f - 0.5f, box.width(), 1e-4);
    }

    @Test
    void withoutASourceTheOutlineIsEmpty() {
        Outlines.uninstall(SQUARES);
        assertTrue(line(FACE, 4).outline(0, 0).isEmpty());
        Outlines.install(SQUARES); // for the uninstall after
    }
}
