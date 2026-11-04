package limn.backend.lwjgl;

import limn.graphics.Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A weight between regular and bold reaches a face: the bundled family's nearest, a system
 * family's own face of that weight, and a variable face drawn at the weight itself.
 */
class FontWeightTest {

    /** The variable face the demo ships, covering 100 to 900 on its wght axis. */
    private static final Path VARIABLE = Path.of("..", "limn-demo", "fonts", "Inter-Variable.ttf");

    private java.util.concurrent.ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = java.util.concurrent.Executors.newFixedThreadPool(1);
        runtime = new limn.concurrent.UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        limn.concurrent.Ui.install(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        limn.concurrent.Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    @Test
    void aWeightBetweenTheBundledFacesDrawsTheNearestInTheStyleSheetOrder() {
        try (FontStore store = new FontStore()) {
            StbFont regular = store.resolve(Font.of(14));
            StbFont bold = store.resolve(Font.of(14).bold());
            boolean medium = FontStore.class.getResource("/limn/fonts/Roboto-Medium.ttf") != null;
            // 500 is Roboto Medium where the jar has it; otherwise, of four faces, the Regular
            // (500 searches 400 first below it), and 600 the Bold (heavier first).
            StbFont at500 = store.resolve(Font.of(14).withWeight(500));
            if (medium) {
                assertEquals("Roboto Medium", at500.name());
            } else {
                assertSame(regular, at500);
            }
            assertSame(bold, store.resolve(Font.of(14).withWeight(600)));
            assertSame(bold, store.resolve(Font.of(14).withWeight(700)));
            assertEquals("Roboto Italic", store.resolve(Font.of(14).withWeight(400).italic()).name());
        }
    }

    @Test
    void aFontKeepsItsBoldFlagAsAWeight() {
        assertEquals(Font.BOLD, new Font("x", 12, true, false).weight());
        assertEquals(Font.REGULAR, new Font("x", 12, false, false).weight());
        assertEquals(new Font("x", 12, true, true), new Font("x", 12, 700, true));
        assertTrue(Font.of(12).withWeight(600).isBold());
        assertEquals(900, Font.of(12).withWeight(900).bold().weight(), "bold() never lightens");
    }

    @Test
    void twoFacesOfOneFamilyThatAreNeitherBoldNorItalicAreTwoResidentFaces(@TempDir Path dir)
            throws Exception {
        Path regularFile = copy("/limn/fonts/Roboto-Regular.ttf", dir.resolve("probe-regular.ttf"));
        Path mediumFile = copy("/limn/fonts/Roboto-Regular.ttf", dir.resolve("probe-medium.ttf"));
        try (FontStore store = new FontStore()) {
            store.setSystemFaces(List.of(
                    new SystemFonts.Face("Probe", "Regular", regularFile, 0, false, false, 400, 5),
                    new SystemFonts.Face("Probe", "Medium", mediumFile, 0, false, false, 500, 5)));
            StbFont regular = store.resolve(new Font("Probe", 14));
            StbFont medium = store.resolve(new Font("Probe", 14, 500, false));
            // Before: both resident under "probe", so the second asked for came back as the first.
            assertEquals("Probe Regular", regular.name());
            assertEquals("Probe Medium", medium.name());
            assertNotSame(regular, medium);
        }
    }

    @Test
    void aVariableFaceIsReadWithItsRange() {
        assertTrue(Files.exists(VARIABLE), "the demo's variable face: " + VARIABLE.toAbsolutePath());
        SystemFonts.Face face = SystemFonts.facesIn(VARIABLE).get(0);
        assertEquals(100, face.minWeight());
        assertEquals(900, face.maxWeight());
        assertTrue(face.variable());
        assertEquals(650, face.instanceWeight(650));
        assertEquals(900, face.instanceWeight(1000));
    }

    @Test
    void aVariableFaceIsShapedAndDrawnAtTheWeightAskedFor() {
        try (FontStore store = new FontStore()) {
            String family = store.loadFile(VARIABLE);
            StbFont light = store.resolve(new Font(family, 32, 100, false));
            StbFont regular = store.resolve(new Font(family, 32, 400, false));
            StbFont black = store.resolve(new Font(family, 32, 900, false));
            assertTrue(light.isInstance() && black.isInstance(), "100 and 900 are instances");
            assertTrue(store.faceId(light) != store.faceId(black), "each instance has its own glyph cache id");
            int h = regular.glyphIndex('H');
            // The advance an instance is shaped with is its own...
            assertTrue(light.glyphAdvance(h, 32) < regular.glyphAdvance(h, 32));
            assertTrue(black.glyphAdvance(h, 32) > regular.glyphAdvance(h, 32));
            // ...and so is the ink it is drawn with.
            long lightInk = ink(light.rasterizeGlyph(h, 32));
            long regularInk = ink(regular.rasterizeGlyph(h, 32));
            long blackInk = ink(black.rasterizeGlyph(h, 32));
            assertTrue(lightInk < regularInk && regularInk < blackInk,
                    "ink 100/400/900: " + lightInk + " / " + regularInk + " / " + blackInk);
            assertSame(black, store.resolve(new Font(family, 32, 900, false)), "made once per weight");
        }
    }

    @Test
    void aFaceIsBoldByTheWeightItDrawsAtNotByItsName() {
        try (FontStore store = new FontStore()) {
            assertEquals(400, store.resolve(new Font("roboto", 14, 400, false)).weight());
            assertEquals(700, store.resolve(new Font("roboto", 14, 700, false)).weight(), "OS/2 of Roboto Bold");
            String family = store.loadFile(VARIABLE);
            StbFont black = store.resolve(new Font(family, 14, 900, false));
            assertTrue(black.isInstance() && !black.name().toLowerCase(java.util.Locale.ROOT).contains("bold"));
            assertEquals(900, black.weight(), "an instance named by its number is as heavy as it draws");
        }
    }

    @Test
    void anInstanceDrawnByHarfBuzzHasTheBoxStbGivesTheSameGlyph() {
        // The default instance drawn both ways: the box and the ink agree.
        try (FontStore store = new FontStore()) {
            String family = store.loadFile(VARIABLE);
            StbFont regular = store.resolve(new Font(family, 32, 400, false));
            int h = regular.glyphIndex('H');
            StbFont.RasterizedGlyph stb = regular.rasterizeGlyph(h, 32);
            StbFont.RasterizedGlyph hb = HarfBuzzGlyphs.rasterize(regular.shaper().font(),
                    regular.unitsPerEm(), h, 32, stb.advance());
            assertNotNull(hb);
            assertEquals(stb.width(), hb.width(), 1);
            assertEquals(stb.height(), hb.height(), 1);
            assertEquals(stb.bearingX(), hb.bearingX(), 1);
            assertEquals(stb.bearingY(), hb.bearingY(), 1);
            long stbInk = ink(stb);
            assertEquals(stbInk, ink(hb), stbInk / 50.0);
        }
    }

    /** The glyph's summed coverage; frees its bitmap, so call it once per glyph. */
    private static long ink(StbFont.RasterizedGlyph glyph) {
        ByteBuffer bitmap = glyph.bitmap();
        long sum = 0;
        for (int i = 0; i < glyph.width() * glyph.height(); i++) {
            sum += bitmap.get(i) & 0xFF;
        }
        org.lwjgl.system.MemoryUtil.memFree(bitmap);
        return sum;
    }

    private static Path copy(String resource, Path to) throws Exception {
        try (InputStream in = FontWeightTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
        }
        return to;
    }
}
