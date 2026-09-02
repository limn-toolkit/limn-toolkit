package limn.video.ffmpeg;

import java.nio.ByteBuffer;

/**
 * The boundary, and nothing else: one declaration per entry point in {@code limn_ffmpeg.c}, no
 * logic, no state, no checking. Everything a caller has to be careful about lives in
 * {@link FfmpegMedia}, which is the only class that calls these.
 *
 * <p>Kept to about a dozen entry points on purpose. A thin binding over the codec API would be
 * hundreds, would put every lifetime question on the Java side of a boundary that cannot enforce
 * one, and would make the native library a thing to be reviewed rather than read. What crosses
 * here instead is a handle, a slot index and a picture's geometry.
 *
 * <p><b>Nothing here validates anything.</b> A handle that is not a handle is a wild pointer, and
 * these methods are package-private for that reason alone. {@link FfmpegMedia} holds the only
 * handle that exists, takes a lock around every call, and answers the closed case itself.
 */
final class FfmpegNative {

    // ---- indices into the array describe() fills; LicenceTest pins the enum ordinals
    static final int DESCRIBE_LENGTH = 18;
    static final int D_WIDTH = 0;
    static final int D_HEIGHT = 1;
    static final int D_PIXEL_FORMAT = 2;
    static final int D_MATRIX = 3;
    static final int D_RANGE = 4;
    static final int D_RATE_NUM = 5;
    static final int D_RATE_DEN = 6;
    static final int D_DURATION_MICROS = 7;
    static final int D_HAS_AUDIO = 8;
    static final int D_AUDIO_CHANNELS = 9;
    static final int D_AUDIO_SAMPLE_RATE = 10;
    static final int D_SLOTS = 11;
    static final int D_AUDIO_SOURCE_CHANNELS = 12;
    static final int D_ROTATION_DEGREES = 13;
    static final int D_TRANSFER = 14;
    /** 1 when an accelerator is attached and this stream's pictures are handles, not samples. */
    static final int D_HARDWARE = 15;
    /**
     * Audio streams the container holds, decodable or not. A count and not a list: how many there
     * are is fixed-width and belongs here, while what each one is varies in length and is read one
     * call at a time by {@link #describeAudioTrack}.
     */
    static final int D_AUDIO_TRACKS = 16;
    /**
     * Subtitle streams the container holds. There is no "has subtitles" entry beside it because
     * there is nothing for one to mean: a container that holds tracks still shows none until an
     * application picks one.
     */
    static final int D_SUBTITLE_TRACKS = 17;

    // ---- indices into the array describeAudioTrack() fills
    static final int AUDIO_TRACK_LENGTH = 6;
    static final int AT_STREAM_INDEX = 0;
    static final int AT_SOURCE_CHANNELS = 1;
    static final int AT_CHANNELS = 2;
    static final int AT_SAMPLE_RATE = 3;
    static final int AT_DEFAULT = 4;
    static final int AT_DECODABLE = 5;

    // ---- indices into the array describeSubtitleTrack() fills
    static final int SUBTITLE_TRACK_LENGTH = 5;
    static final int ST_STREAM_INDEX = 0;
    /** Whether the format is text rather than a paletted bitmap; the codec's property, not the
     *  build's, so it stays the answer in a build that grew a bitmap decoder. */
    static final int ST_TEXT = 1;
    static final int ST_DECODABLE = 2;
    static final int ST_DEFAULT = 3;
    static final int ST_FORCED = 4;

    // ---- indices into the array readCue() fills
    static final int CUE_LENGTH = 4;
    static final int C_STATUS = 0;
    /** Filled on every call, including the ones that produce nothing: a seek that emptied the
     *  queue has to reach the consumer whether or not a cue happens to follow it. */
    static final int C_EPOCH = 1;
    static final int C_START_MICROS = 2;
    /** {@link Long#MIN_VALUE} for a cue the container gave no duration, which ends where the next
     *  one begins. */
    static final int C_END_MICROS = 3;

