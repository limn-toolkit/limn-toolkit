package limn.render3d;

import limn.math.Mat4;

/**
 * A candidate for {@link Picker}: geometry ({@link MeshData}, kept CPU-side for
 * ray tests), its world {@code transform}, and an opaque {@code tag} the caller
 * uses to identify the hit.
 */
public record Pickable(MeshData mesh, Mat4 transform, Object tag) {
}
