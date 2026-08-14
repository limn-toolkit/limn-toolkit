package limn.backend.lwjgl;

import limn.math.Aabb;
import limn.render3d.GpuMesh;
import limn.render3d.MeshData;
import limn.render3d.MeshUsage;
import limn.render3d.VertexAttribute;
import org.lwjgl.opengl.GL33C;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A {@link GpuMesh} uploaded to one interleaved VBO + index EBO, with attributes
 * bound at their fixed {@link VertexAttribute#location}s so any material's shader
 * finds them. Per-GL-context; owned by a {@link Gl3DContext}.
 *
 * <p>A {@link MeshUsage#DYNAMIC} mesh keeps its interleave scratch between frames
 * and respecifies both buffers whole on {@link #update}, the buffer-orphaning
 * idiom the debug-line batch already uses, which lets the driver hand back fresh
 * storage instead of stalling until the in-flight frame stops reading the old one.
 * The attribute layout is fixed at upload, so an update never rebuilds the VAO.
 */
final class GlMesh implements GpuMesh {

    private final Gl3DContext owner;
    private final MeshUsage usage;
    /** Attributes in location order: the interleave layout, fixed at upload. */
    private final List<VertexAttribute> layout = new ArrayList<>();
    /** Floats per vertex. */
    private final int stride;

    private Aabb bounds;
    private int indexCount;
    private long gpuBytes;
    /**
     * Bumped by every {@link #update}. A dynamic mesh keeps its identity across
     * frames, so anything that caches by identity (the shadow map's "did anything
     * change" test) needs this to notice that its contents did.
     */
    private int revision;

    private int vao;
    private int vbo;
    private int ebo;
    private float[] interleaved = new float[0];
    private boolean disposed;

    GlMesh(Gl3DContext owner, MeshData mesh, MeshUsage usage) {
        this.owner = owner;
        this.usage = usage;

        // Present attributes in location order (POSITION, NORMAL, UV0, COLOR).
        int floats = 0;
        for (VertexAttribute a : VertexAttribute.values()) {
            if (mesh.has(a)) {
                layout.add(a);
                floats += a.components;
            }
        }
        this.stride = floats;

        vao = GL33C.glGenVertexArrays();
        vbo = GL33C.glGenBuffers();
        ebo = GL33C.glGenBuffers();
        GL33C.glBindVertexArray(vao);
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo);
        GL33C.glBindBuffer(GL33C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        int strideBytes = stride * Float.BYTES;
        int offset = 0;
        for (VertexAttribute a : layout) {
            GL33C.glVertexAttribPointer(a.location, a.components, GL33C.GL_FLOAT, false,
                    strideBytes, (long) offset * Float.BYTES);
            GL33C.glEnableVertexAttribArray(a.location);
            offset += a.components;
        }
        write(mesh);
        GL33C.glBindVertexArray(0);
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, 0);
        GL33C.glBindBuffer(GL33C.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    @Override
    public void update(MeshData mesh) {
        if (usage != MeshUsage.DYNAMIC) {
            throw new UnsupportedOperationException(
                    "mesh was uploaded as STATIC; use Graphics3D.upload(mesh, MeshUsage.DYNAMIC)");
        }
        if (disposed) {
            throw new IllegalStateException("mesh has been disposed");
        }
        if (!mesh.presentAttributes().equals(Set.copyOf(layout))) {
            throw new IllegalArgumentException("update changes the vertex layout: uploaded with "
                    + layout + ", updated with " + mesh.presentAttributes());
        }
        GL33C.glBindVertexArray(vao);
        write(mesh);
        GL33C.glBindVertexArray(0);
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, 0);
        GL33C.glBindBuffer(GL33C.GL_ELEMENT_ARRAY_BUFFER, 0);
        revision++;
    }

    /** Interleaves the live prefix and respecifies both buffers. The VAO must be bound. */
    private void write(MeshData mesh) {
        int vertexCount = mesh.vertexCount();
        int floats = vertexCount * stride;
        if (interleaved.length < floats) {
            interleaved = new float[floats];
        }
        for (int v = 0; v < vertexCount; v++) {
            int base = v * stride;
            int off = 0;
            for (VertexAttribute a : layout) {
                float[] src = mesh.get(a);
                for (int c = 0; c < a.components; c++) {
                    interleaved[base + off + c] = src[v * a.components + c];
                }
                off += a.components;
            }
        }

        this.bounds = mesh.bounds();
        this.indexCount = mesh.indexCount();
        int hint = usage == MeshUsage.DYNAMIC ? GL33C.GL_DYNAMIC_DRAW : GL33C.GL_STATIC_DRAW;
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo);
        GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER, prefix(interleaved, floats), hint);
        GL33C.glBindBuffer(GL33C.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL33C.glBufferData(GL33C.GL_ELEMENT_ARRAY_BUFFER, prefix(mesh.indices(), indexCount), hint);
        this.gpuBytes = (long) floats * Float.BYTES + (long) indexCount * Integer.BYTES;
    }

    /**
     * The first {@code count} elements. Copies only when the array is longer than
     * the live prefix, so a static upload (and a dynamic one that happens to fill
     * its capacity) still hands GL the caller's array with no intermediate.
     */
    private static float[] prefix(float[] data, int count) {
        return data.length == count ? data : Arrays.copyOf(data, count);
    }

    private static int[] prefix(int[] data, int count) {
        return data.length == count ? data : Arrays.copyOf(data, count);
    }

    void drawTriangles() {
        GL33C.glBindVertexArray(vao);
        GL33C.glDrawElements(GL33C.GL_TRIANGLES, indexCount, GL33C.GL_UNSIGNED_INT, 0L);
        GL33C.glBindVertexArray(0);
    }

    /** Triangle count (for render stats). */
    int triangleCount() {
        return indexCount / 3;
    }

    /** Estimated GPU bytes for the interleaved VBO + index EBO. */
    long gpuBytes() {
        return gpuBytes;
    }

    /** Changes whenever {@link #update} rewrites the contents. */
    int revision() {
        return revision;
    }

    @Override
    public Aabb bounds() {
        return bounds;
    }

    @Override
    public void dispose() {
        // Idempotent: GL recycles deleted names, so a second delete could
        // destroy a LIVE mesh's VAO/VBO that reused them.
        if (disposed) {
            return;
        }
        disposed = true;
        GL33C.glDeleteVertexArrays(vao);
        GL33C.glDeleteBuffers(vbo);
        GL33C.glDeleteBuffers(ebo);
        owner.forget(this);
    }
}
