package limn.backend.lwjgl;

import limn.graphics.BlendMode;

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
