package limn.render3d;

import limn.math.Aabb;
import limn.math.Vec3;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * CPU-side geometry: one float array per {@link VertexAttribute} plus a triangle
 * index list. Upload it once with {@code Graphics3D.upload(...)} to get a
 * {@link GpuMesh}. {@link VertexAttribute#POSITION} is required. All attributes
 * must have the same vertex count.
 *
 * <p><b>Geometry that changes every frame</b> uploads once with
 * {@link MeshUsage#DYNAMIC} and is rewritten through {@link GpuMesh#update}. Such
 * a producer sizes its arrays to the worst case, rewrites them in place, and calls
 * {@link #counts} to say how much of them is live this frame, so a frame costs no
 * allocation at all. The arrays are held by reference, never copied, which is what
 * makes writing into them afterwards work.
 */
public final class MeshData {

    private final Map<VertexAttribute, float[]> attributes = new EnumMap<>(VertexAttribute.class);
    private int[] indices = new int[0];
    /** Vertices the backing arrays hold; {@code -1} until the first {@link #put}. */
    private int capacity = -1;
    /** Live vertices, {@code -1} for "all of them"; see {@link #counts}. */
    private int liveVertices = -1;
    /** Live indices, {@code -1} for "all of them". */
    private int liveIndices = -1;

    /** Adds/replaces an attribute; its length must be {@code vertexCount × components}. */
    public MeshData put(VertexAttribute attribute, float[] data) {
        int count = data.length / attribute.components;
        if (data.length % attribute.components != 0) {
            throw new IllegalArgumentException(attribute + " length " + data.length
                    + " is not a multiple of " + attribute.components);
        }
        if (capacity < 0) {
            capacity = count;
        } else if (count != capacity) {
            throw new IllegalArgumentException(attribute + " has " + count
                    + " vertices, expected " + capacity);
        }
        attributes.put(attribute, data);
        liveVertices = -1; // new data describes itself; narrow again with counts()
        return this;
    }

    /** Sets the triangle index buffer; three consecutive indices are one triangle. */
    public MeshData indices(int[] triangleIndices) {
        this.indices = triangleIndices.clone();
        liveIndices = -1;
        return this;
    }

    /**
     * Narrows this mesh to a live prefix: the first {@code vertexCount} vertices of
     * every attribute array and the first {@code indexCount} entries of the index
     * list. Everything past the prefix is capacity, and is ignored by
     * {@link #vertexCount}, {@link #indexCount}, {@link #bounds} and by an upload.
     *
     * <p>This is how a per-frame producer avoids reallocating: size the arrays once
     * to the worst case, write this frame's data into the front of them, and declare
     * how much is live. {@link #put} and {@link #indices} reset the prefix to the
     * whole array (they are describing new backing storage), so call {@code counts}
     * last.
     *
     * <p>Leaving stale data past the prefix is not merely wasteful, it is wrong if
     * anything reads it: last frame's vertex sitting at a far-away position would
     * inflate {@link #bounds}, and nothing about the picture would look off while
     * picking and shadow fitting quietly used the bigger box. Hence the prefix.
     *
     * @throws IllegalArgumentException if either count is negative or exceeds capacity
     */
    public MeshData counts(int vertexCount, int indexCount) {
        if (vertexCount < 0 || vertexCount > Math.max(0, capacity)) {
            throw new IllegalArgumentException("vertexCount " + vertexCount
                    + " is outside [0, " + Math.max(0, capacity) + "]");
        }
        if (indexCount < 0 || indexCount > indices.length) {
            throw new IllegalArgumentException("indexCount " + indexCount
                    + " is outside [0, " + indices.length + "]");
        }
        this.liveVertices = vertexCount;
        this.liveIndices = indexCount;
        return this;
    }

    /** Which vertex attributes this mesh actually carries. */
    public Set<VertexAttribute> presentAttributes() {
        return attributes.keySet();
    }

    /** Whether one attribute is present, what a shader checks before binding it. */
    public boolean has(VertexAttribute attribute) {
        return attributes.containsKey(attribute);
    }

    /** The backing array for {@code attribute}: the whole capacity, not the live prefix. */
    public float[] get(VertexAttribute attribute) {
        return attributes.get(attribute);
    }

    /** Live vertices: what an upload reads, and what {@link #bounds} measures. */
    public int vertexCount() {
        return liveVertices >= 0 ? liveVertices : Math.max(0, capacity);
    }

    /** Vertices the backing arrays hold; equals {@link #vertexCount} unless narrowed. */
    public int vertexCapacity() {
        return Math.max(0, capacity);
    }

    /** The backing index array: the whole capacity, not the live prefix. */
    public int[] indices() {
        return indices;
    }

    /** Live indices: what an upload draws. */
    public int indexCount() {
        return liveIndices >= 0 ? liveIndices : indices.length;
    }

    /** Object-space bounds over the live prefix of {@link VertexAttribute#POSITION}. */
    public Aabb bounds() {
        float[] pos = attributes.get(VertexAttribute.POSITION);
        if (pos == null) {
            return Aabb.EMPTY;
        }
        int limit = Math.min(pos.length, vertexCount() * 3);
        Aabb box = Aabb.EMPTY;
        for (int i = 0; i + 2 < limit; i += 3) {
            box = box.union(new Vec3(pos[i], pos[i + 1], pos[i + 2]));
        }
        return box;
    }
}
