package limn.scene;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Widget mutations are UI-thread-confined; tests install a UiRuntime bound to
 * the JUnit thread so the confinement checks pass (and can be asserted).
 */
abstract class SceneTestBase {

    protected ExecutorService workers;
    protected UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    /** Fixed-preferred-size leaf widget. */
    static class FixedBox extends Widget {
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
