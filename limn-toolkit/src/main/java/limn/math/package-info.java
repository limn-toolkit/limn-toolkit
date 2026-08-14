/**
 * Single-precision linear algebra for 3D: immutable {@link limn.math.Vec2},
 * {@link limn.math.Vec3}, {@link limn.math.Vec4}, {@link limn.math.Mat3},
 * {@link limn.math.Mat4} (column-major, OpenGL convention) and {@link limn.math.Quat},
 * the composed {@link limn.math.Transform3D}, and the geometry a renderer asks about, namely
 * {@link limn.math.Aabb}, {@link limn.math.Frustum} culling and {@link limn.math.Ray}
 * picking. {@link limn.math.MutMat4} is the deliberate exception to immutability, for
 * per-frame arithmetic where the allocation itself is the cost being avoided.
 */
package limn.math;
