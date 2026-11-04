package limn.backend.lwjgl;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.GL_ALWAYS;
import static org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_BACK;
import static org.lwjgl.opengl.GL33C.GL_BLEND;
import static org.lwjgl.opengl.GL33C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL33C.GL_DECR_WRAP;
import static org.lwjgl.opengl.GL33C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL33C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_EQUAL;
import static org.lwjgl.opengl.GL33C.GL_FLOAT;
import static org.lwjgl.opengl.GL33C.GL_FRONT;
import static org.lwjgl.opengl.GL33C.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL33C.GL_INCR_WRAP;
import static org.lwjgl.opengl.GL33C.GL_INVERT;
import static org.lwjgl.opengl.GL33C.GL_KEEP;
import static org.lwjgl.opengl.GL33C.GL_NOTEQUAL;
import static org.lwjgl.opengl.GL33C.GL_ONE;
import static org.lwjgl.opengl.GL33C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL33C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL33C.GL_STREAM_DRAW;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL33C.GL_ZERO;
import static limn.backend.lwjgl.Gl.glActiveTexture;
import static limn.backend.lwjgl.Gl.glBindBuffer;
import static limn.backend.lwjgl.Gl.glBindTexture;
import static limn.backend.lwjgl.Gl.glBindVertexArray;
import static limn.backend.lwjgl.Gl.glBlendFuncSeparate;
import static limn.backend.lwjgl.Gl.glBufferData;
import static limn.backend.lwjgl.Gl.glColorMask;
import static limn.backend.lwjgl.Gl.glDeleteBuffers;
import static limn.backend.lwjgl.Gl.glDeleteVertexArrays;
import static limn.backend.lwjgl.Gl.glDisable;
import static limn.backend.lwjgl.Gl.glDrawArrays;
import static limn.backend.lwjgl.Gl.glDrawElements;
import static limn.backend.lwjgl.Gl.glEnable;
import static limn.backend.lwjgl.Gl.glEnableVertexAttribArray;
import static limn.backend.lwjgl.Gl.glScissor;
import static limn.backend.lwjgl.Gl.glStencilFunc;
import static limn.backend.lwjgl.Gl.glStencilMask;
import static limn.backend.lwjgl.Gl.glStencilOpSeparate;
import static limn.backend.lwjgl.Gl.glGenBuffers;
import static limn.backend.lwjgl.Gl.glGenVertexArrays;
import static limn.backend.lwjgl.Gl.glUniform1f;
import static limn.backend.lwjgl.Gl.glUniform1i;
import static limn.backend.lwjgl.Gl.glUniform2f;
import static limn.backend.lwjgl.Gl.glVertexAttribPointer;
import static org.lwjgl.opengl.GL33C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33C.GL_LINEAR;
import static org.lwjgl.opengl.GL33C.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_T;
import static limn.backend.lwjgl.Gl.glBindSampler;
import static limn.backend.lwjgl.Gl.glDeleteSamplers;
import static limn.backend.lwjgl.Gl.glGenSamplers;
import static limn.backend.lwjgl.Gl.glSamplerParameteri;
import static limn.backend.lwjgl.Gl.glUniform4f;
import static org.lwjgl.opengl.GL33C.GL_NEAREST;
import static org.lwjgl.opengl.GL33C.GL_RGBA;
import static org.lwjgl.opengl.GL33C.GL_RGBA8;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE1;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE2;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE3;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE4;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;

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
 *
 * <p>A path that holes, overlaps or crosses itself is filled through the stencil, in three draws
 * of its own ({@link #stencilPhase}): its triangles count how often the outline winds round each
 * pixel, its antialiasing fringe is drawn where that count says outside, and one quad paints where
 * it says inside and leaves the stencil at zero again, which is what every frame starts from and
 * every fill ends with.
 *
 * <p>Two states more travel as uniforms, since each changes seldom: a path clip's mask
 * ({@link #requireMask}), whose coverage every program but the flat one multiplies in, and a blend
 * rule the programs compute themselves ({@link #requireRule}), drawn run by run as a mode drawn in
 * two passes is, each run after a copy of the target under it.
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

    /*
     * The canvas program's parts: canvas.frag compiles each only under its LIMN_* name. A draw
     * needs some of them, by its kind, its paint and its clip; the program with all of them is
     * the one every draw takes on a GPU, where a branch no pixel of a group takes costs nothing.
     * A renderer that runs fragment shaders on the processor runs every branch for every pixel
     * instead, so there a flush takes the program with only the parts its primitives used: on
     * Mesa's llvmpipe a 512-pixel disc with a gradient and two letters took 41 ms with all of
     * them and 9.7 ms with its own. The price is paid once per combination, at the first flush
     * that needs it: compiling the program, and llvmpipe's compiling of its code at the first
     * draw, 40 to 60 ms on two processors, during that frame. The union of a flush's parts, not
     * each primitive's, keeps the frame's one draw call.
     */
    static final int PART_SHAPES = 1;        // kinds 0, 1: rounded rectangle and ellipse
    static final int PART_GLYPHS = 1 << 1;   // kind 4
    static final int PART_IMAGES = 1 << 2;   // kinds 5, 6, 7: image, image mask, HDR surface
    static final int PART_BACKDROP = 1 << 3; // kind 8
    static final int PART_SHADOW = 1 << 4;   // kind 9
    static final int PART_LINEAR = 1 << 5;   // paint 1
    static final int PART_RADIAL = 1 << 6;   // paint 2
    static final int PART_GRADIENT = 1 << 7; // paint 3
    static final int PART_IMAGE_PAINT = 1 << 8; // paint 4
    static final int PART_ROUND_CLIP = 1 << 9;
    static final int PART_MASK = 1 << 10;
    static final int PART_RULE = 1 << 11;
    private static final String[] PART_NAMES = {
        "LIMN_SHAPES", "LIMN_GLYPHS", "LIMN_IMAGES", "LIMN_BACKDROP", "LIMN_SHADOW", "LIMN_LINEAR",
        "LIMN_RADIAL", "LIMN_GRADIENT", "LIMN_IMAGE_PAINT", "LIMN_ROUND_CLIP", "LIMN_MASK", "LIMN_RULE",
    };
    static final int ALL_PARTS = (1 << PART_NAMES.length) - 1;

    /** The canvas program with some of its parts, and its uniforms' locations. */
    private static final class MainProgram implements AutoCloseable {
        final ShaderProgram program;
        final int viewport;
        final int origin;
        final int window;
        final int exposure;
        final int maskOn;
        final int maskRect;
        final int rule;

        /**
         * @param parts the parts compiled in; with all of them every uniform must be found, and
         *              with fewer the ones a missing part alone read are left out (location -1,
         *              which a uniform call ignores)
         */
        MainProgram(int parts) {
            java.util.List<String> defines = new java.util.ArrayList<>();
            for (int i = 0; i < PART_NAMES.length; i++) {
                if ((parts & (1 << i)) != 0) {
                    defines.add(PART_NAMES[i]);
                }
            }
            program = ShaderProgram.fromResources("/limn/backend/lwjgl/shaders/canvas.vert",
                    "/limn/backend/lwjgl/shaders/canvas.frag", defines);
            boolean all = parts == ALL_PARTS;
            viewport = location("u_viewport", all);
            origin = location("u_origin", all);
            window = location("u_window", all);
            exposure = location("u_exposure", all);
            maskOn = location("u_maskOn", all);
            maskRect = location("u_maskRect", all);
            rule = location("u_rule", all);
            program.use();
            glUniform1i(location("u_atlas", all), 0);
            glUniform1i(location("u_mask", all), 1);
            glUniform1i(location("u_dst", all), 2);
            glUniform1i(location("u_stops", all), 3);
            glUniform1i(location("u_paint", all), 4);
        }

        private int location(String name, boolean required) {
            return required ? program.uniformLocation(name) : program.optionalUniformLocation(name);
        }

        @Override
        public void close() {
            program.close();
        }
    }

    private final MainProgram program;
    /**
     * The programs with fewer parts, made as flushes ask for them, each with the time a flush last
     * drew with it; see {@link #parted}.
     */
    private final java.util.Map<Integer, MainProgram> partedPrograms = new java.util.HashMap<>();
    private final java.util.Map<Integer, Long> partedLastUse = new java.util.HashMap<>();
    private long lastIdleSweep;

    /**
     * {@code -Dlimn.backend.softwareProgramIdle=<seconds>}: how long a program with fewer parts goes
     * undrawn before the next flush lets it go, 30 by default; 0 keeps every one for the context's
     * life. Each is compiled the first time a flush needs its parts, which on a software renderer
     * costs milliseconds, and holds the renderer's compiled code until it goes.
     */
    static final String IDLE_PROPERTY = "limn.backend.softwareProgramIdle";
    private static final long IDLE_NANOS = idleNanos();

    private static long idleNanos() {
        String value = System.getProperty(IDLE_PROPERTY);
        if (value == null || value.isBlank()) {
            return 30_000_000_000L;
        }
        try {
            double seconds = Double.parseDouble(value.trim());
            return seconds > 0 ? (long) (seconds * 1_000_000_000L) : 0;
        } catch (NumberFormatException notANumber) {
            return 30_000_000_000L;
        }
    }
    /**
     * Whether a flush takes the program with only the parts its primitives used rather than the one
     * with all of them: where the renderer runs fragment shaders on the processor. Set by the canvas.
     */
    boolean parted;
    /** The parts the registers' primitive needs, and the parts the geometry queued needs. */
    private int shapeParts = ALL_PARTS;
    private int clipParts;
    private int queuedParts;
    private final int vao;
    private final int vbo;
    private final int ebo;

    /** Floats per flat vertex: device position, then premultiplied colour. */
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
    private final int clippedUniformMaskOn;
    private final int clippedUniformMaskRect;
    private final int clippedUniformRule;
    /**
     * Bilinear sampling for a picture laid at an angle or another scale, bound over the picture's
     * own sampling, which is nearest because a picture laid back on the pixels it was drawn on must
     * not be filtered.
     */
    private final int smoothSampler;
    /**
     * Trilinear sampling, for a picture minified past two to one somewhere, whose mipmap chain the
     * canvas made for that composite: between the two levels nearest each pixel's footprint, each
     * read bilinearly, so that a pattern finer than the pixels it lands on averages instead of
     * aliasing.
     */
    private final int mipmapSampler;
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
        program = new MainProgram(ALL_PARTS);
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
        clippedUniformMaskOn = clippedCompositeProgram.uniformLocation("u_maskOn");
        clippedUniformMaskRect = clippedCompositeProgram.uniformLocation("u_maskRect");
        clippedUniformRule = clippedCompositeProgram.uniformLocation("u_rule");
        clippedCompositeProgram.use();
        glUniform1i(clippedCompositeProgram.uniformLocation("u_picture"), 0);
        glUniform1i(clippedCompositeProgram.uniformLocation("u_mask"), 1);
        glUniform1i(clippedCompositeProgram.uniformLocation("u_dst"), 2);

        smoothSampler = glGenSamplers();
        glSamplerParameteri(smoothSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glSamplerParameteri(smoothSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(smoothSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(smoothSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        mipmapSampler = glGenSamplers();
        glSamplerParameteri(mipmapSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glSamplerParameteri(mipmapSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(mipmapSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(mipmapSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
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
        twoPassCount = 0;
        drawCalls = 0;
        targetCopies = 0;
        maskTexture = 0;
        paintTexture = 0;
        rule = GlBlend.RULE_NONE;
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
        this.shapeParts = partsOf(shapeKind, paint);
    }

    /** The canvas program's parts a primitive of {@code kind} drawn with {@code paint} needs. */
    static int partsOf(int kind, int paint) {
        int parts = switch (kind) {
            case 0, 1 -> PART_SHAPES;
            case 4 -> PART_GLYPHS;
            case 5, 6, 7 -> PART_IMAGES;
            case 8 -> PART_BACKDROP;
            case 9 -> PART_SHADOW;
            default -> 0; // plain and fringe, which every program draws
        };
        return parts | switch (paint) {
            case 1 -> PART_LINEAR;
            case 2 -> PART_RADIAL;
            case 3 -> PART_GRADIENT;
            case 4 -> PART_IMAGE_PAINT;
            default -> 0;
        };
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
        clipParts = clip.radius > 0 ? PART_ROUND_CLIP : 0;
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
     * The factors of a second pass over the same geometry, or null for one pass: a mode drawn into
     * a group's picture that no single set of factors gives ({@link GlBlend#inGroup}).
     */
    private GlBlend.@Nullable Factors blendThen;

    /**
     * Declares the blend factors for upcoming geometry. A change with pending
     * geometry flushes it: one extra draw call per mode switch.
     */
    void requireBlend(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        requireBlend(srcRgb, dstRgb, srcAlpha, dstAlpha, null);
    }

    /**
     * {@link #requireBlend(int, int, int, int)} for a mode drawn in two passes: every draw of the
     * geometry that follows is made a second time under {@code then}'s factors, the composite's
     * too. The second pass reads what the first left, so two primitives that overlap must not share
     * a draw; the caller flushes between them.
     */
    void requireBlend(GlBlend.Factors first, GlBlend.@Nullable Factors then) {
        requireBlend(first.srcRgb(), first.dstRgb(), first.srcAlpha(), first.dstAlpha(), then);
    }

    private void requireBlend(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, GlBlend.@Nullable Factors then) {
        boolean changed = srcRgb != blendSrcRgb || dstRgb != blendDstRgb
                || srcAlpha != blendSrcAlpha || dstAlpha != blendDstAlpha || then != blendThen;
        if (changed && (indices.position() > 0 || flatVertexCount > 0)) {
            flush();
        }
        blendSrcRgb = srcRgb;
        blendDstRgb = dstRgb;
        blendSrcAlpha = srcAlpha;
        blendDstAlpha = dstAlpha;
        blendThen = then;
    }

    /**
     * Sets the second pass's factors, when the mode in force has one: called between a draw and
     * the same draw made again. The next {@link #applyRasterState} puts the first pass's back.
     *
     * @return whether there is a second pass to draw
     */
    private boolean secondPass() {
        GlBlend.Factors then = blendThen;
        if (then == null) {
            return false;
        }
        glBlendFuncSeparate(then.srcRgb(), then.dstRgb(), then.srcAlpha(), then.dstAlpha());
        return true;
    }

    // The primitives queued under a blend drawn in two passes, in order: where each one's indices
    // (or, for the flat program, its vertices) start, and its device bounds, x0 y0 x1 y1. The
    // second pass reads what the first left, so two primitives that overlap must not share a pass:
    // the flush draws, both passes, each run of primitives none of which overlaps another of the
    // run, then the next run. A glyph quad, a shape, a piece of a path is a primitive, each opened
    // by the ensure() that reserves its room. Kept only while such a blend is in force.
    private int[] twoPassStart = new int[16];
    private float[] twoPassBox = new float[64];
    /**
     * Per primitive, how far inside its quad's edges an analytic shape's own bounds lie, its
     * antialiasing pad; 0 for any other primitive (see below).
     */
    private float[] twoPassInset = new float[16];
    private int twoPassCount;
    /** The inset the next {@link #ensure} records for its primitive; 0 unless the canvas says. */
    private float nextShapeInset;
    /** How far below a whole device pixel a gap may come out and still count as one (float noise). */
    private static final float GAP_TOLERANCE = 1e-3f;

    /**
     * Says that the primitive the next {@link #ensure} opens is an analytic shape (a rectangle,
     * rounded or not, a circle, an ellipse, a stroke of one) whose own bounds lie {@code pad}
     * device pixels inside its quad's edges, the room its antialiasing ramp is given. Read only
     * while a mode drawn in two passes is in force, to let two such shapes a pixel apart share a
     * run.
     */
    void shapeInsetForNext(float pad) {
        nextShapeInset = pad;
    }
    /** The most primitives one run tests against each other: the test is quadratic in the run. */
    private static final int MAX_TWO_PASS_RUN = 64;

    private void beginTwoPassPrimitive(int start, float x0, float y0, float x1, float y1, float inset) {
        if (twoPassCount == twoPassStart.length) {
            twoPassStart = java.util.Arrays.copyOf(twoPassStart, twoPassCount * 2);
            twoPassBox = java.util.Arrays.copyOf(twoPassBox, twoPassCount * 8);
            twoPassInset = java.util.Arrays.copyOf(twoPassInset, twoPassCount * 2);
        }
        twoPassStart[twoPassCount] = start;
        twoPassInset[twoPassCount] = inset;
        int at = twoPassCount * 4;
        twoPassBox[at] = x0;
        twoPassBox[at + 1] = y0;
        twoPassBox[at + 2] = x1;
        twoPassBox[at + 3] = y1;
        twoPassCount++;
    }

    /**
     * Whether primitive {@code i} can cover a pixel that any from {@code from} up to it covers.
     * Two analytic shapes cannot when their own bounds are a device pixel or more apart on an
     * axis: where an outline runs along an axis the ramp is spent half a pixel past it, but beside
     * a corner, sharp or rounded, the field's gradient turns diagonal and the ramp widens toward a
     * pixel, so a pixel diagonal to the corner of a shape that touches another, or stands less
     * than a pixel from it, has coverage from both. Any other pair is judged on the quads, taken
     * to the pixels whose centres they hold, edges included, which are the pixels the rasterizer
     * shades for each.
     */
    private boolean overlapsRun(int i, int from) {
        float pad = twoPassInset[i];
        int ax0 = firstPixel(twoPassBox[i * 4]);
        int ay0 = firstPixel(twoPassBox[i * 4 + 1]);
        int ax1 = lastPixel(twoPassBox[i * 4 + 2]);
        int ay1 = lastPixel(twoPassBox[i * 4 + 3]);
        for (int j = from; j < i; j++) {
            float other = twoPassInset[j];
            if (pad > 0 && other > 0) {
                float gapX = Math.max(twoPassBox[j * 4] + other - (twoPassBox[i * 4 + 2] - pad),
                        twoPassBox[i * 4] + pad - (twoPassBox[j * 4 + 2] - other));
                float gapY = Math.max(twoPassBox[j * 4 + 1] + other - (twoPassBox[i * 4 + 3] - pad),
                        twoPassBox[i * 4 + 1] + pad - (twoPassBox[j * 4 + 3] - other));
                if (gapX < 1 - GAP_TOLERANCE && gapY < 1 - GAP_TOLERANCE) {
                    return true;
                }
            } else if (ax0 <= lastPixel(twoPassBox[j * 4 + 2]) && firstPixel(twoPassBox[j * 4]) <= ax1
                    && ay0 <= lastPixel(twoPassBox[j * 4 + 3]) && firstPixel(twoPassBox[j * 4 + 1]) <= ay1) {
                return true;
            }
        }
        return false;
    }

    /** The first pixel whose centre lies at or past {@code edge}. */
    private static int firstPixel(float edge) {
        return (int) Math.ceil(edge - 0.5f);
    }

    /** The last pixel whose centre lies at or before {@code edge}. */
    private static int lastPixel(float edge) {
        return (int) Math.floor(edge - 0.5f);
    }

    /**
     * Draws {@code total} queued indices (or flat vertices), both passes, run by run: a run ends
     * before a primitive that overlaps one already in it. What precedes the first record (nothing,
     * as the canvas emits) is a run of its own.
     */
    private void drawInTwoPasses(boolean indexed, int total) {
        int n = twoPassCount;
        int head = n == 0 ? total : twoPassStart[0];
        if (head > 0) {
            if (rule != GlBlend.RULE_NONE) {
                copyTarget(0, 0, targetWidth, targetHeight); // unrecorded: its bounds are unknown
                bindTargetCopy();
                drawRange(indexed, 0, head);
            } else {
                drawBothPasses(indexed, 0, head);
            }
        }
        int runFrom = 0;
        for (int i = 1; i <= n; i++) {
            if (i == n || i - runFrom >= MAX_TWO_PASS_RUN || overlapsRun(i, runFrom)) {
                int first = twoPassStart[runFrom];
                int end = i == n ? total : twoPassStart[i];
                if (end > first) {
                    if (rule != GlBlend.RULE_NONE) {
                        copyTargetUnderRecords(runFrom, i);
                        bindTargetCopy();
                        drawRange(indexed, first, end - first);
                    } else {
                        drawBothPasses(indexed, first, end - first);
                    }
                }
                runFrom = i;
            }
        }
        twoPassCount = 0;
    }

    private void drawBothPasses(boolean indexed, int first, int count) {
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        drawRange(indexed, first, count);
        secondPass();
        drawRange(indexed, first, count);
    }

    private void drawRange(boolean indexed, int first, int count) {
        if (indexed) {
            glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, (long) first * Integer.BYTES);
        } else {
            glDrawArrays(GL_TRIANGLES, first, count);
        }
        drawCalls++;
    }

    /** Forgets the tracked texture (after atlas eviction deletes the pages). */
    void resetTexture() {
        textureId = 0;
        paintTexture = 0;
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

    // A path clip's mask (GlCanvas.clipPath): its picture's texture, 0 for none, and its rectangle in
    // the target space's device pixels, top-down. A uniform: it changes where a clip is made or
    // restored, and a flush there is the price.
    private int maskTexture;
    private float maskX;
    private float maskY;
    private float maskW;
    private float maskH;

    /**
     * Declares the path clip's mask for upcoming geometry, or none with a texture of 0: every
     * program but the flat one multiplies its coverage in. A change with pending geometry flushes it.
     *
     * @param texture the mask's picture
     * @param x       its rectangle's left edge in the target space's device pixels
     * @param y       its top edge, top-down
     * @param width   its width in pixels
     * @param height  its height
     */
    void requireMask(int texture, float x, float y, float width, float height) {
        boolean changed = texture != maskTexture || (texture != 0
                && (x != maskX || y != maskY || width != maskW || height != maskH));
        if (changed && (indices.position() > 0 || flatVertexCount > 0)) {
            flush();
        }
        maskTexture = texture;
        maskX = x;
        maskY = y;
        maskW = width;
        maskH = height;
    }

    /** @return whether a path clip's mask is in force; the flat program cannot draw under one */
    boolean masked() {
        return maskTexture != 0;
    }

    // The blend rule the programs compute (GlBlend.RULE_*), RULE_NONE while the fixed factors draw.
    private int rule = GlBlend.RULE_NONE;
    // The copy of the target a rule reads: one texture the framebuffer's size, made on first use.
    private int targetCopy;
    private int targetCopyWidth;
    private int targetCopyHeight;
    private int targetCopies;

    /**
     * Declares the blend rule the programs compute for upcoming geometry, drawn under
     * {@link GlBlend#REPLACE}'s factors, which the caller requires with it. A change with pending
     * geometry flushes it. Every run of primitives none of which overlaps another is drawn after a
     * copy of the target under it, since a rule reads what the pixel held before the draw.
     */
    void requireRule(int value) {
        if (value != rule && (indices.position() > 0 || flatVertexCount > 0)) {
            flush();
        }
        rule = value;
    }

    /** @return whether a blend rule computed by the programs is in force; the flat program has none */
    boolean ruled() {
        return rule != GlBlend.RULE_NONE;
    }

    /** @return the copies of the target made this frame for a blend rule; for tests */
    int targetCopies() {
        return targetCopies;
    }

    /** Whether the primitives queued are recorded one by one, to be drawn in runs that do not overlap. */
    private boolean byRuns() {
        return blendThen != null || rule != GlBlend.RULE_NONE;
    }

    /**
     * Copies the bound target's pixels {@code (x, y)} to {@code (x + width, y + height)}, counted as
     * GL counts them from its bottom-left corner, into the same texels of the copy the rule reads.
     */
    private void copyTarget(int x, int y, int width, int height) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(targetWidth, x + width);
        int y1 = Math.min(targetHeight, y + height);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        glActiveTexture(GL_TEXTURE2);
        if (targetCopy == 0 || targetCopyWidth < fbWidth || targetCopyHeight < fbHeight) {
            if (targetCopy == 0) {
                targetCopy = Gl.glGenTextures();
            }
            glBindTexture(GL_TEXTURE_2D, targetCopy);
            targetCopyWidth = Math.max(targetCopyWidth, fbWidth);
            targetCopyHeight = Math.max(targetCopyHeight, fbHeight);
            Gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, targetCopyWidth, targetCopyHeight, 0, GL_RGBA,
                    GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            Gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            Gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        } else {
            glBindTexture(GL_TEXTURE_2D, targetCopy);
        }
        Gl.glCopyTexSubImage2D(GL_TEXTURE_2D, 0, x0, y0, x0, y0, x1 - x0, y1 - y0);
        targetCopies++;
        glActiveTexture(GL_TEXTURE0);
    }

    /**
     * Copies the target under device pixels {@code (x0, y0)} to {@code (x1, y1)} of the target's
     * space, top-down, grown to whole pixels and by one more for the antialiasing ramp.
     */
    private void copyTargetUnder(float x0, float y0, float x1, float y1) {
        if (!(x1 >= x0) || !(y1 >= y0)) {
            return; // nothing recorded
        }
        int left = (int) Math.floor(x0) - 1 - targetX;
        int right = (int) Math.ceil(x1) + 1 - targetX;
        int top = (int) Math.floor(y0) - 1 - targetY;
        int bottom = (int) Math.ceil(y1) + 1 - targetY;
        copyTarget(left, targetHeight - bottom, right - left, bottom - top);
    }

    /** Copies the target under the union of the primitives recorded from {@code from} to {@code to}. */
    private void copyTargetUnderRecords(int from, int to) {
        float x0 = Float.POSITIVE_INFINITY;
        float y0 = Float.POSITIVE_INFINITY;
        float x1 = Float.NEGATIVE_INFINITY;
        float y1 = Float.NEGATIVE_INFINITY;
        for (int i = from; i < to; i++) {
            x0 = Math.min(x0, twoPassBox[i * 4]);
            y0 = Math.min(y0, twoPassBox[i * 4 + 1]);
            x1 = Math.max(x1, twoPassBox[i * 4 + 2]);
            y1 = Math.max(y1, twoPassBox[i * 4 + 3]);
        }
        copyTargetUnder(x0, y0, x1, y1);
    }

    /** Binds the mask and the copy a rule reads, and sets the main program's uniforms for them. */
    private void applyMaskAndRule(int maskOnUniform, int maskRectUniform, int ruleUniform) {
        glUniform1i(maskOnUniform, maskTexture != 0 ? 1 : 0);
        if (maskTexture != 0) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, maskTexture);
            glUniform4f(maskRectUniform, maskX, maskY, maskW, maskH);
        }
        glUniform1i(ruleUniform, rule);
        if (rule != GlBlend.RULE_NONE) {
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, targetCopy);
        }
        glActiveTexture(GL_TEXTURE0);
    }

    // The texture of the gradients' stops (GradientRamps), the same for the context's life, and the
    // image an image paint samples with the sampler its tiles and sampling ask for: each read by the
    // canvas program on a unit of its own, so that neither takes the glyph atlas's.
    private int stopsTexture;
    private int paintTexture;
    private int paintSampler;

    /** Declares the texture of the gradients' stops, which every flush binds. */
    void setStopsTexture(int texture) {
        stopsTexture = texture;
    }

    /**
     * Declares the image an image paint samples for upcoming geometry, and its sampler. A change
     * with pending geometry flushes it.
     */
    void requirePaintImage(int texture, int sampler) {
        if ((texture != paintTexture || sampler != paintSampler) && (indices.position() > 0 || flatVertexCount > 0)) {
            flush();
        }
        paintTexture = texture;
        paintSampler = sampler;
    }

    /** Geometry drawn as every other draw is, outside a fill through the stencil. */
    static final int STENCIL_NONE = 0;
    /**
     * A fill's triangles, fanned from each contour's first point: the stencil counts, per pixel,
     * the times the outline winds round it, or flips its lowest bit for the even-odd rule; no
     * colour is written.
     */
    static final int STENCIL_WINDING = 1;
    /**
     * The fill's antialiasing fringe, drawn only on pixels the count left outside and each of
     * those once: a pixel it covers is marked, so two bands that meet at a corner do not blend
     * twice.
     */
    static final int STENCIL_FRINGE = 2;
    /**
     * One quad over the fill and its fringe: it paints where the count says inside, and zeroes
     * every stencil value it passes over, inside or not, so the next fill starts from nothing.
     */
    static final int STENCIL_COVER = 3;

    /**
     * The stencil bits that hold a fill's winding count, which wraps every 64 turns: the two above
     * them mark a fringe pixel, the first for a blend's only (or first) pass and the second for its
     * second pass, which must find the pixel unmarked by itself.
     */
    private static final int WINDING_BITS = 0x3f;
    private static final int FIRST_MARK = 0x40;
    private static final int SECOND_MARK = 0x80;

    private int stencilPhase = STENCIL_NONE;
    private boolean stencilEvenOdd;

    /**
     * Moves to a phase of a fill through the stencil, or back out of one with
     * {@link #STENCIL_NONE}: what was queued under the phase before is drawn first, under that
     * phase's stencil state, so each phase is its own draw. The canvas runs the three phases in
     * order for one fill and then leaves; the target bound must have a stencil buffer.
     *
     * @param phase   one of the {@code STENCIL_} phases
     * @param evenOdd whether the fill takes the even-odd rule rather than the non-zero one
     */
    void stencilPhase(int phase, boolean evenOdd) {
        if ((phase != stencilPhase || evenOdd != stencilEvenOdd)
                && (indices.position() > 0 || flatVertexCount > 0)) {
            flush();
        }
        stencilPhase = phase;
        stencilEvenOdd = evenOdd;
    }

    /**
     * Draws the queued indices under the stencil phase in force, both passes where the blend has
     * two, and puts the stencil test, its write mask and the colour mask back as every other draw
     * expects them.
     */
    private void drawStencilPhase(int indexCount) {
        glEnable(GL_STENCIL_TEST);
        int inside = stencilEvenOdd ? 0x01 : WINDING_BITS;
        switch (stencilPhase) {
            case STENCIL_WINDING -> {
                glColorMask(false, false, false, false);
                glStencilMask(inside);
                glStencilFunc(GL_ALWAYS, 0, 0xff);
                if (stencilEvenOdd) {
                    glStencilOpSeparate(GL_FRONT_AND_BACK, GL_KEEP, GL_KEEP, GL_INVERT);
                } else {
                    // Which face is front depends on the projection's flip; the count's sign does
                    // not matter to the non-zero rule, only whether it is zero.
                    glStencilOpSeparate(GL_FRONT, GL_KEEP, GL_KEEP, GL_INCR_WRAP);
                    glStencilOpSeparate(GL_BACK, GL_KEEP, GL_KEEP, GL_DECR_WRAP);
                }
                drawRange(true, 0, indexCount);
            }
            case STENCIL_FRINGE -> {
                if (rule != GlBlend.RULE_NONE) {
                    copyTargetUnderRecords(0, twoPassCount);
                    bindTargetCopy();
                }
                glStencilOpSeparate(GL_FRONT_AND_BACK, GL_KEEP, GL_KEEP, GL_INVERT);
                glStencilMask(FIRST_MARK);
                glStencilFunc(GL_EQUAL, 0, 0xff);
                drawRange(true, 0, indexCount);
                if (secondPass()) {
                    glStencilMask(SECOND_MARK);
                    glStencilFunc(GL_EQUAL, FIRST_MARK, 0xff);
                    drawRange(true, 0, indexCount);
                }
            }
            case STENCIL_COVER -> {
                // The cover paints only pixels the fringe left alone, inside the outline, so the copy
                // made for the fringe still holds what they held; made again, it is the same there.
                if (rule != GlBlend.RULE_NONE) {
                    copyTargetUnderRecords(0, twoPassCount);
                    bindTargetCopy();
                }
                glStencilMask(0xff);
                glStencilFunc(GL_NOTEQUAL, 0, inside);
                if (blendThen != null) {
                    // The second pass paints the same pixels, so the first leaves the count.
                    glStencilOpSeparate(GL_FRONT_AND_BACK, GL_KEEP, GL_KEEP, GL_KEEP);
                    drawRange(true, 0, indexCount);
                    secondPass();
                }
                glStencilOpSeparate(GL_FRONT_AND_BACK, GL_ZERO, GL_ZERO, GL_ZERO);
                drawRange(true, 0, indexCount);
            }
            default -> throw new IllegalStateException("stencil phase " + stencilPhase);
        }
        twoPassCount = 0; // a fill's phases are drawn whole, whatever a two-pass blend recorded
        glColorMask(true, true, true, true);
        glStencilMask(0xff);
        glDisable(GL_STENCIL_TEST);
    }

    /** Binds the copy a rule reads after {@link #copyTarget} left another texture on its unit. */
    private void bindTargetCopy() {
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, targetCopy);
        glActiveTexture(GL_TEXTURE0);
    }

    void ensure(int vertexSlots, int indexSlots) {
        if (byRuns()) {
            if (flatVertexCount > 0) {
                flushFlat(); // drawn before this primitive, and its records with it
            }
            beginTwoPassPrimitive(indices.position(), Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, nextShapeInset);
        }
        nextShapeInset = 0;
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
        if (twoPassCount > 0 && byRuns()) {
            int at = (twoPassCount - 1) * 4; // the primitive this vertex belongs to grows to hold it
            twoPassBox[at] = Math.min(twoPassBox[at], posX);
            twoPassBox[at + 1] = Math.min(twoPassBox[at + 1], posY);
            twoPassBox[at + 2] = Math.max(twoPassBox[at + 2], posX);
            twoPassBox[at + 3] = Math.max(twoPassBox[at + 3], posY);
        }
        queuedParts |= shapeParts | clipParts;
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
        float pr = r * a;
        float pg = g * a;
        float pb = b * a;
        shadedRect(x0, y0, x1, y1, pr, pg, pb, a, pr, pg, pb, a, pr, pg, pb, a, pr, pg, pb, a);
    }

    /**
     * A rectangle for the flat program whose four corners have colours of their own, premultiplied,
     * in the order (x0, y0), (x1, y0), (x1, y1), (x0, y1), interpolated between them: a linear
     * gradient's interior.
     */
    void shadedRect(float x0, float y0, float x1, float y1,
                    float r00, float g00, float b00, float a00, float r10, float g10, float b10, float a10,
                    float r11, float g11, float b11, float a11, float r01, float g01, float b01, float a01) {
        if (indices.position() > 0) {
            flushMain();
        }
        if (flatVertices.remaining() < 6 * FLAT_VERTEX_FLOATS) {
            flatVertices = MemoryUtil.memRealloc(flatVertices, flatVertices.capacity() * 2);
        }
        if (byRuns()) {
            beginTwoPassPrimitive(flatVertexCount, x0, y0, x1, y1, 0);
        }
        flatVertex(x0, y0, r00, g00, b00, a00);
        flatVertex(x1, y0, r10, g10, b10, a10);
        flatVertex(x1, y1, r11, g11, b11, a11);
        flatVertex(x0, y0, r00, g00, b00, a00);
        flatVertex(x1, y1, r11, g11, b11, a11);
        flatVertex(x0, y1, r01, g01, b01, a01);
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
        drawComposite(texture, alpha, 0, null);
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
        compositeThrough(texture, corners, uvs, count, alpha, clip, false);
    }

    /**
     * {@link #compositeThrough(int, float[], float[], int, float, DeviceClip)}, sampled trilinearly
     * when {@code mipmapped}: the picture's mipmap chain has been made for this composite.
     */
    void compositeThrough(int texture, float[] corners, float[] uvs, int count, float alpha, DeviceClip clip,
                          boolean mipmapped) {
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
        drawComposite(texture, alpha, mipmapped ? mipmapSampler : smoothSampler, clip);
    }

    /** @param sampler the sampler bound over the picture's own nearest sampling, or 0 for none */
    private void drawComposite(int texture, float alpha, int sampler, DeviceClip clip) {
        compositeVertices.flip();
        glBindVertexArray(compositeVao);
        glBindBuffer(GL_ARRAY_BUFFER, compositeVbo);
        glBufferData(GL_ARRAY_BUFFER, compositeVertices, GL_STREAM_DRAW);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        boolean smooth = sampler != 0;
        if (smooth) {
            glBindSampler(0, sampler);
        }
        applyRasterState();
        drawCompositeOnce(alpha, clip);
        if (secondPass()) {
            // The pieces of one composite never overlap, so the second pass may follow them all,
            // each piece setting its own scissor again.
            applyRasterScissor();
            drawCompositeOnce(alpha, clip);
        }
        glDisable(GL_SCISSOR_TEST);
        if (smooth) {
            glBindSampler(0, 0);
        }
        glBindVertexArray(0);
    }

    /** The composite's quad, or its pieces, under the clip, once, in the factors set now. */
    private void drawCompositeOnce(float alpha, @Nullable DeviceClip clip) {
        if (maskTexture != 0 || rule != GlBlend.RULE_NONE) {
            // A mask's coverage and a rule are computed per fragment, by the variant program,
            // over all of the picture; under no clip, the target is the clip.
            DeviceClip under = clip;
            if (under == null) {
                under = wholeTarget;
                under.resetTo(targetX, targetY, targetX + targetWidth, targetY + targetHeight);
            }
            useComputedComposite(alpha, under);
            drawIn((int) Math.floor(under.x0), (int) Math.floor(under.y0),
                    (int) Math.ceil(under.x1), (int) Math.ceil(under.y1));
            return;
        }
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

    /** The target's own rectangle, for a composite under a mask or a rule and no clip. */
    private final DeviceClip wholeTarget = new DeviceClip();

    private void useComputedComposite(float alpha, DeviceClip clip) {
        clippedCompositeProgram.use();
        applyMaskAndRule(clippedUniformMaskOn, clippedUniformMaskRect, clippedUniformRule);
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
        if (rule != GlBlend.RULE_NONE) {
            glDisable(GL_SCISSOR_TEST);
            copyTarget(left, bottom, right - left, top - bottom);
            bindTargetCopy();
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

        MainProgram main = programFor(queuedParts | (maskTexture != 0 ? PART_MASK : 0)
                | (rule != GlBlend.RULE_NONE ? PART_RULE : 0));
        queuedParts = 0;
        main.program.use();
        glUniform2f(main.viewport, targetWidth, targetHeight);
        glUniform2f(main.origin, targetX, targetY);
        glUniform2f(main.window, fbWidth, fbHeight);
        glUniform1f(main.exposure, exposure);
        applyMaskAndRule(main.maskOn, main.maskRect, main.rule);
        if (stopsTexture != 0) {
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D, stopsTexture);
        }
        if (paintTexture != 0) {
            glActiveTexture(GL_TEXTURE4);
            glBindTexture(GL_TEXTURE_2D, paintTexture);
            glBindSampler(4, paintSampler);
        }
        glActiveTexture(GL_TEXTURE0);
        if (textureId != 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textureId);
        }
        applyRasterState();
        if (stencilPhase != STENCIL_NONE) {
            drawStencilPhase(indexCount);
        } else if (byRuns()) {
            drawInTwoPasses(true, indexCount);
        } else {
            glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
            drawCalls++;
        }
        glBindVertexArray(0);

        vertices.clear();
        indices.clear();
        vertexCount = 0;
    }

    /** The program a flush of geometry needing {@code parts} draws with; see {@link #parted}. */
    private MainProgram programFor(int parts) {
        if (!parted || parts == ALL_PARTS) {
            return program;
        }
        long now = System.nanoTime();
        letIdleProgramsGo(now, IDLE_NANOS);
        MainProgram made = partedPrograms.get(parts);
        if (made == null) {
            made = new MainProgram(parts);
            partedPrograms.put(parts, made);
        }
        partedLastUse.put(parts, now);
        return made;
    }

    /**
     * Deletes the programs with fewer parts no flush has drawn with for {@code idle} nanoseconds,
     * looking at most once a second; none when {@code idle} is 0.
     */
    void letIdleProgramsGo(long now, long idle) {
        if (idle <= 0 || now - lastIdleSweep < 1_000_000_000L) {
            return;
        }
        lastIdleSweep = now;
        partedLastUse.entrySet().removeIf(entry -> {
            if (now - entry.getValue() < idle) {
                return false;
            }
            partedPrograms.remove(entry.getKey()).close();
            return true;
        });
    }

    /** @return how many programs with fewer parts this batch holds; for tests */
    int partedProgramCount() {
        return partedPrograms.size();
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
        if (byRuns()) {
            drawInTwoPasses(false, flatVertexCount);
        } else {
            glDrawArrays(GL_TRIANGLES, 0, flatVertexCount);
            drawCalls++;
        }
        glBindVertexArray(0);
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
        applyRasterScissor();
    }

    /** The damage scissor while the window is the target, none in a layer. */
    private void applyRasterScissor() {
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
        partedPrograms.values().forEach(MainProgram::close);
        partedPrograms.clear();
        partedLastUse.clear();
        glDeleteBuffers(flatVbo);
        glDeleteVertexArrays(flatVao);
        flatProgram.close();
        glDeleteBuffers(compositeVbo);
        glDeleteVertexArrays(compositeVao);
        compositeProgram.close();
        clippedCompositeProgram.close();
        glDeleteSamplers(smoothSampler);
        glDeleteSamplers(mipmapSampler);
        if (targetCopy != 0) {
            Gl.glDeleteTextures(targetCopy);
        }
        MemoryUtil.memFree(compositeVertices);
        MemoryUtil.memFree(vertices);
        MemoryUtil.memFree(indices);
        MemoryUtil.memFree(flatVertices);
    }
}
