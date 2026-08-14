package limn.backend.lwjgl;

import limn.graphics.BlendMode;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the blend-factor table the 2D canvas and the 3D pass share. Pure constant
 * lookup, no GL context, which is the point of having lifted it out of
 * {@code GlCanvas.applyBlend}: the one piece of blend behaviour that used to be
 * verifiable only from a screenshot is now an integer assertion.
 *
 * <p>The destination-alpha column is the one worth staring at. It is the target's
 * coverage, not a colour, and getting it wrong is invisible on an opaque
 * background and wrong everywhere else.
 */
class GlBlendTest {

    @Test
    void normalIsPremultipliedSourceOver() {
        GlBlend.Factors f = GlBlend.of(BlendMode.NORMAL);
        assertEquals(GL33C.GL_ONE, f.srcRgb(), "premultiplied source is already scaled by alpha");
        assertEquals(GL33C.GL_ONE_MINUS_SRC_ALPHA, f.dstRgb());
        assertEquals(GL33C.GL_ONE, f.srcAlpha());
        assertEquals(GL33C.GL_ONE_MINUS_SRC_ALPHA, f.dstAlpha(), "coverage composites too");
    }

    @Test
    void additiveLeavesDestinationAlphaAlone() {
        GlBlend.Factors f = GlBlend.of(BlendMode.ADDITIVE);
        assertEquals(GL33C.GL_ONE, f.srcRgb());
        assertEquals(GL33C.GL_ONE, f.dstRgb(), "light accumulates");
        // NOT (ONE, ONE): a glow only brightens, it does not cover. With ONE/ONE
        // here every additive pixel drives the target's alpha to 1, and a 3D target
        // composited as a premultiplied quad would start OCCLUDING what is behind
        // the widget instead of adding to it.
        assertEquals(GL33C.GL_ZERO, f.srcAlpha());
        assertEquals(GL33C.GL_ONE, f.dstAlpha());
    }

    @Test
    void multiplyAlsoLeavesDestinationAlphaAlone() {
        GlBlend.Factors f = GlBlend.of(BlendMode.MULTIPLY);
        assertEquals(GL33C.GL_DST_COLOR, f.srcRgb());
        assertEquals(GL33C.GL_ONE_MINUS_SRC_ALPHA, f.dstRgb());
        assertEquals(GL33C.GL_ZERO, f.srcAlpha());
        assertEquals(GL33C.GL_ONE, f.dstAlpha());
    }

    @Test
    void everyModeResolvesToOneSharedConstant() {
        // Shared instances, not per-call allocations: this runs in the 2D emission
        // path, which is a hot loop.
        for (BlendMode mode : BlendMode.values()) {
            assertSame(GlBlend.of(mode), GlBlend.of(mode), mode.name());
        }
    }
}
