package limn.backend.lwjgl;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_BLEND;
import static org.lwjgl.opengl.GL33C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL33C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL33C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_FLOAT;
import static org.lwjgl.opengl.GL33C.GL_ONE;
import static org.lwjgl.opengl.GL33C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL33C.GL_STREAM_DRAW;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL33C.glActiveTexture;
import static org.lwjgl.opengl.GL33C.glBindBuffer;
import static org.lwjgl.opengl.GL33C.glBindTexture;
import static org.lwjgl.opengl.GL33C.glBindVertexArray;
import static org.lwjgl.opengl.GL33C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL33C.glBufferData;
import static org.lwjgl.opengl.GL33C.glDeleteBuffers;
import static org.lwjgl.opengl.GL33C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL33C.glDisable;
import static org.lwjgl.opengl.GL33C.glDrawArrays;
import static org.lwjgl.opengl.GL33C.glDrawElements;
import static org.lwjgl.opengl.GL33C.glEnable;
import static org.lwjgl.opengl.GL33C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL33C.glScissor;
import static org.lwjgl.opengl.GL33C.glGenBuffers;
import static org.lwjgl.opengl.GL33C.glGenVertexArrays;
import static org.lwjgl.opengl.GL33C.glUniform1f;
import static org.lwjgl.opengl.GL33C.glUniform1i;
import static org.lwjgl.opengl.GL33C.glUniform2f;
import static org.lwjgl.opengl.GL33C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL33C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33C.GL_LINEAR;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL33C.glDeleteSamplers;
import static org.lwjgl.opengl.GL33C.glGenSamplers;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL33C.glUniform4f;

/**
 * Geometry batcher: accumulates every canvas primitive of a frame into one
 * dynamic vertex/index buffer pair and issues (normally) a single
 * {@code glDrawElements} at frame end. Clip, paint and shape parameters all
 * travel as vertex attributes; the only batch breaker is a glyph-atlas
 * texture-page switch ({@link #requireTexture}), and v1 virtually always
 * stays on one page. Buffers grow on demand and are reused across frames:
 * zero per-frame allocation on the hot path.
 *
 * <p>Beside it runs a second, minimal program for large flat rectangles ({@link #flatRect}):
 * position and colour, no distance field. Pending geometry belongs to one program at a time, so
 * a flat rectangle after anything else flushes it, and anything after a flat rectangle flushes
 * that: one extra draw call per switch, which is why only rectangles large enough to repay it
 * take this path (see {@code GlCanvas.emitFlatRect}).
 */
final class GlBatch implements AutoCloseable {

    /**
     * Floats per vertex, 144 bytes. The last four are the rounded clip's own rectangle, apart from
     * the clip rectangle four before them: see {@link DeviceClip} for why a rounded clip cannot be
     * rounded from the rectangle every clip has narrowed. Per vertex rather than a uniform because
     * the batch is one draw a frame and a rounded clip changes with every widget that sets one; a
     * uniform would flush at each of them.
     */
    static final int VERTEX_FLOATS = 36;

    private static final int INITIAL_VERTICES = 4096;
    private static final int INITIAL_INDICES = INITIAL_VERTICES * 3 / 2;

    private final ShaderProgram program;
    private final int uniformViewport;
    private final int uniformOrigin;
    private final int uniformWindow;
    private final int uniformExposure;
    private final int vao;
    private final int vbo;
    private final int ebo;

    /** Floats per flat vertex: device position, then straight-alpha colour. */
    static final int FLAT_VERTEX_FLOATS = 6;
    private final ShaderProgram flatProgram;
    private final int flatUniformViewport;
    private final int flatUniformOrigin;
    private final int flatVao;
    private final int flatVbo;
    private FloatBuffer flatVertices = MemoryUtil.memAllocFloat(6 * 8 * FLAT_VERTEX_FLOATS);
    private int flatVertexCount;

