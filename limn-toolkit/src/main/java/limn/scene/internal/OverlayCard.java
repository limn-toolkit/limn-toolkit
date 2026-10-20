package limn.scene.internal;

/**
 * Where an overlay draws its own surface, for an overlay that draws it itself rather than with
 * child widgets: a menu's columns. While the overlay fades out after giving the keyboard back, a
 * press there is still the overlay's and a press elsewhere goes to what is beneath. Not API:
 * registered through {@link SceneAccess#overlayCard}, asked by the scene's hit test.
 */
@FunctionalInterface
public interface OverlayCard {

    /**
     * @param sceneX the point, in scene coordinates
     * @param sceneY the point, in scene coordinates
     * @return whether the overlay's surface is drawn there
     */
    boolean drawsAt(float sceneX, float sceneY);
}
