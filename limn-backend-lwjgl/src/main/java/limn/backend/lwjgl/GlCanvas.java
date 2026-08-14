package limn.backend.lwjgl;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.LinearGradient;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.PolygonTriangulator;
import limn.graphics.RadialGradient;
import limn.graphics.RoundRect;
import limn.graphics.TextMetrics;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL33C.GL_DST_COLOR;
import static org.lwjgl.opengl.GL33C.GL_ONE;
import static org.lwjgl.opengl.GL33C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_ZERO;
import static org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.GL_STENCIL_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.glClear;
import static org.lwjgl.opengl.GL33C.glClearColor;

/**
 * OpenGL implementation of {@link Canvas}: applies the state stack (transform,
 * opacity, clip) on the CPU, converts every shape into an SDF-carrying quad
 * (or plain triangles for path fills) and appends it to the {@link GlBatch}.
 * Antialiasing happens analytically in the fragment shader; this class only
 * guarantees each quad covers the shape plus ~1 device pixel of AA skirt.
 *
 * <p>Hot-path rule: no allocation per draw call on the shape paths (rects,
 * round rects, circles, lines); state objects, path buffers and scratch
 * arrays are pooled and reused across frames. Exception (v1): {@code fillPath}
 * triangulation allocates its index arrays per call; cached path geometry is
 * planned alongside the widget layer.
 */
final class GlCanvas implements Canvas {

    private static final System.Logger LOG = System.getLogger(GlCanvas.class.getName());

    private static final int KIND_ROUND_RECT = 0;
    private static final int KIND_ELLIPSE = 1;
    private static final int KIND_PLAIN = 2;
    private static final int KIND_FRINGE = 3;
    private static final int KIND_GLYPH = 4;
    private static final int KIND_IMAGE = 5;
    private static final int KIND_IMAGE_MASK = 6; // texel alpha = coverage, colored by tint (icons)
    private static final int KIND_BACKDROP = 8;    // samples a copy of the framebuffer under it
    private static final int KIND_HDR_SURFACE = 7; // 3D target: premultiplied linear;
                                                   // the shader applies the display transform
    private static final float FRINGE_MITER_LIMIT = 2f;
    private static final float MIN_DEVICE_FONT_SIZE = 0.5f;
    private static final int PAINT_SOLID = 0;
    private static final int PAINT_LINEAR = 1;
    private static final int PAINT_RADIAL = 2;
    private static final float AA_PAD_DEVICE = 1.0f;
    private static final float FLATTEN_TOLERANCE_DEVICE = 0.25f;
    private static final float EPSILON = 1e-6f;

    private static final class State {
        final Transform2D transform = new Transform2D();
        float opacity = 1;
        float clipX0, clipY0, clipX1, clipY1; // device px
        float clipRadius;                     // device px
        limn.graphics.BlendMode blendMode = limn.graphics.BlendMode.NORMAL;
        limn.graphics.Sampling sampling = limn.graphics.Sampling.SMOOTH;
        boolean pixelSnap = true;             // image quads only (see Canvas doc)

        void copyFrom(State other) {
            transform.copyFrom(other.transform);
            opacity = other.opacity;
            clipX0 = other.clipX0;
            clipY0 = other.clipY0;
            clipX1 = other.clipX1;
            clipY1 = other.clipY1;
            clipRadius = other.clipRadius;
            blendMode = other.blendMode;
            sampling = other.sampling;
            pixelSnap = other.pixelSnap;
        }
    }

    // The canvas rendering the current frame: set at beginFrame, cleared at
    // endFrame. Lets the (stateless) 3D provider route to this window's context.
    // Safe as a static: rendering is single-threaded on the UI thread, one window
    // at a time (no nested frames).
    private static GlCanvas current;

    private final GlBatch batch = new GlBatch();
    private final GlyphAtlas atlas = new GlyphAtlas();
    private final ImageTextureCache imageTextures = new ImageTextureCache();
    private final FontStore fontStore;
    private final List<State> states = new ArrayList<>();
    private final PathCollector pathCollector = new PathCollector();
    private Gl3DContext gl3d; // lazy: only windows that use 3D pay for it
    private GlVideoContext glVideo; // lazy: only windows that play video pay for it
    private int depth;
    private int fbWidth;
    private int fbHeight;
    private GlBackdrop backdrop; // lazy: only a window that draws an effect pays for the copy
    private float scale = 1;
    private boolean inFrame;

    GlCanvas(FontStore fontStore) {
        this.fontStore = fontStore;
        states.add(new State());
    }

    void beginFrame(int framebufferWidth, int framebufferHeight, float contentScale) {
        fbWidth = framebufferWidth;
        fbHeight = framebufferHeight;
        scale = contentScale;
        depth = 0;
        State root = states.get(0);
        root.transform.setScale(contentScale);
        root.opacity = 1;
        root.clipX0 = 0;
        root.clipY0 = 0;
        root.clipX1 = framebufferWidth;
        root.clipY1 = framebufferHeight;
        root.clipRadius = 0;
        root.blendMode = limn.graphics.BlendMode.NORMAL;
        root.sampling = limn.graphics.Sampling.SMOOTH;
        root.pixelSnap = true;
        // Frame boundary: no pending geometry references the old pages/textures.
        boolean texturesDeleted = imageTextures.beginFrameAndEvict();
        texturesDeleted |= atlas.beginFrameAndEvict();
        if (texturesDeleted) {
            batch.resetTexture();
        }
        batch.beginFrame(framebufferWidth, framebufferHeight);
        inFrame = true;
        current = this;
    }

    void endFrame() {
        if (depth != 0) {
            LOG.log(Level.WARNING, "unbalanced save()/restore(): {0} save(s) not restored", depth);
        }
        batch.flush();
        inFrame = false;
        current = null;
    }

    /** The canvas rendering the current frame (its GL context is current), or {@code null}. */
    static GlCanvas current() {
        return current;
    }

    /** This context's 3D resources, created on first use. */
    Gl3DContext gl3d() {
        if (gl3d == null) {
            gl3d = new Gl3DContext();
        }
        return gl3d;
    }

    /** This context's video resources (conversion program + surfaces), created on first use. */
    GlVideoContext glVideo() {
        if (glVideo == null) {
            glVideo = new GlVideoContext(this);
        }
        return glVideo;
    }

    int drawCallsLastFrame() {
        return batch.drawCalls();
    }

    /**
     * Draws whatever geometry is pending and forgets the bound texture, for a
     * caller about to delete a texture that pending geometry may sample. The
     * batch binds the texture it is tracking when it eventually flushes, so a
     * texture freed with a quad against it still queued draws either a GL error
     * or, once the driver recycles the id, some unrelated picture. The atlas and
     * the image cache avoid this by only deleting at a frame boundary; a video
     * surface is resized and released mid-frame by design.
     */
    void flushBeforeDeletingTexture() {
        batch.flush();
        batch.resetTexture();
    }

    /** @return resident texture count + bytes (glyph atlas + image cache + 3D targets + video). */
    limn.backend.RenderStats stats() {
        limn.backend.RenderStats s = atlas.stats().plus(imageTextures.stats());
        if (backdrop != null) {
            s = s.plus(backdrop.stats());
        }
        if (gl3d != null) {
            s = s.plus(gl3d.stats());
        }
        return glVideo != null ? s.plus(glVideo.stats()) : s;
    }

