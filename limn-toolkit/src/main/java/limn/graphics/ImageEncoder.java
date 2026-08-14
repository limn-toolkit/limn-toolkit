package limn.graphics;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Encodes an {@link Image} into a file format. Several encoders coexist and are asked, in order,
 * which requests they will take; install one through {@link Images#installEncoder} to make it
 * reachable. This is the mirror of {@link ImageDecoder} and deliberately a different shape: what a
 * decoder can read is decided by the bytes it is handed, while what an encoder can write is decided
 * by the caller, so the set of encoders has to be negotiated rather than fixed at one.
 *
 * <p>An encoder must not need a window, a GL context or a running backend. Writing an image file is
 * CPU work; a test that wants to produce a reference image, or a tool that generates assets, has no
 * window to open.
 *
 * <p>Implementations are used from any thread and must hold no per-call state.
 */
public interface ImageEncoder {

    /**
     * A short, stable, lower-case identifier used in diagnostics. It appears in the failure raised
     * when nothing accepts a request, which is the only way anyone finds out which encoders existed
     * and in what order they were consulted.
     *
     * <p>It carries no meaning and nothing may branch on it; the wrong edit this prevents is a
     * comparison against a literal name somewhere on a control-flow path, which would put one
     * encoder's identity into logic that is supposed to be encoder-neutral.
     *
     * <p>Deliberately without a default: deriving it from the implementing class produces an
     * unreadable synthetic name for a lambda or an anonymous class, in exactly the message that
     * exists to be readable.
     *
     * @return the identifier, never null and never blank
     */
    String name();

    /**
     * Whether this encoder will take {@code options}: the whole request, not just the format, so
     * an encoder that supports a format only over part of the quality range can decline the rest
     * instead of silently rounding it.
     *
     * <p>Cheap and honest, and it must never throw: it runs on the caller's thread, once per
     * installed encoder, every time anything is encoded, and one encoder that throws here makes the
     * probe unusable for every encoder behind it.
     *
     * <p>Declaring support is the whole reason this method exists. An encoder that claims a format
     * it cannot fully represent (alpha into a format without an alpha channel, say) must decline
     * rather than accept and drop the channel: a caller who is refused can choose another format,
     * whereas a caller handed a degraded file finds out when someone looks at it.
     *
     * <p>Deliberately without a default, because both possible defaults are wrong: claiming
     * everything destroys the ordering, and claiming nothing makes an encoder that forgot to
     * override it silently unreachable.
     */
    boolean supports(ImageEncodeOptions options);

    /**
     * Writes {@code image} to {@code out} in the requested format. Called only after
     * {@link #supports} returned true for the same options. Does not close {@code out} and does not
     * flush it; the caller owns the stream.
     *
     * <p>Orientation is not this method's business: {@code image} is top-down (row 0 at the top) by
     * {@link Image}'s contract and every format this writes is defined against that same order. The
     * wrong edit this prevents is "correcting" the row order here to match a GL framebuffer's
     * bottom-up layout; the flip belongs to whatever read those pixels back (see
     * {@link ReadableSurface}), and a second flip here would cancel it for one path and not the
     * other, producing an upside-down file with no other symptom.
     *
     * <p>Alpha is straight, never premultiplied, likewise by {@link Image}'s contract.
     *
     * <p>The same image and the same options must produce the same bytes. Determinism is what makes
     * an exported file comparable against a checked-in reference; an encoder that stamps a
     * timestamp, a producer string or a random seed into its output breaks every such test.
     *
     * @throws IOException              if {@code out} fails
     * @throws IllegalArgumentException if the image cannot be represented in this format (a size
     *                                  beyond what the format's headers can express, say)
     */
    void encode(Image image, ImageEncodeOptions options, OutputStream out) throws IOException;
}
