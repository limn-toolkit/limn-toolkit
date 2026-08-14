package limn.render3d;

import limn.math.Vec3;

/**
 * An image-based lighting environment as a procedural gradient sky: {@code sky}
 * straight up, {@code horizon} at the equator, {@code ground} straight down, in
 * linear color, scaled by {@code intensity}. It drives diffuse irradiance (baked to
 * spherical harmonics; see {@link IrradianceSh}), analytic specular reflections in
 * the PBR shader, and the skybox background. A full HDR/equirect loader can replace
 * the analytic sky later without changing the shading path.
 */
public record Environment(Vec3 sky, Vec3 horizon, Vec3 ground, float intensity) {

    /** A three-stop sky gradient used as ambient light, in linear units. */
    public static Environment gradient(Vec3 sky, Vec3 horizon, Vec3 ground) {
        return new Environment(sky, horizon, ground, 1f);
    }

    /** Scales the whole environment's contribution; {@code 1} leaves it as authored. */
    public Environment intensity(float intensity) {
        return new Environment(sky, horizon, ground, intensity);
    }

    /** Incoming radiance from {@code direction} (need not be normalized). */
    public Vec3 radiance(Vec3 direction) {
        float t = direction.normalize().y();
        Vec3 color = t >= 0 ? horizon.lerp(sky, t) : horizon.lerp(ground, -t);
        return color.mul(intensity);
    }
}