    /**
     * Floats per composite vertex: the homogeneous device position {@code (x, y, 0, 1)} times w,
     * then the picture's texture coordinates.
     */
    static final int COMPOSITE_VERTEX_FLOATS = 6;
    private final ShaderProgram compositeProgram;
    private final int compositeUniformViewport;
    private final int compositeUniformOrigin;
    private final int compositeUniformAlpha;
    /**
     * The composite under a rounded clip, for a picture drawn in a space of its own: the same
     * vertices, and the clip's coverage at each fragment. Apart from the plain one so that a picture
     * under no rounded clip pays nothing per pixel for it.
     */
    private final ShaderProgram clippedCompositeProgram;
    private final int clippedUniformViewport;
    private final int clippedUniformOrigin;
    private final int clippedUniformAlpha;
    private final int clippedUniformClip;
    private final int clippedUniformRoundClip;
    private final int clippedUniformClipRadius;
    /**
     * Bilinear sampling for a picture laid at an angle or another scale, bound over the picture's
     * own sampling, which is nearest because a picture laid back on the pixels it was drawn on must
     * not be filtered.
     */
    private final int smoothSampler;
    private final int compositeVao;
    private final int compositeVbo;
    private final FloatBuffer compositeVertices = MemoryUtil.memAllocFloat(
            3 * (MAX_COMPOSITE_CORNERS - 2) * COMPOSITE_VERTEX_FLOATS);
    /**
     * The most corners a composite's polygon has: a quad cut by the near plane and the four edges
     * of the clip, each cut adding at most one corner.
     */
    static final int MAX_COMPOSITE_CORNERS = 9;
    /** How many vertices the composite queued now draws: two triangles for a quad, more for a cut one. */
    private int compositeCount;

    private FloatBuffer vertices = MemoryUtil.memAllocFloat(INITIAL_VERTICES * VERTEX_FLOATS);
    private IntBuffer indices = MemoryUtil.memAllocInt(INITIAL_INDICES);
    private int vertexCount;
    private int fbWidth;
    private int fbHeight;
    private int drawCalls;
    private int textureId; // 0 = none required by pending geometry

    // Per-primitive attribute registers, written once per shape and copied
    // into each of its vertices.
    private float halfW, halfH;
    private float radTl, radTr, radBr, radBl;
    private float strokeHalfWidth, kind, paintType, clipRadius;
    private float colA0, colA1, colA2, colA3;
    private float colB0, colB1, colB2, colB3;
    private float grad0, grad1, grad2, grad3;
    private float clip0, clip1, clip2, clip3;
    private float round0, round1, round2, round3;

