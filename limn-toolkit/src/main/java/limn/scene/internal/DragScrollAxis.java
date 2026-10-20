package limn.scene.internal;

/**
 * One axis a widget scrolls along, as a pointer drag scrolls it: a scroll bar says it for the
 * widget that holds it, whose box is the viewport. The scene moves it while a drag rests near an
 * edge of that box, or is past one. Not API: registered through {@link SceneAccess#scrollsUnderDrag},
 * asked by the scene's drag.
 *
 * <p>Both methods speak in the holder's own physical coordinates: forward is down, and on a
 * horizontal axis to the right, whichever way the holder reads. A right-to-left holder that counts
 * its offset from the right edge turns the sign itself.
 */
public interface DragScrollAxis {

    /** @return whether this axis is the vertical one */
    boolean vertical();

    /**
     * @param forward toward the bottom (or the right), or toward the top (or the left)
     * @return how many points the content can still move that way, {@code 0} at the limit
     */
    float room(boolean forward);

    /**
     * Moves the content, as the user's own scroll would, clamped by the holder.
     *
     * @param points how far: positive brings into view what lies below (or to the right)
     */
    void scrollBy(float points);
}