    /** Nothing was queued: the pump stops here until the pictures advance. */
    static final int CUE_NONE = 0;

    /** A cue was produced, and the returned string is its text. */
    static final int CUE_READY = 1;

    /**
     * A packet was consumed and produced no cue, so the pump must come back. Not an error: mov_text
     * writes an empty sample across every gap between lines, and a pump that stopped at the first
     * one would fall behind by exactly the number of gaps.
     */
    static final int CUE_SKIPPED = 2;

    // ---- indices into the array readVideo() fills
    static final int READ_LENGTH = 6;
    static final int R_PTS_MICROS = 0;
    static final int R_EPOCH = 1;
    static final int R_STRIDE_0 = 2;
    /** The IOSurface a hardware picture lives in, or 0 when the picture is planar samples. */
    static final int R_HANDLE = 5;

    /** What readVideo returns instead of a slot: no picture now, and no end either. */
    static final int READ_PENDING = -1;

    /** What readVideo returns instead of a slot when no further picture will ever arrive. */
    static final int READ_END = -2;

    /** {@code PixelFormat.I420.ordinal()}, asserted rather than assumed. */
    static final int FORMAT_I420 = 0;
    static final int FORMAT_NV12 = 1;
    static final int FORMAT_I444 = 2;
    static final int FORMAT_I420_10LE = 3;
    static final int FORMAT_I444_10LE = 4;
    static final int FORMAT_P010 = 5;

    /** {@code VideoColor.Matrix.BT601.ordinal()}, likewise. */
    static final int MATRIX_BT601 = 0;
    static final int MATRIX_BT709 = 1;
    static final int MATRIX_BT2020 = 2;

    /** What the shim reports when the container signalled no matrix or no range at all. */
    static final int UNSPECIFIED = -1;

    static final int RANGE_LIMITED = 0;
    static final int RANGE_FULL = 1;

    /** {@code VideoColor.Transfer.SDR.ordinal()}, likewise. */
    static final int TRANSFER_SDR = 0;
    static final int TRANSFER_PQ = 1;
    static final int TRANSFER_HLG = 2;

    private FfmpegNative() {
    }

    /** The licence, version and configure line of the linked libraries, newline separated. */
    /**
     * The shim's JNI surface version, the first thing {@link FfmpegLibrary} asks a loaded shim.
     *
     * <p>This class and {@code limn_ffmpeg.c} are two halves of one interface that live in two
     * repositories and release apart (ADR 037): the C in limn-ffmpeg-natives, versioned with
     * FFmpeg; this file here, versioned with the toolkit. Every other method below is bound by
     * name, so a mismatch would otherwise surface as an {@code UnsatisfiedLinkError} in the middle
     * of a decode. Bump {@link FfmpegLibrary#EXPECTED_ABI} and {@code LIMN_FFMPEG_ABI} in the shim
     * together whenever a native signature changes, and never otherwise.
     */
    static native int abi();

    static native String identity();

    /**
     * Every component the linked libraries hold, one per line as {@code decoder:h264},
     * {@code encoder:mpeg4}, {@code demuxer:mov} or {@code muxer:mp4} (read out of libavcodec and
     * libavformat, never out of the configure line).
     */
    static native String components();

    /** Whether this build has the encoders {@link #writeClip} needs. */
    static native boolean canWrite();

    /**
     * @param wantHardware whether to attach a platform accelerator where one exists. Ignored where
     *                     none does, because a hardware decode that cannot be had has to be a
     *                     software decode and never a failure to play.
     * @return a handle, never 0; throws {@link FfmpegException} on every failure
     */
    static native long open(String path, boolean wantAudio, int slots, boolean wantHardware);

    static native void describe(long handle, long[] out);