    GlBatch() {
        program = ShaderProgram.fromResources(
                "/limn/backend/lwjgl/shaders/canvas.vert",
                "/limn/backend/lwjgl/shaders/canvas.frag");
        uniformViewport = program.uniformLocation("u_viewport");
        uniformOrigin = program.uniformLocation("u_origin");
        uniformWindow = program.uniformLocation("u_window");
        uniformExposure = program.uniformLocation("u_exposure");
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        ebo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        int stride = VERTEX_FLOATS * Float.BYTES;
        attribute(0, 2, stride, 0);   // a_pos
        attribute(1, 2, stride, 2);   // a_local
        attribute(2, 2, stride, 4);   // a_halfSize
        attribute(3, 4, stride, 6);   // a_radii
        attribute(4, 4, stride, 10);  // a_misc
        attribute(5, 4, stride, 14);  // a_colorA
        attribute(6, 4, stride, 18);  // a_colorB
        attribute(7, 4, stride, 22);  // a_grad
        attribute(8, 4, stride, 26);  // a_clip
        attribute(9, 2, stride, 30);  // a_uv
        attribute(10, 4, stride, 32); // a_roundClip
        glBindVertexArray(0);
        program.use();
        glUniform1i(program.uniformLocation("u_atlas"), 0);

        flatProgram = ShaderProgram.fromResources(
                "/limn/backend/lwjgl/shaders/canvas_flat.vert",
                "/limn/backend/lwjgl/shaders/canvas_flat.frag");
        flatUniformViewport = flatProgram.uniformLocation("u_viewport");
        flatUniformOrigin = flatProgram.uniformLocation("u_origin");
        flatVao = glGenVertexArrays();
        flatVbo = glGenBuffers();
        glBindVertexArray(flatVao);
        glBindBuffer(GL_ARRAY_BUFFER, flatVbo);
        int flatStride = FLAT_VERTEX_FLOATS * Float.BYTES;
        attribute(0, 2, flatStride, 0); // a_pos
        attribute(1, 4, flatStride, 2); // a_color
        glBindVertexArray(0);

        compositeProgram = ShaderProgram.fromResources(
                "/limn/backend/lwjgl/shaders/canvas_composite.vert",
                "/limn/backend/lwjgl/shaders/canvas_composite.frag");
        compositeUniformViewport = compositeProgram.uniformLocation("u_viewport");
        compositeUniformOrigin = compositeProgram.uniformLocation("u_origin");
        compositeUniformAlpha = compositeProgram.uniformLocation("u_alpha");
        compositeProgram.use();
        glUniform1i(compositeProgram.uniformLocation("u_picture"), 0);
        compositeVao = glGenVertexArrays();
        compositeVbo = glGenBuffers();
        glBindVertexArray(compositeVao);
        glBindBuffer(GL_ARRAY_BUFFER, compositeVbo);
        int compositeStride = COMPOSITE_VERTEX_FLOATS * Float.BYTES;
        attribute(0, 4, compositeStride, 0); // a_pos
        attribute(1, 2, compositeStride, 4); // a_uv
        glBindVertexArray(0);

        clippedCompositeProgram = ShaderProgram.fromResources(
                "/limn/backend/lwjgl/shaders/canvas_composite.vert",
                "/limn/backend/lwjgl/shaders/canvas_composite_clip.frag");
        clippedUniformViewport = clippedCompositeProgram.uniformLocation("u_viewport");
        clippedUniformOrigin = clippedCompositeProgram.uniformLocation("u_origin");
        clippedUniformAlpha = clippedCompositeProgram.uniformLocation("u_alpha");
        clippedUniformClip = clippedCompositeProgram.uniformLocation("u_clip");
        clippedUniformRoundClip = clippedCompositeProgram.uniformLocation("u_roundClip");
        clippedUniformClipRadius = clippedCompositeProgram.uniformLocation("u_clipRadius");
        clippedCompositeProgram.use();
        glUniform1i(clippedCompositeProgram.uniformLocation("u_picture"), 0);

        smoothSampler = glGenSamplers();
        glSamplerParameteri(smoothSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glSamplerParameteri(smoothSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(smoothSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(smoothSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    private static void attribute(int location, int size, int strideBytes, int floatOffset) {
        glEnableVertexAttribArray(location);
        glVertexAttribPointer(location, size, GL_FLOAT, false, strideBytes, floatOffset * (long) Float.BYTES);
    }

    void beginFrame(int framebufferWidth, int framebufferHeight) {
        fbWidth = framebufferWidth;
        fbHeight = framebufferHeight;
        targetX = 0;
        targetY = 0;
        targetWidth = framebufferWidth;
        targetHeight = framebufferHeight;
        inLayer = false;
        vertices.clear();
        indices.clear();
        vertexCount = 0;
        flatVertices.clear();
        flatVertexCount = 0;
        drawCalls = 0;
        scissorEnabled = false; // per-frame: partial rendering re-hints each frame
    }

    // Damage scissor (partial rendering): fragments outside this device-space
    // rect are culled at the raster stage; the per-vertex shader clip alone
    // still shades (and discards) every fragment the scene submits.
    private boolean scissorEnabled;
    private int scissorX;
    private int scissorY; // GL scissor space: origin at the bottom-left
    private int scissorW;
    private int scissorH;

    // The target the geometry is drawn into (ADR 071): the window, or a layer, given as its corner
    // and size in the window's device pixels. Every vertex and every clip stays in the window's
    // pixels whatever the target is; only the projection and the fragment's way back to the window
    // move with it, which is what lets a layer open without touching the canvas's state.
    private int targetX;
    private int targetY;
    private int targetWidth;
    private int targetHeight;
    private boolean inLayer;

    /**
     * Declares the target the geometry that follows is drawn into. The caller has flushed what was
     * pending for the old one and bound the new one: this only changes what the next draw tells the
     * programs. Inside a layer the damage scissor is off, since a layer is already cut to the pass,
     * and the window's scissor is kept for when the window is the target again.
     *
     * @param layer  whether the target is a layer rather than the window
     * @param x      its left edge in the window's device pixels
     * @param y      its top edge, top-down
     * @param width  its width in device pixels
     * @param height its height
     */
    void setTarget(boolean layer, int x, int y, int width, int height) {
        inLayer = layer;
        targetX = x;
        targetY = y;
        targetWidth = width;
        targetHeight = height;
    }

    void setScissor(boolean enabled, int x, int yBottomUp, int w, int h) {
        this.scissorEnabled = enabled;
        this.scissorX = x;
        this.scissorY = yBottomUp;
        this.scissorW = w;
        this.scissorH = h;
    }

    void setShape(float halfWidth, float halfHeight,
                  float topLeft, float topRight, float bottomRight, float bottomLeft,
                  float strokeHalfWidth, int shapeKind, int paint) {
        this.halfW = halfWidth;
        this.halfH = halfHeight;
        this.radTl = topLeft;
        this.radTr = topRight;
        this.radBr = bottomRight;
        this.radBl = bottomLeft;
        this.strokeHalfWidth = strokeHalfWidth;
        this.kind = shapeKind;
        this.paintType = paint;
    }

    void setColors(float aR, float aG, float aB, float aA,
                   float bR, float bG, float bB, float bA) {
        colA0 = aR;
        colA1 = aG;
        colA2 = aB;
        colA3 = aA;
        colB0 = bR;
        colB1 = bG;
        colB2 = bB;
        colB3 = bA;
    }

    void setGradient(float g0, float g1, float g2, float g3) {
        grad0 = g0;
        grad1 = g1;
        grad2 = g2;
        grad3 = g3;
    }

    /**
     * Loads a state's clip for the primitives that follow: the clip rectangle, and the rounded
     * clip's own rectangle and radius, each copied into every vertex like the other registers.
     */
    void setClip(DeviceClip clip) {
        clip0 = clip.x0;
        clip1 = clip.y0;
        clip2 = clip.x1;
        clip3 = clip.y1;
        round0 = clip.roundX0;
        round1 = clip.roundY0;
        round2 = clip.roundX1;
        round3 = clip.roundY1;
        clipRadius = clip.radius;
    }

    /**
     * For fringe vertices (path-fill AA skirt) the radii slot is repurposed as
     * a per-vertex coverage ramp; set between {@link #vertex} calls.
     */
    void setFringeCoverage(float coverage) {
        radTl = coverage;
    }

    /**
     * Declares that upcoming glyph geometry samples {@code texture}. Switching
     * atlas pages mid-frame flushes pending geometry, a batch breaker (the
     * other is {@link #requireBlend}).
     */
    void requireTexture(int texture) {
        if (textureId != 0 && textureId != texture && indices.position() > 0) {
            flush();
        }
        textureId = texture;
    }

    // Premultiplied-alpha blend factors for the accumulated geometry, RGB and
    // alpha declared separately: on TRANSLUCENT window framebuffers destination
    // alpha is the window's coverage seen by the OS compositor, so a mode like
    // additive must add light (RGB) without growing coverage (alpha), or a
    // black-neutral glow would occlude the desktop behind the window.
    private int blendSrcRgb = GL_ONE;
    private int blendDstRgb = GL_ONE_MINUS_SRC_ALPHA;
    private int blendSrcAlpha = GL_ONE;
    private int blendDstAlpha = GL_ONE_MINUS_SRC_ALPHA;

    /**
     * Declares the blend factors for upcoming geometry. A change with pending
     * geometry flushes it: one extra draw call per mode switch.
     */
    void requireBlend(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        boolean changed = srcRgb != blendSrcRgb || dstRgb != blendDstRgb
                || srcAlpha != blendSrcAlpha || dstAlpha != blendDstAlpha;
        if (changed && (indices.position() > 0 || flatVertexCount > 0)) {
            flush();
        }
        blendSrcRgb = srcRgb;
        blendDstRgb = dstRgb;
        blendSrcAlpha = srcAlpha;
        blendDstAlpha = dstAlpha;
    }

    /** Forgets the tracked texture (after atlas eviction deletes the pages). */
    void resetTexture() {
        textureId = 0;
    }

    // Display exposure for HDR-surface quads (kind 7): a uniform, not a vertex
    // attribute, because it is per render target, and a target switch already
    // breaks the batch through requireTexture (each target is its own texture).
    private float exposure = 1f;

    /**
     * Declares the display exposure for upcoming HDR-surface geometry. A change
     * with pending geometry flushes it, the same batch-breaker rule as
     * {@link #requireBlend}; non-HDR quads never read the uniform.
     */
    void requireExposure(float value) {
        if (value != exposure && indices.position() > 0) {
            flush();
        }
        exposure = value;
    }

    void ensure(int vertexSlots, int indexSlots) {
        if (vertices.remaining() < vertexSlots * VERTEX_FLOATS) {
            int needed = vertices.position() + vertexSlots * VERTEX_FLOATS;
            vertices = MemoryUtil.memRealloc(vertices, Math.max(needed, vertices.capacity() * 2));
        }
        if (indices.remaining() < indexSlots) {
            int needed = indices.position() + indexSlots;
            indices = MemoryUtil.memRealloc(indices, Math.max(needed, indices.capacity() * 2));
        }
    }

    /** @return the index of the next vertex to be written */
    int baseVertex() {
        return vertexCount;
    }

    /** Writes one vertex: position (device px) + shape-local coords + registers. */
    void vertex(float posX, float posY, float localX, float localY) {
        vertex(posX, posY, localX, localY, 0f, 0f);
    }

    /** Vertex with explicit atlas UV (glyph quads). */
    void vertex(float posX, float posY, float localX, float localY, float u, float v) {
        if (flatVertexCount > 0) {
            flushFlat(); // drawn before this primitive, which the canvas asked for after it
        }
        vertices.put(posX).put(posY)
                .put(localX).put(localY)
                .put(halfW).put(halfH)
                .put(radTl).put(radTr).put(radBr).put(radBl)
                .put(strokeHalfWidth).put(kind).put(paintType).put(clipRadius)
                .put(colA0).put(colA1).put(colA2).put(colA3)
                .put(colB0).put(colB1).put(colB2).put(colB3)
                .put(grad0).put(grad1).put(grad2).put(grad3)
                .put(clip0).put(clip1).put(clip2).put(clip3)
                .put(u).put(v)
                .put(round0).put(round1).put(round2).put(round3);
        vertexCount++;
    }

    void triangle(int a, int b, int c) {
        indices.put(a).put(b).put(c);
    }

    /**
     * Queues a solid rectangle for the flat program, in device pixels, with a straight-alpha
     * colour whose alpha already carries the canvas opacity. Geometry pending for the main program
     * is drawn first, so paint order holds.
     */
    void flatRect(float x0, float y0, float x1, float y1, float r, float g, float b, float a) {
        if (indices.position() > 0) {
            flushMain();
        }
        if (flatVertices.remaining() < 6 * FLAT_VERTEX_FLOATS) {
            flatVertices = MemoryUtil.memRealloc(flatVertices, flatVertices.capacity() * 2);
        }
        flatVertex(x0, y0, r, g, b, a);
        flatVertex(x1, y0, r, g, b, a);
        flatVertex(x1, y1, r, g, b, a);
        flatVertex(x0, y0, r, g, b, a);
        flatVertex(x1, y1, r, g, b, a);
        flatVertex(x0, y1, r, g, b, a);
    }

    private void flatVertex(float x, float y, float r, float g, float b, float a) {
        flatVertices.put(x).put(y).put(r).put(g).put(b).put(a);
        flatVertexCount++;
    }

    /**
     * Draws a layer's picture into the current target now, as one quad: device pixels of the
     * window {@code (x0, y0)} to {@code (x1, y1)}, sampling the picture's texture coordinates
     * {@code (u0, v0)} to {@code (u1, v1)}, every texel times {@code alpha}, under the blend
     * factors and the damage scissor in force and under no clip. What was pending is drawn first,
     * so paint order holds; the composite is a draw call of its own, as it was in the measurement
     * that priced a layer, and runs a program that does nothing else, because the canvas program's
     * clip and branches over every pixel of a layer cost a renderer without a GPU most of a frame.
     */
    void composite(int texture, float x0, float y0, float x1, float y1,
                   float u0, float v0, float u1, float v1, float alpha) {
        flush();
        compositeVertices.clear();
        compositeVertex(x0, y0, 1, u0, v0);
        compositeVertex(x1, y0, 1, u1, v0);
        compositeVertex(x1, y1, 1, u1, v1);
        compositeVertex(x0, y0, 1, u0, v0);
        compositeVertex(x1, y1, 1, u1, v1);
        compositeVertex(x0, y1, 1, u0, v1);
        compositeCount = 6;
        drawComposite(texture, alpha, false, null);
    }

    /**
     * Draws a picture drawn in a space of its own into the current target now, as one polygon
     * through the picture's transform: its corners, in order round it, each given as the
     * homogeneous device position {@code (x, y)} times {@code w} and its {@code w}, with the
     * picture's texture coordinates at each, sampled bilinearly, so that a turned edge ramps over
     * about a pixel and a picture in perspective is interpolated perspective-correctly. Four corners
     * for a quad; more for one a perspective cut at the near plane, drawn as a fan of triangles from
     * the first, which a convex polygon is. The picture was drawn under a clip of its own, so the enclosing clip is
     * applied here: a rectangle on whole pixels as a scissor, inside the damage scissor while the
     * window is the target, which costs nothing per pixel; a rounded clip, or a rectangle with an
     * edge between pixels, by the variant program, which computes the coverage at each fragment as
     * the canvas program does, inside a scissor at the clip's bounds.
     *
     * @param texture the picture
     * @param corners {@code x, y, w} of each corner, x and y already times w
     * @param uvs     {@code u, v} of each corner
     * @param count   how many corners, 3 to {@link #MAX_COMPOSITE_CORNERS}
     * @param alpha   the opacity
     * @param clip    the enclosing clip, in the device pixels of the target's space
     */
    void compositeThrough(int texture, float[] corners, float[] uvs, int count, float alpha, DeviceClip clip) {
        if (count < 3) {
            return; // nothing of it in front of the eye
        }
        flush();
        compositeVertices.clear();
        for (int t = 1; t < count - 1; t++) {
            cornerVertex(corners, uvs, 0);
            cornerVertex(corners, uvs, t);
            cornerVertex(corners, uvs, t + 1);
        }
        compositeCount = 3 * (count - 2);
        drawComposite(texture, alpha, true, clip);
    }

    private void drawComposite(int texture, float alpha, boolean smooth, DeviceClip clip) {
        compositeVertices.flip();
        glBindVertexArray(compositeVao);
        glBindBuffer(GL_ARRAY_BUFFER, compositeVbo);
        glBufferData(GL_ARRAY_BUFFER, compositeVertices, GL_STREAM_DRAW);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        if (smooth) {
            glBindSampler(0, smoothSampler);
        }
        applyRasterState();
        if (clip == null) {
            usePlainComposite(alpha);
            glDrawArrays(GL_TRIANGLES, 0, compositeCount);
            drawCalls++;
        } else if (!(clip.radius > 0) && !offTheGrid(clip)) {
            usePlainComposite(alpha);
            drawIn(Math.round(clip.x0), Math.round(clip.y0), Math.round(clip.x1), Math.round(clip.y1));
        } else {
            drawUnderComputedClip(alpha, clip);
        }
        glDisable(GL_SCISSOR_TEST);
        if (smooth) {
            glBindSampler(0, 0);
        }
        glBindVertexArray(0);
    }

    /**
     * The composite under a clip only the variant program makes, which computes the clip's coverage
     * at each fragment, and so costs a renderer without a GPU per pixel: run only where a pixel can
     * be covered in part. The band between the rounded clip's corners and inside every edge by half
     * a pixel is covered wholly, and takes the plain program under a scissor; the variant takes the
     * strips above, below and beside it, at most the corners' height and a pixel wide at the sides.
     */
    private void drawUnderComputedClip(float alpha, DeviceClip clip) {
        int bx0 = (int) Math.floor(clip.x0);
        int by0 = (int) Math.floor(clip.y0);
        int bx1 = (int) Math.ceil(clip.x1);
        int by1 = (int) Math.ceil(clip.y1);
        float radius = 0;
        if (clip.radius > 0) {
            // The radius the program rounds by: no more than half the rounded rectangle's sides.
            float half = Math.min(clip.roundX1 - clip.roundX0, clip.roundY1 - clip.roundY0) / 2;
            radius = Math.min(clip.radius, Math.max(0, half));
        }
        int ix0 = (int) Math.ceil(Math.max(clip.x0, clip.roundX0));
        int ix1 = (int) Math.floor(Math.min(clip.x1, clip.roundX1));
        int iy0 = (int) Math.ceil(Math.max(clip.y0, clip.roundY0 + radius));
        int iy1 = (int) Math.floor(Math.min(clip.y1, clip.roundY1 - radius));
        if (ix1 <= ix0 || iy1 <= iy0) {
            useComputedComposite(alpha, clip);
            drawIn(bx0, by0, bx1, by1);
            return;
        }
        usePlainComposite(alpha);
        drawIn(ix0, iy0, ix1, iy1);
        useComputedComposite(alpha, clip);
        drawIn(bx0, by0, bx1, iy0);
        drawIn(bx0, iy1, bx1, by1);
        drawIn(bx0, iy0, ix0, iy1);
        drawIn(ix1, iy0, bx1, iy1);
    }

    private void usePlainComposite(float alpha) {
        compositeProgram.use();
        glUniform2f(compositeUniformViewport, targetWidth, targetHeight);
        glUniform2f(compositeUniformOrigin, targetX, targetY);
        glUniform1f(compositeUniformAlpha, alpha);
    }

    private void useComputedComposite(float alpha, DeviceClip clip) {
        clippedCompositeProgram.use();
        glUniform2f(clippedUniformViewport, targetWidth, targetHeight);
        glUniform2f(clippedUniformOrigin, targetX, targetY);
        glUniform1f(clippedUniformAlpha, alpha);
        glUniform4f(clippedUniformClip, clip.x0, clip.y0, clip.x1, clip.y1);
        glUniform4f(clippedUniformRoundClip, clip.roundX0, clip.roundY0, clip.roundX1, clip.roundY1);
        glUniform1f(clippedUniformClipRadius, clip.radius);
    }

    /**
     * Whether an edge of the clip's rectangle falls between pixels, where the canvas program covers
     * the pixels it cuts in part: a scissor would cover them wholly or not at all, so the variant
     * program computes that coverage as the canvas's does. A pass's clip and a viewport laid on the
     * grid fall on whole pixels, and are left to the scissor.
     */
    private static boolean offTheGrid(DeviceClip clip) {
        return offPixel(clip.x0) || offPixel(clip.y0) || offPixel(clip.x1) || offPixel(clip.y1);
    }

    private static boolean offPixel(float edge) {
        return Math.abs(edge - Math.round(edge)) > 1e-3f;
    }

    /**
     * Draws the composite's quad under a scissor at the pixels {@code (x0, y0)} to {@code (x1, y1)}
     * of the target's space, top-down, cut to the target and, while the window is the target, to
     * the damage scissor; nothing when nothing is left.
     */
    private void drawIn(int x0, int y0, int x1, int y1) {
        int left = Math.max(x0 - targetX, 0);
        int right = Math.min(x1 - targetX, targetWidth);
        // GL counts rows up from the target's bottom edge.
        int bottom = Math.max(targetHeight - (y1 - targetY), 0);
        int top = Math.min(targetHeight - (y0 - targetY), targetHeight);
        if (scissorEnabled && !inLayer) {
            left = Math.max(left, scissorX);
            bottom = Math.max(bottom, scissorY);
            right = Math.min(right, scissorX + scissorW);
            top = Math.min(top, scissorY + scissorH);
        }
        if (right <= left || top <= bottom) {
            return;
        }
        glEnable(GL_SCISSOR_TEST);
        glScissor(left, bottom, right - left, top - bottom);
        glDrawArrays(GL_TRIANGLES, 0, compositeCount);
        drawCalls++;
    }

    private void cornerVertex(float[] corners, float[] uvs, int i) {
        compositeVertex(corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2], uvs[i * 2], uvs[i * 2 + 1]);
    }

    private void compositeVertex(float x, float y, float w, float u, float v) {
        compositeVertices.put(x).put(y).put(0f).put(w).put(u).put(v);
    }

    /** Uploads accumulated geometry and draws it: one draw call per program with any pending. */
    void flush() {
        flushFlat();
        flushMain();
    }

    private void flushMain() {
        int indexCount = indices.position();
        if (indexCount == 0) {
            return;
        }
        vertices.flip();
        indices.flip();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STREAM_DRAW);

        program.use();
        glUniform2f(uniformViewport, targetWidth, targetHeight);
        glUniform2f(uniformOrigin, targetX, targetY);
        glUniform2f(uniformWindow, fbWidth, fbHeight);
        glUniform1f(uniformExposure, exposure);
        if (textureId != 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textureId);
        }
        applyRasterState();
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
        drawCalls++;

        vertices.clear();
        indices.clear();
        vertexCount = 0;
    }

    private void flushFlat() {
        if (flatVertexCount == 0) {
            return;
        }
        flatVertices.flip();
        glBindVertexArray(flatVao);
        glBindBuffer(GL_ARRAY_BUFFER, flatVbo);
        glBufferData(GL_ARRAY_BUFFER, flatVertices, GL_STREAM_DRAW);
        flatProgram.use();
        glUniform2f(flatUniformViewport, targetWidth, targetHeight);
        glUniform2f(flatUniformOrigin, targetX, targetY);
        applyRasterState();
        glDrawArrays(GL_TRIANGLES, 0, flatVertexCount);
        glBindVertexArray(0);
        drawCalls++;
        flatVertices.clear();
        flatVertexCount = 0;
    }

    /** Blend, depth, culling and the damage scissor: what both programs draw under. */
    private void applyRasterState() {
        glEnable(GL_BLEND);
        // Premultiplied alpha; separate alpha factors (see requireBlend).
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        if (scissorEnabled && !inLayer) {
            glEnable(GL_SCISSOR_TEST);
            glScissor(scissorX, scissorY, scissorW, scissorH);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }
    }

    /** @return draw calls issued since {@link #beginFrame} (debug/perf stat) */
    int drawCalls() {
        return drawCalls;
    }

    @Override
    public void close() {
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteVertexArrays(vao);
        program.close();
        glDeleteBuffers(flatVbo);
        glDeleteVertexArrays(flatVao);
        flatProgram.close();
        glDeleteBuffers(compositeVbo);
        glDeleteVertexArrays(compositeVao);
        compositeProgram.close();
        clippedCompositeProgram.close();
        glDeleteSamplers(smoothSampler);
        MemoryUtil.memFree(compositeVertices);
        MemoryUtil.memFree(vertices);
        MemoryUtil.memFree(indices);
        MemoryUtil.memFree(flatVertices);
    }
}
