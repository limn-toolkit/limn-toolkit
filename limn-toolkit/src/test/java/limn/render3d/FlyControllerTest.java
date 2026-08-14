package limn.render3d;

import limn.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fly camera: drag looks in place, the wheel flies along the view direction. */
class FlyControllerTest {

    private static Camera camera() {
        return new Camera().eye(new Vec3(0, 0, 5)).target(Vec3.ZERO); // looking down -z
    }

    @Test
    void dragLooksWithoutMovingTheEyeAndKeepsTheLookDistance() {
        Camera cam = camera();
        FlyController fly = new FlyController(cam);
        float distance = cam.eye().distance(cam.target());

        fly.drag(120, 0); // look right

        assertEquals(0f, cam.eye().x(), 1e-4f, "eye stays put while looking");
        assertEquals(5f, cam.eye().z(), 1e-4f);
        assertEquals(distance, cam.eye().distance(cam.target()), 1e-3f, "look preserves distance");
        assertTrue(Math.abs(cam.target().x()) > 0.05f, "aim swung off the -z axis");
    }

    @Test
    void wheelFliesForwardAlongTheViewDirection() {
        Camera cam = camera();
        FlyController fly = new FlyController(cam);

        fly.zoom(1); // fly forward (toward -z)

        assertTrue(cam.eye().z() < 5f, "eye advanced toward the target");
        assertEquals(0f, cam.eye().x(), 1e-4f);
        assertEquals(5f, cam.eye().distance(cam.target()), 1e-3f, "look distance unchanged");
    }
}
