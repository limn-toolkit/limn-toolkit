package limn.render3d;

/**
 * A snapshot of the 3D subsystem's GPU footprint and last-frame workload, for
 * diagnostics (a performance monitor). Counts are cheap CPU-side bookkeeping.
 * {@code gpuBytes} covers meshes (VBO/EBO), textures, render targets and UBOs;
 * {@code drawCalls}/{@code triangles} are from the most recent 3D pass.
 */
public record Render3DStats(int meshes, int textures, int renderTargets,
                            long gpuBytes, int drawCalls, long triangles) {

    public static final Render3DStats EMPTY = new Render3DStats(0, 0, 0, 0, 0, 0);

    /** @return the component-wise sum, for aggregating across windows. */
    public Render3DStats plus(Render3DStats other) {
        return new Render3DStats(
                meshes + other.meshes,
                textures + other.textures,
                renderTargets + other.renderTargets,
                gpuBytes + other.gpuBytes,
                drawCalls + other.drawCalls,
                triangles + other.triangles);
    }
}
