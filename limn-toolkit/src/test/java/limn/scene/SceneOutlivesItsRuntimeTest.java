package limn.scene;

import limn.backend.CrashHandler;
import limn.backend.CrashPhase;
import limn.backend.Crashes;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.testfixtures.PlainWidget;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scene is told when the process's language, direction or size moves, and it is still told
 * after the runtime it was built under has gone, until it is collected: the axis is the
 * process's, and a scene is held weakly by it. With no runtime there is no thread to be on and no
 * frame to ask for, so the scene keeps the fact that it must measure again and does nothing else.
 */
class SceneOutlivesItsRuntimeTest {

    /** Counts its measures, so that a test can see one was owed. */
    private static final class Counted extends PlainWidget {
        int measures;

        @Override
        protected Size onMeasure(Constraints constraints) {
            measures++;
            return new Size(40, 20);
        }
    }

    @Test
    void aChangeOfLanguageAfterTheRuntimeIsGoneThrowsNothingAndIsMeasuredForLater() {
        TextRuler ruler = TestRulers.FIXED;
        Counted root = new Counted();
        Scene scene;
        try (HeadlessUi ui = new HeadlessUi()) {
            scene = new Scene(root);
            scene.setTextRuler(ruler);
            scene.layoutPass(200, 100);
        }
        int measured = root.measures;
        List<Throwable> contained = new ArrayList<>();
        CrashHandler watching = (CrashPhase phase, Throwable error) -> {
            contained.add(error);
            return false;
        };
        Locale before = I18n.processLocale();
        Crashes.install(watching);
        try {
            I18n.setLocale(Locale.forLanguageTag("pt-BR"));
            I18n.setLocale(Locale.ENGLISH);
        } finally {
            I18n.setLocale(before);
            Crashes.uninstall(watching);
        }
        assertTrue(contained.isEmpty(), "a scene with no runtime asks for no frame, and so has no "
                + "thread to be found on the wrong side of: " + contained);

        try (HeadlessUi ui = new HeadlessUi()) {
            scene.layoutPass(200, 100);
            scene.renderFrame(new NoopCanvas(200, 100));
        }
        assertEquals(measured + 1, root.measures,
                "and what the change asked for was kept: under a runtime again, it measures again");
    }
}
