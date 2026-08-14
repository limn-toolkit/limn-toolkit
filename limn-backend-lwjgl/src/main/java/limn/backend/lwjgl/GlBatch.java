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
 */
final class GlBatch implements AutoCloseable {

    static final int VERTEX_FLOATS = 32;

    private static final int INITIAL_VERTICES = 4096;
    private static final int INITIAL_INDICES = INITIAL_VERTICES * 3 / 2;

    private final ShaderProgram program;
    private final int uniformViewport;
    private final int uniformExposure;
    private final int vao;
    private final int vbo;
    private final int ebo;

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

    GlBatch() {
        program = ShaderProgram.fromResources(
                "/limn/backend/lwjgl/shaders/canvas.vert",
                "/limn/backend/lwjgl/shaders/canvas.frag");
        uniformViewport = program.uniformLocation("u_viewport");
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
        glBindVertexArray(0);
        program.use();
        glUniform1i(program.uniformLocation("u_atlas"), 0);
    }

    private static void attribute(int location, int size, int strideBytes, int floatOffset) {
        glEnableVertexAttribArray(location);
        glVertexAttribPointer(location, size, GL_FLOAT, false, strideBytes, floatOffset * (long) Float.BYTES);
    }

    void beginFrame(int framebufferWidth, int framebufferHeight) {
        fbWidth = framebufferWidth;
        fbHeight = framebufferHeight;
        vertices.clear();
        indices.clear();
        vertexCount = 0;
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

    void setScissor(boolean enabled, int x, int yBottomUp, int w, int h) {
        this.scissorEnabled = enabled;
        this.scissorX = x;
        this.scissorY = yBottomUp;
        this.scissorW = w;
        this.scissorH = h;
    }

    void setShape(float halfWidth, float halfHeight,
                  float topLeft, float topRight, float bottomRight, float bottomLeft,
                  float strokeHalfWidth, int shapeKind, int paint, float clipCornerRadius) {
        this.halfW = halfWidth;
        this.halfH = halfHeight;
        this.radTl = topLeft;
        this.radTr = topRight;
        this.radBr = bottomRight;
        this.radBl = bottomLeft;
        this.strokeHalfWidth = strokeHalfWidth;
        this.kind = shapeKind;
        this.paintType = paint;
        this.clipRadius = clipCornerRadius;
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

    void setClip(float x0, float y0, float x1, float y1) {
        clip0 = x0;
        clip1 = y0;
        clip2 = x1;
        clip3 = y1;
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
        if (changed && indices.position() > 0) {
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
        vertices.put(posX).put(posY)
                .put(localX).put(localY)
                .put(halfW).put(halfH)
                .put(radTl).put(radTr).put(radBr).put(radBl)
                .put(strokeHalfWidth).put(kind).put(paintType).put(clipRadius)
                .put(colA0).put(colA1).put(colA2).put(colA3)
                .put(colB0).put(colB1).put(colB2).put(colB3)
                .put(grad0).put(grad1).put(grad2).put(grad3)
                .put(clip0).put(clip1).put(clip2).put(clip3)
                .put(u).put(v);
        vertexCount++;
    }

    void triangle(int a, int b, int c) {
        indices.put(a).put(b).put(c);
    }

    /** Uploads accumulated geometry and draws it (one draw call). */
    void flush() {
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
        glUniform2f(uniformViewport, fbWidth, fbHeight);
        glUniform1f(uniformExposure, exposure);
        if (textureId != 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textureId);
        }
        glEnable(GL_BLEND);
        // Premultiplied alpha; separate alpha factors (see requireBlend).
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        if (scissorEnabled) {
            glEnable(GL_SCISSOR_TEST);
            glScissor(scissorX, scissorY, scissorW, scissorH);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
        drawCalls++;

        vertices.clear();
        indices.clear();
        vertexCount = 0;
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
        MemoryUtil.memFree(vertices);
        MemoryUtil.memFree(indices);
    }
}
