package limn.render3d;

import limn.math.Mat4;
import limn.math.Vec3;

/**
 * A perspective camera positioned by {@code eye}/{@code target}/{@code up}.
 * Mutable so controllers (orbit/fly) can move it; the aspect ratio is supplied at
 * render time from the target's dimensions.
 *
 * <p>Right-handed, looking down −Z in view space. Distances are in scene units and
 * angles in radians. The setters chain and mutate in place; a camera handed to a
 * controller is the same object the renderer reads.
 */
public final class Camera {

    private Vec3 eye = new Vec3(0, 0, 4);
    private Vec3 target = Vec3.ZERO;
    private Vec3 up = Vec3.UNIT_Y;
    private float fovyRadians = (float) Math.toRadians(45);
    private float near = 0.05f;
    private float far = 100f;

    /** Where the camera is. */
    public Vec3 eye() {
        return eye;
    }

    /** Moves the camera without changing what it looks at. */
    public Camera eye(Vec3 eye) {
        this.eye = eye;
        return this;
    }

    /** The point the camera looks at. */
    public Vec3 target() {
        return target;
    }

    /** Aims the camera at a point, keeping its position. */
    public Camera target(Vec3 target) {
        this.target = target;
        return this;
    }

    /** The world-space up hint that fixes roll. */
    public Vec3 up() {
        return up;
    }

    /**
     * Sets the up hint. Must not be parallel to the eye-to-target direction, which
     * would leave the view basis degenerate.
     */
    public Camera up(Vec3 up) {
        this.up = up;
        return this;
    }

    /** Vertical field of view, in radians. */
    public float fovyRadians() {
        return fovyRadians;
    }

    /** Sets the vertical field of view in radians; the horizontal one follows the aspect. */
    public Camera fovy(float radians) {
        this.fovyRadians = radians;
        return this;
    }

    /** Near clip distance, in scene units. */
    public float near() {
        return near;
    }

    /** Far clip distance, in scene units. */
    public float far() {
        return far;
    }

    /**
     * Sets the clip range. Depth precision comes from the <em>ratio</em> far/near, so a
     * near plane far smaller than the scene needs is what causes z-fighting, not a large
     * far plane.
     */
    public Camera clip(float near, float far) {
        this.near = near;
        this.far = far;
        return this;
    }

    /** The world-to-view matrix for the current eye, target and up. */
    public Mat4 view() {
        return Mat4.lookAt(eye, target, up);
    }

    /** The projection matrix for a viewport of the given width/height ratio. */
    public Mat4 projection(float aspect) {
        return Mat4.perspective(fovyRadians, aspect, near, far);
    }

    /** {@link #projection} times {@link #view}, which is what a shader usually wants. */
    public Mat4 viewProjection(float aspect) {
        return projection(aspect).multiply(view());
    }
}
