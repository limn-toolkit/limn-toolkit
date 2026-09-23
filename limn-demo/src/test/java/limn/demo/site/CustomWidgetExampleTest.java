package limn.demo.site;

import limn.demo.site.CustomWidgetExample.Rating;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;
import limn.testing.a11y.ValueContract;
import limn.testing.a11y.ValueSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the custom widgets guide says its rating does, and the value contract it is held to. */
class CustomWidgetExampleTest {

    private HeadlessUi ui;

    @BeforeEach
    void install() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void uninstall() {
        ui.close();
    }

    private static Scene sceneOf(Rating rating) {
        Column root = new Column();
        root.add(rating);
        Scene scene = new Scene(root);
        scene.setTextRuler(TestRulers.FIXED);
        scene.layoutPass(400, 100);
        return scene;
    }

    @Test
    void aClickOnTheThirdDotRatesThreeAndRunsTheHandlerOnce() {
        Rating rating = new Rating();
        int[] handled = {0};
        rating.onChange(() -> handled[0]++);
        Scene scene = sceneOf(rating);

        float dot = rating.height();
        drive(scene).click(rating.localToSceneX() + 2.5f * dot, rating.localToSceneY() + dot / 2);

        assertEquals(3, rating.value());
        assertEquals(1, handled[0]);
    }

    @Test
    void codeReachesAWatcherAndNotTheHandler() {
        Rating rating = new Rating();
        int[] handled = {0};
        rating.onChange(() -> handled[0]++);
        List<Change.Origin> heard = new ArrayList<>();
        rating.observeChanges((source, change) -> heard.add(change.origin()));
        sceneOf(rating);

        rating.setValue(4);

        assertEquals(List.of(Change.Origin.CODE), heard);
        assertEquals(0, handled[0]);
    }

    @Test
    void theArrowsStepAndMirrorInRightToLeft() {
        Rating rating = new Rating().setValue(2);
        Scene scene = sceneOf(rating);
        drive(scene).click(rating);
        rating.setValue(2);

        drive(scene).press(Keys.RIGHT);
        assertEquals(3, rating.value());

        rating.setLayoutDirection(LayoutDirection.RTL);
        drive(scene).press(Keys.RIGHT);
        assertEquals(2, rating.value());
        drive(scene).press(Keys.UP);
        assertEquals(3, rating.value());
    }

    @Test
    void theFirstDotIsWhereReadingStarts() {
        Rating rating = new Rating().setLayoutDirection(LayoutDirection.RTL);
        Scene scene = sceneOf(rating);

        float dot = rating.height();
        drive(scene).click(rating.localToSceneX() + rating.width() - dot / 2,
                rating.localToSceneY() + dot / 2);

        assertEquals(1, rating.value());
    }

    @TestFactory
    Stream<DynamicTest> theValueContract() {
        return ValueContract.cases(new Subject(), ui.runtime()).stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> c.body().run()));
    }

    private static final class Subject implements ValueSubject {
        private Rating rating;
        private int changes;

        @Override
        public Widget<?> build() {
            rating = new Rating().setValue(2).setAccessibleName("Rating");
            changes = 0;
            rating.onChange(() -> changes++);
            Column root = new Column();
            root.add(rating);
            return root;
        }

        @Override
        public Widget<?> widget() {
            return rating;
        }

        @Override
        public double value() {
            return rating.value();
        }

        @Override
        public double min() {
            return 0;
        }

        @Override
        public double max() {
            return Rating.MAX;
        }

        @Override
        public double step() {
            return 1;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public void set(double value) {
            rating.setValue((int) Math.round(value));
        }

        @Override
        public int changes() {
            return changes;
        }
    }
}
