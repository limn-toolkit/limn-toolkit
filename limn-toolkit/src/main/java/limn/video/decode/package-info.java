/**
 * Decoders, and the pooling every decoder needs. The toolkit's {@code limn.video} publishes what a
 * picture is and how a source is driven; this is where implementations of that live, so that a codec
 * dependency has somewhere to land that is not a module everything else depends on.
 *
 * <p>Install what an application wants and open through the facade:
 * <pre>{@code
 * Videos.installDecoder(new Y4mDecoder());
 * Videos.installDecoder(new SyntheticVideoDecoder());
 * try (VideoStreamSource source = Videos.open(file)) {
 *     while (source.readFrame() == VideoStreamSource.Read.FRAME) {
 *         VideoFrame frame = source.frame();
 *         // ... upload it, draw it ...
 *         frame.release();
 *     }
 * }
 * }</pre>
 *
 * <p>The application installs decoders, not the backend. Which decoders exist is an application's
 * decision (it is what it ships and what it is licensed for), and the install order is the probe
 * order, so a backend that installed its own would silently take priority over the application's.
 *
 * <p>Both decoders here are pure Java with no dependency of any kind, which is why they are the ones
 * that came first: a Y4M file proves the seam against real bytes somebody else's tool wrote, and a
 * synthetic stream proves it against arithmetic, with no codec, no native and no committed media in
 * either direction.
 */
package limn.video.decode;
