package limn.render3d;

import limn.math.Vec3;

/**
 * A first-person "fly" camera: dragging looks around (yaw/pitch about the eye,
 * pitch clamped near the poles) and the wheel flies forward/back along the view
 * direction. Unlike {@link OrbitController} the eye moves through the scene rather
 * than circling a fixed target. Seeded from the camera's current eye/target.
 */
public final class FlyController implements CameraController {

    private static final float MAX_PITCH = (float) Math.toRadians(89);

    private final Camera camera;
    private float yaw;
    private float pitch;
    private float distance; // eye→target look distance, kept constant
    private float lookSensitivity = 0.005f; // radians per point
    private float moveSpeed = 0.35f;        // world units per wheel step

    /** Free-look control of {@code camera}: drag aims, keys move. */
    public FlyController(Camera camera) {
        this.camera = camera;
        Vec3 forward = camera.target().sub(camera.eye());
        distance = Math.max(1e-3f, forward.length());
        Vec3 dir = forward.normalize();
        pitch = (float) Math.asin(clamp(dir.y(), -1, 1));
        yaw = (float) Math.atan2(dir.x(), dir.z());
    }

    /** Scene units travelled per movement step. */
    public FlyController moveSpeed(float unitsPerStep) {
        this.moveSpeed = unitsPerStep;
        return this;
    }

    @Override
    public void drag(float dx, float dy) {
        yaw -= dx * lookSensitivity;
        pitch = clamp(pitch - dy * lookSensitivity, -MAX_PITCH, MAX_PITCH);
        camera.target(camera.eye().add(forward().mul(distance)));
    }

    @Override
    public void zoom(float amount) {
        Vec3 eye = camera.eye().add(forward().mul(amount * moveSpeed));
        camera.eye(eye).target(eye.add(forward().mul(distance)));
    }

    private Vec3 forward() {
        float cp = (float) Math.cos(pitch);
        return new Vec3(cp * (float) Math.sin(yaw), (float) Math.sin(pitch), cp * (float) Math.cos(yaw));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
