package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The scale a re-based layer's picture is drawn at (ADR 072 section 12, the owner's decision of
 * 2026-10-18): the near edge's magnification, but no more than the natural scale for a picture seen
 * in perspective on a renderer that runs fragment shaders on the CPU. No device: the rule alone,
 * which {@code GlDepthLayerTest} holds on a GPU and on Apple's software renderer.
 */
class PictureScaleTest {

    @Test
    void aPictureInPerspectiveOnASoftwareRendererIsDrawnAtItsNaturalSize() {
        assertEquals(2.0, GlCanvas.pictureScale(2.74, 2.0, true, true), 0);
        assertEquals(1.0, GlCanvas.pictureScale(1.37, 1.0, true, true), 0);
    }

    @Test
    void onAGpuTheNearEdgesMagnificationIsKept() {
        assertEquals(2.74, GlCanvas.pictureScale(2.74, 2.0, true, false), 0);
    }

    @Test
    void anAffinePictureKeepsItsMagnificationOnEitherRenderer() {
        assertEquals(3.3, GlCanvas.pictureScale(3.3, 2.0, false, true), 0);
        assertEquals(3.3, GlCanvas.pictureScale(3.3, 2.0, false, false), 0);
    }

    @Test
    void aPictureSeenSmallerThanItsNaturalSizeIsNotDrawnLarger() {
        // Turned far from a distant eye, the near edge may be seen at less than the natural size:
        // the cap only ever lowers the scale.
        assertEquals(1.6, GlCanvas.pictureScale(1.6, 2.0, true, true), 0);
    }
}
