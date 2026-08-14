package limn.graphics;

/**
 * Decodes encoded image bytes (PNG/JPG/…) into an {@link Image}. The LWJGL
 * backend installs an stb_image-based decoder; tests can inject a fake.
 */
@FunctionalInterface
public interface ImageDecoder {

    /**
     * <b>Called from any thread, including several at once.</b> It runs on the caller's thread from
     * {@link Images#decode} and on the {@code Ui} worker pool from {@link Images#decodeAsync} and
     * {@link Images#loadShared}, which is what lets those exist at all, so an implementation
     * must be pure CPU, must need no GL context and no window, and must keep no state between
     * calls unless it is synchronized. {@code fileBytes} is read, never written.
     *
     * @param fileBytes the full encoded file
     * @return the decoded RGBA image; a fresh instance per call, because that instance's identity is
     *         the texture-cache key
     * @throws RuntimeException if the bytes cannot be decoded
     */
    Image decode(byte[] fileBytes);
}
