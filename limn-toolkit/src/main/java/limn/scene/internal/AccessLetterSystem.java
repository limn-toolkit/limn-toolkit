package limn.scene.internal;

/**
 * Whether this system has access letters: Windows and Linux do, macOS does not. One answer read by
 * the scene, which draws and dispatches them, and by the widgets that mark them, so the two cannot
 * disagree. Not API.
 */
public final class AccessLetterSystem {

    private static volatile boolean present = !limn.backend.Platform.current().isMacOs();

    private AccessLetterSystem() {
    }

    /** @return whether access letters exist here */
    public static boolean present() {
        return present;
    }

    /**
     * Test seam: answers as another system would, so the behaviour of each is tested on any host.
     *
     * @param value whether access letters exist
     * @return what was answered before, to put back
     */
    public static boolean setPresentForTests(boolean value) {
        boolean was = present;
        present = value;
        return was;
    }
}
