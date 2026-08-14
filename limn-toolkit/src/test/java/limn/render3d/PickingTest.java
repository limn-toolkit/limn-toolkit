package limn.render3d;

import limn.math.Mat4;
import limn.math.Ray;
import limn.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CPU raycast picking and the orbit controller. */
class PickingTest {

    private static final MeshData CUBE = Primitives.cube(2); // spans −1..1

    @Test
    void picksTheNearestHitAndReportsThePoint() {
        Ray ray = Ray.of(new Vec3(0, 0, 5), new Vec3(0, 0, -1)); // toward −Z
        PickResult hit = Picker.pick(ray, List.of(new Pickable(CUBE, Mat4.identity(), "cube")));
        assertNotNull(hit);
        assertEquals("cube", hit.tag());
        assertEquals(4, hit.distance(), 1e-3f);   // front face at z = 1
        assertEquals(1, hit.point().z(), 1e-3f);
    }

    @Test
    void missesReturnNull() {
        Ray ray = Ray.of(new Vec3(0, 0, 5), Vec3.UNIT_Y);
        assertNull(Picker.pick(ray, List.of(new Pickable(CUBE, Mat4.identity(), "cube"))));
    }

    @Test
    void nearestOfSeveralWins() {
        Ray ray = Ray.of(new Vec3(0, 0, 5), new Vec3(0, 0, -1));
        Pickable near = new Pickable(CUBE, Mat4.identity(), "near");           // front z=1
        Pickable far = new Pickable(CUBE, Mat4.translation(new Vec3(0, 0, -4)), "far");
        assertEquals("near", Picker.pick(ray, List.of(far, near)).tag());
    }

    @Test
    void picksATransformedObjectInItsLocalSpace() {
        Pickable moved = new Pickable(CUBE, Mat4.translation(new Vec3(3, 0, 0)), "moved");
        Ray ray = Ray.of(new Vec3(3, 0, 5), new Vec3(0, 0, -1)); // aimed at the moved cube
        assertEquals("moved", Picker.pick(ray, List.of(moved)).tag());
        // A ray down the original axis now misses it.
        assertNull(Picker.pick(Ray.of(new Vec3(0, 0, 5), new Vec3(0, 0, -1)), List.of(moved)));
    }

    @Test
    void orbitControllerPreservesRadiusOnDragAndDolliesOnZoom() {
        Camera cam = new Camera().eye(new Vec3(0, 0, 5)).target(Vec3.ZERO);
        OrbitController orbit = new OrbitController(cam);
        orbit.drag(100, 0); // spin
        assertEquals(5, cam.eye().sub(cam.target()).length(), 1e-3f, "orbit keeps the radius");
        assertTrue(Math.abs(cam.eye().x()) > 0.1f, "moved off the z axis");
        orbit.zoom(2); // zoom in
        assertTrue(cam.eye().sub(cam.target()).length() < 5, "zoom dollies closer");
    }
}
