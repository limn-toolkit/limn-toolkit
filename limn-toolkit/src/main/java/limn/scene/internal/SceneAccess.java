package limn.scene.internal;

import limn.backend.WindowInput;
import limn.scene.Scene;

/**
 * The one way outside {@code limn.scene} to reach a scene's window input, for the test driver of
 * {@code limn-test} and for a program that replays input into a scene of its own (the demo's
 * captures and films). Not API: an application talks to its scene through {@link Scene}, and a
 * window receives input from the adapter {@link Scene#bind} hands it (ADR 046 §4).
 *
 * <p>The scene installs the hook from its own static initializer, and only the scene can: an
 * install from any other class is refused, so the hook cannot be replaced by whoever loads first.
 */
public final class SceneAccess {

    /** What the scene lends this class. */
    public interface Hook {
        /**
         * @param scene a scene
         * @return the input adapter it hands its window
         */
        WindowInput inputOf(Scene scene);

        /**
         * @param input a window's input
         * @return the scene it delivers to, or {@code null} when it is not a scene's adapter
         */
        Scene sceneOf(WindowInput input);
    }

    private static volatile Hook hook;

    private SceneAccess() {
    }

    /**
     * Called once, by {@link Scene}'s static initializer.
     *
     * @param sceneHook the scene's hook
     * @throws IllegalStateException when called by any other class, or twice
     */
    public static void install(Hook sceneHook) {
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
        if (caller != Scene.class || hook != null) {
            throw new IllegalStateException("only Scene installs its access hook, once");
        }
        hook = sceneHook;
    }

    /**
     * @param scene a scene
     * @return the input adapter the scene hands its window, which a driver calls as a window would
     */
    public static WindowInput input(Scene scene) {
        return hook().inputOf(java.util.Objects.requireNonNull(scene, "scene"));
    }

    /**
     * @param input what a window was handed by {@link Scene#bind}
     * @return the scene behind it, or {@code null} when {@code input} is not a scene's
     */
    public static Scene sceneOf(WindowInput input) {
        return input == null ? null : hook().sceneOf(input);
    }

    private static Hook hook() {
        Hook installed = hook;
        if (installed == null) {
            try {
                // A scene-less caller (sceneOf before any scene exists) initializes the class that
                // installs the hook.
                Class.forName(Scene.class.getName(), true, SceneAccess.class.getClassLoader());
            } catch (ClassNotFoundException impossible) {
                throw new IllegalStateException(impossible);
            }
            installed = hook;
        }
        return installed;
    }
}
