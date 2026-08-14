package limn.render3d;

import limn.math.Aabb;

/**
 * An opaque handle to mesh geometry uploaded to the GPU (via
 * {@code Graphics3D.upload}). Owned by the GL context it was created in; call
 * {@link #dispose()} to release it (or let the context reclaim it on teardown).
 */
public interface GpuMesh {

    /** Object-space bounds, used by picking's broadphase. */
    Aabb bounds();

    /**
     * Rewrites this mesh's contents, for geometry that changes every frame. Only
     * a mesh uploaded with {@link MeshUsage#DYNAMIC} accepts it; a static one
     * throws, because its buffers were allocated for a single write.
     *
     * <p>The replacement must present the same {@linkplain MeshData#presentAttributes()
     * attributes} as the original upload (the vertex layout is baked into the
     * mesh's GPU state) but may hold any number of live vertices and indices
     * (see {@link MeshData#counts}), growing past the original capacity if it has
     * to. {@link #bounds()} is recomputed from the new data, so picking and shadow
     * fitting stay correct.
     *
     * <p>Default: rejected. Existing implementations and hand-rolled test doubles
     * do not have to know about dynamic geometry.
     *
     * @throws UnsupportedOperationException if this mesh is not {@link MeshUsage#DYNAMIC}
     * @throws IllegalArgumentException      if the attribute set differs from the upload's
     */
    default void update(MeshData mesh) {
        throw new UnsupportedOperationException(
                "mesh was uploaded as STATIC; use Graphics3D.upload(mesh, MeshUsage.DYNAMIC)");
    }

    void dispose();
}
