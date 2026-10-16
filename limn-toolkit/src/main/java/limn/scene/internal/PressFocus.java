package limn.scene.internal;

/**
 * Where a press on a focusable widget does not take the keyboard: a tab's close button, which
 * closes the tab and leaves the keyboard where it was, as a browser's does. Not API: registered
 * through {@link SceneAccess#pressFocus}, asked by the scene's click-to-focus.
 */
@FunctionalInterface
public interface PressFocus {

    /**
     * @param sceneX the press, in scene coordinates
     * @param sceneY the press, in scene coordinates
     * @return whether a press there moves the keyboard to the widget
     */
    boolean takesFocusAt(float sceneX, float sceneY);
}
