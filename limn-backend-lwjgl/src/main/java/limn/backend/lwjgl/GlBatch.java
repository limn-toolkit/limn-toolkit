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

    /** Floats per composite vertex: device position, then the picture's texture coordinates. */
    static final int COMPOSITE_VERTEX_FLOATS = 4;
    private final ShaderProgram compositeProgram;
    private final int compositeUniformViewport;
    private final int compositeUniformOrigin;
    private final int compositeUniformAlpha;
    private final int compositeVao;
    private final int compositeVbo;
    private final FloatBuffer compositeVertices = MemoryUtil.memAllocFloat(6 * COMPOSITE_VERTEX_FLOATS);

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
        attribute(0, 2, compositeStride, 0); // a_pos
        attribute(1, 2, compositeStride, 2); // a_uv
        glBindVertexArray(0);
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
        compositeVertex(x0, y0, u0, v0);
        compositeVertex(x1, y0, u1, v0);
        compositeVertex(x1, y1, u1, v1);
        compositeVertex(x0, y0, u0, v0);
        compositeVertex(x1, y1, u1, v1);
        compositeVertex(x0, y1, u0, v1);
        compositeVertices.flip();
        glBindVertexArray(compositeVao);
        glBindBuffer(GL_ARRAY_BUFFER, compositeVbo);
        glBufferData(GL_ARRAY_BUFFER, compositeVertices, GL_STREAM_DRAW);
        compositeProgram.use();
        glUniform2f(compositeUniformViewport, targetWidth, targetHeight);
        glUniform2f(compositeUniformOrigin, targetX, targetY);
        glUniform1f(compositeUniformAlpha, alpha);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        applyRasterState();
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
        drawCalls++;
    }

    private void compositeVertex(float x, float y, float u, float v) {
        compositeVertices.put(x).put(y).put(u).put(v);
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
        MemoryUtil.memFree(compositeVertices);
        MemoryUtil.memFree(vertices);
        MemoryUtil.memFree(indices);
        MemoryUtil.memFree(flatVertices);
    }
}
