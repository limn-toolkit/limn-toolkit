package limn.backend.lwjgl;

import limn.graphics.BlendMode;

import static org.lwjgl.opengl.GL33C.GL_DST_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_DST_COLOR;
import static org.lwjgl.opengl.GL33C.GL_ONE;
import static org.lwjgl.opengl.GL33C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_ZERO;

/**
 * The one blend-factor table, shared by the 2D canvas and the 3D pass.
 *
 * <p>Both pipelines are premultiplied: the canvas by construction, the 3D pass
 * because the generated fragment writes {@code vec4(color * alpha, alpha)}. So a
 * {@link BlendMode} means the same equation in either, and it must map to the same
 * factors in either. Two hand-written copies of this switch would drift, and the
 * symptom would be "additive looks different in 3D", which nobody debugs quickly.
 *
 * <p><b>Destination alpha is the target's coverage</b>, not a colour channel.
 * {@link BlendMode#ADDITIVE} and {@link BlendMode#MULTIPLY} change only how light
 * or dark a pixel is, so they leave it alone ({@code ZERO, ONE}). Writing
 * {@code ONE, ONE} there instead (the obvious-looking "additive") drives coverage
 * to 1 wherever a glow lands, and since a 3D render target is composited into the
 * scene as a premultiplied quad, the glow would start occluding whatever is behind
 * the widget instead of adding to it.
 */
final class GlBlend {

    /** Separate RGB and alpha factors for {@code glBlendFuncSeparate}. */
    record Factors(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
    }

    private static final Factors NORMAL =
            new Factors(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    private static final Factors ADDITIVE =
            new Factors(GL_ONE, GL_ONE, GL_ZERO, GL_ONE);
    private static final Factors MULTIPLY =
            new Factors(GL_DST_COLOR, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

    /**
     * A modal's veil ({@code limn.scene.internal.VeilCanvas}), which no {@link BlendMode} names: for
     * a veil of premultiplied colour {@code S·a} and alpha {@code a} over a pixel of premultiplied
     * colour {@code C} and coverage {@code A}, {@code C' = S·a·A + C(1 - a)} and
     * {@code A' = A·a + A = A(1 + a)}, clamped to 1 by the fixed-point targets the 2D canvas draws
     * into. A clear pixel stays clear ({@code 0} stays {@code 0}), so a dialog's window round its
     * rounded card has no dark corners; an opaque one is exactly what painting the veil over it gives
     * ({@code A = 1}: the colour is source-over's, and {@code 1 + a} clamps to 1); a partly covered
     * one darkens and gains coverage ({@code 0.6} becomes {@code 0.9} under a half-alpha veil), so
     * the desktop seen through a translucent window is dimmed with it, as it was before the veil was
     * multiplied.
     */
    static final Factors VEIL =
            new Factors(GL_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_DST_ALPHA, GL_ONE);

    private GlBlend() {
    }

    static Factors of(BlendMode mode) {
        return switch (mode) {
            case NORMAL -> NORMAL;
            case ADDITIVE -> ADDITIVE;
            case MULTIPLY -> MULTIPLY;
        };
    }
}
