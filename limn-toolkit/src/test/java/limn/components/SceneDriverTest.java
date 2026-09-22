package limn.components;

import limn.backend.WindowInput;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.internal.SceneAccess;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 046 §4: a scene's input is the window's, not the application's, and {@code limn-test}'s
 * driver is how a test (the toolkit's own, or an application's) reaches it.
 */
class SceneDriverTest extends ComponentTestBase {

    @Test
    void aSceneIsNotAWindowInputAndShowsNoneOfItsMethods() {
        assertFalse(WindowInput.class.isAssignableFrom(Scene.class),
                "a scene hands its window an adapter; it is not the window's input itself");
        List<String> leaked = new ArrayList<>();
        for (Method input : WindowInput.class.getMethods()) {
            for (Method method : Scene.class.getMethods()) {
                if (method.getName().equals(input.getName())) {
                    leaked.add(method.toString());
                }
            }
        }
        assertTrue(leaked.isEmpty(), "window input on the scene's public surface: " + leaked);
    }

    @Test
    void onlyTheSceneInstallsItsAccessHook() {
        new Scene(new Button("load the scene class"));
        assertThrows(IllegalStateException.class, () -> SceneAccess.install(new SceneAccess.Hook() {
            @Override
            public WindowInput inputOf(Scene scene) {
                return null;
            }

            @Override
            public Scene sceneOf(WindowInput input) {
                return null;
            }
        }));
    }

    @Test
    void aClickIsOneActionAndGesturesChain() {
        AtomicInteger fired = new AtomicInteger();
        Button button = new Button("OK");
        button.onAction(fired::incrementAndGet);
        Scene scene = new Scene(button);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 40);

        assertSame(scene, drive(scene).click(10, 10).click(10, 10).scene());
        assertEquals(2, fired.get());
    }

    @Test
    void aWidgetIsClickedAtItsCentreAndOnlyInItsOwnScene() {
        AtomicInteger saved = new AtomicInteger();
        Button cancel = new Button("Cancel");
        Button save = new Button("Save");
        save.onAction(saved::incrementAndGet);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(cancel);
        column.add(save);
        Scene scene = new Scene(column);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 200);

        drive(scene).click(save);
        assertEquals(1, saved.get(), "the second child, below the first, is the one clicked");

        Scene other = new Scene(new Button("elsewhere"));
        assertThrows(IllegalArgumentException.class, () -> drive(other).click(save));
    }

    @Test
    void textTypedAndEnterPressedReachAFocusedField() {
        List<String> submitted = new ArrayList<>();
        TextField field = new TextField();
        field.onSubmit(submitted::add);
        Scene scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 30);

        drive(scene).click(10, 10).type("Ada é").press(Keys.ENTER);

        assertEquals("Ada é", field.text());
        assertEquals(List.of("Ada é"), submitted);
    }

    @Test
    void aHeadlessWindowAnswersTheSceneBoundToItAndNothingElse() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow window = backend.open("Driven", 200, 100);
        assertNull(window.scene(), "nothing bound yet");
        Scene scene = new Scene(new Button("OK"));
        scene.bind(window);
        assertSame(scene, window.scene());
        assertNull(SceneAccess.sceneOf(drive(scene)),
                "a driver is a window input too, and not the scene's own adapter");
    }
}
