package limn.math;

import limn.render3d.Camera;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frustum-plane extraction and the conservative AABB test used for culling. */
class FrustumTest {

    // Camera at z=5 looking toward the origin (down -z), 90°-ish fov, aspect 1.
    private static final Frustum FRUSTUM = Frustum.fromViewProjection(
            new Camera().eye(new Vec3(0, 0, 5)).target(Vec3.ZERO).viewProjection(1f));

    private static Aabb boxAround(float x, float y, float z, float half) {
        return Aabb.of(new Vec3(x - half, y - half, z - half), new Vec3(x + half, y + half, z + half));
    }

    @Test
    void keepsABoxInFrontOfTheCamera() {
        assertTrue(FRUSTUM.intersects(boxAround(0, 0, 0, 0.5f)));
    }

    @Test
    void cullsABoxBehindTheCamera() {
        // Behind the eye (further along +z than the eye at z=5).
        assertFalse(FRUSTUM.intersects(boxAround(0, 0, 10, 0.5f)));
    }

    @Test
    void cullsABoxFarToTheSide() {
        assertFalse(FRUSTUM.intersects(boxAround(50, 0, 0, 0.5f)));
        assertFalse(FRUSTUM.intersects(boxAround(0, 50, 0, 0.5f)));
    }

    @Test
    void cullsABoxBeyondTheFarPlane() {
        // Default far is 100; a box ~105 units ahead of the eye is past it.
        assertFalse(FRUSTUM.intersects(boxAround(0, 0, -100, 0.5f)));
    }

    @Test
    void keepsAHugeBoxThatEnclosesTheFrustum() {
        assertTrue(FRUSTUM.intersects(boxAround(0, 0, 0, 1000f)));
    }
}
