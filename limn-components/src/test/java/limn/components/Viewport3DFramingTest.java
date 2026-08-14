package limn.components;

import limn.math.Aabb;
import limn.math.Vec3;
import limn.scene.Scene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render scale and camera framing. Neither needs a GPU: the scale only changes the
 * pixel size asked of the target, and framing is camera arithmetic.
 */
class Viewport3DFramingTest extends ComponentTestBase {

    private static final Aabb UNIT =
            Aabb.of(new Vec3(-1, -1, -1), new Vec3(1, 1, 1));

    private Viewport3D viewport;

    @BeforeEach
    void build() {
        viewport = new Viewport3D();
        Scene scene = new Scene(viewport);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 200); // 2:1, so the vertical half-angle is the binding one
    }

    @Test
    void renderScaleDefaultsToFullResolution() {
        assertEquals(1f, viewport.renderScale());
    }

    @Test
    void renderScaleIsClampedToAUsefulRange() {
        assertEquals(0.5f, viewport.setRenderScale(0.5f).renderScale());
        assertEquals(0.25f, viewport.setRenderScale(0.05f).renderScale(), "below the floor");
        assertEquals(1f, viewport.setRenderScale(4f).renderScale(), "above full resolution");
        assertEquals(0.25f, viewport.setRenderScale(Float.NEGATIVE_INFINITY).renderScale());
    }

    @Test
    void framingCentresTheCameraOnTheBoxAndBacksItOff() {
        viewport.camera().eye(new Vec3(0, 0, 100)).target(Vec3.ZERO);
        viewport.frameContent(UNIT);

        Vec3 target = viewport.camera().target();
        assertEquals(0f, target.x(), 1e-4f);
        assertEquals(0f, target.y(), 1e-4f);
        assertEquals(0f, target.z(), 1e-4f);

        // Bounding sphere of the unit box is sqrt(3); the fitted distance holds it inside
        // the vertical half-angle, and the default margin leaves a tenth of the frame.
        float radius = (float) Math.sqrt(3);
        float halfV = viewport.camera().fovyRadians() * 0.5f;
        float expected = radius / (float) Math.sin(halfV) * 1.1f;
        assertEquals(expected, viewport.camera().eye().sub(target).length(), 1e-3f);
    }

    @Test
    void framingKeepsTheViewingDirection() {
        viewport.camera().eye(new Vec3(10, 10, 10)).target(Vec3.ZERO);
        Vec3 before = viewport.camera().eye().sub(viewport.camera().target()).normalize();

        viewport.frameContent(Aabb.of(new Vec3(4, 4, 4), new Vec3(6, 6, 6)));

        Vec3 after = viewport.camera().eye().sub(viewport.camera().target()).normalize();
        assertEquals(before.x(), after.x(), 1e-4f);
        assertEquals(before.y(), after.y(), 1e-4f);
        assertEquals(before.z(), after.z(), 1e-4f);
        assertEquals(5f, viewport.camera().target().x(), 1e-4f, "re-aimed at the new centre");
    }

    @Test
    void aTallViewportBacksOffFurtherThanAWideOne() {
        Viewport3D wide = new Viewport3D();
        new Scene(wide).layoutPass(400, 200);
        wide.frameContent(UNIT);
        float wideDistance = wide.camera().eye().sub(wide.camera().target()).length();

        Viewport3D tall = new Viewport3D();
        new Scene(tall).layoutPass(200, 400);
        tall.frameContent(UNIT);
        float tallDistance = tall.camera().eye().sub(tall.camera().target()).length();

        assertTrue(tallDistance > wideDistance,
                "a narrow frame binds on the horizontal half-angle, which is the smaller one");
    }

    @Test
    void aBiggerMarginBacksOffFurther() {
        Viewport3D tight = new Viewport3D();
        new Scene(tight).layoutPass(400, 200);
        tight.frameContent(UNIT, 1f);

        viewport.frameContent(UNIT, 2f);
        assertTrue(viewport.camera().eye().length() > tight.camera().eye().length());
    }

    @Test
    void clipPlanesBracketWhatWasFramed() {
        viewport.frameContent(UNIT);
        float distance = viewport.camera().eye().sub(viewport.camera().target()).length();
        float radius = (float) Math.sqrt(3);

        assertTrue(viewport.camera().near() > 0, "a zero near plane has no depth precision");
        assertTrue(viewport.camera().near() < distance - radius,
                "the near plane must not clip the front of the content");
        assertTrue(viewport.camera().far() > distance + radius,
                "nor the far plane its back");
    }

    @Test
    void anEmptyBoxIsIgnoredRatherThanSendingTheCameraToInfinity() {
        Vec3 eye = viewport.camera().eye();
        viewport.frameContent(Aabb.EMPTY);
        assertEquals(eye.x(), viewport.camera().eye().x(), 1e-6f);
        assertEquals(eye.y(), viewport.camera().eye().y(), 1e-6f);
        assertEquals(eye.z(), viewport.camera().eye().z(), 1e-6f);
    }

    @Test
    void aDegenerateBoxStillGetsAFiniteDistance() {
        Vec3 point = new Vec3(2, 2, 2);
        viewport.frameContent(Aabb.of(point, point));
        float distance = viewport.camera().eye().sub(viewport.camera().target()).length();
        assertTrue(Float.isFinite(distance) && distance > 0);
    }

    @Test
    void framingBeforeLayoutIsDeferredRatherThanLost() {
        Viewport3D unlaid = new Viewport3D();
        Vec3 before = unlaid.camera().eye();
        unlaid.frameContent(UNIT);
        assertEquals(before.z(), unlaid.camera().eye().z(), 1e-6f,
                "nothing to fit against yet: the aspect ratio is unknown");

        Scene scene = new Scene(unlaid);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 200);

        assertNotEquals(before.z(), unlaid.camera().eye().z(),
                "the pending fit lands on the layout pass that gives it an aspect ratio");
    }
}
