package limn.scene.internal;

import limn.backend.WindowInput;
import limn.concurrent.Subscription;
import limn.scene.Scene;
import limn.scene.Widget;

/**
 * The way outside {@code limn.scene} to what a scene keeps from applications: its window input,
 * for the test driver of {@code limn-test} and for a program that replays input into a scene of
 * its own (the demo's captures and films), and the move of content from one scene into another
 * with its keyboard and the word that the scene's window has the focus, both for a modal dialog
 * leaving its own window for its owner's scene. Not API: an
 * application talks to its scene through {@link Scene}, and a window receives input from the
 * adapter {@link Scene#bind} hands it.
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

        /**
         * Pushes a modal overlay into {@code scene} over content {@code movedFrom} drew until now,
         * with the keyboard where it was there; see {@link SceneAccess#pushMovedOverlay}.
         *
         * @param scene     the scene the content moves into
         * @param overlay   the overlay's root, holding that content
         * @param movedFrom the scene it comes from
         */
        void pushMovedOverlay(Scene scene, Widget<?> overlay, Scene movedFrom);

        /**
         * Observes {@code scene}'s window gaining OS focus; see
         * {@link SceneAccess#observeWindowFocus}.
         *
         * @param scene    a scene
         * @param observer told when its window gains focus
         * @return a handle that unregisters
         */
        Subscription observeWindowFocus(Scene scene, Runnable observer);
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

    /**
     * Pushes {@code overlay} into {@code scene} as {@link Scene#pushOverlay(Widget)} does, over
     * content {@code movedFrom} drew until now, with the keyboard where it was there: the widget
     * that held that scene's focus, now inside {@code overlay}, holds this one's and is told of
     * neither a loss nor a gain, so a field keeps its caret and selection and a half-typed entry
     * stays open. Only an input method's open composition, which belongs to the window being left,
     * is ended. When nothing inside {@code overlay} held {@code movedFrom}'s focus, the overlay's
     * first stop takes it, as with {@code pushOverlay}.
     *
     * @param scene     the scene the content moves into
     * @param overlay   the overlay's root, holding content {@code movedFrom} drew until now
     * @param movedFrom the scene that content comes from, which lets go of its focus
     */
    public static void pushMovedOverlay(Scene scene, Widget<?> overlay, Scene movedFrom) {
        java.util.Objects.requireNonNull(scene, "scene");
        hook().pushMovedOverlay(scene, overlay, movedFrom);
    }

    /**
     * Observes {@code scene}'s window gaining OS focus, told at the end of the input batch that
     * brought the report, as {@link Scene#observeWindowBlur} is told of a loss. For a modal dialog
     * whose card has moved into {@code scene}: its own window, which held the focus, stays up until
     * the owner has it, so that the desktop never has to choose where the focus goes.
     *
     * @param scene    the scene whose window is watched
     * @param observer told when that window gains focus; never null
     * @return a handle that unregisters; cancelling it twice is a no-op. UI thread
     */
    public static Subscription observeWindowFocus(Scene scene, Runnable observer) {
        java.util.Objects.requireNonNull(scene, "scene");
        return hook().observeWindowFocus(scene, observer);
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
