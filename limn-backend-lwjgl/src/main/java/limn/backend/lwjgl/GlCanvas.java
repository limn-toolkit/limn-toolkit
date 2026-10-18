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

import static org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.GL_STENCIL_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.glClear;

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
    private static final int KIND_SHADOW = 9;      // a rounded rect blurred analytically
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
    /** How far from a whole device pixel an edge may be and still count as on it (float noise). */
    private static final float PIXEL_EDGE_TOLERANCE = 1e-3f;
    /** How far past the antialiasing ramp's reach a rounded rectangle's interior starts, device px. */
    private static final float RING_MARGIN_DEVICE = 1f / 16;
    /**
     * The smallest interior, in device pixels, worth splitting a rounded rectangle's quad for: below
     * it the extra pieces cost more to set up than the pixels they spare the fragment shader.
     */
    private static final float MIN_HOLE_DEVICE_PIXELS = 256;
    /**
     * The smallest part of a solid fill's interior, in device pixels, drawn with the flat program
     * (see {@code emitFlatInterior}); 64 x 64, a 32-point square at 2x.
     */
    static final float FLAT_INTERIOR_MIN_DEVICE_PIXELS = 4096;

    private static final class State {
        final Transform2D transform = new Transform2D();
        float opacity = 1;
        final DeviceClip clip = new DeviceClip();
        limn.graphics.BlendMode blendMode = limn.graphics.BlendMode.NORMAL;
        limn.graphics.Sampling sampling = limn.graphics.Sampling.SMOOTH;
        boolean pixelSnap = true;             // image quads only (see Canvas doc)
        /**
         * The picture this state draws into when it is a layer's (see {@link #saveLayer}), and
         * null for every other state, a save inside a layer included: its restore composites it.
         */
        GlLayerPool.Picture layer;
        /** The layer's rectangle in the window's device pixels, top-down. */
        int layerX;
        int layerY;
        int layerWidth;
        int layerHeight;
        /** What the layer is composited with, before the enclosing state's opacity. */
        float layerOpacity;
        /**
         * Whether the layer is drawn in a space of its own, re-based, and composited through
         * {@link #toParent}: a rotated or skewed subtree. A layer that is not draws on the device
         * pixels of the space it was opened in, and lays its picture back on them.
         */
        boolean rebased;
        /**
         * For a re-based layer, the plane's homography from its own space's device pixels to those
         * of the space it is composited into, row-major: {@code (x, y, w)} of a point is this times
         * {@code (vx, vy, 1)}.
         */
        final double[] toParent = new double[9];

        void copyFrom(State other) {
            transform.copyFrom(other.transform);
            opacity = other.opacity;
            clip.copyFrom(other.clip);
            blendMode = other.blendMode;
            sampling = other.sampling;
            pixelSnap = other.pixelSnap;
            layer = null; // a save is never a layer of its own; saveLayer makes it one
            rebased = false;
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
    /** {@link #snapToStrokeGrid}'s answer: left, top, right, bottom. Reused, as every shape is. */
    private final float[] gridEdges = new float[4];
    private Gl3DContext gl3d; // lazy: only windows that use 3D pay for it
    private GlVideoContext glVideo; // lazy: only windows that play video pay for it
    private int depth;
    private int fbWidth;
    private int fbHeight;
    private GlBackdrop backdrop; // lazy: only a window that draws an effect pays for the copy
    private GlLayerPool layers;  // lazy: only a window that opens a layer pays for the pool
    /** How many layers are open now, nested: the states between here and the root that hold one. */
    private int openLayers;
    /** The scale the last re-based layer was drawn at; see {@link #lastSpaceScale()}. */
    private double lastSpaceScale;
    /** A layer's homography as it opens, before its state holds it; reused, as every scratch is. */
    private final double[] spaceScratch = new double[9];
    /**
     * A polygon being cut, {@code x, y} per corner, and the one each cut writes into: the visible
     * part of a re-based layer, and a composite's quad cut at the near plane.
     */
    private final double[] polygonScratch = new double[2 * GlBatch.MAX_COMPOSITE_CORNERS];
    private final double[] polygonSpare = new double[2 * GlBatch.MAX_COMPOSITE_CORNERS];
    /** The part of a re-based layer the window lets be seen, which its magnification is taken over. */
    private final double[] polygonReach = new double[2 * GlBatch.MAX_COMPOSITE_CORNERS];
    /** The window's own rectangle, in the device pixels of any space, as a clip. */
    private final DeviceClip windowScratch = new DeviceClip();
    /** A composite's corners, {@code x, y, w} each, and their texture coordinates. */
    private final float[] cornerScratch = new float[3 * GlBatch.MAX_COMPOSITE_CORNERS];
    private final float[] uvScratch = new float[2 * GlBatch.MAX_COMPOSITE_CORNERS];
    /** The region a glass reads, as the clip the composites into its copy are cut to. */
    private final DeviceClip readRegion = new DeviceClip();
    /** Maps from a glass's space to each space outward, by state index; see {@link #captureInSpace}. */
    private double[][] spaceMaps = new double[8][9];
    /**
     * The framebuffer the frame draws into when no layer is open, read when the frame's first layer
     * opens: the window's own, or whatever a test or a probe bound before beginning the frame.
     */
    private int baseFramebuffer;
    // Lazy, and normally never built: the standing-in ruler for a canvas nobody installed one for.
    // See ruler() for why that case exists at all and why it is not simply an error.
    private ShapingRuler ownRuler;
    private float scale = 1;
    private boolean inFrame;
    private int flatRects;
    private long flatPixels;
    /**
     * Whether rounded rectangles are drawn in pieces (see {@link #emitRoundRectPieces}); off, each
     * is the single quad over its box. Tests turn it off to render the reference the pieces must
     * match.
     */
    boolean splitRoundRects = true;
    /**
     * Whether a large solid interior of a rounded fill is drawn by the flat program (see
     * {@link #emitFlatInterior}): only where the renderer runs fragment shaders on the CPU, read from
     * the context when the canvas is built. On a GPU the interior stays plain geometry in the same
     * batch, because there the draw calls the flat program adds cost more than the pixels it
     * spares. Tests set it to take either path on whatever device they run on.
     */
    boolean flatInteriors;
    /**
     * The current shape's pieces (see {@link #emitRoundRectPieces}): each boundary's device
     * coordinate and local coordinate, in local order, and the cut to the clip in device pixels.
     */
    private final float[] pieceDevX = new float[6];
    private final float[] pieceLocX = new float[6];
    private final float[] pieceDevY = new float[6];
    private final float[] pieceLocY = new float[6];
    private float cutX0;
    private float cutY0;
    private float cutX1;
    private float cutY1;
    private float cutCentreX;
    private float cutCentreY;

    GlCanvas(FontStore fontStore) {
        this.fontStore = fontStore;
        states.add(new State());
        // Built with its context current (the batch above has just made its buffers in it), and a
        // context's renderer never changes, so it is read once.
        flatInteriors = GraphicsProbe.isSoftwareRenderer(
                org.lwjgl.opengl.GL33C.glGetString(org.lwjgl.opengl.GL33C.GL_RENDERER));
    }

    void beginFrame(int framebufferWidth, int framebufferHeight, float contentScale) {
        fbWidth = framebufferWidth;
        fbHeight = framebufferHeight;
        scale = contentScale;
        depth = 0;
        State root = states.get(0);
        root.transform.setScale(contentScale);
        root.opacity = 1;
        root.clip.reset(framebufferWidth, framebufferHeight);
        root.blendMode = limn.graphics.BlendMode.NORMAL;
        root.sampling = limn.graphics.Sampling.SMOOTH;
        root.pixelSnap = true;
        // Frame boundary: no pending geometry references the old pages/textures.
        boolean texturesDeleted = imageTextures.beginFrameAndEvict();
        texturesDeleted |= atlas.beginFrameAndEvict();
        if (layers != null) {
            texturesDeleted |= layers.beginFrame(framebufferWidth, framebufferHeight);
        }
        openLayers = 0;
        if (texturesDeleted) {
            batch.resetTexture();
        }
        batch.beginFrame(framebufferWidth, framebufferHeight);
        flatRects = 0;
        flatPixels = 0;
        inFrame = true;
        current = this;
    }

    void endFrame() {
        if (depth != 0) {
            LOG.log(Level.WARNING, "unbalanced save()/restore(): {0} save(s) not restored", depth);
            if (openLayers > 0) {
                restoreToCount(0); // a layer left open still holds the target: put the window back
            }
        }
        batch.flush();
        if (layers != null && layers.endFrame()) {
            batch.resetTexture(); // a picture this frame did not use went back to the driver
        }
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

    /** @return how many rectangles this frame drew with the flat program (see {@link #emitFlatRect}) */
    int flatRectsThisFrame() {
        return flatRects;
    }

    /** @return how many device pixels those rectangles covered, after the clip cut them */
    long flatPixelsThisFrame() {
        return flatPixels;
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
        if (layers != null) {
            s = s.plus(layers.stats());
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
        if (layers != null) {
            layers.dispose();
        }
        batch.close();
        if (ownRuler != null) {
            ownRuler.close(); // the ruler a canvas without a backend made for itself
        }
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
        GlColors.clearPremultiplied(color);
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
        // Inside a layer the region is the layer's part of it, counted from the layer's corner.
        State layer = innermostLayer();
        int originX = layer != null ? layer.layerX : 0;
        int originY = layer != null ? layer.layerY : 0;
        int targetHeight = layer != null ? layer.layerHeight : fbHeight;
        if (layer != null) {
            x0 = Math.max(x0, originX);
            y0 = Math.max(y0, originY);
            x1 = Math.min(x1, originX + layer.layerWidth);
            y1 = Math.min(y1, originY + targetHeight);
            if (x1 <= x0 || y1 <= y0) {
                return;
            }
        }
        // A scissored clear REPLACES the region, including alpha 0 on the
        // translucent popup framebuffers, which no blended fill can produce.
        org.lwjgl.opengl.GL33C.glEnable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL33C.glScissor(x0 - originX, originY + targetHeight - y1, x1 - x0, y1 - y0);
        GlColors.clearPremultiplied(color);
        glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        // The next batch flush re-arms the damage scissor from its own state.
        org.lwjgl.opengl.GL33C.glDisable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
    }

    @Override
    public limn.graphics.Image readBack(float x, float y, float width, float height) {
        ensureInFrame();
        int x0 = Math.max(0, Math.round(x * scale));
        int y0 = Math.max(0, Math.round(y * scale));
        int w = Math.min(fbWidth - x0, Math.round(width * scale));
        int h = Math.min(fbHeight - y0, Math.round(height * scale));
        if (w <= 0 || h <= 0) {
            return null;
        }
        // A framebuffer with no alpha plane answers 255 everywhere: nothing would be see-through.
        int alphaBits = org.lwjgl.opengl.GL33C.glGetFramebufferAttachmentParameteri(
                org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER, org.lwjgl.opengl.GL33C.GL_BACK_LEFT,
                org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE);
        if (alphaBits == 0) {
            return null;
        }
        batch.flush(); // what was painted so far, into the buffer
        java.nio.ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
        try {
            org.lwjgl.opengl.GL33C.glPixelStorei(org.lwjgl.opengl.GL33C.GL_PACK_ALIGNMENT, 1);
            org.lwjgl.opengl.GL33C.glReadBuffer(org.lwjgl.opengl.GL33C.GL_BACK);
            org.lwjgl.opengl.GL33C.glReadPixels(x0, fbHeight - y0 - h, w, h, org.lwjgl.opengl.GL33C.GL_RGBA,
                    org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE, pixels);
            byte[] rgba = new byte[w * h * 4];
            int stride = w * 4;
            for (int row = 0; row < h; row++) {
                pixels.position((h - 1 - row) * stride);
                pixels.get(rgba, row * stride, stride);
            }
            GlRenderer.unpremultiply(rgba); // the canvas blends premultiplied; an Image's alpha is straight
            return new limn.graphics.Image(w, h, rgba);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(pixels);
        }
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
        State closing = states.get(depth);
        depth--;
        if (closing.layer != null) {
            closeLayer(closing);
        }
    }

    /**
     * Opens a layer: a picture of the part of the rectangle the clip lets show, on
     * whole device pixels, into which everything until the matching {@link #restore()} is drawn,
     * and which that restore composites with {@code opacity} as one textured quad.
     *
     * <p><b>Nothing of the state is re-based.</b> Every vertex and every clip stays in the window's
     * device pixels; the batch is told the layer's corner and size, and the programs move the
     * projection and the fragment's way back to the window by it. Opening one therefore costs a
     * flush, a bind and the clear of the picture's part, and nothing per draw inside it.
     *
     * <p>Inside it the opacity starts again from 1 and the blend mode from normal, since the
     * composite carries both; the clip is cut to the layer. An opacity of 1 under
     * {@link limn.graphics.BlendMode#NORMAL} opens no layer and is a {@link #save()}, since the
     * picture is then the same drawn straight; under another mode it opens one, so that the rule
     * holds at every opacity and a fade arriving at 1 does not jump. One of 0, or a rectangle
     * nothing of which can show, is a save under an empty clip, which draws nothing. Where the
     * driver cannot make a picture, the layer falls back to what every canvas without layers draws:
     * the per-draw opacity, each draw in the blend mode in force.
     */
    @Override
    public void saveLayer(float x, float y, float width, float height, float opacity) {
        ensureInFrame();
        save();
        openLayer(x, y, width, height, opacity);
    }

    /** {@link #saveLayer(float, float, float, float, float)} after its save. */
    private void openLayer(float x, float y, float width, float height, float opacity) {
        float alpha = opacity > 0 ? Math.min(1f, opacity) : 0f;
        State s = state();
        if (alpha >= 1f && s.blendMode == limn.graphics.BlendMode.NORMAL) {
            // Nothing to fade and nothing to blend: drawing straight into the target is the same
            // picture. Under another mode it is not, since each draw would blend on its own where
            // the picture is blended once, and a fade arriving at 1 would jump.
            return;
        }
        // The rectangle's device bounds, cut to the clip and moved outward to whole device pixels,
        // so every draw inside keeps the pixel grid it has in the window.
        Transform2D t = s.transform;
        float rx1 = x + Math.max(0, width);
        float ry1 = y + Math.max(0, height);
        float dx0 = Math.max(s.clip.x0, min4(t.x(x, y), t.x(rx1, y), t.x(x, ry1), t.x(rx1, ry1)));
        float dy0 = Math.max(s.clip.y0, min4(t.y(x, y), t.y(rx1, y), t.y(x, ry1), t.y(rx1, ry1)));
        float dx1 = Math.min(s.clip.x1, max4(t.x(x, y), t.x(rx1, y), t.x(x, ry1), t.x(rx1, ry1)));
        float dy1 = Math.min(s.clip.y1, max4(t.y(x, y), t.y(rx1, y), t.y(x, ry1), t.y(rx1, ry1)));
        int lx0 = Math.max(0, (int) Math.floor(dx0 + PIXEL_EDGE_TOLERANCE));
        int ly0 = Math.max(0, (int) Math.floor(dy0 + PIXEL_EDGE_TOLERANCE));
        int lx1 = Math.min(fbWidth, (int) Math.ceil(dx1 - PIXEL_EDGE_TOLERANCE));
        int ly1 = Math.min(fbHeight, (int) Math.ceil(dy1 - PIXEL_EDGE_TOLERANCE));
        if (alpha <= 0f || s.opacity <= 0f || lx1 <= lx0 || ly1 <= ly0) {
            s.clip.intersectRect(0, 0, 0, 0); // nothing of it can show: draw nothing
            return;
        }
        if (layers == null) {
            layers = new GlLayerPool();
        }
        // What is queued belongs to the target the layer opens over, and must reach it first.
        batch.flush();
        if (openLayers == 0) {
            baseFramebuffer = org.lwjgl.opengl.GL33C.glGetInteger(
                    org.lwjgl.opengl.GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
        }
        // And on the window's grid of two by two pixels: a fragment's derivatives are taken across
        // such a quad, counted from the target's bottom-left corner, and the distance field of a
        // curve (a rounded corner, a circle) bends inside one, so a picture whose corner sat an odd
        // pixel off the window's grouped its pixels differently and antialiased every curve
        // differently from the same draw made straight into the window: up to 12 levels at a
        // bordered panel's rounded corners while it faded, measured on this Mac's GPU, where the
        // grid kept puts them within one. A column and a row more, at most, both cleared and both
        // inside the clip's cut of the layer, which is what it was before.
        lx0 &= ~1;
        if (((fbHeight - ly1) & 1) != 0) {
            ly1++; // the picture's bottom edge, its rows' origin, an even number of rows above the window's
        }
        int lw = lx1 - lx0;
        int lh = ly1 - ly0;
        GlLayerPool.Picture picture = layers.take(lw, lh, fbWidth, fbHeight);
        if (picture == null) {
            bindTarget(innermostLayer()); // making it bound another framebuffer; put ours back
            s.opacity *= alpha; // no picture to draw into: the per-draw fade of a canvas without layers
            return;
        }
        s.layer = picture;
        s.layerX = lx0;
        s.layerY = ly0;
        s.layerWidth = lw;
        s.layerHeight = lh;
        s.layerOpacity = alpha;
        s.opacity = 1f;
        s.blendMode = limn.graphics.BlendMode.NORMAL;
        s.clip.intersectRect(lx0, ly0, lx1, ly1);
        openLayers++;
        bindTarget(s);
        // Only the part this layer draws in is cleared: the picture may be larger than the layer,
        // and the rest of it is never sampled.
        org.lwjgl.opengl.GL33C.glEnable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL33C.glScissor(0, 0, lw, lh);
        org.lwjgl.opengl.GL33C.glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        org.lwjgl.opengl.GL33C.glDisable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
    }

    /**
     * Opens a layer drawn in a space of its own and composited through {@code transform}, after the
     * canvas's own transform: a rotated, skewed or turned subtree.
     *
     * <p><b>A transform that keeps the axes needs no picture.</b> A translation and a scale, a
     * mirror among them, are applied straight to the state, every clip inside staying exact, and the
     * layer is then the plain one of {@link #saveLayer(float, float, float, float, float)}, which at
     * an opacity of 1 under normal blending is no layer at all.
     *
     * <p><b>Any other is drawn re-based.</b> The picture covers what of the rectangle can be seen:
     * the rectangle cut by the near plane, where a perspective puts the eye, and by the enclosing
     * clip's four edges seen back in the layer's space, its bounds grown by two of the picture's
     * pixels so that a filtered sample at the clip's edge reads what was drawn. It is drawn at the
     * largest magnification the transform gives any corner of that part, the one nearest the eye
     * under a perspective, so that text and shapes are rasterized at the size they are seen at, and
     * smaller when that would not fit the framebuffer. A plane seen edge-on, which covers no pixel,
     * opens no picture. Inside, the state's transform is the layer's
     * space times that one scale, and its clip the picture's own rectangle, so that every clip
     * inside is an exact rectangle again, rounded clips included, and {@link #clipBounds()} answers.
     *
     * <p>The layer's space is window-sized, with the picture at its bottom-left corner: what clamps a
     * draw to the window's pixels clamps it to the picture's space as well, a glass inside finds its
     * copy at its own pixels as a glass in the window does, and the picture's rows count from the
     * same bottom edge as the space's. Its corner sits on the space's grid of two by two pixels, as a
     * plain layer's sits on the window's, measured from the scaled space's origin, so that a pass
     * that cuts a smaller picture of the same subtree antialiases every curve in it as the whole
     * frame's did. A ring of one pixel round what is drawn is cleared, inside the picture and past
     * it, since the composite filters and would otherwise read an earlier frame's texels there.
     *
     * <p>Closed by {@link #restore()}, which lays the picture through the transform as one quad in
     * homogeneous coordinates, filtered, under the enclosing clip (see {@link #closeLayer}).
     */
    @Override
    public void saveLayer(float x, float y, float width, float height, float opacity,
                          limn.math.Mat4 transform) {
        ensureInFrame();
        Objects.requireNonNull(transform, "transform");
        Transform2D t = state().transform;
        double m00 = transform.get(0, 0);
        double m01 = transform.get(0, 1);
        double m03 = transform.get(0, 3);
        double m10 = transform.get(1, 0);
        double m11 = transform.get(1, 1);
        double m13 = transform.get(1, 3);
        double m30 = transform.get(3, 0);
        double m31 = transform.get(3, 1);
        double m33 = transform.get(3, 3);
        // The plane's homography, the canvas's transform after the layer's: the x, y and w rows and
        // columns, since the layer's space is the plane z = 0.
        double[] h = spaceScratch;
        h[0] = t.m00 * m00 + t.m01 * m10 + t.tx * m30;
        h[1] = t.m00 * m01 + t.m01 * m11 + t.tx * m31;
        h[2] = t.m00 * m03 + t.m01 * m13 + t.tx * m33;
        h[3] = t.m10 * m00 + t.m11 * m10 + t.ty * m30;
        h[4] = t.m10 * m01 + t.m11 * m11 + t.ty * m31;
        h[5] = t.m10 * m03 + t.m11 * m13 + t.ty * m33;
        h[6] = m30;
        h[7] = m31;
        h[8] = m33;
        State parent = state();
        save();
        State s = state();
        boolean affine = h[6] == 0 && h[7] == 0;
        if (affine && !(h[8] > 0)) {
            s.clip.intersectRect(0, 0, 0, 0); // the plane lies behind the eye: nothing of it is seen
            return;
        }
        if (affine && h[1] == 0 && h[3] == 0) {
            Transform2D straight = s.transform;
            straight.m00 = (float) (h[0] / h[8]);
            straight.m01 = 0;
            straight.m10 = 0;
            straight.m11 = (float) (h[4] / h[8]);
            straight.tx = (float) (h[2] / h[8]);
            straight.ty = (float) (h[5] / h[8]);
            openLayer(x, y, width, height, opacity);
            return;
        }
        openSpace(x, y, width, height, opacity, h, parent, s);
    }

    /** The re-based half of {@link #saveLayer(float, float, float, float, float, limn.math.Mat4)}. */
    private void openSpace(float x, float y, float width, float height, float opacity, double[] h,
                           State parent, State s) {
        float alpha = opacity > 0 ? Math.min(1f, opacity) : 0f;
        double rx0 = x;
        double ry0 = y;
        double rx1 = x + Math.max(0, width);
        double ry1 = y + Math.max(0, height);
        if (alpha <= 0f || parent.opacity <= 0f || parent.clip.isEmpty() || rx1 <= rx0 || ry1 <= ry0) {
            s.clip.intersectRect(0, 0, 0, 0);
            return;
        }
        // What of the rectangle the enclosing clip lets be seen, in the layer's own coordinates: a
        // large panel turned inside a small viewport draws only its visible part, and a plane
        // turned partly past the eye only the part in front of it.
        double[] seen = polygonScratch;
        int corners = visiblePart(h, parent.clip, rx0, ry0, rx1, ry1, seen, polygonSpare);
        if (corners < 3) {
            s.clip.intersectRect(0, 0, 0, 0); // turned out of sight, or behind the eye
            return;
        }
        double vx0 = Double.POSITIVE_INFINITY;
        double vy0 = Double.POSITIVE_INFINITY;
        double vx1 = Double.NEGATIVE_INFINITY;
        double vy1 = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < corners; i++) {
            vx0 = Math.min(vx0, seen[i * 2]);
            vy0 = Math.min(vy0, seen[i * 2 + 1]);
            vx1 = Math.max(vx1, seen[i * 2]);
            vy1 = Math.max(vy1, seen[i * 2 + 1]);
        }
        if (!(vx1 > vx0 && vy1 > vy0) || edgeOn(h, seen, corners)) {
            s.clip.intersectRect(0, 0, 0, 0); // seen edge-on: a line, nothing to draw
            return;
        }
        // The magnification over what the window, and not the clip, lets be seen: under a
        // perspective it changes from point to point, and a pass, whose clip is a part of the
        // window, must draw the picture at the scale the whole frame drew it at, or the part it
        // repaints is rasterized at another size and lies beside the rest off by a share of a pixel.
        DeviceClip window = windowScratch;
        window.resetTo(0, 0, fbWidth, fbHeight);
        double[] reach = polygonReach;
        int reachCorners = visiblePart(h, window, rx0, ry0, rx1, ry1, reach, polygonSpare);
        double k = magnification(h, reach, reachCorners);
        if (!(k > 1e-4) || Double.isInfinite(k)) {
            s.clip.intersectRect(0, 0, 0, 0); // collapsed to a line, or nearly: nothing to draw
            return;
        }
        long kx0 = 0;
        long ky0 = 0;
        long kx1 = 0;
        long ky1 = 0;
        for (int attempt = 0; attempt < 4; attempt++) {
            // Two of the picture's pixels past what can be seen, so that a filtered sample at the
            // clip's edge reads texels that were drawn; then a ring of one more, cleared.
            double margin = 2 / k;
            double ax0 = Math.max(rx0, vx0 - margin);
            double ay0 = Math.max(ry0, vy0 - margin);
            double ax1 = Math.min(rx1, vx1 + margin);
            double ay1 = Math.min(ry1, vy1 + margin);
            kx0 = Math.floorDiv((long) Math.floor(k * ax0) - 1, 2) * 2;
            ky0 = (long) Math.floor(k * ay0) - 1;
            kx1 = (long) Math.ceil(k * ax1) + 1;
            ky1 = -Math.floorDiv(-((long) Math.ceil(k * ay1) + 1), 2) * 2;
            if (kx1 - kx0 <= fbWidth && ky1 - ky0 <= fbHeight) {
                break;
            }
            // Larger than the window: drawn smaller, and composited softer.
            k *= Math.min((fbWidth - 4.0) / (kx1 - kx0), (fbHeight - 4.0) / (ky1 - ky0));
        }
        lastSpaceScale = k;
        int lw = (int) Math.min(kx1 - kx0, fbWidth);
        int lh = (int) Math.min(ky1 - ky0, fbHeight);
        if (lw <= 0 || lh <= 0) {
            s.clip.intersectRect(0, 0, 0, 0);
            return;
        }
        if (layers == null) {
            layers = new GlLayerPool();
        }
        batch.flush();
        if (openLayers == 0) {
            baseFramebuffer = org.lwjgl.opengl.GL33C.glGetInteger(
                    org.lwjgl.opengl.GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
        }
        GlLayerPool.Picture picture = layers.take(lw, lh, fbWidth, fbHeight);
        if (picture == null) {
            bindTarget(innermostLayer()); // making it bound another framebuffer; put ours back
            // No picture to draw into: the flat part straight, and the per-draw fade, as a canvas
            // without layers draws it.
            Transform2D flat = s.transform;
            double w = h[8] != 0 ? h[8] : 1;
            flat.m00 = (float) (h[0] / w);
            flat.m01 = (float) (h[1] / w);
            flat.tx = (float) (h[2] / w);
            flat.m10 = (float) (h[3] / w);
            flat.m11 = (float) (h[4] / w);
            flat.ty = (float) (h[5] / w);
            s.opacity *= alpha;
            return;
        }
        // The picture's space: k times the layer's coordinates, moved so that the picture's
        // top-left corner is at (0, fbHeight - lh) and its bottom edge on the space's.
        float originX = -kx0;
        float originY = fbHeight - ky1;
        s.layer = picture;
        s.rebased = true;
        s.layerX = 0;
        s.layerY = fbHeight - lh;
        s.layerWidth = lw;
        s.layerHeight = lh;
        s.layerOpacity = alpha;
        s.opacity = 1f;
        s.blendMode = limn.graphics.BlendMode.NORMAL;
        Transform2D inside = s.transform;
        inside.m00 = (float) k;
        inside.m01 = 0;
        inside.m10 = 0;
        inside.m11 = (float) k;
        inside.tx = originX;
        inside.ty = originY;
        s.clip.resetTo(0, fbHeight - lh, lw, fbHeight);
        // From the picture's space back to the parent's: the homography after the scale's inverse.
        double[] up = s.toParent;
        for (int row = 0; row < 3; row++) {
            double a = h[row * 3];
            double b = h[row * 3 + 1];
            up[row * 3] = a / k;
            up[row * 3 + 1] = b / k;
            up[row * 3 + 2] = (-originX * a - originY * b) / k + h[row * 3 + 2];
        }
        openLayers++;
        bindTarget(s);
        // The part drawn and a ring of one texel past it on the sides the picture goes on: the
        // composite filters, and those texels hold an earlier frame's content otherwise.
        org.lwjgl.opengl.GL33C.glEnable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL33C.glScissor(0, 0, Math.min(picture.width, lw + 1), Math.min(picture.height, lh + 1));
        org.lwjgl.opengl.GL33C.glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        org.lwjgl.opengl.GL33C.glDisable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
    }

    /**
     * How far in front of the eye a point of a layer's plane must lie to be drawn: its {@code w},
     * which is 1 on the plane through the transform's origin and shrinks toward the eye. Above 0
     * by a margin, as the scene's own conversions take it, so that no corner of a composite is
     * divided by nothing.
     */
    private static final double NEAR = 1e-6;

    /**
     * The part of a layer's rectangle, in its own coordinates, that can be seen: the rectangle cut
     * by the near plane and by the four edges of the clip rectangle seen back through the
     * homography {@code h}, as a convex polygon into {@code out}, {@code x, y} per corner. In front
     * of the eye each cut is a straight line of the plane, {@code x' >= x0} being
     * {@code X - x0 W >= 0} for the homogeneous {@code (X, Y, W)} the homography gives, so the part
     * is exact wherever the plane passes, behind the eye included, where mapping the clip's corners
     * back would land them on the wrong side.
     *
     * @return how many corners the part has: fewer than 3 when nothing of it can be seen
     */
    private static int visiblePart(double[] h, DeviceClip clip, double x0, double y0, double x1, double y1,
                                   double[] out, double[] spare) {
        out[0] = x0;
        out[1] = y0;
        out[2] = x1;
        out[3] = y0;
        out[4] = x1;
        out[5] = y1;
        out[6] = x0;
        out[7] = y1;
        int n = cut(out, 4, h[6], h[7], h[8] - NEAR, spare);
        n = cut(spare, n, h[0] - clip.x0 * h[6], h[1] - clip.x0 * h[7], h[2] - clip.x0 * h[8], out);
        n = cut(out, n, clip.x1 * h[6] - h[0], clip.x1 * h[7] - h[1], clip.x1 * h[8] - h[2], spare);
        n = cut(spare, n, h[3] - clip.y0 * h[6], h[4] - clip.y0 * h[7], h[5] - clip.y0 * h[8], out);
        n = cut(out, n, clip.y1 * h[6] - h[3], clip.y1 * h[7] - h[4], clip.y1 * h[8] - h[5], spare);
        System.arraycopy(spare, 0, out, 0, n * 2);
        return n;
    }

    /**
     * One plane of Sutherland and Hodgman's clipping: the corners of the convex polygon
     * {@code in} where {@code a x + b y + c} is not below 0, and where its edges cross that line,
     * into {@code out}. A convex polygon cut by a line gains at most one corner.
     *
     * @return how many corners {@code out} holds
     */
    private static int cut(double[] in, int n, double a, double b, double c, double[] out) {
        int kept = 0;
        for (int i = 0; i < n; i++) {
            double px = in[i * 2];
            double py = in[i * 2 + 1];
            int j = (i + 1) % n;
            double qx = in[j * 2];
            double qy = in[j * 2 + 1];
            double dp = a * px + b * py + c;
            double dq = a * qx + b * qy + c;
            if (dp >= 0) {
                out[kept * 2] = px;
                out[kept * 2 + 1] = py;
                kept++;
            }
            if ((dp >= 0) != (dq >= 0)) {
                double t = dp / (dp - dq);
                out[kept * 2] = px + t * (qx - px);
                out[kept * 2 + 1] = py + t * (qy - py);
                kept++;
            }
        }
        return kept;
    }

    /**
     * Whether the homography {@code h} leaves the polygon {@code p} less than a millionth of its
     * area at every corner: a plane seen edge-on, turned exactly a quarter out of the screen, whose
     * picture would cost a frame and cover no pixel. The area a small patch keeps at a point is
     * {@code det / w³}, the same for {@code h} times any factor.
     */
    private static boolean edgeOn(double[] h, double[] p, int n) {
        double det = h[0] * (h[4] * h[8] - h[5] * h[7]) - h[1] * (h[3] * h[8] - h[5] * h[6])
                + h[2] * (h[3] * h[7] - h[4] * h[6]);
        for (int i = 0; i < n; i++) {
            double w = h[6] * p[i * 2] + h[7] * p[i * 2 + 1] + h[8];
            if (Math.abs(det / (w * w * w)) > 1e-6) {
                return false;
            }
        }
        return true;
    }

    /**
     * The largest magnification the homography {@code h} gives a point of the polygon {@code p}:
     * the largest singular value of its derivative, which for an affine map is one number everywhere
     * and under a perspective is largest at a corner, the one nearest the eye.
     */
    private static double magnification(double[] h, double[] p, int n) {
        if (h[6] == 0 && h[7] == 0) {
            return largestStretch(h[0] / h[8], h[1] / h[8], h[3] / h[8], h[4] / h[8]);
        }
        double most = 0;
        for (int i = 0; i < n; i++) {
            double px = p[i * 2];
            double py = p[i * 2 + 1];
            double w = h[6] * px + h[7] * py + h[8];
            if (!(w > NEAR / 2)) {
                continue;
            }
            double sx = (h[0] * px + h[1] * py + h[2]) / w;
            double sy = (h[3] * px + h[4] * py + h[5]) / w;
            most = Math.max(most, largestStretch((h[0] - sx * h[6]) / w, (h[1] - sx * h[7]) / w,
                    (h[3] - sy * h[6]) / w, (h[4] - sy * h[7]) / w));
        }
        return most;
    }

    /** The largest singular value of the 2 by 2 matrix {@code [[a, b], [c, d]]}. */
    private static double largestStretch(double a, double b, double c, double d) {
        double sum = a * a + b * b + c * c + d * d;
        double det = a * d - b * c;
        return Math.sqrt((sum + Math.sqrt(Math.max(0, sum * sum - 4 * det * det))) / 2);
    }

    /**
     * Closes a layer: what was drawn into it reaches its picture, the target it opened over is bound
     * again, and the picture goes into that target as one quad, at the layer's opacity times the
     * enclosing state's, with the enclosing state's blend mode, by the batch's composite program.
     * A plain layer's quad draws under no clip: the picture was drawn under the clip already, its
     * pixels cut where the clip cut them, and a second pass of the same clip would square its
     * antialiased edge. A re-based layer's picture was drawn under a clip of its own, so its quad,
     * the picture's corners through the layer's homography, is cut by the enclosing clip.
     */
    private void closeLayer(State closing) {
        GlLayerPool.Picture picture = closing.layer;
        closing.layer = null;
        batch.flush();
        openLayers--;
        bindTarget(innermostLayer());
        State s = state();
        float alpha = closing.layerOpacity * s.opacity;
        if (alpha > 0f) {
            applyBlend(s);
            if (closing.rebased) {
                compositeThrough(picture, closing, s.clip, alpha);
            } else {
                compositePart(picture, closing, closing.layerX, closing.layerY,
                        closing.layerX + closing.layerWidth, closing.layerY + closing.layerHeight, alpha);
            }
        }
        layers.give(picture);
    }

    /**
     * Lays a re-based layer's picture through its homography into the current target, cut by
     * {@code clip}: the picture's four corners, top-left first round the quad, each as the
     * homogeneous position the homography gives it, so that the rasterizer interpolates the texture
     * perspective-correctly whatever the transform. In perspective the quad is first cut at the near
     * plane and a little past the clip, as a polygon of up to nine corners.
     */
    private void compositeThrough(GlLayerPool.Picture picture, State layer, DeviceClip clip, float alpha) {
        double[] map = layer.toParent;
        if (map[6] == 0 && map[7] == 0) {
            throughCorners(picture, layer, map, cornerScratch, uvScratch);
            batch.compositeThrough(picture.texture, cornerScratch, uvScratch, 4, alpha, clip);
            return;
        }
        // In perspective: the quad cut at the near plane, so that no corner lies behind the eye or
        // at it, where its position would be divided by nothing; and at the clip grown by two
        // pixels, so that no corner lies far past what is drawn, where its device position runs to
        // millions of pixels near the eye. The scissor or the clip's program still cuts at the clip.
        double[] poly = polygonScratch;
        double[] spare = polygonSpare;
        poly[0] = layer.layerX;
        poly[1] = layer.layerY;
        poly[2] = layer.layerX + layer.layerWidth;
        poly[3] = layer.layerY;
        poly[4] = layer.layerX + layer.layerWidth;
        poly[5] = layer.layerY + layer.layerHeight;
        poly[6] = layer.layerX;
        poly[7] = layer.layerY + layer.layerHeight;
        double gx0 = clip.x0 - 2;
        double gy0 = clip.y0 - 2;
        double gx1 = clip.x1 + 2;
        double gy1 = clip.y1 + 2;
        int n = cut(poly, 4, map[6], map[7], map[8] - NEAR, spare);
        n = cut(spare, n, map[0] - gx0 * map[6], map[1] - gx0 * map[7], map[2] - gx0 * map[8], poly);
        n = cut(poly, n, gx1 * map[6] - map[0], gx1 * map[7] - map[1], gx1 * map[8] - map[2], spare);
        n = cut(spare, n, map[3] - gy0 * map[6], map[4] - gy0 * map[7], map[5] - gy0 * map[8], poly);
        n = cut(poly, n, gy1 * map[6] - map[3], gy1 * map[7] - map[4], gy1 * map[8] - map[5], spare);
        float[] corners = cornerScratch;
        float[] uvs = uvScratch;
        for (int i = 0; i < n; i++) {
            double vx = spare[i * 2];
            double vy = spare[i * 2 + 1];
            corners[i * 3] = (float) (map[0] * vx + map[1] * vy + map[2]);
            corners[i * 3 + 1] = (float) (map[3] * vx + map[4] * vy + map[5]);
            corners[i * 3 + 2] = (float) (map[6] * vx + map[7] * vy + map[8]);
            uvs[i * 2] = (float) ((vx - layer.layerX) / picture.width);
            uvs[i * 2 + 1] = (float) ((layer.layerY + layer.layerHeight - vy) / picture.height);
        }
        batch.compositeThrough(picture.texture, corners, uvs, n, alpha, clip);
    }

    /**
     * The corners of a layer's part of its picture, top-left first round the quad, through
     * {@code map}, as {@code x, y, w} with x and y times w, and their texture coordinates: the
     * layer drew into the picture's bottom-left part, whose rows count up from its bottom edge.
     */
    private static void throughCorners(GlLayerPool.Picture picture, State layer, double[] map,
                                       float[] corners, float[] uvs) {
        for (int i = 0; i < 4; i++) {
            int vx = layer.layerX + (i == 1 || i == 2 ? layer.layerWidth : 0);
            int vy = layer.layerY + (i >= 2 ? layer.layerHeight : 0);
            corners[i * 3] = (float) (map[0] * vx + map[1] * vy + map[2]);
            corners[i * 3 + 1] = (float) (map[3] * vx + map[4] * vy + map[5]);
            corners[i * 3 + 2] = (float) (map[6] * vx + map[7] * vy + map[8]);
            uvs[i * 2] = (vx - layer.layerX) / (float) picture.width;
            uvs[i * 2 + 1] = (layer.layerY + layer.layerHeight - vy) / (float) picture.height;
        }
    }

    /**
     * Lays the part of a layer's picture that covers the window's device pixels {@code (x0, y0)} to
     * {@code (x1, y1)} over the current target at {@code alpha}. The layer drew into the picture's
     * bottom-left part, and a picture's rows count up from its bottom edge.
     */
    private void compositePart(GlLayerPool.Picture picture, State layer, int x0, int y0, int x1, int y1,
                               float alpha) {
        float u0 = (x0 - layer.layerX) / (float) picture.width;
        float u1 = (x1 - layer.layerX) / (float) picture.width;
        float v0 = (layer.layerY + layer.layerHeight - y0) / (float) picture.height;
        float v1 = (layer.layerY + layer.layerHeight - y1) / (float) picture.height;
        batch.composite(picture.texture, x0, y0, x1, y1, u0, v0, u1, v1, alpha);
    }

    /** @return the state of the innermost layer open now, or null when the frame's own target is */
    private State innermostLayer() {
        if (openLayers == 0) {
            return null;
        }
        for (int i = depth; i > 0; i--) {
            State s = states.get(i);
            if (s.layer != null) {
                return s;
            }
        }
        return null;
    }

    /** Binds a layer's picture as the target, or the frame's own target for null, and tells the batch. */
    private void bindTarget(State layer) {
        if (layer != null) {
            org.lwjgl.opengl.GL33C.glBindFramebuffer(org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER,
                    layer.layer.framebuffer);
            org.lwjgl.opengl.GL33C.glViewport(0, 0, layer.layerWidth, layer.layerHeight);
            batch.setTarget(true, layer.layerX, layer.layerY, layer.layerWidth, layer.layerHeight);
        } else {
            org.lwjgl.opengl.GL33C.glBindFramebuffer(org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER, baseFramebuffer);
            org.lwjgl.opengl.GL33C.glViewport(0, 0, fbWidth, fbHeight);
            batch.setTarget(false, 0, 0, fbWidth, fbHeight);
        }
    }

    /**
     * @return the scale the last re-based layer was drawn at, in its picture's pixels per point of
     *         its own space: the magnification it was drawn for; for tests
     */
    double lastSpaceScale() {
        return lastSpaceScale;
    }

    /** @return how many layers are open now; for tests */
    int openLayers() {
        return openLayers;
    }

    /** @return the layer pictures this canvas holds now, as the performance monitor counts them; for tests */
    limn.backend.RenderStats layerStats() {
        return layers == null ? limn.backend.RenderStats.EMPTY : layers.stats();
    }

    /** @return the names of the textures its layer pictures hold now; for tests */
    int[] layerTextures() {
        return layers == null ? new int[0] : layers.textures();
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

    /**
     * Multiplies, as the interface says: an opacity set inside a fading surface or a layer
     * combines with it. NaN is taken as 0, so a bad factor hides what it would have tinted rather
     * than poisoning every colour after it.
     */
    @Override
    public void setOpacity(float opacity) {
        State s = state();
        s.opacity = opacity > 0 ? s.opacity * Math.min(1f, opacity) : 0f;
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
        // Narrows the clip rectangle only. A rounded clip already in force keeps its own rectangle
        // and radius, so this cuts it straight rather than rounding the corners of what is left:
        // the pass a partial repaint clips to is one of these, and a pass must draw what the whole
        // frame drew inside it (see DeviceClip).
        intersectClip(x, y, width, height, 0);
    }

    @Override
    public limn.graphics.Rect clipBounds() {
        State s = state();
        Transform2D t = s.transform;
        if (t.m01 != 0 || t.m10 != 0 || t.m00 == 0 || t.m11 == 0) {
            return null; // rotated or degenerate: the device box has no rectangular pre-image
        }
        // Axis-aligned: device = local * m + t on each axis, so the pre-image is the box between
        // the two mapped-back edges, whichever way a negative scale turned them.
        float x0 = (s.clip.x0 - t.tx) / t.m00;
        float x1 = (s.clip.x1 - t.tx) / t.m00;
        float y0 = (s.clip.y0 - t.ty) / t.m11;
        float y1 = (s.clip.y1 - t.ty) / t.m11;
        float left = Math.min(x0, x1);
        float top = Math.min(y0, y1);
        return new limn.graphics.Rect(left, top, Math.max(x0, x1) - left, Math.max(y0, y1) - top);
    }

    @Override
    public void clipRoundRect(RoundRect roundRect) {
        ensureInFrame();
        RoundRect rr = roundRect.normalized();
        float maxRadius = Math.max(Math.max(rr.topLeft(), rr.topRight()),
                Math.max(rr.bottomRight(), rr.bottomLeft()));
        // One rounded rect, with one uniform radius, converted to device pixels (exact for
        // axis-aligned transforms); DeviceClip says what a second one nested in it does.
        intersectClip(rr.x(), rr.y(), rr.width(), rr.height(),
                maxRadius * state().transform.approxScale());
    }

    /**
     * Intersects the current clip with the device-space bounds of a rectangle in current
     * coordinates, as a rounded clip of {@code deviceRadius} when that is positive and a plain
     * rectangle otherwise. Under a rotation the bounds are the transformed corners' box, a superset
     * of the requested region, which is the documented degradation.
     */
    private void intersectClip(float x, float y, float width, float height, float deviceRadius) {
        ensureInFrame();
        State s = state();
        Transform2D t = s.transform;
        float x1 = x + Math.max(0, width);
        float y1 = y + Math.max(0, height);
        float dx0 = min4(t.x(x, y), t.x(x1, y), t.x(x, y1), t.x(x1, y1));
        float dy0 = min4(t.y(x, y), t.y(x1, y), t.y(x, y1), t.y(x1, y1));
        float dx1 = max4(t.x(x, y), t.x(x1, y), t.x(x, y1), t.x(x1, y1));
        float dy1 = max4(t.y(x, y), t.y(x1, y), t.y(x, y1), t.y(x1, y1));
        s.clip.intersectRoundRect(dx0, dy0, dx1, dy1, deviceRadius);
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

    /**
     * The one radius a uniform rounded rectangle draws with: {@link RoundRect#normalized()}'s
     * reduction, with the same float operations, so these scalar forms draw exactly what building a
     * {@code RoundRect} drew, without building one per border per frame.
     */
    private static float uniformRadius(float width, float height, float radius) {
        float r = Math.max(0, radius);
        float sum = r + r;
        float f = 1f;
        f = Math.min(f, sum <= width || sum == 0 ? 1f : width / sum);
        f = Math.min(f, sum <= height || sum == 0 ? 1f : height / sum);
        return f >= 1f ? r : r * f;
    }

    @Override
    public void fillRoundRect(float x, float y, float width, float height, float radius, Paint paint) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float r = uniformRadius(width, height, radius);
        emitRoundRect(x, y, width, height, r, r, r, r, -1, paint);
    }

    @Override
    public void drawRoundRect(float x, float y, float width, float height, float radius,
                              float strokeWidth, Paint paint) {
        if (width <= 0 || height <= 0 || strokeWidth <= 0) {
            return;
        }
        float r = uniformRadius(width, height, radius);
        emitRoundRect(x, y, width, height, r, r, r, r, strokeWidth, paint);
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
    public void drawShadow(RoundRect shape, limn.graphics.Shadow shadow) {
        java.util.Objects.requireNonNull(shadow, "shadow");
        RoundRect rr = shape.normalized();
        if (rr.width() <= 0 || rr.height() <= 0 || shadow.color().a() <= 0) {
            return;
        }
        ensureInFrame();
        float halfW = rr.width() / 2f;
        float halfH = rr.height() / 2f;
        float max = Math.min(halfW, halfH);
        // The quad reaches three deviations past the shape, the blur being two: past that the
        // shadow is under a thousandth of itself, which no display shows.
        float reach = Math.max(shadow.blur() * 1.5f, 0);
        emitSdfQuad(KIND_SHADOW, rr.x() + halfW + shadow.offsetX(), rr.y() + halfH + shadow.offsetY(),
                halfW, halfH, Math.min(rr.topLeft(), max), Math.min(rr.topRight(), max),
                Math.min(rr.bottomRight(), max), Math.min(rr.bottomLeft(), max), reach, 1, 0,
                shadow.color());
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
        if (s.clip.isEmpty()) {
            return; // fully clipped out: nothing to copy and nothing to draw
        }
        Transform2D t = s.transform;
        float scale = t.approxScale();
        float x1 = rr.x() + rr.width();
        float y1 = rr.y() + rr.height();
        // The copy must cover every texel the shader can reach: the shape's device bounds, the AA
        // skirt, and however far this effect displaces its sample (BackdropEffect.reach, the same
        // number the scene widens a panel's repaint by). Short by a pixel and the rim samples
        // whatever an earlier effect left in the texture.
        float margin = effect.reach() * scale + AA_PAD_DEVICE + 1;
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
        int texture = openLayers > 0 ? captureThroughLayers(cx0, cy0, cx1, cy1)
                : backdrop.capture(fbWidth, fbHeight, cx0, fbHeight - cy1, cx1 - cx0, cy1 - cy0);

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
                scale, KIND_BACKDROP, PAINT_SOLID);
        batch.setColors(tint.r(), tint.g(), tint.b(), tint.a(),
                variantId(effect), param1(effect), param2(effect), s.opacity);
        batch.setGradient(u0, v0, u1, v1);
        batch.setClip(s.clip);
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
     * The backdrop of a glass drawn inside open layers: what it would
     * read if every layer around it were opaque. The frame's own target is copied first, and each
     * open layer's picture so far is laid over the copy, outermost first, at full opacity, for the
     * region the glass reads; the glass then draws into its own layer as it draws into a window. So
     * a frosted surface fading in shows what it will show at the end of the fade from the first
     * frame, at the fade's opacity, and at an opacity of 1 there is no layer and the reading is the
     * window's, as it always was.
     *
     * <p>The price is one composite per open layer per glass, over the region the glass reads, and
     * two binds. The caller has flushed: every layer's picture holds what was drawn into it before
     * the glass.
     *
     * @return the copy, ready to sample, in the window's coordinates like any backdrop copy
     */
    private int captureThroughLayers(int cx0, int cy0, int cx1, int cy1) {
        int space = innermostSpace(depth);
        if (space > 0) {
            return captureInSpace(space, cx0, cy0, cx1, cy1);
        }
        org.lwjgl.opengl.GL33C.glBindFramebuffer(org.lwjgl.opengl.GL33C.GL_READ_FRAMEBUFFER, baseFramebuffer);
        int texture = backdrop.capture(fbWidth, fbHeight, cx0, fbHeight - cy1, cx1 - cx0, cy1 - cy0);
        org.lwjgl.opengl.GL33C.glBindFramebuffer(org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER, backdrop.framebuffer());
        org.lwjgl.opengl.GL33C.glViewport(0, 0, fbWidth, fbHeight);
        // A layer's target, so no damage scissor: the copy is not the window.
        batch.setTarget(true, 0, 0, fbWidth, fbHeight);
        GlBlend.Factors normal = GlBlend.of(limn.graphics.BlendMode.NORMAL);
        batch.requireBlend(normal.srcRgb(), normal.dstRgb(), normal.srcAlpha(), normal.dstAlpha());
        for (int i = 1; i <= depth; i++) {
            State layer = states.get(i);
            if (layer.layer == null) {
                continue;
            }
            int x0 = Math.max(cx0, layer.layerX);
            int y0 = Math.max(cy0, layer.layerY);
            int x1 = Math.min(cx1, layer.layerX + layer.layerWidth);
            int y1 = Math.min(cy1, layer.layerY + layer.layerHeight);
            if (x1 <= x0 || y1 <= y0) {
                continue;
            }
            compositePart(layer.layer, layer, x0, y0, x1, y1, 1f);
        }
        batch.flush();
        bindTarget(innermostLayer());
        return texture;
    }

    /**
     * @param from a state's index
     * @return the index of the innermost re-based layer open at or outside it, whose space the
     *         state draws in, or 0 for the window's own
     */
    private int innermostSpace(int from) {
        for (int i = from; i > 0; i--) {
            State s = states.get(i);
            if (s.layer != null && s.rebased) {
                return i;
            }
        }
        return 0;
    }

    /**
     * The backdrop of a glass drawn inside a re-based layer, a rotated or skewed subtree: what it
     * would read if every layer round it were opaque, in the glass's own space. The window's copy
     * is warped into that space through the inverse of the way from it to the window, as one quad
     * over the region the glass reads, and each open layer's picture so far is laid over it through
     * its own way into the space, outermost first, as {@link #captureThroughLayers} lays them for a
     * glass in the window's space. A frosted card turned a few degrees keeps showing the window
     * through its glass, as it does upright.
     *
     * <p>A layer whose way out is not affine, a subtree turned in perspective, is a backdrop root:
     * what lies outside it is not read, and a glass inside it reads only the content drawn into it
     * and the layers inside it, since what a blur reads through a plane seen at an angle has no
     * answer a user could check.
     *
     * <p>The price is one quad for the window and one per open layer, over the region the glass
     * reads, into a second copy the size of the window, kept beside the first.
     *
     * @param glassSpace the index of the re-based layer the glass draws in
     * @return the warped copy, ready to sample at the glass's own pixels
     */
    private int captureInSpace(int glassSpace, int cx0, int cy0, int cx1, int cy1) {
        if (spaceMaps.length <= depth) {
            spaceMaps = new double[depth + 8][9];
        }
        // The way from the glass's space to each space outward, held at that space's index: the
        // glass's own is the identity, and each outer one the layer's homography after the last.
        double[] own = spaceMaps[glassSpace];
        java.util.Arrays.fill(own, 0);
        own[0] = 1;
        own[4] = 1;
        own[8] = 1;
        int root = 0;
        for (int inner = glassSpace; inner > 0; ) {
            State layer = states.get(inner);
            if (layer.toParent[6] != 0 || layer.toParent[7] != 0) {
                root = inner; // turned in perspective: nothing outside it is read
                break;
            }
            int outer = innermostSpace(inner - 1);
            multiply(layer.toParent, spaceMaps[inner], spaceMaps[outer]);
            inner = outer;
        }
        int warped = backdrop.warped(fbWidth, fbHeight); // which may bind its framebuffer, made now
        org.lwjgl.opengl.GL33C.glBindFramebuffer(org.lwjgl.opengl.GL33C.GL_READ_FRAMEBUFFER, baseFramebuffer);
        boolean window = false;
        if (root == 0) {
            // The window's pixels the region comes from, copied as any glass's are.
            double[] toWindow = spaceMaps[0];
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                double px = i == 1 || i == 2 ? cx1 : cx0;
                double py = i >= 2 ? cy1 : cy0;
                double w = toWindow[6] * px + toWindow[7] * py + toWindow[8];
                double wx = (toWindow[0] * px + toWindow[1] * py + toWindow[2]) / w;
                double wy = (toWindow[3] * px + toWindow[4] * py + toWindow[5]) / w;
                minX = Math.min(minX, wx);
                minY = Math.min(minY, wy);
                maxX = Math.max(maxX, wx);
                maxY = Math.max(maxY, wy);
            }
            int wx0 = (int) Math.max(0, Math.floor(minX) - 2);
            int wy0 = (int) Math.max(0, Math.floor(minY) - 2);
            int wx1 = (int) Math.min(fbWidth, Math.ceil(maxX) + 2);
            int wy1 = (int) Math.min(fbHeight, Math.ceil(maxY) + 2);
            if (wx1 > wx0 && wy1 > wy0) {
                backdrop.capture(fbWidth, fbHeight, wx0, fbHeight - wy1, wx1 - wx0, wy1 - wy0);
                window = true;
            }
        }
        org.lwjgl.opengl.GL33C.glBindFramebuffer(org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER, backdrop.warpedFramebuffer());
        org.lwjgl.opengl.GL33C.glViewport(0, 0, fbWidth, fbHeight);
        // A layer's target, so no damage scissor: the copy is not the window.
        batch.setTarget(true, 0, 0, fbWidth, fbHeight);
        GlBlend.Factors normal = GlBlend.of(limn.graphics.BlendMode.NORMAL);
        batch.requireBlend(normal.srcRgb(), normal.dstRgb(), normal.srcAlpha(), normal.dstAlpha());
        // What an earlier glass left in the region goes: the root of a backdrop reads from nothing.
        org.lwjgl.opengl.GL33C.glEnable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL33C.glScissor(cx0, fbHeight - cy1, cx1 - cx0, cy1 - cy0);
        org.lwjgl.opengl.GL33C.glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        org.lwjgl.opengl.GL33C.glDisable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
        readRegion.resetTo(cx0, cy0, cx1, cy1);
        float[] corners = cornerScratch;
        float[] uvs = uvScratch;
        if (window) {
            double[] toWindow = spaceMaps[0];
            for (int i = 0; i < 4; i++) {
                int px = i == 1 || i == 2 ? cx1 : cx0;
                int py = i >= 2 ? cy1 : cy0;
                double w = toWindow[6] * px + toWindow[7] * py + toWindow[8];
                double wx = (toWindow[0] * px + toWindow[1] * py + toWindow[2]) / w;
                double wy = (toWindow[3] * px + toWindow[4] * py + toWindow[5]) / w;
                corners[i * 3] = px;
                corners[i * 3 + 1] = py;
                corners[i * 3 + 2] = 1;
                uvs[i * 2] = (float) (wx / fbWidth);
                uvs[i * 2 + 1] = (float) ((fbHeight - wy) / fbHeight); // the copy's rows count up
            }
            batch.compositeThrough(backdrop.texture(), corners, uvs, 4, 1f, readRegion);
        }
        double[] back = spaceScratch;
        for (int i = Math.max(1, root); i <= depth; i++) {
            State layer = states.get(i);
            if (layer.layer == null) {
                continue;
            }
            int space = layer.rebased ? i : innermostSpace(i - 1);
            adjugate(spaceMaps[space], back); // from that space into the glass's, up to a factor
            if (back[8] < 0) {
                for (int j = 0; j < 9; j++) {
                    back[j] = -back[j]; // the factor's sign: a mirrored way out keeps w positive
                }
            }
            throughCorners(layer.layer, layer, back, corners, uvs);
            batch.compositeThrough(layer.layer.texture, corners, uvs, 4, 1f, readRegion);
        }
        batch.flush();
        bindTarget(innermostLayer());
        return warped;
    }

    /** {@code out = a · b}, three by three, row-major; {@code out} is neither. */
    private static void multiply(double[] a, double[] b, double[] out) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out[row * 3 + col] = a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col]
                        + a[row * 3 + 2] * b[6 + col];
            }
        }
    }

    /** The adjugate of {@code m}, three by three, row-major: its inverse up to a factor. */
    private static void adjugate(double[] m, double[] out) {
        out[0] = m[4] * m[8] - m[5] * m[7];
        out[1] = m[2] * m[7] - m[1] * m[8];
        out[2] = m[1] * m[5] - m[2] * m[4];
        out[3] = m[5] * m[6] - m[3] * m[8];
        out[4] = m[0] * m[8] - m[2] * m[6];
        out[5] = m[2] * m[3] - m[0] * m[5];
        out[6] = m[3] * m[7] - m[4] * m[6];
        out[7] = m[1] * m[6] - m[0] * m[7];
        out[8] = m[0] * m[4] - m[1] * m[3];
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

    /**
     * The interface's geometry, with the fill's edge where the border's centre line is actually
     * drawn. A stroke's centre line moves up to half a device pixel onto the grid and a fill's edge
     * does not, so a fill stopping at the unmoved centre line of a border that moved inward
     * reached past the border's outer edge at the corners, where the two arcs no longer shared a
     * centre (at 1x a one-point border on a box half a point off the grid showed up to 35% of the
     * fill on the pixels just outside it), and where the border moved outward the background
     * showed between the two. Moving the fill's edges with the border's keeps the arcs concentric
     * at every offset; the border itself is drawn exactly as before.
     */
    @Override
    public void fillRoundRectInsideBorder(float x, float y, float width, float height, float radius,
                                          float borderWidth, Paint paint) {
        float half = borderWidth / 2;
        float w = width - borderWidth;
        float h = height - borderWidth;
        if (w <= 0 || h <= 0) {
            return; // what fillRoundRect does with the default's inset box
        }
        float r = uniformRadius(w, h, Math.max(0, radius - half));
        emitRoundRect(x + half, y + half, w, h, r, r, r, r, -1, borderWidth, paint);
    }

    /**
     * The interface's geometry, with the clip's edge where the border's centre line is actually
     * drawn, for the reason {@link #fillRoundRectInsideBorder} gives: content painted under this
     * clip then stays inside the border at every offset.
     */
    @Override
    public void clipRoundRectInsideBorder(float x, float y, float width, float height, float radius,
                                          float borderWidth) {
        float half = borderWidth / 2;
        float w = Math.max(0, width - borderWidth);
        float h = Math.max(0, height - borderWidth);
        // RoundRect.normalized()'s radius, which clipRoundRect would have taken from the default's
        // RoundRect; the shader clamps it to the half extents of the rectangle it is given.
        float r = uniformRadius(w, h, Math.max(0, radius - half));
        float x0 = x + half;
        float y0 = y + half;
        float x1 = x0 + w;
        float y1 = y0 + h;
        Transform2D t = state().transform;
        if (snapToStrokeGrid(t, x0, y0, x1, y1, borderWidth) > 0) {
            x0 = gridEdges[0];
            y0 = gridEdges[1];
            x1 = gridEdges[2];
            y1 = gridEdges[3];
        }
        intersectClip(x0, y0, x1 - x0, y1 - y0, r * t.approxScale());
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

    /**
     * The {@code String} overload, which is the shaped one with the shaping done here.
     *
     * <p><b>This is a wrapper and not a renderer.</b> There was a second glyph-emitting loop here
     * once — a walk over code points that resolved a face per character, carried its own pen, its
     * own kerning and its own colour-emoji branch. It drew Latin correctly and drew Arabic as
     * unjoined letterforms in string order, which is what every caption in the toolkit got, because
     * only three widgets hold a {@code ShapedText} of their own. Two loops for one job could also
     * drift apart, and had already begun to: the pen here summed atlas advances while
     * {@code measureText} summed face advances, and the correction that kept them together was a
     * ratio applied per glyph. One loop cannot drift from itself, and the position a glyph lands at
     * is now the position the measurement counted, because it is the same number.
     *
     * <p>What is <em>not</em> lost by dropping that loop: the snapped versus rotated split, the
     * batch discipline and the emoji fallback all live in the overload below, which this delegates
     * to. The pen-drift correction is gone rather than moved, and that one is worth naming: it
     * existed because the pen was built from advances read at the atlas's <em>quantized</em> size,
     * and a shaped value carries unquantized logical positions computed at the exact font size, so
     * there is no longer a quantization for it to correct.
     *
     * <p>The two early-outs below are duplicated from that overload rather than left to it, and the
     * reason is the shaping that now sits between here and there. Both decide from canvas state
     * alone that <em>nothing will be drawn</em> — an empty clip, or a device size below the floor at
     * which a glyph has no pixels — and paying for a shaping to arrive at that answer would be
     * paying the expensive half of drawing text for a frame that draws none of it.
     */
    @Override
    public void drawText(String text, float x, float y, Font font, Paint paint) {
        ensureInFrame();
        Objects.requireNonNull(font, "font");
        Objects.requireNonNull(paint, "paint");
        if (text == null || text.isEmpty()) {
            return;
        }
        State s = state();
        if (s.clip.isEmpty()) {
            return;
        }
        if (font.size() * s.transform.approxScale() < MIN_DEVICE_FONT_SIZE) {
            return;
        }
        drawText(ruler().shape(text, font), x, y, paint);
    }

    /**
     * The ruler this canvas shapes and measures through: the one the process installed.
     *
     * <p><b>Why the registry and not a ruler of this canvas's own.</b> A widget lays out through
     * {@code TextRulers.get()} and paints through this canvas, and the whole point of routing both
     * through the shaper is that those two passes agree. Reaching for the same installed instance
     * is what makes that agreement structural rather than a coincidence of two objects being
     * configured alike: same memo, so the string measured during layout is already shaped when the
     * paint asks for it, and same epoch, so a face arriving between the two passes invalidates both
     * or neither. A second ruler built here over the same {@code FontStore} would answer the same
     * numbers and pay for them twice.
     *
     * <p><b>What it means when something else is installed.</b> A test that installs a fake ruler
     * gets that fake's idea of the text on the screen as well as in the layout, which is the honest
     * behaviour: the canvas is not entitled to a second opinion about a string a widget has already
     * been positioned by. The one case that needs an answer of its own is
     * {@link limn.graphics.TextRuler#NONE} — the no-backend sentinel, which measures everything as
     * zero. Zero is
     * a defensible answer for a detached widget doing layout arithmetic and a catastrophic one for
     * a painter, which would stack a whole string of glyphs on top of each other at the origin. A
     * canvas always has a {@link FontStore}, so it can always shape; this is reached only by a
     * {@code GlCanvas} built without the backend that would have installed a ruler, which is to say
     * by a test.
     */
    private limn.graphics.TextRuler ruler() {
        limn.graphics.TextRuler installed = limn.graphics.TextRulers.get();
        if (installed != limn.graphics.TextRuler.NONE) {
            return installed;
        }
        if (ownRuler == null) {
            ownRuler = new ShapingRuler(fontStore);
        }
        return ownRuler;
    }

    /**
     * The one glyph-emitting loop: a walk over glyphs that already know where they go.
     *
     * <p>Everything about rasterization, snapping, clipping, paint and batching lives here, and
     * the {@code String} overload above is a call to it with the shaping done first. There is no
     * pen and no kerning arithmetic — the value carries an absolute x per glyph, already reordered
     * — and a face is resolved once per <em>run</em> rather than once per code point, which is
     * what makes a joining script joined and a right-to-left one ordered.
     *
     * <p>The one remaining fallback, {@link #drawClusterCharacters}, is not a second renderer but
     * the answer to a cluster this value says has no glyph: a colour-emoji strike, a line from a
     * ruler with no shaper, or a run whose face this canvas no longer knows. It places from the
     * same positions and emits through the same {@link #emitGlyphQuad}.
     */
    @Override
    public void drawText(limn.graphics.ShapedText text, float x, float y, Paint paint) {
        ensureInFrame();
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(paint, "paint");
        if (text.glyphCount() == 0) {
            return;
        }
        State s = state();
        if (s.clip.isEmpty()) {
            return;
        }
        // The font is the one the glyphs were CHOSEN for, taken from the value rather than from a
        // parameter: a font passed here could disagree with it, and the line would then be
        // measured by one face and drawn by another.
        Font font = text.font();
        Transform2D t = s.transform;
        float deviceScale = t.approxScale();
        float deviceSize = font.size() * deviceScale;
        if (deviceSize < MIN_DEVICE_FONT_SIZE) {
            return;
        }
        int quantized = GlyphAtlas.quantizeSize(deviceSize);
        boolean snap = t.isUniformAxisAligned();
        int paintType = applyPaint(paint, x, y, 1, 0, 0, 1, s.opacity);
        applyBlend(s);
        batch.setShape(0, 0, 0, 0, 0, 0, -1, KIND_GLYPH, paintType);
        batch.setClip(s.clip);

        // The run origin is snapped to the device grid: with a fractional origin every glyph
        // rounds independently and the rounding pattern changes as the origin slides by sub-pixel
        // amounts (a tooltip following the pointer, centred text during a live resize), visible as
        // letters "dancing". With an integer origin the per-glyph rounding depends only on the
        // shaped positions, so spacing is stable and the whole run moves in whole pixels.
        float originDevX = snap ? Math.round(t.x(x, y)) : t.x(x, y);
        float baselineDevY = snap ? Math.round(t.y(x, y)) : 0;
        StbFont primary = fontStore.resolve(font);

        for (limn.graphics.ShapedText.Run run : text.runs()) {
            StbFont face = fontStore.faceById(run.faceId());
            for (int g = run.glyphStart(); g < run.glyphEnd(); g++) {
                float glyphUserX = text.glyphX(g);
                float glyphUserY = text.glyphY(g);
                int glyphIndex = text.glyphId(g);
                // Two different reasons to fall back to the characters, one branch. NO_GLYPH is a
                // cluster this face draws by other means (a colour-emoji strike, or a whole line
                // from a ruler that could not shape). A null face is an id issued before an
                // eviction: a stale value then draws the right characters by the slower route
                // rather than the wrong glyphs from whichever face inherited the id.
                if (glyphIndex == limn.graphics.ShapedText.NO_GLYPH || face == null) {
                    drawClusterCharacters(text, g, primary, t, x, y, glyphUserX, deviceScale,
                            deviceSize, quantized, snap, originDevX, baselineDevY, paint,
                            paintType, s);
                    continue;
                }
                GlyphAtlas.Glyph glyph =
                        atlas.glyph(face, run.faceId(), quantized, glyphIndex);
                if (glyph.width() <= 0) {
                    continue; // whitespace and empty glyphs: a real advance, no ink
                }
                emitGlyphQuad(glyph, t, x, y, glyphUserX, glyphUserY, deviceScale, snap,
                        originDevX, baselineDevY);
            }
        }
    }

    /**
     * Draws one {@linkplain limn.graphics.ShapedText#NO_GLYPH glyphless} cluster from the shaped
     * text's own characters, at the x the value assigned it.
     *
     * <p>The cluster's extent comes from the value's own caret-stop table
     * ({@code caretIndex(caretOrdinal(i) + 1)}) rather than from a second rule for finding
     * cluster boundaries here, because two rules for one boundary is how a mark ends up drawn
     * against the wrong base.
     */
    private void drawClusterCharacters(limn.graphics.ShapedText text, int glyphIndex,
                                       StbFont primary, Transform2D t, float x, float y,
                                       float glyphUserX, float deviceScale, float deviceSize,
                                       int quantized, boolean snap, float originDevX,
                                       float baselineDevY, Paint paint, int paintType, State s) {
        int from = text.glyphCluster(glyphIndex);
        int to = text.caretIndex(text.caretOrdinal(from) + 1);
        String value = text.text();
        if (to <= from || to > value.length()) {
            return;
        }
        float penUser = glyphUserX;
        for (int i = from; i < to; ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || StbFont.isZeroWidthFormat(cp)) {
                continue;
            }
            int primaryGlyph = primary.glyphIndex(cp);
            boolean primaryHas = primaryGlyph != 0;
            if (!primaryHas) {
                ColorEmojiFont.Emoji colored = fontStore.colorEmojiGlyph(cp);
                if (colored != null) {
                    Image emoji = colored.image();
                    // The picture's own box, never the advance: a strike is authored at one size
                    // with its own width, height and height-above-baseline, and stretching it to
                    // the advance lifts it out of the line box the run was measured into.
                    float boxW = colored.width() * text.font().size();
                    float boxH = colored.height() * text.font().size();
                    float boxTop = colored.top() * text.font().size();
                    float textAlpha = paint instanceof Color solid ? solid.a() : 1;
                    emitImageQuad(emoji, 0, 0, emoji.width(), emoji.height(),
                            x + penUser, y - boxTop, boxW, boxH,
                            1, 1, 1, textAlpha, KIND_IMAGE, false);
                    // The emoji quad overwrote the batch's shape AND its paint (with the white
                    // tint); restore both, or glyphs after the emoji lose the text colour.
                    applyPaint(paint, x, y, 1, 0, 0, 1, s.opacity);
                    applyBlend(s);
                    batch.setShape(0, 0, 0, 0, 0, 0, -1, KIND_GLYPH, paintType);
                    batch.setClip(s.clip);
                    penUser += (float) fontStore.colorEmojiAdvance(cp, deviceSize) / deviceScale;
                    continue;
                }
            }
            StbFont face = primaryHas ? primary : fontStore.faceForCodepoint(primary, cp);
            // From the face that will DRAW it, never from the primary: an index is a row number
            // in the face that issued it, and the primary has already answered 0 here.
            int index = primaryHas ? primaryGlyph : face.glyphIndex(cp);
            GlyphAtlas.Glyph glyph =
                    atlas.glyph(face, fontStore.faceId(face), quantized, index);
            if (glyph.width() > 0) {
                emitGlyphQuad(glyph, t, x, y, penUser, 0, deviceScale, snap, originDevX,
                        baselineDevY);
            }
            penUser += face.glyphAdvance(index, deviceSize) / deviceScale;
        }
    }

    /** One glyph bitmap into the batch, at a position the caller has already decided. */
    private void emitGlyphQuad(GlyphAtlas.Glyph glyph, Transform2D t, float x, float y,
                               float glyphUserX, float glyphUserY, float deviceScale, boolean snap,
                               float originDevX, float baselineDevY) {
        batch.requireTexture(glyph.texture());
        batch.ensure(4, 6);
        int base = batch.baseVertex();
        if (snap) {
            float gx0 = Math.round(originDevX + glyphUserX * deviceScale) + glyph.bearingX();
            float gy0 = baselineDevY + Math.round(glyphUserY * deviceScale) + glyph.bearingY();
            float gx1 = gx0 + glyph.width();
            float gy1 = gy0 + glyph.height();
            float lx0 = glyphUserX + glyph.bearingX() / deviceScale;
            float ly0 = glyphUserY + glyph.bearingY() / deviceScale;
            float lx1 = lx0 + glyph.width() / deviceScale;
            float ly1 = ly0 + glyph.height() / deviceScale;
            batch.vertex(gx0, gy0, lx0, ly0, glyph.u0(), glyph.v0());
            batch.vertex(gx1, gy0, lx1, ly0, glyph.u1(), glyph.v0());
            batch.vertex(gx1, gy1, lx1, ly1, glyph.u1(), glyph.v1());
            batch.vertex(gx0, gy1, lx0, ly1, glyph.u0(), glyph.v1());
        } else {
            float ux0 = x + glyphUserX + glyph.bearingX() / deviceScale;
            float uy0 = y + glyphUserY + glyph.bearingY() / deviceScale;
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

    /**
     * The <b>shaped</b> metrics, from the same ruler and the same memo the paint pass draws from,
     * so a caller that measures here and draws here is measuring the thing it is about to draw.
     *
     * <p>It summed one advance per code point before, which is a different number from the one the
     * painter walks the moment a ligature or a joining script is involved — and the same string
     * measured one way and drawn the other is how a caption ends up a hair wider than the box that
     * was sized for it, or a scroll extent stops short of a caret.
     */
    @Override
    public TextMetrics measureText(String text, Font font) {
        Objects.requireNonNull(font, "font");
        return ruler().shape(text == null ? "" : text, font).metrics();
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
        if (s.clip.isEmpty()) {
            return;
        }
        Transform2D t = s.transform;
        batch.requireTexture(texture);
        applyBlend(s);
        batch.setShape(0, 0, 0, 0, 0, 0, -1, kind, PAINT_SOLID);
        // Straight-alpha tint; the shader multiplies the texel and premultiplies.
        batch.setColors(tintR, tintG, tintB, tintA * s.opacity, 0, 0, 0, 0);
        batch.setGradient(0, 0, 0, 0);
        batch.setClip(s.clip);
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
        emitRoundRect(x, y, width, height, tl, tr, br, bl, strokeWidth, strokeWidth, paint);
    }

    /**
     * Emits a rounded rectangle, filled when {@code strokeWidth} is not positive and stroked
     * otherwise, with its edges where a stroke {@code gridWidth} wide on them would have its
     * centre lines (see {@link #snapToStrokeGrid}): a stroke passes its own width, the fill inside
     * a border passes the border's, so the two share one outline, and a fill that stands alone
     * passes no positive width and keeps its own edges.
     */
    private void emitRoundRect(float x, float y, float width, float height,
                               float tl, float tr, float br, float bl,
                               float strokeWidth, float gridWidth, Paint paint) {
        ensureInFrame();
        State s = state();
        Transform2D t = s.transform;
        float x1 = x + width;
        float y1 = y + height;
        float strokeHalfWidth = strokeWidth > 0 ? strokeWidth / 2f : -1;

        int widthDev = snapToStrokeGrid(t, x, y, x1, y1, gridWidth);
        if (widthDev > 0) {
            x = gridEdges[0];
            y = gridEdges[1];
            x1 = gridEdges[2];
            y1 = gridEdges[3];
            if (strokeWidth > 0) {
                strokeHalfWidth = widthDev / t.m00 / 2f;
            }
        }

        float halfW = (x1 - x) / 2f;
        float halfH = (y1 - y) / 2f;
        if (halfW < 0 || halfH < 0 || (strokeHalfWidth < 0 && (halfW == 0 || halfH == 0))) {
            return;
        }
        if (strokeWidth <= 0 && tl <= 0 && tr <= 0 && br <= 0 && bl <= 0
                && emitFlatRect(s, x, y, x1, y1, paint)) {
            return;
        }
        float maxRadius = Math.min(halfW, halfH);
        tl = Math.min(tl, maxRadius);
        tr = Math.min(tr, maxRadius);
        br = Math.min(br, maxRadius);
        bl = Math.min(bl, maxRadius);
        if (!emitRoundRectPieces(s, x + halfW, y + halfH, halfW, halfH, tl, tr, br, bl,
                strokeHalfWidth, paint)) {
            emitSdfQuad(KIND_ROUND_RECT, x + halfW, y + halfH, halfW, halfH, tl, tr, br, bl,
                    strokeHalfWidth, 1, 0, paint);
        }
    }

    /**
     * Emits an axis-aligned rounded rectangle as pieces of the quad {@link #emitSdfQuad} would
     * draw, leaving out what needs no distance field: a stroke's interior, where its coverage is
     * zero, is not drawn at all, and a fill's interior, where its coverage is exactly the clip's,
     * is drawn as plain geometry that skips the distance field and its derivatives, or, for a large
     * solid one on a software renderer, mostly by the flat program ({@link #emitFlatInterior}), all
     * in the same batch otherwise. Every piece is also cut to
     * the clip rectangle, a device pixel wide of it.
     *
     * <p>Each pixel of the quad is drawn by exactly one piece (the pieces tile it, edge to edge on
     * shared coordinates) with the same attributes, so it gets the colour the quad gave it, to
     * within the rounding of interpolating those attributes over other triangles (a channel off by
     * one on a GPU, rarely); the fragment shader just no longer runs where that colour was already
     * known. On a renderer that runs it on the CPU (macOS's software fallback, a VM with no GPU)
     * that is the cost: a one-point border around a card shaded every pixel of the card to discard
     * all but its edge, as much as the card's fill. Such a renderer also takes its derivatives
     * beside a primitive's edge from something other than the primitive's plane, so there the
     * antialiasing beside a piece's edge moves, as it always did beside the quad's diagonal. The
     * quad's own edges keep the positions it had, and every edge a piece adds lies on a whole
     * device pixel, so no pixel centre sits on one and no piece is a sliver whose interpolation the
     * rasterizer's snapping would skew.
     *
     * <p>Where the interior starts: outside the four corner squares, which are as large as the
     * largest radius, the distance field is the distance to the nearest edge, and the shader's
     * antialiasing ramp reaches at most one device pixel (its width is half the field's screen
     * gradient, which a distance field bounds by one pixel on each axis). So a point further in
     * than half the stroke plus that pixel lies where the stroke's coverage is zero and the fill's
     * is one. The margin past it absorbs float rounding.
     *
     * @return whether the rectangle was emitted; {@code false} (a rotated or skewed transform)
     *         leaves it to the single quad
     */
    private boolean emitRoundRectPieces(State s, float cx, float cy, float halfW, float halfH,
                                        float tl, float tr, float br, float bl,
                                        float strokeHalfWidth, Paint paint) {
        Transform2D t = s.transform;
        if (!splitRoundRects || t.m01 != 0 || t.m10 != 0 || t.m00 == 0 || t.m11 == 0) {
            return false;
        }
        DeviceClip clip = s.clip;
        if (clip.isEmpty()) {
            return true; // fully clipped out, as emitSdfQuad has it
        }
        float stroke = Math.max(strokeHalfWidth, 0);
        // The quad's corners, with emitSdfQuad's expressions, so its outer edges are the same.
        float pad = AA_PAD_DEVICE / t.minScale() + stroke;
        float ex = halfW + pad;
        float ey = halfH + pad;
        pieceDevX[0] = t.x(cx + -ex, cy + -ey);
        pieceLocX[0] = -ex;
        pieceDevX[5] = t.x(cx + ex, cy + -ey);
        pieceLocX[5] = ex;
        pieceDevY[0] = t.y(cx + -ex, cy + -ey);
        pieceLocY[0] = -ey;
        pieceDevY[5] = t.y(cx + -ex, cy + ey);
        pieceLocY[5] = ey;
        // A pixel whose centre lies more than half a pixel outside the clip rectangle has a clip
        // coverage of zero (the clip's ramp is a pixel wide, centred on its edge), so cutting a
        // pixel beyond the clip's outermost whole pixels leaves out nothing that showed.
        cutX0 = (float) Math.floor(clip.x0) - 1;
        cutX1 = (float) Math.ceil(clip.x1) + 1;
        cutY0 = (float) Math.floor(clip.y0) - 1;
        cutY1 = (float) Math.ceil(clip.y1) + 1;
        cutCentreX = cx;
        cutCentreY = cy;
        if (Math.max(pieceDevX[0], pieceDevX[5]) <= cutX0 || Math.min(pieceDevX[0], pieceDevX[5]) >= cutX1
                || Math.max(pieceDevY[0], pieceDevY[5]) <= cutY0
                || Math.min(pieceDevY[0], pieceDevY[5]) >= cutY1) {
            return true; // wholly outside the clip: every pixel's coverage was zero
        }

        int paintType = applyPaint(paint, cx, cy, 1, 0, 0, 1, s.opacity);
        applyBlend(s);
        batch.setShape(halfW, halfH, tl, tr, br, bl, strokeHalfWidth, KIND_ROUND_RECT, paintType);
        batch.setClip(clip);
        batch.ensure(4 * 11, 6 * 11);

        float reach = stroke + (1 + RING_MARGIN_DEVICE) / t.minScale() + 1e-4f;
        float corner = Math.max(Math.max(Math.max(tl, tr), Math.max(br, bl)), reach);
        float holeW = 2 * (halfW - reach);
        float holeH = 2 * (halfH - reach);
        float holeDevice = (holeW * holeH - 4 * (corner - reach) * (corner - reach))
                * Math.abs(t.m00 * t.m11);
        if (corner > halfW || corner > halfH || !(holeDevice >= MIN_HOLE_DEVICE_PIXELS)) {
            piece(0, 0, 5, 5); // no interior worth leaving out
            return true;
        }
        // The band's inner edge and the corner squares' inner edge on each side, each moved onto
        // the device grid towards the interior, which only ever widens the pieces around it.
        pieceBoundary(t, cx, cy, true, 1, -halfW + reach, true);
        pieceBoundary(t, cx, cy, true, 2, -halfW + corner, true);
        pieceBoundary(t, cx, cy, true, 3, halfW - corner, false);
        pieceBoundary(t, cx, cy, true, 4, halfW - reach, false);
        pieceBoundary(t, cx, cy, false, 1, -halfH + reach, true);
        pieceBoundary(t, cx, cy, false, 2, -halfH + corner, true);
        pieceBoundary(t, cx, cy, false, 3, halfH - corner, false);
        pieceBoundary(t, cx, cy, false, 4, halfH - reach, false);
        if (!(pieceLocX[1] < pieceLocX[4]) || !(pieceLocY[1] < pieceLocY[4])) {
            piece(0, 0, 5, 5); // the grid swallowed the interior
            return true;
        }
        if (pieceLocX[3] < pieceLocX[2]) { // the corner squares met: the arms stay, no column
            pieceDevX[3] = pieceDevX[2];
            pieceLocX[3] = pieceLocX[2];
        }
        if (pieceLocY[3] < pieceLocY[2]) {
            pieceDevY[3] = pieceDevY[2];
            pieceLocY[3] = pieceLocY[2];
        }
        // Five rows, top to bottom: the band, the corner squares with the interior's column
        // between them, the side bands with the interior's full width between them, and again.
        piece(0, 0, 5, 1);
        piece(0, 1, 2, 2);
        piece(3, 1, 5, 2);
        piece(0, 2, 1, 3);
        piece(4, 2, 5, 3);
        piece(0, 3, 2, 4);
        piece(3, 3, 5, 4);
        piece(0, 4, 5, 5);
        if (strokeHalfWidth < 0) {
            batch.setShape(halfW, halfH, tl, tr, br, bl, strokeHalfWidth, KIND_PLAIN, paintType);
            if (!emitFlatInterior(s, paint)) {
                piece(2, 1, 3, 2);
                piece(1, 2, 4, 3);
                piece(2, 3, 3, 4);
            }
        }
        return true;
    }

    /**
     * Draws the largest rectangle of a solid fill's interior with the flat program, and the rest of
     * the interior as plain pieces around it, when that rectangle, cut to the clip's whole pixels,
     * covers at least {@link #FLAT_INTERIOR_MIN_DEVICE_PIXELS}. Inside the clip's whole pixels and
     * under no rounded clip, the plain pieces' coverage is exactly one, so the flat program writes
     * the same colour; it just skips the clip test and the attributes the main program interpolates
     * for every pixel, which on a renderer running fragment shaders on the CPU still cost three
     * quarters of the distance field.
     *
     * <p>The price is the batch: the flat program is a second draw call, and the geometry after it
     * a third, so only a software renderer takes this path ({@link #flatInteriors}). Measured at
     * 2604 x 1850 on a form of 28 rows (about 150 surfaces): with Apple Software Renderer the full
     * frame costs 230 ms with plain interiors and 111 ms with flat ones at this threshold, 142 ms at
     * 16384 pixels (only the fields' interiors) and 109 ms with every interior flat; on an Apple M5
     * Max the 155 draw calls it would take there cost about 0.6 ms against 0.54 ms for the whole
     * frame in one.
     *
     * @return whether it did; {@code false} leaves the whole interior to the plain pieces
     */
    private boolean emitFlatInterior(State s, Paint paint) {
        DeviceClip clip = s.clip;
        if (!flatInteriors || !(paint instanceof Color c) || clip.radius > 0) {
            return false;
        }
        // The column between the corner squares, or the row between the side bands: the larger.
        float colArea = Math.abs((pieceDevX[3] - pieceDevX[2]) * (pieceDevY[4] - pieceDevY[1]));
        float rowArea = Math.abs((pieceDevX[4] - pieceDevX[1]) * (pieceDevY[3] - pieceDevY[2]));
        boolean column = colArea >= rowArea;
        int xa = column ? 2 : 1;
        int xb = column ? 3 : 4;
        int ya = column ? 1 : 2;
        int yb = column ? 4 : 3;
        float px0 = Math.min(pieceDevX[xa], pieceDevX[xb]);
        float px1 = Math.max(pieceDevX[xa], pieceDevX[xb]);
        float py0 = Math.min(pieceDevY[ya], pieceDevY[yb]);
        float py1 = Math.max(pieceDevY[ya], pieceDevY[yb]);
        // Inside the clip's whole pixels, where its coverage is exactly one.
        float fx0 = Math.max(px0, (float) Math.ceil(clip.x0));
        float fx1 = Math.min(px1, (float) Math.floor(clip.x1));
        float fy0 = Math.max(py0, (float) Math.ceil(clip.y0));
        float fy1 = Math.min(py1, (float) Math.floor(clip.y1));
        if (!(fx1 > fx0) || !(fy1 > fy0) || (fx1 - fx0) * (fy1 - fy0) < FLAT_INTERIOR_MIN_DEVICE_PIXELS) {
            return false;
        }
        if (column) {
            piece(1, 2, 2, 3);
            piece(3, 2, 4, 3);
        } else {
            piece(2, 1, 3, 2);
            piece(2, 3, 3, 4);
        }
        pieceBetween(px0, px1, py0, fy0);
        pieceBetween(px0, px1, fy1, py1);
        pieceBetween(px0, fx0, fy0, fy1);
        pieceBetween(fx1, px1, fy0, fy1);
        batch.flatRect(fx0, fy0, fx1, fy1, c.r(), c.g(), c.b(), c.a() * s.opacity);
        flatRects++;
        flatPixels += (long) ((fx1 - fx0) * (fy1 - fy0));
        return true;
    }

    /**
     * Sets boundary {@code index} of the current shape's pieces, on the x axis or the y axis, to the
     * whole device pixel nearest {@code local} on the side of increasing local coordinates when
     * {@code forward} holds and decreasing ones otherwise.
     */
    private void pieceBoundary(Transform2D t, float cx, float cy, boolean xAxis, int index, float local,
                               boolean forward) {
        float scale = xAxis ? t.m00 : t.m11;
        float origin = xAxis ? t.tx : t.ty;
        float centre = xAxis ? cx : cy;
        float device = scale * (centre + local) + origin;
        float snapped = (float) (forward == scale > 0 ? Math.ceil(device) : Math.floor(device));
        float snappedLocal = (snapped - origin) / scale - centre;
        if (xAxis) {
            pieceDevX[index] = snapped;
            pieceLocX[index] = snappedLocal;
        } else {
            pieceDevY[index] = snapped;
            pieceLocY[index] = snappedLocal;
        }
    }

    /**
     * Emits the piece of the current shape between boundaries {@code x0}..{@code x1} and
     * {@code y0}..{@code y1} (indices in local order), cut to {@link #cutX0}..{@link #cutY1};
     * nothing when it is empty or the cut empties it.
     */
    private void piece(int x0, int y0, int x1, int y1) {
        pieceAt(pieceDevX[x0], pieceLocX[x0], pieceDevX[x1], pieceLocX[x1],
                pieceDevY[y0], pieceLocY[y0], pieceDevY[y1], pieceLocY[y1]);
    }

    /**
     * Emits the piece of the current shape between two whole device columns and two whole device
     * rows, given in either order; see {@link #piece}.
     */
    private void pieceBetween(float devX0, float devX1, float devY0, float devY1) {
        Transform2D t = state().transform;
        float lx0 = (devX0 - t.tx) / t.m00 - cutCentreX;
        float lx1 = (devX1 - t.tx) / t.m00 - cutCentreX;
        float ly0 = (devY0 - t.ty) / t.m11 - cutCentreY;
        float ly1 = (devY1 - t.ty) / t.m11 - cutCentreY;
        pieceAt(lx0 <= lx1 ? devX0 : devX1, Math.min(lx0, lx1), lx0 <= lx1 ? devX1 : devX0, Math.max(lx0, lx1),
                ly0 <= ly1 ? devY0 : devY1, Math.min(ly0, ly1), ly0 <= ly1 ? devY1 : devY0, Math.max(ly0, ly1));
    }

    private void pieceAt(float dx0, float lx0, float dx1, float lx1,
                         float dy0, float ly0, float dy1, float ly1) {
        if (!(lx0 < lx1) || !(ly0 < ly1)) {
            return;
        }
        Transform2D t = state().transform;
        // The cut, on whichever end each side of the clip falls in a mirrored transform.
        boolean xRising = dx0 < dx1;
        float lowX = xRising ? dx0 : dx1;
        float highX = xRising ? dx1 : dx0;
        float lowY = dy0 < dy1 ? dy0 : dy1;
        float highY = dy0 < dy1 ? dy1 : dy0;
        if (highX <= cutX0 || lowX >= cutX1 || highY <= cutY0 || lowY >= cutY1) {
            return;
        }
        if (lowX < cutX0) {
            float l = (cutX0 - t.tx) / t.m00 - cutCentreX;
            if (xRising) {
                dx0 = cutX0;
                lx0 = l;
            } else {
                dx1 = cutX0;
                lx1 = l;
            }
        }
        if (highX > cutX1) {
            float l = (cutX1 - t.tx) / t.m00 - cutCentreX;
            if (xRising) {
                dx1 = cutX1;
                lx1 = l;
            } else {
                dx0 = cutX1;
                lx0 = l;
            }
        }
        boolean yRising = dy0 < dy1;
        if (lowY < cutY0) {
            float l = (cutY0 - t.ty) / t.m11 - cutCentreY;
            if (yRising) {
                dy0 = cutY0;
                ly0 = l;
            } else {
                dy1 = cutY0;
                ly1 = l;
            }
        }
        if (highY > cutY1) {
            float l = (cutY1 - t.ty) / t.m11 - cutCentreY;
            if (yRising) {
                dy1 = cutY1;
                ly1 = l;
            } else {
                dy0 = cutY1;
                ly0 = l;
            }
        }
        int base = batch.baseVertex();
        batch.vertex(dx0, dy0, lx0, ly0);
        batch.vertex(dx1, dy0, lx1, ly0);
        batch.vertex(dx1, dy1, lx1, ly1);
        batch.vertex(dx0, dy1, lx0, ly1);
        batch.triangle(base, base + 1, base + 2);
        batch.triangle(base, base + 2, base + 3);
    }

    /**
     * Draws a large filled rectangle with the batch's flat program when that draws what the SDF
     * quad would: a solid colour, an axis-aligned transform, no rounded clip, and every edge of the
     * rectangle, as the clip rectangle cuts it, on a whole device pixel. The rectangle is sent
     * already cut to the clip rectangle, and the flat program writes the colour with no distance
     * field, no derivative and no clip test.
     *
     * <p>Under those conditions the SDF quad's coverage is one inside and zero outside: the pixel
     * nearest an edge has its centre half a pixel in, where the clip's antialiasing is fully on and
     * the shape's is on to within a ten-millionth, and the skirt past the edge is off. The one place
     * the two differ is where the SDF was wrong: along the target's last column and row it left up
     * to 33 of 255 of the colour out (a derivative taken across the edge of the target widens the
     * ramp there), and the flat program covers those pixels fully, as the rectangle asks.
     *
     * <p>What it saves is work, and on a renderer that runs the fragment shader on the CPU (macOS's
     * software fallback, a VM with no GPU) that work is the frame. The scrim over a window that a
     * modal blocks was one SDF quad over the whole window, as expensive as every widget of a form
     * beneath it together, so the blocked window's full frame cost twice the unblocked one; and a
     * partial repaint drew the scrim once per pass over the bounding box of all of them, because the
     * clip discarded its pixels only after the shader had run for each.
     *
     * <p>Only a rectangle covering at least a quarter of the target (before the cut) takes this
     * path, because switching programs breaks the batch: a draw call more on each side of the flat
     * rectangle, which a scrim, a page background or a letterbox repays many times over and a list
     * row between two labels would not.
     *
     * @return whether the rectangle was drawn; {@code false} leaves it to the SDF path
     */
    private boolean emitFlatRect(State s, float x, float y, float x1, float y1, Paint paint) {
        Transform2D t = s.transform;
        if (!(paint instanceof Color c) || s.clip.radius > 0 || t.m01 != 0 || t.m10 != 0) {
            return false;
        }
        float ax = t.x(x, y);
        float bx = t.x(x1, y1);
        float ay = t.y(x, y);
        float by = t.y(x1, y1);
        if (Math.abs((bx - ax) * (by - ay)) * 4 < (float) fbWidth * fbHeight) {
            return false; // too small to repay the batch break
        }
        float dx0 = Math.max(Math.min(ax, bx), s.clip.x0);
        float dx1 = Math.min(Math.max(ax, bx), s.clip.x1);
        float dy0 = Math.max(Math.min(ay, by), s.clip.y0);
        float dy1 = Math.min(Math.max(ay, by), s.clip.y1);
        int px0 = Math.round(dx0);
        int px1 = Math.round(dx1);
        int py0 = Math.round(dy0);
        int py1 = Math.round(dy1);
        if (px1 <= px0 || py1 <= py0
                || Math.abs(dx0 - px0) > PIXEL_EDGE_TOLERANCE || Math.abs(dx1 - px1) > PIXEL_EDGE_TOLERANCE
                || Math.abs(dy0 - py0) > PIXEL_EDGE_TOLERANCE
                || Math.abs(dy1 - py1) > PIXEL_EDGE_TOLERANCE) {
            // Clipped away, or an edge that crosses pixels and so has a fractional coverage (a
            // clip's fractional edge can even leave part of a pixel showing past an empty cut).
            return false;
        }
        applyBlend(s);
        batch.flatRect(px0, py0, px1, py1, c.r(), c.g(), c.b(), c.a() * s.opacity);
        flatRects++;
        flatPixels += (long) (px1 - px0) * (py1 - py0);
        return true;
    }

    /**
     * Moves a rectangle's edges to where a stroke {@code strokeWidth} wide on them has its centre
     * lines on the device grid, and writes them to {@link #gridEdges} in current coordinates
     * (left, top, right, bottom). The stroke's width is rounded to whole device pixels (at least
     * one) and each centre line goes to a half pixel when that width is odd and to a whole one when
     * it is even, so an edge moves by up to half a device pixel either way. Uniform axis-aligned
     * transforms only: the parity rule assumes one device width per stroke.
     *
     * @return the stroke's width in device pixels, or 0 when the edges stay where they are (no
     *         stroke width, or a rotated or anisotropic transform), leaving {@link #gridEdges}
     *         untouched
     */
    private int snapToStrokeGrid(Transform2D t, float x, float y, float x1, float y1, float strokeWidth) {
        if (!(strokeWidth > 0) || !t.isUniformAxisAligned()) {
            return 0;
        }
        int widthDev = Snapping.strokeWidthDev(strokeWidth, t.m00);
        gridEdges[0] = (Snapping.snapCenter(t.x(x, y), widthDev) - t.tx) / t.m00;
        gridEdges[1] = (Snapping.snapCenter(t.y(x, y), widthDev) - t.ty) / t.m11;
        gridEdges[2] = (Snapping.snapCenter(t.x(x1, y1), widthDev) - t.tx) / t.m00;
        gridEdges[3] = (Snapping.snapCenter(t.y(x1, y1), widthDev) - t.ty) / t.m11;
        return widthDev;
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
        if (s.clip.isEmpty()) {
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
        batch.setShape(halfW, halfH, tl, tr, br, bl, strokeHalfWidth, kind, paintType);
        batch.setClip(s.clip);
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
        batch.setShape(0, 0, 0, 0, 0, 0, -1, KIND_PLAIN, paintType);
        batch.setClip(s.clip);
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
        batch.setShape(0, 0, 0, 0, 0, 0, -1, KIND_FRINGE, paintType);
        batch.setClip(s.clip);
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
