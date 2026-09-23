package limn.animation;

import limn.concurrent.UiRuntime;
import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ColorTransitionTest {

    private HeadlessUi ui;
    private UiRuntime runtime;

    static final class Owner extends Widget<Owner> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(10, 10);
        }
    }

    private Owner attached() {
        Owner owner = new Owner();
        new Scene(owner);
        return owner;
    }

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
        runtime = ui.runtime();
    }

    @AfterEach
    void uninstallRuntime() {
        ui.close();
    }

    @Test
    void blendsFromTheCurrentColorTowardTheTarget() {
        ColorTransition ct = new ColorTransition(attached(), Color.BLACK)
                .duration(0.2).easing(Easing.LINEAR);
        ct.to(Color.WHITE);

        ct.tick(0.1); // half way, LINEAR
        Color mid = ct.value();
        assertEquals(0.5f, mid.r(), 0.02f);
        assertEquals(0.5f, mid.g(), 0.02f);
        assertEquals(0.5f, mid.b(), 0.02f);

        ct.tick(0.1); // arrives
        assertEquals(1f, ct.value().r(), 1e-3f);
        assertFalse(ct.isAnimating());
    }

    @Test
    void jumpsToTheTargetWhenTheOwnerIsHeadless() {
        ColorTransition ct = new ColorTransition(new Owner(), Color.BLACK).duration(0.2);
        ct.to(Color.WHITE);
        assertEquals(1f, ct.value().r(), 0, "no scene → straight to the target color");
        assertFalse(ct.isAnimating());
    }

    @Test
    void snapSetsTheColorImmediately() {
        ColorTransition ct = new ColorTransition(attached(), Color.BLACK).duration(0.2);
        ct.snap(Color.WHITE);
        assertEquals(1f, ct.value().r(), 0);
        assertFalse(ct.isAnimating());
    }
}
