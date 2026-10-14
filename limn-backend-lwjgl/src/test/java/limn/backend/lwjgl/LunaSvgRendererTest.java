package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.graphics.SvgRenderer;
import limn.io.MalformedContentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend's SVG renderer: {@code liblimnsvg} from the {@code limn-lunasvg-natives} payload,
 * extracted from the class path, handshaken, and drawing on the CPU with no window and no GL
 * context (ADR 066).
 */
class LunaSvgRendererTest {

    private static final SvgRenderer RENDERER = new LunaSvgRenderer();

    private static byte[] utf8(String svg) {
        return svg.getBytes(StandardCharsets.UTF_8);
    }

    private static int alphaAt(Image image, int x, int y) {
        return image.pixels()[(y * image.width() + x) * 4 + 3] & 0xFF;
    }

    @Test
    void thePayloadForThisMachineLoadsAndSpeaksThisBackendsAbi() {
        assertTrue(LunaSvgLibrary.isAvailable(), String.valueOf(LunaSvgLibrary.failure()));
        assertNull(LunaSvgLibrary.failure());
        assertEquals(LunaSvgLibrary.EXPECTED_ABI, LunaSvgNative.abi());
        assertTrue(LunaSvgNative.lunasvgVersion().startsWith("3."), LunaSvgNative.lunasvgVersion());
    }

    @Test
    void aShapeIsDrawnWhereItIsAtTheSizeAsked() {
        try (SvgRenderer.Document document = RENDERER.open(utf8("<svg xmlns='http://www.w3.org/2000/svg' "
                + "viewBox='0 0 10 10'><rect x='1' y='1' width='8' height='8' fill='#000'/></svg>"))) {
            assertEquals(10, document.width());
            assertEquals(10, document.height());
            Image image = document.render(32, 32);
            assertEquals(32, image.width());
            assertEquals(255, alphaAt(image, 16, 16), "inside the square");
            assertEquals(0, alphaAt(image, 1, 1), "outside it");
        }
    }

    @Test
    void anyAspectIsMeasuredAndDrawnAsItIs() {
        try (SvgRenderer.Document document = RENDERER.open(utf8("<svg xmlns='http://www.w3.org/2000/svg' "
                + "width='40' height='10'><rect width='20' height='10' fill='red'/></svg>"))) {
            assertEquals(40, document.width());
            assertEquals(10, document.height());
            Image image = document.render(80, 20);
            assertEquals(255, alphaAt(image, 10, 10), "the left half is the rectangle");
            assertEquals(0, alphaAt(image, 70, 10), "the right half is empty");
        }
    }

    @Test
    void anAttributeChangedBetweenTwoDrawingsChangesTheSecond() {
        try (SvgRenderer.Document document = RENDERER.open(utf8("<svg xmlns='http://www.w3.org/2000/svg' "
                + "viewBox='0 0 10 10'><circle id='dot' cx='2' cy='5' r='2'/></svg>"))) {
            assertEquals(255, alphaAt(document.render(10, 10), 2, 5));
            assertTrue(document.setAttribute("dot", "cx", "8"));
            Image moved = document.render(10, 10);
            assertEquals(0, alphaAt(moved, 2, 5), "it left");
            assertEquals(255, alphaAt(moved, 8, 5), "and is where it was sent");
        }
    }

    @Test
    void whatIsNoSvgOrHasNoSizeIsRefusedAsDamagedContent() {
        assertThrows(MalformedContentException.class, () -> RENDERER.open(utf8("this is not an svg")));
        assertThrows(MalformedContentException.class, () -> RENDERER.open(utf8(
                "<svg xmlns='http://www.w3.org/2000/svg' width='0' height='0'/>")));
    }

    @Test
    void aClosedDocumentRefusesAndClosingTwiceIsNothing() {
        SvgRenderer.Document document = RENDERER.open(utf8("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'/>"));
        document.close();
        document.close();
        assertThrows(IllegalStateException.class, () -> document.render(4, 4));
    }

    @Test
    void aDrawingAboveTheCeilingIsRefusedBeforeAnyMemoryIsAsked() {
        try (SvgRenderer.Document document = RENDERER.open(utf8("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'/>"))) {
            assertThrows(IllegalArgumentException.class, () -> document.render(LunaSvgRenderer.MAX_PIXEL_SIZE + 1, 4));
        }
    }
}
