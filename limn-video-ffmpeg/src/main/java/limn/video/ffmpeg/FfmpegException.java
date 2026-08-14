package limn.video.ffmpeg;

/**
 * What every failure inside the FFmpeg decoder arrives as, including every failure that started
 * in C.
 *
 * <p>That is the point of it. A native decoder handed a malformed file has two ways to react: it
 * can dereference something the file said was there and take the whole virtual machine down with
 * it, or it can check and report. Nothing below this class ever does the first, so a truncated
 * container, a header claiming an impossible size, a codec this build was not compiled with and a
 * stream that changes resolution halfway through all reach Java as this exception, thrown on the
 * thread that asked.
 *
 * <p>Unchecked, because that is the shape the SPI publishes: {@code VideoDecoder.openStream} and
 * {@code VideoStreamSource.readFrame} both document a {@code RuntimeException} for a stream that
 * cannot be opened or decoded, and a decode thread carrying a checked exception would have
 * nowhere to put it.
 *
 * <p>The message names what was being attempted and, where FFmpeg supplied one, its own
 * description of the failure. There is no error code and nothing to branch on: a caller either
 * shows the message or shows a poster.
 */
public class FfmpegException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** @param message what was being attempted, and what went wrong with it */
    public FfmpegException(String message) {
        super(message);
    }

    /** @param message what was being attempted; {@code cause} is what it failed on */
    public FfmpegException(String message, Throwable cause) {
        super(message, cause);
    }
}
