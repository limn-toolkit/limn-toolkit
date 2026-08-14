package limn.render3d;

/**
 * How often a mesh's contents change, declared at upload, because a GPU buffer's
 * usage is fixed when it is allocated.
 *
 * <p>{@link #STATIC} is written once and never again: geometry loaded from a file,
 * a procedural primitive, anything whose shape outlives the frame that made it.
 * {@link #DYNAMIC} sizes its buffers from the first upload and can then be
 * rewritten every frame through {@link GpuMesh#update} without allocating, for
 * geometry that is rebuilt rather than transformed (a particle batch, a live
 * surface plot, a spline cage being dragged).
 *
 * <p>There is no third "stream" level. The distinction between "rewritten
 * occasionally" and "rewritten every frame" has no portable meaning, and a hint
 * nobody can reason about is a hint nobody should have to choose.
 */
public enum MeshUsage {

    /** Uploaded once; {@link GpuMesh#update} is rejected. */
    STATIC,

    /** Rewritable through {@link GpuMesh#update}, ideally without reallocating. */
    DYNAMIC
}
