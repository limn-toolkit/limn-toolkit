package limn.backend.lwjgl;

import limn.graphics.Image;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NanoSVG rasterizes SVG icon bytes to a straight-alpha RGBA8 {@link Image} on
 * the CPU (no AWT, no GL context), so it runs headlessly in the regular task.
 */
class NanoSvgRasterizerTest {

    @Test
    void rasterizesAPathSvgToANonEmptyMask() {
        NanoSvgRasterizer rasterizer = new NanoSvgRasterizer();
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
                + "<rect x=\"1\" y=\"1\" width=\"8\" height=\"8\" fill=\"#000000\"/></svg>";

        Image image = rasterizer.rasterize(svg.getBytes(StandardCharsets.UTF_8), 32);

        assertEquals(32, Math.max(image.width(), image.height()), "fit into the requested box");
        byte[] pixels = image.pixels();
        int opaque = 0;
        for (int i = 3; i < pixels.length; i += 4) {
            if ((pixels[i] & 0xFF) > 200) {
                opaque++;
            }
        }
        // The 8x8 fill covers ~64% of a 32x32 box → hundreds of opaque pixels.
        assertTrue(opaque > 100, "the filled rect produced a solid alpha region, got " + opaque);
    }
}
