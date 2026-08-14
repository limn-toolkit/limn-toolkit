package limn.render3d;

/**
 * An opaque handle to a texture uploaded to the GPU (see
 * {@code Graphics3D.uploadTexture}). Like {@link GpuMesh} it belongs to the GL
 * context it was created in and is freed either explicitly via {@link #dispose()}
 * or when that context is disposed. Reference it from a {@link Material.Pbr}.
 */
public interface GpuTexture {

    int widthPx();

    int heightPx();

    void dispose();
}
