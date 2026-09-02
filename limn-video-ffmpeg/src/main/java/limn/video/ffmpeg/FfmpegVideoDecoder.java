package limn.video.ffmpeg;

import limn.video.VideoDecoder;
import limn.video.VideoStreamSource;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Decodes H.264, HEVC, VP9 and VP8 video and AAC, Opus and Vorbis audio out of MP4 and
 * Matroska/WebM, through a trimmed FFmpeg behind a hand-written native shim.
 *
 * <p>AV1 is deliberately absent, and not because it was forgotten: FFmpeg's own AV1 decoder has no
 * software path (it refuses outright unless a hardware accelerator is attached, and this build has
 * none), so software AV1 means linking libdav1d, which is a second external library with its own
 * build system and its own payload rather than another configure flag.
 *
 * <p>Install it the way an application installs any decoder (the order is the probe order, and
 * nothing installs one on anybody's behalf):
 *
 * <pre>{@code
 * Videos.installDecoder(new FfmpegVideoDecoder());
 * Videos.warmUpAsync().start();   // optional: links the native on a worker rather than on a click
 * }</pre>
 *
 * <p><b>Installing it with no native library is harmless and is an expected case.</b> The
 * FFmpeg libraries ride in a {@code natives-<os>-<arch>} classifier of the limn-ffmpeg-natives
 * artifact an application adds for its platform (ADR 037), so a build that added none — or added
 * another platform's — has no library here. This decoder then answers {@code false} to every
 * input, the decoders behind it in the probe order are reached exactly as if it were not there,
 * and {@link #unavailableReason()} says why in one sentence for anything that wants to explain
 * itself. Nothing throws, nothing logs, and nothing about the rest of the application changes.
 *
 * <p>This decoder returns video and only video, which is what the facade's entry point is shaped
 * for. A container's <em>soundtrack</em> has nowhere to arrive through that shape, so an
 * application that wants both opens {@link FfmpegMedia} directly and hands the two tracks to a
 * player, which needs no change anywhere above this module, because they are the two types the
 * toolkit already publishes.
 *
 * <p>Any thread. There is no state here beyond the decision of whether the library loaded, which
 * is taken once per process.
 */
public final class FfmpegVideoDecoder implements VideoDecoder {

    /** Extensions this decoder will consider, before it looks at any bytes. */
    private static final String[] ISO_EXTENSIONS = {".mp4", ".m4v", ".mov", ".3gp"};

    /** The same for the other container. .webm and .mkv are one format and are told apart by neither. */
    private static final String[] MATROSKA_EXTENSIONS = {".mkv", ".webm"};

    @Override
    public String name() {
        return "ffmpeg";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cheap, and cheap in the way the contract means: an extension comparison and twelve bytes
     * read from the front of the file. It does not open a container, does not build an index and
     * above all does not call {@code avformat_find_stream_info}; that call reads and decodes real
     * data to fill in what an MP4 header does not state, which makes it exactly the wrong thing to
     * run once per installed decoder every time anything at all is opened. It belongs in
     * {@link #openStream}, and that is where it is.
     *
     * <p>Never throws, for any input. A path that does not exist, cannot be read, is a directory,
     * or is shorter than a header is {@code false}, because a decoder that threw here would make
     * the whole probe unusable for every decoder behind it, over one bad file.
     */
    @Override
    public boolean supports(Path file) {
        if (file == null || !FfmpegLibrary.isAvailable()) {
            return false;
        }
        try {
            Path name = file.getFileName();
            if (name == null) {
                return false;
            }
            String lower = name.toString().toLowerCase(Locale.ROOT);
            // The extension alone is not enough: a file named .mp4 that is not one should fall
            // through to whatever else is installed rather than be claimed and then fail, since
            // openStream throwing is final and no later decoder is tried. Each container is then
            // checked against its OWN magic: a .webm that begins with an ftyp atom is neither, and
            // claiming it because one of the two checks passed would be the same mistake.
            if (endsWithAny(lower, ISO_EXTENSIONS)) {
                return FfmpegMedia.looksLikeIsoBaseMedia(file);
            }
            if (endsWithAny(lower, MATROSKA_EXTENSIONS)) {
                return FfmpegMedia.looksLikeMatroska(file);
            }
            return false;
        } catch (Exception | LinkageError surprising) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The returned source owns the container, so closing it releases the decoder and the input.
     * The audio track is not claimed on this path and its packets are discarded as they are met;
     * {@link FfmpegMedia#open(Path)} is what takes both.
     *
     * @throws FfmpegException if the input is not a container this build can demultiplex, holds no
     *                         video, uses a codec this build was not compiled with, or is
     *                         malformed in a way that stops it being read at all
     */
    @Override
    public VideoStreamSource openStream(Path file) {
        return FfmpegMedia.open(file, false, FfmpegMedia.DEFAULT_SLOTS).video();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Links the native library, which is this decoder's entire first-call cost and is otherwise
     * charged to whoever asks first, including {@link #supports}, which is cheap in every other
     * respect and runs once per installed decoder every time anything is opened or merely asked
     * about. On a build carrying the libraries in its jar that first call digests them, copies tens
     * of megabytes out to a cache directory, links them in dependency order and runs an identity
     * probe; on a machine with no build it is one failed lookup. Either way it happens once per
     * process, and doing it here means it happens where no frame is waiting on it.
     *
     * <p>It does not touch a file and cannot prepare an open: nothing about a container is known
     * before there is a container. Never throws, on any machine: a missing native is the expected
     * case and stays a {@code false} from {@link #supports}, not a failure of the warm-up.
     */
    @Override
    public void warmUp() {
        FfmpegLibrary.isAvailable();
    }

    private static boolean endsWithAny(String lower, String[] extensions) {
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether this decoder can open anything at all on this machine; that is, whether the
     *         native library loaded
     */
    public static boolean isAvailable() {
        return FfmpegLibrary.isAvailable();
    }

    /**
     * @return one sentence naming the platform that was looked for and what went wrong (no build
     *         for this operating system, a build for another processor, a temporary directory that
     *         cannot be written to), or null when the library did load. For a status line or a
     *         log; nothing should branch on its text.
     */
    public static String unavailableReason() {
        return FfmpegLibrary.failure();
    }
}