    /**
     * Fills {@code out} with one audio track's numbers and returns its two names: the codec's, and
     * the language the container states, which is <b>null</b> when it states none. A null element
     * rather than an empty string, because a caller has to be able to tell "no language" from a
     * language whose tag is blank, and a UI rendering the second as a row shows an empty cell.
     *
     * @param track a position in {@code [0..D_AUDIO_TRACKS)}, not a container stream index
     * @return {codec name, language or null}
     */
    static native String[] describeAudioTrack(long handle, int track, long[] out);

    /**
     * Makes {@code track} the audio stream this handle decodes, superseding whatever consumer held
     * the last one. Must not be called for the track already selected: it would supersede a
     * consumer for nothing.
     */
    static native void selectAudio(long handle, int track);

    /** @return the generation a consumer of the currently selected audio track must present */
    static native long audioGeneration(long handle);

    /**
     * Fills {@code out} with one subtitle track's numbers and returns its two names: the codec's,
     * and the language the container states, null when it states none, on the same rule as
     * {@link #describeAudioTrack}.
     *
     * @param track a position in {@code [0..D_SUBTITLE_TRACKS)}, not a container stream index
     * @return {codec name, language or null}
     */
    static native String[] describeSubtitleTrack(long handle, int track, long[] out);

    /**
     * Makes {@code track} the subtitle stream this handle decodes; a negative track opens none and
     * frees whatever was open, which is how subtitles are turned off. Either way the epoch moves,
     * so a consumer holding cues from the last selection empties its window.
     */
    static native void selectSubtitle(long handle, int track);

    /**
     * The next cue from what the demuxer has already queued. <b>Never demultiplexes</b>: a
     * subtitle track is silent between lines, so reading forward to find one would read through
     * the whole gap.
     *
     * @return the cue's text when {@code out[C_STATUS]} is {@link #CUE_READY}, and null otherwise
     */
    static native String readCue(long handle, long[] out);

    static native void close(long handle);

    /** @return a slot index, {@link #READ_PENDING} or {@link #READ_END} */
    static native int readVideo(long handle, long[] out);

    static native ByteBuffer planeBuffer(long handle, int slot, int plane);

    /** Reads a hardware picture back into memory and re-points the slot's planes at it. */
    static native void downloadVideo(long handle, int slot, long[] out);

    static native void releaseVideo(long handle, int slot);

    static native void resetVideo(long handle);

    /** Moves the demuxer; {@code exact} makes the next picture the first at or after the target. */
    static native void seekVideo(long handle, long micros, boolean exact);

    // Every audio entry point takes the generation its caller was handed. A call presenting an
    // older one comes from a track that has since been replaced, and is answered rather than acted
    // on: the read reports the end, and the rest do nothing. See limn_ffmpeg.c's section on
    // several audio tracks for why the release is the one that would otherwise be silent.

    static native void seekAudio(long handle, long micros, long generation);

    /** @return frames written into {@code out}; 0 at the true end of the track, and 0 when
     *          {@code generation} names a track that has been superseded */
    static native int readAudio(long handle, ByteBuffer out, int maxFrames, long generation);

    static native void resetAudio(long handle, long generation);

    static native void releaseAudio(long handle, long generation);

    /**
     * Entries {@link #stats} fills: dropped video, dropped audio, both those queue depths, container
     * seeks, then dropped subtitle packets and that queue's depth.
     */
    static final int STATS_LENGTH = 7;

    static native void stats(long handle, long[] out);

    /**
     * @param audioChannels one entry per audio track, each its channel count; an entry of 0 writes
     *                      no track at all, which is how the no-soundtrack case is said
     * @param audioLanguages one entry per audio track, each a language tag or null for a track the
     *                       file states no language for
     * @param subtitleLanguages one entry per subtitle track, on the same rule. The cues themselves
     *                          are generated rather than supplied; see the writer's own note for
     *                          what they say and why they are contiguous
     */
    static native void writeClip(String path, int codecId, int width, int height, int frames,
                                 int rateNum, int rateDen, int[] audioChannels,
                                 String[] audioLanguages, int sampleRate,
                                 String[] subtitleLanguages);
}
