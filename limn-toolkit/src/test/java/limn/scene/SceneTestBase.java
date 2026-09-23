package limn.scene;

import limn.concurrent.UiRuntime;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;


/**
 * Widget mutations are UI-thread-confined; tests install a UiRuntime bound to
 * the JUnit thread so the confinement checks pass (and can be asserted).
 */
abstract class SceneTestBase {

    protected HeadlessUi ui;
    protected UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
        runtime = ui.runtime();
    }

    @AfterEach
    void uninstallRuntime() {
        ui.close();
    }

    /** Fixed-preferred-size leaf widget. */
    static class FixedBox extends Widget<FixedBox> {
        private final float prefWidth;
        private final float prefHeight;

        FixedBox(float prefWidth, float prefHeight) {
            this.prefWidth = prefWidth;
            this.prefHeight = prefHeight;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(prefWidth, prefHeight);
        }
    }
}