    /** @return this canvas's 3D-subsystem stats (empty when it has no 3D context yet). */
    limn.render3d.Render3DStats render3DStats() {
        return gl3d != null ? gl3d.render3DStats() : limn.render3d.Render3DStats.EMPTY;
    }

    void dispose() {
        if (gl3d != null) {
            gl3d.dispose(); // context is current here (LwjglWindow.destroy)
        }
        if (glVideo != null) {
            glVideo.dispose();
        }
        atlas.close();
        imageTextures.close();
        if (backdrop != null) {
            backdrop.dispose();
        }
        batch.close();
    }

    // ------------------------------------------------------------ frame info

    @Override
    public float width() {
        return fbWidth / scale;
    }

    @Override
    public float height() {
        return fbHeight / scale;
    }

    @Override
    public float contentScale() {
        return scale;
    }

    @Override
    public void clear(Color color) {
        ensureInFrame();
        // Draw-then-clear must erase what was drawn: flush before clearing.
        batch.flush();
        // clear() means the WHOLE framebuffer: a damage scissor left enabled by
        // the previous frame's last draw must not clip it (an empty flush above
        // issues no state calls, so the stale scissor would still be armed).
        org.lwjgl.opengl.GL33C.glDisable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
        glClearColor(color.r() * color.a(), color.g() * color.a(), color.b() * color.a(), color.a());
        glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    }

    @Override
    public void clearRect(float x, float y, float width, float height, Color color) {
        ensureInFrame();
        if (width <= 0 || height <= 0) {
            return;
        }
        // Draw-then-clear must erase what was drawn: flush before clearing.
        batch.flush();
        // Partial rendering snaps pass rects to the device-pixel grid, so the
        // products below are integral up to float noise; round() recovers them.
        int x0 = Math.max(0, Math.round(x * scale));
        int y0 = Math.max(0, Math.round(y * scale));
        int x1 = Math.min(fbWidth, Math.round((x + width) * scale));
        int y1 = Math.min(fbHeight, Math.round((y + height) * scale));
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        // A scissored clear REPLACES the region, including alpha 0 on the
        // translucent popup framebuffers, which no blended fill can produce.
        org.lwjgl.opengl.GL33C.glEnable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL33C.glScissor(x0, fbHeight - y1, x1 - x0, y1 - y0);
        glClearColor(color.r() * color.a(), color.g() * color.a(), color.b() * color.a(), color.a());
        glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        // The next batch flush re-arms the damage scissor from its own state.
        org.lwjgl.opengl.GL33C.glDisable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
    }

    @Override
    public void damageScissorHint(float x, float y, float width, float height) {
        ensureInFrame();
        if (width <= 0 || height <= 0) {
            batch.setScissor(false, 0, 0, 0, 0);
            return;
        }
        // Logical → device, one pixel of margin for analytic AA, y flipped
        // (glScissor origin is the bottom-left corner).
        int x0 = Math.max(0, (int) Math.floor(x * scale) - 1);
        int y0 = Math.max(0, (int) Math.floor(y * scale) - 1);
        int x1 = Math.min(fbWidth, (int) Math.ceil((x + width) * scale) + 1);
        int y1 = Math.min(fbHeight, (int) Math.ceil((y + height) * scale) + 1);
        if (x1 <= x0 || y1 <= y0) {
            batch.setScissor(false, 0, 0, 0, 0);
            return;
        }
        batch.setScissor(true, x0, fbHeight - y1, x1 - x0, y1 - y0);
    }

    // ------------------------------------------------------------------ state

    @Override
    public void save() {
        ensureInFrame();
        depth++;
        if (states.size() == depth) {
            states.add(new State());
        }
        states.get(depth).copyFrom(states.get(depth - 1));
    }

    @Override
    public void restore() {
        if (depth == 0) {
            throw new IllegalStateException("restore() without a matching save()");
        }
        depth--;
    }

    @Override
    public int saveCount() {
        return depth;
    }

    @Override
    public void restoreToCount(int count) {
        int floor = Math.max(0, count);
        while (depth > floor) {
            restore();
        }
    }

    @Override
    public void translate(float dx, float dy) {
        state().transform.translate(dx, dy);
    }

    @Override
    public void scale(float sx, float sy) {
        state().transform.scale(sx, sy);
    }

    @Override
    public void rotate(float angleRadians) {
        state().transform.rotate(angleRadians);
    }

    @Override
    public void setOpacity(float opacity) {
        state().opacity = Math.min(1f, Math.max(0f, opacity));
    }

    @Override
    public float opacity() {
        return state().opacity;
    }

    @Override
    public void setBlendMode(limn.graphics.BlendMode mode) {
        state().blendMode = mode == null ? limn.graphics.BlendMode.NORMAL : mode;
    }

    @Override
    public limn.graphics.BlendMode blendMode() {
        return state().blendMode;
    }

    @Override
    public void setSampling(limn.graphics.Sampling sampling) {
        state().sampling = sampling == null ? limn.graphics.Sampling.SMOOTH : sampling;
    }

    @Override
    public limn.graphics.Sampling sampling() {
        return state().sampling;
    }

    @Override
    public void setPixelSnap(boolean snap) {
        state().pixelSnap = snap;
    }

    @Override
    public boolean pixelSnap() {
        return state().pixelSnap;
    }

    /**
     * Pushes the current state's blend factors into the batch (premultiplied
     * pipeline; RGB and alpha separate). Called at every emission entry
     * point: a mode switch with pending geometry flushes.
     *
     * <p>Destination alpha is the window's COVERAGE on translucent
     * framebuffers: ADDITIVE and MULTIPLY change only light/darkness, so they
     * leave it untouched; an additive glow with the idiomatic black-neutral
     * sheet must not punch an opaque rectangle into a transparent overlay. The
     * factors live in {@link GlBlend}, shared with the 3D pass so one mode cannot
     * come to mean two different equations.
     */
    private void applyBlend(State s) {
        GlBlend.Factors f = GlBlend.of(s.blendMode);
        batch.requireBlend(f.srcRgb(), f.dstRgb(), f.srcAlpha(), f.dstAlpha());
    }

    @Override
    public void clipRect(float x, float y, float width, float height) {
        intersectClipAabb(x, y, width, height);
        // Rect clip keeps a previously set corner radius: the radius now
        // applies to the shrunken rect, which can only clip more, the
        // conservative side of the v1 single-round-rect clip model.
    }

    @Override
    public void clipRoundRect(RoundRect roundRect) {
        RoundRect rr = roundRect.normalized();
        intersectClipAabb(rr.x(), rr.y(), rr.width(), rr.height());
        State s = state();
        float maxRadius = Math.max(Math.max(rr.topLeft(), rr.topRight()),
                Math.max(rr.bottomRight(), rr.bottomLeft()));
        // V1 model: one rounded rect; the most recent radius wins, converted
        // to device pixels (exact for axis-aligned transforms).
        s.clipRadius = maxRadius * s.transform.approxScale();
    }

