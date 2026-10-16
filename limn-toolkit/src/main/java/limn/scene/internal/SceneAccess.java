package limn.scene.internal;

import limn.backend.spi.WindowInput;
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
         * Gives the keyboard back from an overlay that has begun to close; see
         * {@link SceneAccess#releaseOverlayKeyboard}.
         *
         * @param scene   the scene holding the overlay
         * @param overlay the overlay that is closing
         */
        void releaseOverlayKeyboard(Scene scene, Widget<?> overlay);

        /**
         * Observes {@code scene}'s window gaining OS focus; see
         * {@link SceneAccess#observeWindowFocus}.
         *
         * @param scene    a scene
         * @param observer told when its window gains focus
         * @return a handle that unregisters
         */
        Subscription observeWindowFocus(Scene scene, Runnable observer);

        Subscription observeWindowBound(Scene scene, Runnable observer);

        @org.jspecify.annotations.Nullable Widget<?> focusedBefore(Scene scene);

        /**
         * Routes {@code scene}'s keyboard to {@code route}; see {@link SceneAccess#routeKeyboard}.
         *
         * @param scene the scene whose window keeps the desktop's keyboard
         * @param route where its keys and characters go instead of its focus
         * @return a handle that ends the route
         */
        Subscription routeKeyboard(Scene scene, KeyRoute route);

        /**
         * @param scene the scene the popup draws in
         * @param popup its root; see {@link SceneAccess#pushPopupLeavingKeyboard}
         */
        void pushPopupLeavingKeyboard(Scene scene, Widget<?> popup);

        /**
         * @param scene the scene whose tree the popup is grafted into
         * @param popup the grafted popup; see {@link SceneAccess#showCursorOf}
         * @return a handle that ends it
         */
        Subscription showCursorOf(Scene scene, Scene popup);

        /**
         * @param scene a scene
         * @return whether its access letters are drawn now: Alt held, on a system that has them
         */
        boolean accessLettersShown(Scene scene);

        void ownAccessLetter(Widget<?> widget, AccessLetterOwner owner);

        void pressFocus(Widget<?> widget, PressFocus filter);

        /**
         * @param scene a scene
         * @return how many keys other than a modifier were pressed in it, counted whoever took
         *         them: what tells a bare Alt from one that was part of a chord
         */
        long keyPresses(Scene scene);
    }

    /**
     * Where a scene's keys go while a menu it opened in a window of its own is up: the menu, which
     * draws in that window and publishes in this scene's tree, and never takes the desktop's
     * keyboard, as a Win32 menu and an AppKit menu never do.
     */
    public interface KeyRoute {
        /**
         * A key the scene's window received, as the window delivered it.
         *
         * @param key             the physical key
         * @param layoutCodePoint what the active layout types at it, or 0
         * @param pressed         press or release
         * @param repeat          an auto-repeat
         * @param modifiers       the modifier bits
         */
        void key(int key, int layoutCodePoint, boolean pressed, boolean repeat, int modifiers);

        /** @param codepoint a character the scene's window received */
        void character(int codepoint);

        /**
         * @return the grafted popup the keys drive, whose cursor is then this scene's, or
         *         {@code null} while it has no window yet
         */
        @org.jspecify.annotations.Nullable Scene popup();
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
    /**
     * Whether {@code scene}'s access letters are drawn now: while Alt is held, on Windows and
     * Linux. A menu opened from the keyboard draws its own as well; never on macOS.
     *
     * @param scene a scene, or {@code null}, which shows none
     */
    public static boolean accessLettersShown(@org.jspecify.annotations.Nullable Scene scene) {
        return scene != null && hook().accessLettersShown(scene);
    }

    /**
     * Makes {@code owner} what the scene asks for {@code widget}'s access letter on Alt and a
     * letter: the widget keeps the owner private, so the interface stays off its public face.
     *
     * @param widget a button, a check box, a radio button, a label
     * @param owner  its letter and what the letter does
     */
    public static void ownAccessLetter(Widget<?> widget, AccessLetterOwner owner) {
        hook().ownAccessLetter(widget, owner);
    }

    /**
     * Makes {@code filter} what the scene asks before a press moves the keyboard to {@code widget}.
     *
     * @param widget a focusable widget with a spot that acts without taking the keyboard
     * @param filter where a press takes it
     */
    public static void pressFocus(Widget<?> widget, PressFocus filter) {
        hook().pressFocus(widget, filter);
    }

    /**
     * How many keys other than a modifier were pressed in {@code scene}, whoever took them. A menu
     * bar compares it across an Alt held and let go: a press of Alt+letter that a control took
     * never reaches the bar, and without this the Alt let go after it read as Alt alone.
     *
     * @param scene a scene, or {@code null}, which counts none
     */
    public static long keyPresses(@org.jspecify.annotations.Nullable Scene scene) {
        return scene == null ? 0 : hook().keyPresses(scene);
    }

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
    /** Tells {@code observer} each time {@code scene} is bound to a window. */
    public static Subscription observeWindowBound(Scene scene, Runnable observer) {
        return hook().observeWindowBound(java.util.Objects.requireNonNull(scene, "scene"), observer);
    }

    /** @return the widget that had the keyboard in {@code scene} before the one that has it now, or null */
    public static @org.jspecify.annotations.Nullable Widget<?> focusedBefore(Scene scene) {
        return hook().focusedBefore(java.util.Objects.requireNonNull(scene, "scene"));
    }

    public static void pushMovedOverlay(Scene scene, Widget<?> overlay, Scene movedFrom) {
        java.util.Objects.requireNonNull(scene, "scene");
        hook().pushMovedOverlay(scene, overlay, movedFrom);
    }

    /**
     * An overlay that has begun to fade out gives the keyboard back to what it took it from, now
     * and not when the fade ends: the focus is never published on a layer about to be destroyed.
     * The overlay stays on screen until it is removed, leaving the keyboard beneath it.
     *
     * @param scene   the scene holding the overlay
     * @param overlay the overlay that is closing
     */
    public static void releaseOverlayKeyboard(Scene scene, Widget<?> overlay) {
        java.util.Objects.requireNonNull(scene, "scene");
        hook().releaseOverlayKeyboard(scene, overlay);
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

    /**
     * Sends every key and character {@code scene}'s window receives to {@code route} instead of
     * the scene's focus, at the end of the input batch that brought them, until the handle is
     * cancelled; a later route replaces an earlier one. While the route names a grafted popup, the
     * popup's cursor is where this scene's tree says the keyboard is. A key that arrives after the
     * route ended, in the batch that ended it, goes to the scene's own focus.
     *
     * @param scene the scene whose window keeps the desktop's keyboard
     * @param route where its keys go
     * @return a handle that ends the route, if it is still the scene's; twice is a no-op. UI thread
     */
    public static Subscription routeKeyboard(Scene scene, KeyRoute route) {
        java.util.Objects.requireNonNull(scene, "scene");
        java.util.Objects.requireNonNull(route, "route");
        return hook().routeKeyboard(scene, route);
    }

    /**
     * Pushes a popup into {@code scene} as {@link Scene#pushPopup} does, except that the keyboard
     * stays where it is, in the layer beneath: an editable combo's list drawn in scene, whose field
     * keeps the focus, the caret and the input method while the list is up. The pointer reaches the
     * popup as it reaches any overlay, and the popup's cursor is where the tree says the keyboard
     * is. Closed with {@link Scene#removeOverlay}.
     *
     * @param scene the scene the popup draws in
     * @param popup its root
     */
    public static void pushPopupLeavingKeyboard(Scene scene, Widget<?> popup) {
        java.util.Objects.requireNonNull(scene, "scene");
        hook().pushPopupLeavingKeyboard(scene, popup);
    }

    /**
     * Makes the cursor of {@code popup}, grafted into {@code scene}'s tree, where that tree says the
     * keyboard is, while the opener keeps the keyboard and drives the popup from it: an editable
     * combo's list in a window of its own, whose field holds the caret while the list's highlight
     * is what a reader follows.
     *
     * @param scene the scene the popup is grafted into
     * @param popup the grafted popup
     * @return a handle that ends it, if it is still the scene's; twice is a no-op. UI thread
     */
    public static Subscription showCursorOf(Scene scene, Scene popup) {
        java.util.Objects.requireNonNull(scene, "scene");
        java.util.Objects.requireNonNull(popup, "popup");
        return hook().showCursorOf(scene, popup);
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
