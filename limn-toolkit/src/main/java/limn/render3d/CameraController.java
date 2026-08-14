package limn.render3d;

/**
 * Translates pointer input into camera motion. A 3D viewport widget routes its
 * drag/wheel events here. Implementations mutate the {@link Camera} they were
 * given.
 */
public interface CameraController {

    /** Drag by {@code (dx, dy)} device-independent points (since the last event). */
    void drag(float dx, float dy);

    /** Wheel/zoom by {@code amount} (positive = zoom in). */
    void zoom(float amount);
}