    private void intersectClipAabb(float x, float y, float width, float height) {
        ensureInFrame();
        State s = state();
        Transform2D t = s.transform;
        float x1 = x + Math.max(0, width);
        float y1 = y + Math.max(0, height);
        float dx0 = min4(t.x(x, y), t.x(x1, y), t.x(x, y1), t.x(x1, y1));
        float dy0 = min4(t.y(x, y), t.y(x1, y), t.y(x, y1), t.y(x1, y1));
        float dx1 = max4(t.x(x, y), t.x(x1, y), t.x(x, y1), t.x(x1, y1));
        float dy1 = max4(t.y(x, y), t.y(x1, y), t.y(x, y1), t.y(x1, y1));
        s.clipX0 = Math.max(s.clipX0, dx0);
        s.clipY0 = Math.max(s.clipY0, dy0);
        s.clipX1 = Math.min(s.clipX1, dx1);
        s.clipY1 = Math.min(s.clipY1, dy1);
        if (s.clipX1 < s.clipX0) {
            s.clipX1 = s.clipX0;
        }
        if (s.clipY1 < s.clipY0) {
            s.clipY1 = s.clipY0;
        }
    }

    // ----------------------------------------------------------------- shapes

    @Override
    public void fillRect(float x, float y, float width, float height, Paint paint) {
        if (width <= 0 || height <= 0) {
            return;
        }
        emitRoundRect(x, y, width, height, 0, 0, 0, 0, -1, paint);
    }

    @Override
    public void drawRect(float x, float y, float width, float height, float strokeWidth, Paint paint) {
        if (strokeWidth <= 0 || width < 0 || height < 0) {
            return;
        }
        emitRoundRect(x, y, width, height, 0, 0, 0, 0, strokeWidth, paint);
    }

    @Override
    public void fillRoundRect(RoundRect roundRect, Paint paint) {
        RoundRect rr = roundRect.normalized();
        if (rr.width() <= 0 || rr.height() <= 0) {
            return;
        }
        emitRoundRect(rr.x(), rr.y(), rr.width(), rr.height(),
                rr.topLeft(), rr.topRight(), rr.bottomRight(), rr.bottomLeft(), -1, paint);
    }

    @Override
    public void fillBackdropRoundRect(RoundRect roundRect, limn.graphics.BackdropEffect effect) {
        java.util.Objects.requireNonNull(effect, "effect");
        RoundRect rr = roundRect.normalized();
        if (rr.width() <= 0 || rr.height() <= 0) {
            return;
        }
        ensureInFrame();
        State s = state();
        if (s.clipX1 - s.clipX0 <= 0 || s.clipY1 - s.clipY0 <= 0) {
            return; // fully clipped out: nothing to copy and nothing to draw
        }
        Transform2D t = s.transform;
        float scale = t.approxScale();
        float x1 = rr.x() + rr.width();
        float y1 = rr.y() + rr.height();
        // The copy must cover every texel the shader can reach: the shape's device bounds, the AA
        // skirt, and however far this effect displaces its sample. Short by a pixel and the rim
        // samples whatever an earlier effect left in the texture.
        float margin = reachPx(effect, scale, rr.width() * 0.5f, rr.height() * 0.5f)
                + AA_PAD_DEVICE + 1;
        int cx0 = (int) Math.max(0, Math.floor(min4(t.x(rr.x(), rr.y()), t.x(x1, rr.y()),
                t.x(rr.x(), y1), t.x(x1, y1)) - margin));
        int cy0 = (int) Math.max(0, Math.floor(min4(t.y(rr.x(), rr.y()), t.y(x1, rr.y()),
                t.y(rr.x(), y1), t.y(x1, y1)) - margin));
        int cx1 = (int) Math.min(fbWidth, Math.ceil(max4(t.x(rr.x(), rr.y()), t.x(x1, rr.y()),
                t.x(rr.x(), y1), t.x(x1, y1)) + margin));
        int cy1 = (int) Math.min(fbHeight, Math.ceil(max4(t.y(rr.x(), rr.y()), t.y(x1, rr.y()),
                t.y(rr.x(), y1), t.y(x1, y1)) + margin));
        if (cx1 <= cx0 || cy1 <= cy0) {
            return; // entirely off-screen
        }

        // The batch break this feature costs: what is queued has not reached the framebuffer, and
        // the framebuffer is what is about to be copied.
        batch.flush();
        if (backdrop == null) {
            backdrop = new GlBackdrop();
        }
        int texture = backdrop.capture(fbWidth, fbHeight, cx0, fbHeight - cy1, cx1 - cx0, cy1 - cy0);

        // Half a texel in from the copied region: LINEAR filtering at the boundary would otherwise
        // fetch the neighbour outside it, which holds an older frame.
        float u0 = (cx0 + 0.5f) / fbWidth;
        float u1 = (cx1 - 0.5f) / fbWidth;
        float v0 = (fbHeight - cy1 + 0.5f) / fbHeight; // texture rows are bottom-up
        float v1 = (fbHeight - cy0 - 0.5f) / fbHeight;

        float cx = rr.x() + rr.width() / 2f;
        float cy = rr.y() + rr.height() / 2f;
        float halfW = rr.width() / 2f;
        float halfH = rr.height() / 2f;
        float pad = AA_PAD_DEVICE / t.minScale();

        Color tint = effect.tint();
        applyBlend(s);
        batch.requireTexture(texture);
        // The stroke slot carries the device scale here: a backdrop quad is always a fill, and the
        // shader needs points-to-pixels to turn a rim width or a cell size into a displacement.
        batch.setShape(halfW, halfH, rr.topLeft(), rr.topRight(), rr.bottomRight(), rr.bottomLeft(),
                scale, KIND_BACKDROP, PAINT_SOLID, s.clipRadius);
        batch.setColors(tint.r(), tint.g(), tint.b(), tint.a(),
                variantId(effect), param1(effect), param2(effect), s.opacity);
        batch.setGradient(u0, v0, u1, v1);
        batch.setClip(s.clipX0, s.clipY0, s.clipX1, s.clipY1);
        batch.ensure(4, 6);
        int base = batch.baseVertex();
        putCorner(t, cx, cy, 1, 0, 0, 1, -(halfW + pad), -(halfH + pad));
        putCorner(t, cx, cy, 1, 0, 0, 1, halfW + pad, -(halfH + pad));
        putCorner(t, cx, cy, 1, 0, 0, 1, halfW + pad, halfH + pad);
        putCorner(t, cx, cy, 1, 0, 0, 1, -(halfW + pad), halfH + pad);
        batch.triangle(base, base + 1, base + 2);
        batch.triangle(base, base + 2, base + 3);
    }

    /**
     * How far, in device pixels, this effect can pull a sample away from the fragment.
     *
     * <p>{@code halfW}/{@code halfH} are the shape's own half-extents in points, and only
     * {@code Crt} reads them: its displacement is a fraction of the distance from the shape's
     * centre, so unlike a rim width or a cell size its reach is not knowable from the effect
     * alone. Short by a pixel here and the corners of a bent picture sample whatever the last
     * effect left in the copy.
     */
    private static float reachPx(limn.graphics.BackdropEffect effect, float scale,
            float halfW, float halfH) {
        if (effect instanceof limn.graphics.BackdropEffect.Clear clear) {
            // The slab shift peaks at a grazing ray, where it is exactly the depth; the
            // dispersion spreads the three taps either side of that and never past it.
            return clear.thickness() * scale;
        }
        if (effect instanceof limn.graphics.BackdropEffect.Pixelate pixelate) {
            return pixelate.cell() * scale;
        }
        if (effect instanceof limn.graphics.BackdropEffect.Crt) {
            // None. The bulge clamps its target to the shape's own extent, which the copy
            // already covers, so a tube reads only what is behind the tube however hard it
            // curves. Reserving for the bend instead is what let it reach the widget next
            // door and repeat that content inside the screen.
            return 0;
        }
        if (effect instanceof limn.graphics.BackdropEffect.Blur blur) {
            return blur.radius() * scale;
        }
        return 0; // Wash samples where it stands
    }

