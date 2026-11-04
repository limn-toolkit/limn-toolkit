package limn.backend.lwjgl;

import limn.graphics.BlendMode;
import org.jspecify.annotations.Nullable;

import static org.lwjgl.opengl.GL33C.GL_DST_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_DST_COLOR;
import static org.lwjgl.opengl.GL33C.GL_ONE;
import static org.lwjgl.opengl.GL33C.GL_ONE_MINUS_DST_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_ONE_MINUS_SRC_COLOR;
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
    /** Screen, {@code cs + cb·(1 − cs)} per channel; on the window the coverage is kept. */
    private static final Factors SCREEN =
            new Factors(GL_ONE, GL_ONE_MINUS_SRC_COLOR, GL_ZERO, GL_ONE);
    /*
     * The Porter-Duff rules whose fixed factors are exact at an antialiased edge, where a draw's
     * coverage m is folded into its alpha: each weighs the destination by 1 or by 1 - as, and
     * 1 - m·as is what weighing the rule's result against the destination by m gives there.
     */
    private static final Factors DESTINATION_OVER =
            new Factors(GL_ONE_MINUS_DST_ALPHA, GL_ONE, GL_ONE_MINUS_DST_ALPHA, GL_ONE);
    private static final Factors DESTINATION_OUT =
            new Factors(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
    private static final Factors SOURCE_ATOP =
            new Factors(GL_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    private static final Factors XOR =
            new Factors(GL_ONE_MINUS_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE_MINUS_DST_ALPHA,
                    GL_ONE_MINUS_SRC_ALPHA);
    /**
     * The factors of a mode the canvas program computes itself ({@link #shaderRule}): its output is
     * the pixel, which replaces the target's.
     */
    static final Factors REPLACE = new Factors(GL_ONE, GL_ZERO, GL_ONE, GL_ZERO);

    /**
     * How a mode draws into a group's picture: one pass, or two over the same geometry, the second
     * with {@code then}'s factors.
     */
    record InGroup(Factors first, @Nullable Factors then) {
    }

    private static final InGroup NORMAL_IN_GROUP = new InGroup(NORMAL, null);
    /**
     * Additive in a group: the colours add, as on the window, and so does the coverage, both
     * clamped, the web's plus-lighter: {@code co = cs + cb}, {@code αo = αs + αb}. Where the group
     * drew nothing the source is itself, and over what it drew opaque the window's sum. The
     * coverage of an over, {@code αs + αb·(1 − αs)}, fell short of the summed colour over
     * translucent content (white at one half over white at one half: a colour of 1 at a coverage of
     * 0.75), a pixel no premultiplied picture may hold, which the composite laid as more light than
     * either rule gives; the sum keeps every channel within the coverage, as {@code cs ≤ αs} and
     * {@code cb ≤ αb}.
     */
    private static final InGroup ADDITIVE_IN_GROUP = new InGroup(
            new Factors(GL_ONE, GL_ONE, GL_ONE, GL_ONE), null);
    /**
     * Multiply in a group, the web's {@code co = cs·(1 − αb) + cs·cb + cb·(1 − αs)} with
     * {@code αo = αs + αb·(1 − αs)}: no one pass of fixed factors gives the first term beside the
     * other two, so the window's multiply goes first, {@code cs·cb + cb·(1 − αs)} with the
     * coverage kept, and a second pass over the same geometry adds the source where the backdrop is
     * not, {@code cs·(1 − αb)}, and grows the coverage by as much. On an opaque backdrop the second
     * adds nothing, and the two are the window's multiply.
     */
    private static final InGroup MULTIPLY_IN_GROUP = new InGroup(MULTIPLY,
            new Factors(GL_ONE_MINUS_DST_ALPHA, GL_ONE, GL_ONE_MINUS_DST_ALPHA, GL_ONE));
    /**
     * Screen in a group, the web's {@code co = cs + cb − cs·cb} with the coverage of an over:
     * premultiplied, the screen of a source over a translucent backdrop needs no term of its own,
     * so one pass gives it.
     */
    private static final InGroup SCREEN_IN_GROUP = new InGroup(
            new Factors(GL_ONE, GL_ONE_MINUS_SRC_COLOR, GL_ONE, GL_ONE_MINUS_SRC_ALPHA), null);

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
            case SCREEN -> SCREEN;
            case DESTINATION_OVER -> DESTINATION_OVER;
            case DESTINATION_OUT -> DESTINATION_OUT;
            case SOURCE_ATOP -> SOURCE_ATOP;
            case XOR -> XOR;
            case DARKEN, LIGHTEN, CLEAR, SOURCE, SOURCE_IN, DESTINATION_IN, SOURCE_OUT, DESTINATION_ATOP ->
                    REPLACE;
        };
    }

    /*
     * The rules the canvas program computes from a copy of the target, numbered as canvas.frag and
     * canvas_composite_clip.frag number them. A rule that weighs the destination by 0 or by as
     * leaves the destination at an antialiased edge more or less than the edge's coverage says when
     * the coverage is folded into the source's alpha, which is all fixed factors can see; and the
     * minimum and maximum equations take no factors at all, so darken and lighten by them darkened
     * every antialiased edge toward black (a white shape's edge over grey: min(m, 0.5)). The
     * program keeps the coverage apart and gives each pixel its rule's result weighed against the
     * destination by the coverage.
     */
    static final int RULE_NONE = 0;
    static final int RULE_CLEAR = 1;
    static final int RULE_SOURCE = 2;
    static final int RULE_SOURCE_IN = 3;
    static final int RULE_DESTINATION_IN = 4;
    static final int RULE_SOURCE_OUT = 5;
    static final int RULE_DESTINATION_ATOP = 6;
    /** Darken and lighten in a group, by the web's separable formula with an over's coverage. */
    static final int RULE_DARKEN = 7;
    static final int RULE_LIGHTEN = 8;
    /** Darken and lighten on the window, whose coverage they leave as {@link #MULTIPLY} does. */
    static final int RULE_DARKEN_KEEP = 9;
    static final int RULE_LIGHTEN_KEEP = 10;

    /**
     * The rule the canvas program computes for {@code mode}, or {@link #RULE_NONE} when the fixed
     * factors of {@link #of} or {@link #inGroup} draw it exactly; drawn under {@link #REPLACE}.
     *
     * @param inGroup whether the target is a group's picture rather than the window
     */
    static int shaderRule(BlendMode mode, boolean inGroup) {
        return switch (mode) {
            case CLEAR -> RULE_CLEAR;
            case SOURCE -> RULE_SOURCE;
            case SOURCE_IN -> RULE_SOURCE_IN;
            case DESTINATION_IN -> RULE_DESTINATION_IN;
            case SOURCE_OUT -> RULE_SOURCE_OUT;
            case DESTINATION_ATOP -> RULE_DESTINATION_ATOP;
            case DARKEN -> inGroup ? RULE_DARKEN : RULE_DARKEN_KEEP;
            case LIGHTEN -> inGroup ? RULE_LIGHTEN : RULE_LIGHTEN_KEEP;
            default -> RULE_NONE;
        };
    }

    /**
     * The factors of {@code mode} drawn into a group's picture, which starts transparent: the mode
     * blends with what the group drew there, its backdrop, and where the group drew nothing the
     * source shows as itself, as an isolated group of the web's compositing rules has it. The
     * window's factors leave the target's coverage alone, right for a window, whose coverage the
     * system's compositor reads, and for a 3D target laid over a widget; in a picture, whose
     * coverage is what the composite lays over the window, they multiplied or added to nothing.
     * Equal to {@link #of} wherever the backdrop is opaque.
     */
    static InGroup inGroup(BlendMode mode) {
        return switch (mode) {
            case NORMAL -> NORMAL_IN_GROUP;
            case ADDITIVE -> ADDITIVE_IN_GROUP;
            case MULTIPLY -> MULTIPLY_IN_GROUP;
            case SCREEN -> SCREEN_IN_GROUP;
            // The Porter-Duff rules are defined against the target's coverage, which in a picture
            // is the group's own: the window's factors are the group's.
            case DESTINATION_OVER -> DESTINATION_OVER_IN_GROUP;
            case DESTINATION_OUT -> DESTINATION_OUT_IN_GROUP;
            case SOURCE_ATOP -> SOURCE_ATOP_IN_GROUP;
            case XOR -> XOR_IN_GROUP;
            case DARKEN, LIGHTEN, CLEAR, SOURCE, SOURCE_IN, DESTINATION_IN, SOURCE_OUT, DESTINATION_ATOP ->
                    REPLACE_IN_GROUP;
        };
    }

    private static final InGroup DESTINATION_OVER_IN_GROUP = new InGroup(DESTINATION_OVER, null);
    private static final InGroup DESTINATION_OUT_IN_GROUP = new InGroup(DESTINATION_OUT, null);
    private static final InGroup SOURCE_ATOP_IN_GROUP = new InGroup(SOURCE_ATOP, null);
    private static final InGroup XOR_IN_GROUP = new InGroup(XOR, null);
    private static final InGroup REPLACE_IN_GROUP = new InGroup(REPLACE, null);
}
