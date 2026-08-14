package limn.render3d;

import limn.math.Vec3;

/**
 * Orbits a {@link Camera} around its target: horizontal drag spins the azimuth,
 * vertical drag tilts the elevation (clamped near the poles), the wheel dollies
 * in/out. Seeded from the camera's current eye/target so it takes over smoothly.
 */
public final class OrbitController implements CameraController {

    private static final float MAX_ELEVATION = (float) Math.toRadians(89);

    private final Camera camera;
    private float azimuth;
    private float elevation;
    private float radius;
    private float dragSensitivity = 0.01f; // radians per point
    private float minRadius = 0.5f;
    private float maxRadius = 100f;

    /** Orbits {@code camera} around its own target: drag rotates, wheel zooms. */
    public OrbitController(Camera camera) {
        this.camera = camera;
        Vec3 offset = camera.eye().sub(camera.target());
        radius = Math.max(1e-3f, offset.length());
        elevation = (float) Math.asin(clamp(offset.y() / radius, -1, 1));
        azimuth = (float) Math.atan2(offset.x(), offset.z());
    }

    /** Clamps how close and how far the zoom can go, in scene units. */
    public OrbitController radiusLimits(float min, float max) {
        this.minRadius = min;
        this.maxRadius = max;
        return this;
    }

    @Override
    public void drag(float dx, float dy) {
        azimuth -= dx * dragSensitivity;
        elevation = clamp(elevation + dy * dragSensitivity, -MAX_ELEVATION, MAX_ELEVATION);
        apply();
    }

    @Override
    public void zoom(float amount) {
        radius = clamp(radius * (float) Math.exp(-amount * 0.1f), minRadius, maxRadius);
        apply();
    }

    private void apply() {
        float ce = (float) Math.cos(elevation);
        float se = (float) Math.sin(elevation);
        float ca = (float) Math.cos(azimuth);
        float sa = (float) Math.sin(azimuth);
        Vec3 dir = new Vec3(ce * sa, se, ce * ca);
        camera.eye(camera.target().add(dir.mul(radius)));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
