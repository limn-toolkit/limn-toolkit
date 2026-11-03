package limn.backend.lwjgl;

import limn.graphics.BlendMode;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * A modal's veil: colour {@code S·a·A + C(1 - a)}, coverage {@code A + A·a}, so clear stays
     * clear, opaque is source-over, and a translucent pixel covers more; and it is none of the
     * application's modes, whose factors it must not share.
     */
    @Test
    void theVeilScalesCoverageByOnePlusItsAlphaAndIsNoApplicationMode() {
        GlBlend.Factors f = GlBlend.VEIL;
        assertEquals(GL33C.GL_DST_ALPHA, f.srcRgb());
        assertEquals(GL33C.GL_ONE_MINUS_SRC_ALPHA, f.dstRgb());
        assertEquals(GL33C.GL_DST_ALPHA, f.srcAlpha());
        assertEquals(GL33C.GL_ONE, f.dstAlpha());
        for (BlendMode mode : BlendMode.values()) {
            org.junit.jupiter.api.Assertions.assertNotEquals(GlBlend.of(mode), f, mode.name());
        }
    }

    @Test
    void inAGroupNormalIsTheWindowsAndAdditiveSumsColourAndCoverage() {
        GlBlend.InGroup normal = GlBlend.inGroup(BlendMode.NORMAL);
        assertSame(GlBlend.of(BlendMode.NORMAL), normal.first());
        assertNull(normal.then());
        GlBlend.InGroup additive = GlBlend.inGroup(BlendMode.ADDITIVE);
        assertEquals(GL33C.GL_ONE, additive.first().srcRgb());
        assertEquals(GL33C.GL_ONE, additive.first().dstRgb(), "light still adds");
        // In a group's picture the coverage is what the composite lays over the window: a glow
        // drawn where the group drew nothing must cover as itself, or it adds to nothing; and the
        // coverage sums as the colour does, or a channel ends above it (plus-lighter).
        assertEquals(GL33C.GL_ONE, additive.first().srcAlpha());
        assertEquals(GL33C.GL_ONE, additive.first().dstAlpha());
        assertNull(additive.then(), "one pass");
    }

    @Test
    void inAGroupMultiplyIsTheWindowsThenTheSourceWhereTheBackdropIsNot() {
        GlBlend.InGroup multiply = GlBlend.inGroup(BlendMode.MULTIPLY);
        assertSame(GlBlend.of(BlendMode.MULTIPLY), multiply.first(), "the window's multiply first");
        GlBlend.Factors then = multiply.then();
        assertNotNull(then, "and a second pass over the same geometry");
        assertEquals(GL33C.GL_ONE_MINUS_DST_ALPHA, then.srcRgb(), "the source where the backdrop is not");
        assertEquals(GL33C.GL_ONE, then.dstRgb());
        assertEquals(GL33C.GL_ONE_MINUS_DST_ALPHA, then.srcAlpha());
        assertEquals(GL33C.GL_ONE, then.dstAlpha(), "the coverage of an over");
    }

    @Test
    void screenAndThePorterDuffRulesTheFactorsDrawExactly() {
        GlBlend.Factors screen = GlBlend.of(BlendMode.SCREEN);
        assertEquals(GL33C.GL_ONE, screen.srcRgb());
        assertEquals(GL33C.GL_ONE_MINUS_SRC_COLOR, screen.dstRgb(), "cs + cb·(1 − cs)");
        assertEquals(GL33C.GL_ZERO, screen.srcAlpha(), "on the window the coverage is kept");
        assertEquals(GL33C.GL_ONE, screen.dstAlpha());
        GlBlend.Factors inGroup = GlBlend.inGroup(BlendMode.SCREEN).first();
        assertEquals(GL33C.GL_ONE, inGroup.srcAlpha(), "in a group, an over's coverage");
        assertEquals(GL33C.GL_ONE_MINUS_SRC_ALPHA, inGroup.dstAlpha());
        // Each weighs the destination by 1 or by 1 − as, which an antialiased edge's coverage,
        // folded into the source's alpha, keeps exact.
        for (BlendMode mode : new BlendMode[] {BlendMode.DESTINATION_OVER, BlendMode.DESTINATION_OUT,
                BlendMode.SOURCE_ATOP, BlendMode.XOR}) {
            GlBlend.Factors f = GlBlend.of(mode);
            assertTrue(f.dstRgb() == GL33C.GL_ONE || f.dstRgb() == GL33C.GL_ONE_MINUS_SRC_ALPHA, mode.name());
            assertEquals(GlBlend.RULE_NONE, GlBlend.shaderRule(mode, false), mode + " needs no rule");
            assertSame(f, GlBlend.inGroup(mode).first(), mode + ": the group's are the window's");
        }
    }

    @Test
    void theOtherRulesAreComputedByTheProgramAndReplaceThePixel() {
        for (BlendMode mode : new BlendMode[] {BlendMode.CLEAR, BlendMode.SOURCE, BlendMode.SOURCE_IN,
                BlendMode.DESTINATION_IN, BlendMode.SOURCE_OUT, BlendMode.DESTINATION_ATOP, BlendMode.DARKEN,
                BlendMode.LIGHTEN}) {
            assertSame(GlBlend.REPLACE, GlBlend.of(mode), mode.name());
            assertSame(GlBlend.REPLACE, GlBlend.inGroup(mode).first(), mode + " in a group");
            assertTrue(GlBlend.shaderRule(mode, true) != GlBlend.RULE_NONE, mode + ": a rule");
        }
        assertEquals(GlBlend.RULE_DARKEN_KEEP, GlBlend.shaderRule(BlendMode.DARKEN, false), "the window's coverage kept");
        assertEquals(GlBlend.RULE_DARKEN, GlBlend.shaderRule(BlendMode.DARKEN, true));
        for (BlendMode mode : new BlendMode[] {BlendMode.NORMAL, BlendMode.ADDITIVE, BlendMode.MULTIPLY}) {
            assertEquals(GlBlend.RULE_NONE, GlBlend.shaderRule(mode, true), mode.name());
        }
    }

    @Test
    void everyModeResolvesToOneSharedConstant() {
        // Shared instances, not per-call allocations: this runs in the 2D emission
        // path, which is a hot loop.
        for (BlendMode mode : BlendMode.values()) {
            assertSame(GlBlend.of(mode), GlBlend.of(mode), mode.name());
            assertSame(GlBlend.inGroup(mode), GlBlend.inGroup(mode), mode.name() + " in a group");
        }
    }
}