    private static float variantId(limn.graphics.BackdropEffect effect) {
        if (effect instanceof limn.graphics.BackdropEffect.Clear) {
            return 0;
        }
        if (effect instanceof limn.graphics.BackdropEffect.Wash) {
            return 1;
        }
        if (effect instanceof limn.graphics.BackdropEffect.Pixelate) {
            return 2;
        }
        return effect instanceof limn.graphics.BackdropEffect.Crt ? 3 : 4;
    }

    private static float param1(limn.graphics.BackdropEffect effect) {
        if (effect instanceof limn.graphics.BackdropEffect.Clear clear) {
            return clear.thickness();
        }
        if (effect instanceof limn.graphics.BackdropEffect.Wash wash) {
            return wash.saturation();
        }
        if (effect instanceof limn.graphics.BackdropEffect.Crt crt) {
            return crt.scanline();
        }
        if (effect instanceof limn.graphics.BackdropEffect.Blur blur) {
            return blur.radius();
        }
        return ((limn.graphics.BackdropEffect.Pixelate) effect).cell();
    }

    private static float param2(limn.graphics.BackdropEffect effect) {
        if (effect instanceof limn.graphics.BackdropEffect.Clear clear) {
            return clear.dispersion();
        }
        if (effect instanceof limn.graphics.BackdropEffect.Crt crt) {
            return crt.curvature();
        }
        if (effect instanceof limn.graphics.BackdropEffect.Blur blur) {
            return blur.axis() == limn.graphics.BackdropEffect.Blur.Axis.X ? 0 : 1;
        }
        if (effect instanceof limn.graphics.BackdropEffect.Wash wash) {
            return wash.lift();
        }
        return 0;
    }

    @Override
    public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
        if (strokeWidth <= 0) {
            return;
        }
        RoundRect rr = roundRect.normalized();
        emitRoundRect(rr.x(), rr.y(), rr.width(), rr.height(),
                rr.topLeft(), rr.topRight(), rr.bottomRight(), rr.bottomLeft(), strokeWidth, paint);
    }

    @Override
    public void fillCircle(float cx, float cy, float radius, Paint paint) {
        if (radius <= 0) {
            return;
        }
        emitSdfQuad(KIND_ROUND_RECT, cx, cy, radius, radius,
                radius, radius, radius, radius, -1, 1, 0, paint);
    }

    @Override
    public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
        if (radius <= 0 || strokeWidth <= 0) {
            return;
        }
        emitSdfQuad(KIND_ROUND_RECT, cx, cy, radius, radius,
                radius, radius, radius, radius, strokeWidth / 2f, 1, 0, paint);
    }

    @Override
    public void fillEllipse(float cx, float cy, float radiusX, float radiusY, Paint paint) {
        if (radiusX <= 0 || radiusY <= 0) {
            return;
        }
        emitSdfQuad(KIND_ELLIPSE, cx, cy, radiusX, radiusY, 0, 0, 0, 0, -1, 1, 0, paint);
    }

    @Override
    public void drawEllipse(float cx, float cy, float radiusX, float radiusY, float strokeWidth, Paint paint) {
        if (radiusX <= 0 || radiusY <= 0 || strokeWidth <= 0) {
            return;
        }
        emitSdfQuad(KIND_ELLIPSE, cx, cy, radiusX, radiusY, 0, 0, 0, 0, strokeWidth / 2f, 1, 0, paint);
    }

    @Override
    public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
        if (strokeWidth <= 0) {
            return;
        }
        ensureInFrame();
        State s = state();
        Transform2D t = s.transform;
        float halfWidth = strokeWidth / 2f;

        // Crisp axis-aligned hairlines: snap the centerline to the pixel grid.
        if (t.isAxisAligned()) {
            if (Math.abs(y1 - y2) < EPSILON) {
                int widthDev = Snapping.strokeWidthDev(strokeWidth, t.m11);
                float deviceY = Snapping.snapCenter(t.y(x1, y1), widthDev);
                y1 = y2 = (deviceY - t.ty) / t.m11;
                halfWidth = widthDev / t.m11 / 2f;
            } else if (Math.abs(x1 - x2) < EPSILON) {
                int widthDev = Snapping.strokeWidthDev(strokeWidth, t.m00);
                float deviceX = Snapping.snapCenter(t.x(x1, y1), widthDev);
                x1 = x2 = (deviceX - t.tx) / t.m00;
                halfWidth = widthDev / t.m00 / 2f;
            }
        }
        emitCapsule(x1, y1, x2, y2, halfWidth, paint);
    }

    @Override
    public void fillPath(Path2D path, Paint paint) {
        ensureInFrame();
        if (path.isEmpty()) {
            return;
        }
        State s = state();
        collectPath(path, s.transform);
        for (int c = 0; c < pathCollector.contourCount(); c++) {
            int start = pathCollector.contourStart(c);
            int count = pathCollector.contourLength(c);
            // Open contour ending on its own start: ignore the duplicate for
            // filling (duplicated vertices poison the ear clipper).
            if (count >= 2
                    && Math.abs(pathCollector.xs[start + count - 1] - pathCollector.xs[start]) < EPSILON
                    && Math.abs(pathCollector.ys[start + count - 1] - pathCollector.ys[start]) < EPSILON) {
                count--;
            }
            if (count < 3) {
                continue;
            }
            fillContour(start, count, paint, s);
        }
    }

    @Override
    public void drawPath(Path2D path, float strokeWidth, Paint paint) {
        ensureInFrame();
        if (path.isEmpty() || strokeWidth <= 0) {
            return;
        }
        collectPath(path, state().transform);
        float halfWidth = strokeWidth / 2f;
        for (int c = 0; c < pathCollector.contourCount(); c++) {
            int start = pathCollector.contourStart(c);
            int count = pathCollector.contourLength(c);
            if (count == 1) {
                fillCircle(pathCollector.xs[start], pathCollector.ys[start], halfWidth, paint);
                continue;
            }
            for (int i = 0; i < count - 1; i++) {
                emitCapsule(pathCollector.xs[start + i], pathCollector.ys[start + i],
                        pathCollector.xs[start + i + 1], pathCollector.ys[start + i + 1],
                        halfWidth, paint);
            }
            if (pathCollector.contourClosed(c)) {
                emitCapsule(pathCollector.xs[start + count - 1], pathCollector.ys[start + count - 1],
                        pathCollector.xs[start], pathCollector.ys[start], halfWidth, paint);
            }
        }
    }

    // ------------------------------------------------------------------ text

    @Override
    public void drawText(String text, float x, float y, Font font, Paint paint) {
        ensureInFrame();
        Objects.requireNonNull(font, "font");
        Objects.requireNonNull(paint, "paint");
        if (text == null || text.isEmpty()) {
            return;
        }
        State s = state();
        if (s.clipX1 - s.clipX0 <= 0 || s.clipY1 - s.clipY0 <= 0) {
            return;
        }
        Transform2D t = s.transform;
        float deviceScale = t.approxScale();
        float deviceSize = font.size() * deviceScale;
        if (deviceSize < MIN_DEVICE_FONT_SIZE) {
            return;
        }
        StbFont primary = fontStore.resolve(font);
        int quantized = GlyphAtlas.quantizeSize(deviceSize);
        float atlasSize = GlyphAtlas.dequantizeSize(quantized);
        // Atlas bitmaps live at the quantized size; pen advances must use the
        // EXACT device size or long strings drift from measureText. Metrics
        // are linear in size, so a ratio corrects them precisely.
        float sizeRatio = deviceSize / atlasSize;

        // Crisp path: axis-aligned uniform transform → glyph bitmaps land 1:1
        // on device pixels, baseline and each glyph origin rounded to the grid.
        boolean snap = t.isUniformAxisAligned();
        int paintType = applyPaint(paint, x, y, 1, 0, 0, 1, s.opacity);
        applyBlend(s);
        batch.setShape(0, 0, 0, 0, 0, 0, -1, KIND_GLYPH, paintType, s.clipRadius);
        batch.setClip(s.clipX0, s.clipY0, s.clipX1, s.clipY1);

        // The run origin is snapped to the device grid: with a fractional
        // origin, each glyph would round independently and the rounding
        // pattern would change as the origin slides by sub-pixel amounts (a
        // tooltip following the pointer, centered text during a live resize),
        // visible as letters "dancing". With an integer origin the per-glyph
        // rounding depends only on the advances, so spacing is stable and the
        // whole run moves in whole pixels.
        float originDevX = snap ? Math.round(t.x(x, y)) : t.x(x, y);
        float baselineDevY = snap ? Math.round(t.y(x, y)) : 0;
        float penDev = 0;   // device px along the baseline (snap path)
        float penUser = 0;  // user units along the baseline (gradient + rotated path)
        int previousCp = -1;
        StbFont previousFace = null;

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || StbFont.isZeroWidthFormat(cp)) {
                previousCp = -1;
                previousFace = null;
                continue;
            }
            boolean primaryHas = primary.hasGlyph(cp);
            // Color emoji: any code point the primary lacks that the color font has
            // is drawn as a bitmap image (its own cmap + advance; measure matches).
            if (!primaryHas) {
                ColorEmojiFont.Emoji colored = fontStore.colorEmojiGlyph(cp);
                if (colored != null) {
                    Image emoji = colored.image();
                    float advanceDev = (float) fontStore.colorEmojiAdvance(cp, deviceSize);
                    float advanceUser = advanceDev / deviceScale;
                    // The picture's own box, NOT the advance: a strike is authored at one size
                    // with its own width, height and height-above-baseline, and the advance is
                    // none of the three. Drawing an advance-sized square stretched the glyph and
                    // lifted it above the line box the run was measured into, which the first
                    // clip on the way out (a Label's own bounds) then cut the top off.
                    float boxW = colored.width() * font.size();
                    float boxH = colored.height() * font.size();
                    float boxTop = colored.top() * font.size();
                    // The paint's alpha applies to emoji too (translucent text
                    // must not carry fully opaque emoji); its RGB does not.
                    float textAlpha = paint instanceof Color solid ? solid.a() : 1;
                    // Square glyph on the baseline (~82% above it; emoji sit low).
                    // Always SMOOTH: text is not a sprite; a widget's PIXELATED
                    // sampling must not alias minified emoji strikes nor flip the
                    // shared emoji texture's filter back and forth per frame.
                    emitImageQuad(emoji, 0, 0, emoji.width(), emoji.height(),
                            x + penUser, y - boxTop, boxW, boxH,
                            1, 1, 1, textAlpha, KIND_IMAGE, false);
                    // The emoji quad overwrote the batch's shape AND its paint (with
                    // the white tint); restore both, or glyphs after the emoji lose
                    // the text color.
                    applyPaint(paint, x, y, 1, 0, 0, 1, s.opacity);
                    applyBlend(s);
                    batch.setShape(0, 0, 0, 0, 0, 0, -1, KIND_GLYPH, paintType, s.clipRadius);
                    batch.setClip(s.clipX0, s.clipY0, s.clipX1, s.clipY1);
                    penDev += advanceDev;
                    penUser += advanceUser;
                    previousCp = -1;
                    previousFace = null;
                    continue;
                }
            }
            // Per-code-point script fallback: CJK glyphs come from a different face.
            StbFont face = primaryHas ? primary : fontStore.faceForCodepoint(primary, cp);
            int faceId = fontStore.faceId(face);
            if (previousCp >= 0 && previousFace == face) {
                float kern = face.kerning(previousCp, cp, atlasSize) * sizeRatio;
                penDev += kern;
                penUser += kern / deviceScale;
            }
            GlyphAtlas.Glyph glyph = atlas.glyph(face, faceId, quantized, cp);
            if (glyph.width() > 0) {
                batch.requireTexture(glyph.texture());
                batch.ensure(4, 6);
                int base = batch.baseVertex();
                if (snap) {
                    float gx0 = Math.round(originDevX + penDev) + glyph.bearingX();
                    float gy0 = baselineDevY + glyph.bearingY();
                    float gx1 = gx0 + glyph.width();
                    float gy1 = gy0 + glyph.height();
                    float lx0 = penUser + glyph.bearingX() / deviceScale;
                    float ly0 = glyph.bearingY() / deviceScale;
                    float lx1 = lx0 + glyph.width() / deviceScale;
                    float ly1 = ly0 + glyph.height() / deviceScale;
                    batch.vertex(gx0, gy0, lx0, ly0, glyph.u0(), glyph.v0());
                    batch.vertex(gx1, gy0, lx1, ly0, glyph.u1(), glyph.v0());
                    batch.vertex(gx1, gy1, lx1, ly1, glyph.u1(), glyph.v1());
                    batch.vertex(gx0, gy1, lx0, ly1, glyph.u0(), glyph.v1());
                } else {
                    float ux0 = x + penUser + glyph.bearingX() / deviceScale;
                    float uy0 = y + glyph.bearingY() / deviceScale;
                    float ux1 = ux0 + glyph.width() / deviceScale;
                    float uy1 = uy0 + glyph.height() / deviceScale;
                    batch.vertex(t.x(ux0, uy0), t.y(ux0, uy0), ux0 - x, uy0 - y, glyph.u0(), glyph.v0());
                    batch.vertex(t.x(ux1, uy0), t.y(ux1, uy0), ux1 - x, uy0 - y, glyph.u1(), glyph.v0());
                    batch.vertex(t.x(ux1, uy1), t.y(ux1, uy1), ux1 - x, uy1 - y, glyph.u1(), glyph.v1());
                    batch.vertex(t.x(ux0, uy1), t.y(ux0, uy1), ux0 - x, uy1 - y, glyph.u0(), glyph.v1());
                }
                batch.triangle(base, base + 1, base + 2);
                batch.triangle(base, base + 2, base + 3);
            }
            penDev += glyph.advance() * sizeRatio;
            penUser += glyph.advance() * sizeRatio / deviceScale;
            previousCp = cp;
            previousFace = face;
        }
    }

    @Override
    public TextMetrics measureText(String text, Font font) {
        Objects.requireNonNull(font, "font");
        return fontStore.measure(font, text);
    }

    // ------------------------------------------------------------------ images

    @Override
    public void drawImage(Image image, float x, float y, float w, float h) {
        emitImageQuad(image, x, y, w, h, 1, 1, 1, 1, KIND_IMAGE);
    }

    @Override
    public void drawImage(Image image, float x, float y, float w, float h, Color tint) {
        emitImageQuad(image, x, y, w, h, tint.r(), tint.g(), tint.b(), tint.a(), KIND_IMAGE);
    }

    @Override
    public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                          float dstX, float dstY, float dstW, float dstH) {
        emitImageQuad(image, srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH,
                1, 1, 1, 1, KIND_IMAGE,
                state().sampling == limn.graphics.Sampling.PIXELATED);
    }

    @Override
    public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                          float dstX, float dstY, float dstW, float dstH, Color tint) {
        emitImageQuad(image, srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH,
                tint.r(), tint.g(), tint.b(), tint.a(), KIND_IMAGE,
                state().sampling == limn.graphics.Sampling.PIXELATED);
    }

    @Override
    public void drawImageMask(Image image, float x, float y, float w, float h, Color tint) {
        emitImageQuad(image, x, y, w, h, tint.r(), tint.g(), tint.b(), tint.a(), KIND_IMAGE_MASK);
    }

    @Override
    public void drawSurface(limn.graphics.GpuSurface surface, float x, float y, float w, float h) {
        // Every GpuSurface is an FBO-backed texture rendered bottom-up (GL
        // origin), so its V is flipped relative to a CPU image, and every one is
        // composited as a quad in the 2D paint order (overlays/clip apply to
        // it). What differs is what its texels MEAN, and therefore which shape
        // kind reads them.
        if (surface instanceof GlRenderTarget gl) {
            // Premultiplied LINEAR scene-referred light (ADR 004): the HDR kind,
            // whose branch applies the display transform with the exposure the
            // 3D pass recorded on the target.
            batch.requireExposure(gl.exposure());
            emitTexturedQuad(gl.colorTexture(), x, y, w, h, 1, 1, 1, 1, KIND_HDR_SURFACE, true);
        } else if (surface instanceof GlVideoSurface video) {
            // Already display-referred and gamma-encoded, like any decoded
            // picture: the ordinary image kind. The HDR kind would run the
            // display transform over it a second time, and the doubly-toned,
            // doubly-encoded result looks like a broken colour matrix rather
            // than like a shape-kind mix-up.
            if (!video.ownedBy(glVideo)) {
                // Texture ids belong to a context, and every context numbers
                // from 1, so drawing another window's surface here would sample
                // whatever this one happens to keep at that number (this
                // window's glyph atlas, most likely) and look like a decode bug.
                LOG.log(Level.WARNING,
                        "drawSurface: this video surface belongs to another window; nothing drawn");
            } else if (video.hasPicture()) {
                emitTexturedQuad(video.colorTexture(), x, y, w, h, 1, 1, 1, 1, KIND_IMAGE, true);
            }
        } else {
            // Never silently. A surface this backend did not create has no
            // texture to sample, and without this a foreign (or newly added)
            // surface type simply draws nothing: no error, no log, and a blank
            // rectangle to debug from.
            LOG.log(Level.WARNING, "drawSurface: {0} was not created by this backend; nothing drawn",
                    surface == null ? "null" : surface.getClass().getName());
        }
    }

    private void emitImageQuad(Image image, float x, float y, float w, float h,
                               float tintR, float tintG, float tintB, float tintA, int kind) {
        emitImageQuad(image, 0, 0, image == null ? 0 : image.width(),
                image == null ? 0 : image.height(), x, y, w, h, tintR, tintG, tintB, tintA, kind,
                state().sampling == limn.graphics.Sampling.PIXELATED);
    }

    private void emitImageQuad(Image image, float srcX, float srcY, float srcW, float srcH,
                               float x, float y, float w, float h,
                               float tintR, float tintG, float tintB, float tintA, int kind,
                               boolean pixelated) {
        ensureInFrame();
        Objects.requireNonNull(image, "image");
        if (w <= 0 || h <= 0 || srcW <= 0 || srcH <= 0) {
            return;
        }
        int texture = imageTextures.textureFor(image);
        // Per-texture filter params apply at DRAW time: geometry already queued
        // against this texture must be drawn with its old filter first.
        if (imageTextures.samplingDiffers(image, pixelated)) {
            batch.flush();
            imageTextures.applySampling(image, pixelated);
        }
        // Source rect (image pixels) → normalized UVs of the image's texture.
        float u0 = srcX / image.width();
        float v0 = srcY / image.height();
        float u1 = (srcX + srcW) / image.width();
        float v1 = (srcY + srcH) / image.height();
        emitTexturedQuad(texture, x, y, w, h, u0, v0, u1, v1,
                tintR, tintG, tintB, tintA, kind);
    }

    /** Full-texture quad for a {@link limn.graphics.GpuSurface} ({@code flipV}: FBOs are bottom-up). */
    private void emitTexturedQuad(int texture, float x, float y, float w, float h,
                                  float tintR, float tintG, float tintB, float tintA,
                                  int kind, boolean flipV) {
        float vTop = flipV ? 1 : 0;
        float vBot = flipV ? 0 : 1;
        emitTexturedQuad(texture, x, y, w, h, 0, vTop, 1, vBot, tintR, tintG, tintB, tintA, kind);
    }

    /**
     * Emits a quad of an already-resident GL texture mapping the UV rectangle
     * {@code (u0,v0)-(u1,v1)}, the sprite/atlas primitive (image cache or a
     * {@link limn.graphics.GpuSurface}).
     */
    private void emitTexturedQuad(int texture, float x, float y, float w, float h,
                                  float u0, float v0, float u1, float v1,
                                  float tintR, float tintG, float tintB, float tintA, int kind) {
        ensureInFrame();
        if (w <= 0 || h <= 0) {
            return;
        }
        State s = state();
        if (s.clipX1 - s.clipX0 <= 0 || s.clipY1 - s.clipY0 <= 0) {
            return;
        }
        Transform2D t = s.transform;
        batch.requireTexture(texture);
        applyBlend(s);
        batch.setShape(0, 0, 0, 0, 0, 0, -1, kind, PAINT_SOLID, s.clipRadius);
        // Straight-alpha tint; the shader multiplies the texel and premultiplies.
        batch.setColors(tintR, tintG, tintB, tintA * s.opacity, 0, 0, 0, 0);
        batch.setGradient(0, 0, 0, 0);
        batch.setClip(s.clipX0, s.clipY0, s.clipX1, s.clipY1);
        batch.ensure(4, 6);
        int base = batch.baseVertex();
        if (t.isUniformAxisAligned() && s.pixelSnap) {
            // Grid-snap the device rect, the same invariant as text run
            // origins: an icon rasterized at its exact device size must land
            // on whole pixels, not be bilinearly smeared across two rows.
            // Sprites moving sub-pixel per frame opt out via setPixelSnap.
            float dx0 = Math.round(t.x(x, y));
            float dy0 = Math.round(t.y(x, y));
            float dx1 = Math.round(t.x(x + w, y + h));
            float dy1 = Math.round(t.y(x + w, y + h));
            batch.vertex(dx0, dy0, 0, 0, u0, v0);
            batch.vertex(dx1, dy0, 0, 0, u1, v0);
            batch.vertex(dx1, dy1, 0, 0, u1, v1);
            batch.vertex(dx0, dy1, 0, 0, u0, v1);
        } else {
            imageVertex(t, x, y, u0, v0);
            imageVertex(t, x + w, y, u1, v0);
            imageVertex(t, x + w, y + h, u1, v1);
            imageVertex(t, x, y + h, u0, v1);
        }
        batch.triangle(base, base + 1, base + 2);
        batch.triangle(base, base + 2, base + 3);
    }

    private void imageVertex(Transform2D t, float userX, float userY, float u, float v) {
        batch.vertex(t.x(userX, userY), t.y(userX, userY), 0, 0, u, v);
    }

    // -------------------------------------------------------------- emission

    private void emitRoundRect(float x, float y, float width, float height,
                               float tl, float tr, float br, float bl,
                               float strokeWidth, Paint paint) {
        ensureInFrame();
        State s = state();
        Transform2D t = s.transform;
        float x1 = x + width;
        float y1 = y + height;
        float strokeHalfWidth = -1;

        if (strokeWidth > 0) {
            if (t.isUniformAxisAligned()) {
                // Snap the stroke centerlines (the rect edges) to the pixel
                // grid. Uniform scale only: the odd/even parity rule assumes a
                // single device width; anisotropic scale renders unsnapped.
                int widthDev = Snapping.strokeWidthDev(strokeWidth, t.m00);
                float dx0 = Snapping.snapCenter(t.x(x, y), widthDev);
                float dy0 = Snapping.snapCenter(t.y(x, y), widthDev);
                float dx1 = Snapping.snapCenter(t.x(x1, y1), widthDev);
                float dy1 = Snapping.snapCenter(t.y(x1, y1), widthDev);
                x = (dx0 - t.tx) / t.m00;
                y = (dy0 - t.ty) / t.m11;
                x1 = (dx1 - t.tx) / t.m00;
                y1 = (dy1 - t.ty) / t.m11;
                strokeHalfWidth = widthDev / t.m00 / 2f;
            } else {
                strokeHalfWidth = strokeWidth / 2f;
            }
        }

        float halfW = (x1 - x) / 2f;
        float halfH = (y1 - y) / 2f;
        if (halfW < 0 || halfH < 0 || (strokeHalfWidth < 0 && (halfW == 0 || halfH == 0))) {
            return;
        }
        float maxRadius = Math.min(halfW, halfH);
        emitSdfQuad(KIND_ROUND_RECT, x + halfW, y + halfH, halfW, halfH,
                Math.min(tl, maxRadius), Math.min(tr, maxRadius),
                Math.min(br, maxRadius), Math.min(bl, maxRadius),
                strokeHalfWidth, 1, 0, paint);
    }

    private void emitCapsule(float x1, float y1, float x2, float y2, float halfWidth, Paint paint) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.hypot(dx, dy);
        if (length < EPSILON) {
            fillCircle(x1, y1, halfWidth, paint);
            return;
        }
        float ux = dx / length;
        float uy = dy / length;
        // A capsule is a rounded rect: half extents (len/2 + hw, hw), radius hw.
        emitSdfQuad(KIND_ROUND_RECT, (x1 + x2) / 2f, (y1 + y2) / 2f,
                length / 2f + halfWidth, halfWidth,
                halfWidth, halfWidth, halfWidth, halfWidth,
                -1, ux, uy, paint);
    }

    /**
     * Emits one SDF quad. {@code (ux, uy)} is the shape's local x-axis in user
     * space (unit vector); local y is its perpendicular. The quad covers the
     * shape's half extents plus stroke and ~1 device pixel of AA skirt.
     */
    private void emitSdfQuad(int kind, float cx, float cy, float halfW, float halfH,
                             float tl, float tr, float br, float bl,
                             float strokeHalfWidth, float ux, float uy, Paint paint) {
        State s = state();
        Transform2D t = s.transform;
        if (s.clipX1 - s.clipX0 <= 0 || s.clipY1 - s.clipY0 <= 0) {
            return; // fully clipped out
        }

        // AA skirt sized against the SMALLEST axis scale, so anisotropic
        // transforms never shrink the skirt below 1 device pixel.
        float pad = AA_PAD_DEVICE / t.minScale() + Math.max(strokeHalfWidth, 0);
        float ex = halfW + pad;
        float ey = halfH + pad;
        float vx = -uy;
        float vy = ux;

        int paintType = applyPaint(paint, cx, cy, ux, uy, vx, vy, s.opacity);
        applyBlend(s);
        batch.setShape(halfW, halfH, tl, tr, br, bl, strokeHalfWidth, kind, paintType, s.clipRadius);
        batch.setClip(s.clipX0, s.clipY0, s.clipX1, s.clipY1);
        batch.ensure(4, 6);
        int base = batch.baseVertex();
        putCorner(t, cx, cy, ux, uy, vx, vy, -ex, -ey);
        putCorner(t, cx, cy, ux, uy, vx, vy, ex, -ey);
        putCorner(t, cx, cy, ux, uy, vx, vy, ex, ey);
        putCorner(t, cx, cy, ux, uy, vx, vy, -ex, ey);
        batch.triangle(base, base + 1, base + 2);
        batch.triangle(base, base + 2, base + 3);
    }

    private void putCorner(Transform2D t, float cx, float cy,
                           float ux, float uy, float vx, float vy,
                           float localX, float localY) {
        float userX = cx + localX * ux + localY * vx;
        float userY = cy + localX * uy + localY * vy;
        batch.vertex(t.x(userX, userY), t.y(userX, userY), localX, localY);
    }

    private void fillContour(int start, int count, Paint paint, State s) {
        // Gradient mapping for plain triangles: identity basis centered on the
        // contour's bounding box.
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = start; i < start + count; i++) {
            minX = Math.min(minX, pathCollector.xs[i]);
            minY = Math.min(minY, pathCollector.ys[i]);
            maxX = Math.max(maxX, pathCollector.xs[i]);
            maxY = Math.max(maxY, pathCollector.ys[i]);
        }
        float cx = (minX + maxX) / 2f;
        float cy = (minY + maxY) / 2f;

        int[] triangles = PolygonTriangulator.triangulate(pathCollector.xs, pathCollector.ys, start, count);
        if (triangles.length == 0) {
            return;
        }

        Transform2D t = s.transform;
        int paintType = applyPaint(paint, cx, cy, 1, 0, 0, 1, s.opacity);
        applyBlend(s);
        batch.setShape(0, 0, 0, 0, 0, 0, -1, KIND_PLAIN, paintType, s.clipRadius);
        batch.setClip(s.clipX0, s.clipY0, s.clipX1, s.clipY1);
        batch.ensure(count, triangles.length);
        int base = batch.baseVertex();
        for (int i = start; i < start + count; i++) {
            float px = pathCollector.xs[i];
            float py = pathCollector.ys[i];
            batch.vertex(t.x(px, py), t.y(px, py), px - cx, py - cy);
        }
        for (int i = 0; i < triangles.length; i += 3) {
            batch.triangle(base + triangles[i], base + triangles[i + 1], base + triangles[i + 2]);
        }

        emitFringeRing(start, count, cx, cy, paintType, s);
    }

    /**
     * Antialiasing skirt for path fills: a one-device-pixel band just outside
     * the contour whose coverage ramps 1 → 0 (NanoVG-style fringe). Interior
     * triangles stay hard-edged; the ring supplies the smooth edge. Vertex
     * normals are averaged between neighbor edges (miter-limited) so the band
     * is continuous, without overlapping quads. Translucent fills may
     * double-blend marginally on the boundary pixel, the v1 trade-off.
     */
    private void emitFringeRing(int start, int count, float cx, float cy, int paintType, State s) {
        Transform2D t = s.transform;
        float fringeWidth = AA_PAD_DEVICE / t.approxScale();
        // Outward side depends on winding: signedArea > 0 = clockwise (y-down).
        float orientation = PolygonTriangulator.signedArea(pathCollector.xs, pathCollector.ys, start, count) > 0
                ? 1f : -1f;

        applyBlend(s);
        batch.setShape(0, 0, 0, 0, 0, 0, -1, KIND_FRINGE, paintType, s.clipRadius);
        batch.setClip(s.clipX0, s.clipY0, s.clipX1, s.clipY1);
        batch.ensure(count * 2, count * 6);
        int base = batch.baseVertex();

        for (int i = 0; i < count; i++) {
            float px = pathCollector.xs[start + i];
            float py = pathCollector.ys[start + i];
            float prevX = pathCollector.xs[start + (i - 1 + count) % count];
            float prevY = pathCollector.ys[start + (i - 1 + count) % count];
            float nextX = pathCollector.xs[start + (i + 1) % count];
            float nextY = pathCollector.ys[start + (i + 1) % count];

            // Outward normals of the two edges meeting at this vertex.
            float n1x = edgeNormalX(prevX, prevY, px, py, orientation);
            float n1y = edgeNormalY(prevX, prevY, px, py, orientation);
            float n2x = edgeNormalX(px, py, nextX, nextY, orientation);
            float n2y = edgeNormalY(px, py, nextX, nextY, orientation);
            float nx = n1x + n2x;
            float ny = n1y + n2y;
            float len = (float) Math.hypot(nx, ny);
            if (len < EPSILON) {
                nx = n2x;
                ny = n2y;
            } else {
                nx /= len;
                ny /= len;
            }
            // Miter factor so the band keeps ~constant width at corners.
            float cosHalf = Math.max(nx * n2x + ny * n2y, 1f / FRINGE_MITER_LIMIT);
            float offset = fringeWidth / cosHalf;

            batch.setFringeCoverage(1f);
            batch.vertex(t.x(px, py), t.y(px, py), px - cx, py - cy);
            float ox = px + nx * offset;
            float oy = py + ny * offset;
            batch.setFringeCoverage(0f);
            batch.vertex(t.x(ox, oy), t.y(ox, oy), ox - cx, oy - cy);
        }
        for (int i = 0; i < count; i++) {
            int in0 = base + i * 2;
            int out0 = in0 + 1;
            int in1 = base + ((i + 1) % count) * 2;
            int out1 = in1 + 1;
            batch.triangle(in0, out0, out1);
            batch.triangle(in0, out1, in1);
        }
    }

    private static float edgeNormalX(float ax, float ay, float bx, float by, float orientation) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.hypot(dx, dy);
        return len < EPSILON ? 0 : orientation * (dy / len);
    }

    private static float edgeNormalY(float ax, float ay, float bx, float by, float orientation) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.hypot(dx, dy);
        return len < EPSILON ? 0 : orientation * (-dx / len);
    }

    /** Loads paint colors/gradient into the batch registers; returns the paint type id. */
    private int applyPaint(Paint paint, float cx, float cy,
                           float ux, float uy, float vx, float vy, float opacity) {
        if (paint instanceof Color c) {
            batch.setColors(c.r(), c.g(), c.b(), c.a() * opacity, 0, 0, 0, 0);
            batch.setGradient(0, 0, 0, 0);
            return PAINT_SOLID;
        }
        if (paint instanceof LinearGradient lg) {
            Color a = lg.start();
            Color b = lg.end();
            batch.setColors(a.r(), a.g(), a.b(), a.a() * opacity,
                    b.r(), b.g(), b.b(), b.a() * opacity);
            float g0x = toLocalX(lg.x0(), lg.y0(), cx, cy, ux, uy);
            float g0y = toLocalY(lg.x0(), lg.y0(), cx, cy, vx, vy);
            float g1x = toLocalX(lg.x1(), lg.y1(), cx, cy, ux, uy);
            float g1y = toLocalY(lg.x1(), lg.y1(), cx, cy, vx, vy);
            batch.setGradient(g0x, g0y, g1x, g1y);
            return PAINT_LINEAR;
        }
        RadialGradient rg = (RadialGradient) paint;
        Color a = rg.center();
        Color b = rg.edge();
        batch.setColors(a.r(), a.g(), a.b(), a.a() * opacity,
                b.r(), b.g(), b.b(), b.a() * opacity);
        batch.setGradient(
                toLocalX(rg.cx(), rg.cy(), cx, cy, ux, uy),
                toLocalY(rg.cx(), rg.cy(), cx, cy, vx, vy),
                rg.radius(), 0);
        return PAINT_RADIAL;
    }

    private static float toLocalX(float px, float py, float cx, float cy, float ux, float uy) {
        return (px - cx) * ux + (py - cy) * uy;
    }

    private static float toLocalY(float px, float py, float cx, float cy, float vx, float vy) {
        return (px - cx) * vx + (py - cy) * vy;
    }

    // ----------------------------------------------------------------- paths

    private void collectPath(Path2D path, Transform2D t) {
        pathCollector.reset();
        path.flatten(FLATTEN_TOLERANCE_DEVICE / t.approxScale(), pathCollector);
        pathCollector.finish();
    }

    private State state() {
        return states.get(depth);
    }

    private void ensureInFrame() {
        if (!inFrame) {
            throw new IllegalStateException("Canvas is only usable inside a frame callback");
        }
    }

    private static float min4(float a, float b, float c, float d) {
        return Math.min(Math.min(a, b), Math.min(c, d));
    }

    private static float max4(float a, float b, float c, float d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }

    /** Reusable sink accumulating flattened contours (user space, no curves). */
    private static final class PathCollector implements Path2D.Flattened {
        float[] xs = new float[128];
        float[] ys = new float[128];
        private int count;
        private int[] starts = new int[8];
        private boolean[] closed = new boolean[8];
        private int contours;

        void reset() {
            count = 0;
            contours = 0;
        }

        @Override
        public void moveTo(float x, float y) {
            finishContour();
            if (contours == starts.length) {
                starts = Arrays.copyOf(starts, starts.length * 2);
                closed = Arrays.copyOf(closed, closed.length * 2);
            }
            starts[contours] = count;
            closed[contours] = false;
            contours++;
            add(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            add(x, y);
        }

        @Override
        public void closePath() {
            if (contours > 0) {
                closed[contours - 1] = true;
            }
        }

        void finish() {
            finishContour();
        }

        private void finishContour() {
            if (contours == 0) {
                return;
            }
            // Closed contours only: drop a trailing point duplicating the
            // start (the closing segment is implicit). Open contours keep it:
            // an open path that happens to end at its start still owes its
            // final stroke segment.
            if (!closed[contours - 1]) {
                return;
            }
            int start = starts[contours - 1];
            int len = count - start;
            if (len >= 2
                    && Math.abs(xs[count - 1] - xs[start]) < EPSILON
                    && Math.abs(ys[count - 1] - ys[start]) < EPSILON) {
                count--;
            }
        }

        private void add(float x, float y) {
            if (count == xs.length) {
                xs = Arrays.copyOf(xs, xs.length * 2);
                ys = Arrays.copyOf(ys, ys.length * 2);
            }
            xs[count] = x;
            ys[count] = y;
            count++;
        }

        int contourCount() {
            return contours;
        }

        int contourStart(int contour) {
            return starts[contour];
        }

        int contourLength(int contour) {
            int end = contour + 1 < contours ? starts[contour + 1] : count;
            return end - starts[contour];
        }

        boolean contourClosed(int contour) {
            return closed[contour];
        }
    }
}
