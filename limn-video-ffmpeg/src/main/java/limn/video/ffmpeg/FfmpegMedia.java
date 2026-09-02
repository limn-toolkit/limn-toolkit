package limn.video.ffmpeg;

import limn.sound.AudioStreamSource;
import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoStreamSource;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * An open container, and the tracks inside it: one picture stream, and whichever of its soundtracks
 * is playing.
 *
 * <p>This type exists because nothing in {@code limn.video} models a container.
 * {@code VideoDecoder.openStream} returns video and only video, which is right for a decoder
 * facade and leaves a real MP4's soundtrack with nowhere to arrive. Rather than widen an SPI that
 * five landed phases depend on, the module that demultiplexes publishes the pairing itself, in
 * terms of the two types the toolkit already defines:
 *
 * <pre>{@code
 * FfmpegMedia media = FfmpegMedia.open(Path.of("clip.mp4"));
 * MediaPlayer player = new MediaPlayer(media.video());
 * if (media.hasAudio()) {
 *     player.setAudio(media.audio(), PlayOptions.DEFAULTS.withBus(AudioBus.MUSIC));
 * }
 * player.start();
 * // ... and when the application is finished:
 * player.close();     // joins the decode thread, so nothing is reading any more
 * media.close();      // and only then
 * }</pre>
 *
 * <p>Nothing above this module needs a line changed to use it: {@link #video()} is a
 * {@code VideoStreamSource} and {@link #audio()} is an {@code AudioStreamSource}, and a player
 * takes exactly those two.
 *
 * <h2>Who closes what</h2>
 *
 * <p><b>This object owns the container, and closing it is what frees the decoder.</b> Closing the
 * video track does the same thing, because a caller that reached the track through
 * {@code Videos.open} never sees this object and closing what it was given has to work.
 *
 * <h2>Several soundtracks</h2>
 *
 * <p>A container may hold more than one audio track; a film with two languages is the ordinary
 * case. {@link #audioTracks()} lists every one of them and {@link #audio(int)} opens the one asked
 * for; {@link #audio()} keeps meaning the container's default, which is what a caller that asks
 * for nothing has always been given.
 *
 * <p><b>One is open at a time, and taking a second one ends the first.</b> The source
 * {@link #audio(int)} returns is <em>transferred</em>, exactly as {@link #audio()}'s is, so
 * asking for another track while the engine is still streaming the last one is the case that has
 * to be right, and what it does is end the old track rather than invalidate it: its
 * {@code readFrames} reports 0, the engine treats that as the track finishing, and the engine
 * closes it the way it closes every source it was given. Nothing is left for the caller to
 * remember, and a close arriving from the superseded track does not disturb the new one.
 *
 * <p><b>Closing the audio track does not close the container.</b> That is not an inconsistency,
 * it is the only thing that can be right: handing a track to the audio engine transfers it, and
 * the engine closes it on every path, so if that closed the container, a soundtrack ending would
 * pull the decoder out from under the pictures. What closing the audio track does instead is tell
 * the demultiplexer that nobody is reading that track, after which its packets are discarded as
 * they are met rather than queued.
 *
 * <p><b>A call that arrives after the container is closed is answered, not punished.</b> Reading
 * video reports the end, reading audio reports zero frames (which is what the end of a track
 * means to the audio engine) and releasing a picture does nothing. That is deliberate: the
 * soundtrack may outlive the player, so the engine's streaming thread can genuinely still be
 * inside a read when an application closes the container, and the alternative to answering it is
 * a use-after-free in a thread the application does not know exists.
 *
 * <h2>Subtitles</h2>
 *
 * <p>{@link #subtitleTracks()} lists them and {@link #selectSubtitles(int)} chooses one;
 * {@link #subtitles()} is where the cues come out, asked for by position. <b>Nothing is selected
 * when a container opens</b>, whatever the file marks as its default: whether a viewer wants
 * subtitles is not a fact about the file, and an unselected track's packets are freed as they are
 * met, so a release carrying a dozen languages costs nothing for the eleven nobody reads.
 *
 * <p>What crosses is text and an interval, and drawing it is the application's. Nothing here
 * rasterises a cue into the picture: that needs libass, fontconfig, freetype and harfbuzz, it means
 * downloading a hardware picture and uploading it again for every frame, and the result could not
 * afterwards be turned off, moved or restyled.
 *
 * <h2>Threads</h2>
 *
 * <p>Any thread, and several at once, which is not a courtesy but a requirement: the pictures are
 * pulled by a player's decode thread, the soundtrack by the audio engine's streaming thread, and
 * a picture is released by whichever thread happened to finish with it. A read-write lock is what
 * makes that safe: every call takes the read lock, so none of them waits for another, and
 * {@link #close()} takes the write lock, so it waits for the reads that are in flight and
 * everything after it finds the handle gone.
 */
public final class FfmpegMedia implements AutoCloseable {

    /**
     * Pictures the decoder may have in flight at once.
     *
     * <p>Two is the smallest that works (one being shown while the next is produced), and four
     * leaves room for a player's ring without the decoder stalling every time the consumer is a
     * frame behind. Each one costs a reference to a picture libavcodec already allocated, not a
     * picture of its own, so the number buys latency rather than memory.
     */
    public static final int DEFAULT_SLOTS = 4;

    /**
     * Whether to decode on a platform accelerator, and therefore what shape this container's
     * pictures have.
     *
     * <p>The choice is the application's because the consequence is: with an accelerator the
     * pictures are {@link limn.video.VideoFrame.Kind#IO_SURFACE} handles, which the GL backend
     * binds without a copy and which every other consumer has to read back through
     * {@code VideoFrame.toPlanar()}. That is a real cost for a consumer that only wanted samples,
     * and a real saving for one that is going to draw them.
     */
    public enum Hardware {

        /**
         * Use one where the platform, the codec and the layout all have it, and decode in software
         * otherwise. The default, and not a promise: {@link FfmpegMedia#isHardwareDecoding()} is
         * what actually happened.
         */
        PREFER,

        /**
         * Decode in software. Every picture is then planar samples, which is what a consumer that
         * reads planes and does not want to pay for a read-back should ask for, and what makes a
         * test reproducible across machines whose accelerators differ.
         */
        OFF,
    }

    private final Path file;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Zero once closed. Only ever read under the lock, and only ever written under the write lock. */
    private long handle;

    private final FfmpegVideoStream video;
    private final boolean hardware;
    private final List<AudioTrack> audioTracks;
    private final List<SubtitleTrack> subtitleTracks;
    private final SubtitleCues cues = new SubtitleCues(this);
    /** A position in {@link #subtitleTracks}, or {@link #NO_SUBTITLES}. Nothing is selected at
     *  open; guarded by this object's monitor, like the audio selection. */
    private int selectedSubtitles = NO_SUBTITLES;
    /** Whether this container was opened for sound at all, which is what {@link #audio(int)} needs
     *  to tell "no track could be opened" from "no track was asked for". */
    private final boolean withAudio;

    /**
     * The soundtrack most recently handed out, or null when there is none. Mutable because
     * {@link #audio(int)} replaces it; guarded by this object's monitor, which selection and every
     * accessor take. The container's read-write lock guards the native handle and says nothing
     * about which track is current.
     */
    private FfmpegAudioStream audio;

    /** The position in {@link #audioTracks} that {@link #audio} decodes, or -1 when none is open. */
    private int selectedTrack = -1;

    private FfmpegMedia(Path file, long handle, boolean withAudio, long[] description) {
        this.file = file;
        this.handle = handle;
        this.withAudio = withAudio;
        this.hardware = description[FfmpegNative.D_HARDWARE] != 0;
        this.audioTracks = readAudioTracks(handle, (int) description[FfmpegNative.D_AUDIO_TRACKS]);
        this.subtitleTracks =
                readSubtitleTracks(handle, (int) description[FfmpegNative.D_SUBTITLE_TRACKS]);

        int width = (int) description[FfmpegNative.D_WIDTH];
        int height = (int) description[FfmpegNative.D_HEIGHT];
        PixelFormat format = PixelFormat.values()[(int) description[FfmpegNative.D_PIXEL_FORMAT]];
        VideoColor color = colorOf((int) description[FfmpegNative.D_MATRIX],
                (int) description[FfmpegNative.D_RANGE],
                (int) description[FfmpegNative.D_TRANSFER]);
        this.video = new FfmpegVideoStream(this, width, height, format, color,
                (int) description[FfmpegNative.D_RATE_NUM],
                (int) description[FfmpegNative.D_RATE_DEN],
                description[FfmpegNative.D_DURATION_MICROS],
                (int) description[FfmpegNative.D_ROTATION_DEGREES],
                (int) description[FfmpegNative.D_SLOTS]);
        if (description[FfmpegNative.D_HAS_AUDIO] != 0) {
            this.audio = streamFor(description, FfmpegNative.audioGeneration(handle));
            for (int track = 0; track < audioTracks.size(); track++) {
                if (audioTracks.get(track).isDefault()) {
                    this.selectedTrack = track;
                    break;
                }
            }
        }
    }

    /**
     * The soundtrack a description describes. Read from the <em>codec</em> the shim opened rather
     * than from the container's stream parameters, because those are what the file claims and this
     * is what the decoder will actually produce.
     */
    private FfmpegAudioStream streamFor(long[] description, long generation) {
        return new FfmpegAudioStream(this,
                (int) description[FfmpegNative.D_AUDIO_CHANNELS],
                (int) description[FfmpegNative.D_AUDIO_SAMPLE_RATE],
                (int) description[FfmpegNative.D_AUDIO_SOURCE_CHANNELS],
                generation);
    }

    private static List<AudioTrack> readAudioTracks(long handle, int count) {
        if (count <= 0) {
            return List.of();
        }
        long[] values = new long[FfmpegNative.AUDIO_TRACK_LENGTH];
        List<AudioTrack> tracks = new ArrayList<>(count);
        for (int track = 0; track < count; track++) {
            String[] names = FfmpegNative.describeAudioTrack(handle, track, values);
            tracks.add(new AudioTrack(track,
                    (int) values[FfmpegNative.AT_STREAM_INDEX],
                    (int) values[FfmpegNative.AT_CHANNELS],
                    (int) values[FfmpegNative.AT_SOURCE_CHANNELS],
                    (int) values[FfmpegNative.AT_SAMPLE_RATE],
                    names[0],
                    languageOf(names[1]),
                    values[FfmpegNative.AT_DEFAULT] != 0,
                    values[FfmpegNative.AT_DECODABLE] != 0));
        }
        return List.copyOf(tracks);
    }

    /**
     * The language a track states, or null for one that states none.
     *
     * <p>{@code und} is ISO 639-2 for undetermined, and it is what a container records when nothing
     * was set: an MP4 whose audio track was never given a language reads back as {@code und} rather
     * than as an absent tag, which was measured rather than assumed. Reporting it as a language
     * would put "und" in a language column; reporting it as null says the same thing in the type,
     * and leaves an empty string impossible.
     */
    static String languageOf(String tag) {
        return tag == null || tag.isBlank() || "und".equals(tag) ? null : tag;
    }

    /**
     * One of a container's audio tracks, as the container describes it: read once at open, from
     * the file's own stream headers, without opening a decoder for it.
     *
     * @param index          this track's position in {@link FfmpegMedia#audioTracks()}, and what
     *                       {@link FfmpegMedia#audio(int)} takes. <b>Not</b> a container stream
     *                       index: a file whose audio is streams 1 and 2 has tracks 0 and 1
     * @param streamIndex    the container's own index for this stream, for a diagnostic; nothing
     *                       in this API takes it
     * @param channels       what this track would deliver: 1 or 2, after the fold the audio engine
     *                       makes necessary
     * @param sourceChannels what the file holds, before that fold. A 5.1 track reports 6 here and
     *                       2 in {@code channels}
     * @param sampleRate     frames a second, as the file declares it. Nothing resamples, so this is
     *                       what the engine would be handed
     * @param codec          the codec's name as libavcodec spells it: {@code aac}, {@code opus}
     * @param language       the language tag the container states, or <b>null</b> when it states
     *                       none. Null and never an empty string, so that a caller can tell the
     *                       two apart rather than rendering a blank row; the tag is whatever the
     *                       container carries, which for MP4 and Matroska is an ISO 639-2
     *                       three-letter code, and the undetermined code {@code und} is reported as
     *                       null because it is the file saying it does not know
     * @param isDefault      whether this is the track {@link FfmpegMedia#audio()} hands over (the
     *                       container's own signalling, ranked as libavformat ranks it: the default
     *                       disposition first, then channel count and bit rate)
     * @param decodable      whether this build has a decoder for it. False is not a broken file: a
     *                       trimmed build carries a few codecs, and a track it cannot decode is
     *                       still listed rather than hidden, because a two-language film with one
     *                       playable track is not a one-language film. Asking for such a track
     *                       throws rather than playing silence
     */
    public record AudioTrack(int index, int streamIndex, int channels, int sourceChannels,
                             int sampleRate, String codec, String language, boolean isDefault,
                             boolean decodable) {
    }

    private static List<SubtitleTrack> readSubtitleTracks(long handle, int count) {
        if (count <= 0) {
            return List.of();
        }
        long[] values = new long[FfmpegNative.SUBTITLE_TRACK_LENGTH];
        List<SubtitleTrack> tracks = new ArrayList<>(count);
        for (int track = 0; track < count; track++) {
            String[] names = FfmpegNative.describeSubtitleTrack(handle, track, values);
            tracks.add(new SubtitleTrack(track,
                    (int) values[FfmpegNative.ST_STREAM_INDEX],
                    names[0],
                    languageOf(names[1]),
                    values[FfmpegNative.ST_TEXT] != 0,
                    values[FfmpegNative.ST_DECODABLE] != 0,
                    values[FfmpegNative.ST_DEFAULT] != 0,
                    values[FfmpegNative.ST_FORCED] != 0));
        }
        return List.copyOf(tracks);
    }

    /**
     * One of a container's subtitle tracks, as the container describes it: read once at open, from
     * the file's own stream headers, without opening a decoder for it.
     *
     * @param index       this track's position in {@link FfmpegMedia#subtitleTracks()}, and what
     *                    {@link FfmpegMedia#selectSubtitles(int)} takes. <b>Not</b> a container
     *                    stream index
     * @param streamIndex the container's own index for this stream, for a diagnostic; nothing in
     *                    this API takes it
     * @param codec       the codec's name as libavcodec spells it: {@code mov_text},
     *                    {@code subrip}, {@code ass}, {@code webvtt}
     * @param language    the language tag the container states, or <b>null</b> when it states none,
     *                    on the same rule as {@link AudioTrack#language()}
     * @param text        whether the format is text rather than a picture. <b>False is a refusal,
     *                    not a warning:</b> PGS, VobSub and DVB subtitles are paletted bitmaps with
     *                    their own rectangle and palette, and this SPI carries text cues only, so
     *                    {@link FfmpegMedia#selectSubtitles(int)} throws for one. Such a track is
     *                    still listed, because a film with one text track and one bitmap track has
     *                    two subtitle tracks and hiding one would say otherwise. This is the
     *                    codec's own property and not the build's, so it stays the answer in a
     *                    build someone compiled a bitmap decoder into
     * @param decodable   whether this build has a decoder for it. The other reason selecting a
     *                    track can be refused, and a separate one: a trimmed build carries a few
     *                    codecs, and which they are can change without the format changing
     * @param isDefault   whether the container marks this track as its default. Reported and not
     *                    acted on: <b>nothing is selected when a container opens</b>, because
     *                    whether a viewer wants subtitles is not a fact about the file
     * @param forced      whether the container marks this track as forced: the subset a viewer
     *                    wants even with subtitles off, for the foreign-language lines inside a
     *                    film. Reported for an application that wants to honour it
     */
    public record SubtitleTrack(int index, int streamIndex, String codec, String language,
                                boolean text, boolean decodable, boolean isDefault,
                                boolean forced) {
    }

    /**
     * Opens {@code file}, taking its video track and its audio track if it has one.
     *
     * <p><b>This blocks, and not briefly.</b> It opens the input, then asks libavformat to probe
     * the container (which reads and <em>decodes real packets</em> to fill in what a header does
     * not state), then enumerates the tracks, opens a decoder, and on the hardware path creates a
     * platform decode device. That is far longer than a frame on an ordinary file, and it grows
     * with the container's stream count, with the file's bitrate, and on a cold or network volume
     * with the disk. The first call in a process additionally links the native library, which is
     * an extraction of tens of megabytes on a build that carries them in its jar.
     *
     * <p>So: any thread <em>except</em> the UI thread, which is a freeze for the whole of it. There
     * is nothing thread-affine about the container itself (it is opened wherever this is called
     * and may be handed over afterwards), but each track it hands out then follows the usual rule
     * and belongs to whichever thread reads it, the video stream to the thread that decodes it and
     * the soundtrack to the audio engine.
     *
     * <p><b>There is no asynchronous form, and the reason is ownership.</b> What this returns is
     * one container holding several tracks, and closing it closes all of them, so a job wrapping
     * it needs a disposer that knows which tracks the caller took and in which order to let them
     * go, which is a fact about the call site rather than about the open. A caller that wants the
     * work off the calling thread writes that job itself: a body returning whatever record it
     * needs (the container, the tracks it took, anything it derived from them) and one disposer
     * on it that closes them in the caller's own order. {@code Videos.openAsync} is the ready-made
     * form for the one case that has a single obvious owner, a container reduced to its video
     * track; anything wanting the soundtrack or the subtitles is past what that shape can carry.
     *
     * @throws FfmpegException      if the native library is not loaded, the input is not a
     *                              container this build can demultiplex, or it holds no video
     * @throws NullPointerException if {@code file} is null
     */
    public static FfmpegMedia open(Path file) {
        return open(file, true, DEFAULT_SLOTS, Hardware.PREFER);
    }

    /**
     * <p>Blocks exactly as {@link #open(Path)} does, and must not be called on the UI thread for
     * the same reason.
     *
     * @param slots pictures in flight at once, in {@code [1..16]}
     * @throws IllegalArgumentException if {@code slots} is outside that range
     */
    public static FfmpegMedia open(Path file, boolean withAudio, int slots) {
        return open(file, withAudio, slots, Hardware.PREFER);
    }

    /**
     * Opens {@code file}, choosing explicitly whether its pictures may be device handles.
     *
     * <p>Blocks exactly as {@link #open(Path)} does, and must not be called on the UI thread for
     * the same reason, with one addition: {@link Hardware#PREFER} also creates a platform decode
     * device here, which is a driver call and is the slowest single step of an open on the machines
     * that have one.
     *
     * @param slots    pictures in flight at once, in {@code [1..16]}
     * @param hardware whether to attach a platform accelerator; see {@link Hardware}, and
     *                 {@link #isHardwareDecoding()} for what was actually attached
     * @throws IllegalArgumentException if {@code slots} is outside that range
     */
    public static FfmpegMedia open(Path file, boolean withAudio, int slots, Hardware hardware) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(hardware, "hardware");
        if (slots < 1 || slots > 16) {
            throw new IllegalArgumentException("slots must be in [1..16], got " + slots);
        }
        FfmpegLibrary.require();
        long handle = FfmpegNative.open(file.toAbsolutePath().toString(), withAudio, slots,
                hardware == Hardware.PREFER);
        if (handle == 0) {
            // Unreachable in practice: the shim throws before returning 0. Kept because a handle
            // of 0 reaching the read path would be a wild pointer, and this is the one place that
            // can still stop it.
            throw new FfmpegException("the FFmpeg decoder returned no handle for " + file);
        }
        long[] description = new long[FfmpegNative.DESCRIBE_LENGTH];
        try {
            FfmpegNative.describe(handle, description);
        } catch (RuntimeException | Error failure) {
            FfmpegNative.close(handle);
            throw failure;
        }
        // Refused at open, out loud, rather than played washed out. Nothing downstream inverts a
        // transfer function, so a PQ or HLG picture would go through a matrix that assumes its
        // values are already a display's and arrive milky and low-contrast, which reads as a
        // shader bug and not as an unsupported file. This is the same rule the rotation metadata
        // follows: refuse what cannot be shown correctly instead of approximating it.
        int transfer = (int) description[FfmpegNative.D_TRANSFER];
        if (transfer != FfmpegNative.TRANSFER_SDR) {
            FfmpegNative.close(handle);
            throw new FfmpegException(file + " carries a "
                    + (transfer == FfmpegNative.TRANSFER_PQ ? "PQ" : "HLG")
                    + " transfer function, and this toolkit shows display-referred pictures only. "
                    + "High dynamic range video needs the inverse of that curve and a linear "
                    + "compositing path, neither of which exists here yet.");
        }
        return new FfmpegMedia(file, handle, withAudio, description);
    }

    /**
     * A container's colour, where FFmpeg reported one.
     *
     * <p>An MP4 either signals both the matrix and the range or signals neither, so the mixed case
     * below is a defensive reading rather than a common one: a signalled matrix with no range is
     * taken as studio range, because that is what every signalled matrix in practice means, and a
     * container that signalled no matrix reports {@code unspecified()}, which decodes as BT.709
     * limited but tells a caller that could do better that nothing was stated.
     *
     * <p>The transfer function is read separately and is <em>not</em> folded into that: a file may
     * signal PQ while signalling no matrix at all, and an unsignalled matrix on an HDR file must
     * not be reported as an unsignalled colour, because unsignalled means SDR here and that is the
     * one thing such a file is not.
     */
    private static VideoColor colorOf(int matrix, int range, int transfer) {
        VideoColor.Transfer curve = switch (transfer) {
            case FfmpegNative.TRANSFER_PQ -> VideoColor.Transfer.PQ;
            case FfmpegNative.TRANSFER_HLG -> VideoColor.Transfer.HLG;
            default -> VideoColor.Transfer.SDR;
        };
        if (matrix == FfmpegNative.UNSPECIFIED) {
            return VideoColor.unspecified().withTransfer(curve);
        }
        VideoColor.Matrix which = switch (matrix) {
            case FfmpegNative.MATRIX_BT601 -> VideoColor.Matrix.BT601;
            case FfmpegNative.MATRIX_BT2020 -> VideoColor.Matrix.BT2020;
            default -> VideoColor.Matrix.BT709;
        };
        return VideoColor.of(which,
                range == FfmpegNative.RANGE_FULL ? VideoColor.Range.FULL
                        : VideoColor.Range.LIMITED,
                curve);
    }

    /** @return the file this was opened from */
    public Path file() {
        return file;
    }

    /** @return the pictures; never null, because a container with no video never opens */
    public VideoStreamSource video() {
        return video;
    }

    /**
     * @return whether an accelerator is attached, and therefore whether this stream's pictures are
     *         {@link limn.video.VideoFrame.Kind#IO_SURFACE} handles rather than samples. False for
     *         every software decode, for every codec with no accelerator, on every platform without
     *         one, and whenever {@link Hardware#OFF} was asked for, so a caller that needs to know
     *         asks this rather than assuming what it requested
     */
    public boolean isHardwareDecoding() {
        return hardware;
    }

    /** @return whether a soundtrack is open; see {@link #audioTracks()} for what the file holds */
    public synchronized boolean hasAudio() {
        return audio != null;
    }

    /**
     * The container's default soundtrack ({@link AudioTrack#isDefault()}), which is what a caller
     * that does not care which track it gets has always been given.
     *
     * <p>Called twice, this hands back the same source both times. Called after
     * {@link #audio(int)} took a different track, it takes the default back and supersedes that
     * one, because that is what asking for the default means.
     *
     * <p>A source that has been closed is not reopened by asking again: closing is the consumer
     * saying it is done with that track. Selecting another track and coming back opens a fresh one.
     *
     * @return the soundtrack, or null: a container may have none, and one whose audio codec this
     *         build was not compiled with is opened without it rather than refused, because a film
     *         with no sound is better than no film
     */
    public synchronized AudioStreamSource audio() {
        if (audio == null || selectedTrack < 0) {
            return audio;
        }
        return audioTracks.get(selectedTrack).isDefault() ? audio : audio(defaultTrackIndex());
    }

    /**
     * Every audio track the container holds, in the container's own order, including any this
     * build has no decoder for, because a track that cannot be played is still a track the file
     * has and hiding it would report a two-language film as having one language.
     *
     * @return an unmodifiable list, empty when the container carries no audio at all. Read once at
     *         open: a container's tracks do not change while it is open
     */
    public List<AudioTrack> audioTracks() {
        return audioTracks;
    }

    /**
     * Opens audio track {@code index} and hands it over, ending whichever track was open.
     *
     * <p><b>This is not a getter.</b> The source it returns is the caller's to close, exactly as
     * {@link #audio()}'s is, and handing one to the audio engine transfers it, so a caller that
     * takes a track and never gives it to a player must close it, and a caller that does give it
     * to one must not.
     *
     * <p><b>What happens to the track that was open is the part worth reading.</b> It is
     * <em>ended</em>, not invalidated: every subsequent {@code readFrames} on it reports 0, which
     * is what the end of a track means to the audio engine, so an engine streaming it stops and
     * closes it on its own. A close arriving from that superseded source afterwards does nothing;
     * in particular it does not tell the demultiplexer that nobody is reading the track that is
     * now open, which is the one way this could go wrong quietly. Asking for the track that is
     * already open hands back the same source, closed or not, and disturbs nothing.
     *
     * <p>Only one track is decoded at a time. Two at once would be two decoders, two packet queues
     * and two positions to keep coherent across a seek, for samples no consumer reads: the audio
     * engine mixes one source per player.
     *
     * <p>A container whose <em>default</em> track this build has no decoder for opens with no
     * soundtrack rather than refusing to open, and another of its tracks can still be asked for
     * here, which is the point of the undecodable ones being listed.
     *
     * @param index a position in {@link #audioTracks()}
     * @throws IndexOutOfBoundsException if there is no such track
     * @throws IllegalStateException     if the container was opened without audio
     * @throws FfmpegException           if this build has no decoder for that track
     */
    public synchronized AudioStreamSource audio(int index) {
        AudioTrack track = audioTracks.get(index);
        if (!withAudio) {
            throw new IllegalStateException("this container was opened without audio, so no track "
                    + "can be selected; open it again with audio to choose one");
        }
        if (index == selectedTrack && audio != null) {
            return audio;
        }
        if (!track.decodable()) {
            throw new FfmpegException("this build of FFmpeg has no decoder for the '" + track.codec()
                    + "' audio track " + index + " of " + file);
        }
        long[] description = new long[FfmpegNative.DESCRIBE_LENGTH];
        lock.readLock().lock();
        try {
            if (handle == 0) {
                throw new FfmpegException("the container is closed");
            }
            FfmpegNative.selectAudio(handle, index);
            // What the decoder produces, rather than what the header claimed: the fold and the
            // rate a source reports have to be the ones its samples will actually arrive in.
            FfmpegNative.describe(handle, description);
            audio = streamFor(description, FfmpegNative.audioGeneration(handle));
        } finally {
            lock.readLock().unlock();
        }
        selectedTrack = index;
        return audio;
    }

    /**
     * @return the position in {@link #audioTracks()} of the track being decoded, or -1 when there
     *         is none: a container with no audio, or one opened without it
     */
    public synchronized int selectedAudioTrack() {
        return selectedTrack;
    }

    private int defaultTrackIndex() {
        for (int track = 0; track < audioTracks.size(); track++) {
            if (audioTracks.get(track).isDefault()) {
                return track;
            }
        }
        return selectedTrack;
    }

    // ------------------------------------------------------------------ subtitles

    /** What {@link #selectSubtitles(int)} takes to turn subtitles off, and what
     *  {@link #selectedSubtitleTrack()} answers when none is on. */
    public static final int NO_SUBTITLES = -1;

    /**
     * Every subtitle track the container holds, in the container's own order, including any this
     * build cannot decode and any that is a bitmap format, because a track that cannot be shown is
     * still a track the file has.
     *
     * @return an unmodifiable list, empty when the container carries no subtitles at all. Read once
     *         at open: a container's tracks do not change while it is open
     */
    public List<SubtitleTrack> subtitleTracks() {
        return subtitleTracks;
    }

    /**
     * @return the position in {@link #subtitleTracks()} being decoded, or {@link #NO_SUBTITLES}.
     *         <b>{@link #NO_SUBTITLES} when a container opens</b>, whatever the file marks as its
     *         default: whether a viewer wants subtitles is the application's question, and a track
     *         nobody selected costs nothing because its packets are freed as they are met
     */
    public synchronized int selectedSubtitleTrack() {
        return selectedSubtitles;
    }

    /**
     * Chooses which subtitle track is decoded, or turns subtitles off with {@link #NO_SUBTITLES}.
     *
     * <p>The cues held by {@link #subtitles()} are dropped either way: they belong to the track
     * that was open, and showing them over another one is the failure this exists to avoid.
     *
     * <p>Only one track is decoded at a time, for the reason {@link #audio(int)} gives, and one
     * more: a second track's packets would be queued for a consumer that never polls them.
     *
     * @param index a position in {@link #subtitleTracks()}, or {@link #NO_SUBTITLES}
     * @throws IndexOutOfBoundsException if there is no such track
     * @throws FfmpegException           if the track is a bitmap format, which this SPI does not
     *                                   carry, or if this build has no decoder for it. Both are
     *                                   refused by name rather than opened to show nothing
     */
    public synchronized void selectSubtitles(int index) {
        if (index != NO_SUBTITLES) {
            SubtitleTrack track = subtitleTracks.get(index);
            if (!track.text()) {
                throw new FfmpegException("subtitle track " + index + " of " + file + " is '"
                        + track.codec() + "', which is a bitmap format; this toolkit carries text "
                        + "cues only, so there is nothing it could hand an application to draw");
            }
            if (!track.decodable()) {
                throw new FfmpegException("this build of FFmpeg has no decoder for the '"
                        + track.codec() + "' subtitle track " + index + " of " + file);
            }
        }
        lock.readLock().lock();
        try {
            if (handle == 0) {
                throw new FfmpegException("the container is closed");
            }
            FfmpegNative.selectSubtitle(handle, index);
        } finally {
            lock.readLock().unlock();
        }
        selectedSubtitles = index;
        cues.reset();
    }

    /**
     * The cues of whichever subtitle track is selected.
     *
     * <p>Never null, and empty while nothing is selected, so a paint loop can ask without a null
     * check and without knowing whether the viewer turned subtitles on.
     *
     * @return the same object for this container's whole life; selecting another track changes what
     *         it answers rather than replacing it
     */
    public SubtitleCues subtitles() {
        return cues;
    }

    /**
     * @return subtitle packets waiting to be turned into cues. Zero while no track is selected,
     *         which is the point: an unselected track's packets are freed as they are demultiplexed
     *         rather than queued, so a release carrying a dozen languages costs nothing for the
     *         eleven nobody is reading
     */
    public long queuedSubtitlePackets() {
        return stats()[6];
    }

    /**
     * @return subtitle packets thrown away because the queue was full, which means the application
     *         selected a track and then stopped asking for cues while the pictures ran on. Zero
     *         throughout an ordinary playback
     */
    public long droppedSubtitlePackets() {
        return stats()[5];
    }

    /**
     * @return how many channels the file actually holds in the <em>selected</em> track, before the
     *         fold to the one or two the audio engine will admit; 0 when there is no soundtrack. A
     *         5.1 track reports 6 here and 2 from {@code audio().channels()}.
     */
    public synchronized int audioSourceChannels() {
        return audio == null ? 0 : audio.sourceChannels();
    }

    /**
     * Packets thrown away because a track's queue was full, which only happens when that track's
     * consumer has stopped reading, since the bound is far above any interleaving a muxer
     * produces. Zero throughout an ordinary playback.
     *
     * @return dropped video packets, then dropped audio packets
     */
    public long[] droppedPackets() {
        long[] values = stats();
        return new long[] {values[0], values[1]};
    }

    /**
     * Packets waiting for a consumer that has not asked for them yet: the video track's, then the
     * selected audio track's. A track nobody is reading at all queues nothing, because its packets
     * are freed as they are demultiplexed rather than held; so is every audio track that is not the
     * selected one, which is what keeps a film's other languages costing nothing.
     *
     * @return queued video packets, then queued audio packets
     */
    public long[] queuedPackets() {
        long[] values = stats();
        return new long[] {values[2], values[3]};
    }

    /**
     * @return how many times this container's demultiplexer was actually moved. Lower than the
     *         number of seeks asked for whenever both tracks asked for the same target: one
     *         position serves both, so a target each of them asks for is one move and not two
     */
    public long containerSeeks() {
        return stats()[4];
    }

    private long[] stats() {
        long[] values = new long[FfmpegNative.STATS_LENGTH];
        lock.readLock().lock();
        try {
            if (handle != 0) {
                FfmpegNative.stats(handle, values);
            }
        } finally {
            lock.readLock().unlock();
        }
        return values;
    }

    /** Releases the decoder and the input. Idempotent, and safe while another thread is reading. */
    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (handle != 0) {
                FfmpegNative.close(handle);
                handle = 0;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** @return whether the container is still open, for a diagnostic */
    public boolean isOpen() {
        lock.readLock().lock();
        try {
            return handle != 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------ the guarded boundary
    //
    // Every native call in this module goes through one of these. The read lock is shared, so a
    // decode that takes ten milliseconds does not delay the picture being released beside it; the
    // write lock is close()'s alone, so nothing is inside libavcodec when the handle is freed.

    int readVideo(long[] out) {
        lock.readLock().lock();
        try {
            return handle == 0 ? FfmpegNative.READ_END : FfmpegNative.readVideo(handle, out);
        } finally {
            lock.readLock().unlock();
        }
    }

    ByteBuffer planeBuffer(int slot, int plane) {
        lock.readLock().lock();
        try {
            if (handle == 0) {
                throw new FfmpegException("the container was closed while a picture was in flight");
            }
            return FfmpegNative.planeBuffer(handle, slot, plane);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Answered rather than punished on a closed container is <em>not</em> right here, unlike the
     * reads: a download that quietly did nothing would leave the frame handle-backed and
     * {@code VideoFrame.toPlanar()} would report that its producer produced no planes, which names
     * the wrong problem. A container closed under a picture still in flight is worth saying.
     */
    void downloadVideo(int slot, long[] out) {
        lock.readLock().lock();
        try {
            if (handle == 0) {
                throw new FfmpegException("the container was closed while a picture was in flight");
            }
            FfmpegNative.downloadVideo(handle, slot, out);
        } finally {
            lock.readLock().unlock();
        }
    }

    void releaseVideo(int slot) {
        lock.readLock().lock();
        try {
            if (handle != 0) {
                FfmpegNative.releaseVideo(handle, slot);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    void resetVideo() {
        lock.readLock().lock();
        try {
            if (handle == 0) {
                throw new FfmpegException("the container is closed");
            }
            FfmpegNative.resetVideo(handle);
        } finally {
            lock.readLock().unlock();
        }
    }

    void seekVideo(long micros, boolean exact) {
        lock.readLock().lock();
        try {
            if (handle == 0) {
                throw new FfmpegException("the container is closed");
            }
            FfmpegNative.seekVideo(handle, micros, exact);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Answered rather than punished on a closed container, the way {@link #readAudio} is: the
     * engine's streaming thread can genuinely be inside a refill when an application closes what a
     * player has finished with, and a seek arriving then is that same race one call along.
     */
    void seekAudio(long micros, long generation) {
        lock.readLock().lock();
        try {
            if (handle != 0) {
                FfmpegNative.seekAudio(handle, micros, generation);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    int readAudio(ByteBuffer out, int maxFrames, long generation) {
        lock.readLock().lock();
        try {
            return handle == 0 ? 0 : FfmpegNative.readAudio(handle, out, maxFrames, generation);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Answered rather than punished on a closed container, the way the reads are: a paint that
     * asks for the cue over a picture can genuinely arrive after the container was closed. What it
     * gets is the epoch it already had and no cue, which the window reads as nothing new.
     */
    String readCue(long[] out) {
        lock.readLock().lock();
        try {
            if (handle == 0) {
                out[FfmpegNative.C_STATUS] = FfmpegNative.CUE_NONE;
                return null;
            }
            return FfmpegNative.readCue(handle, out);
        } finally {
            lock.readLock().unlock();
        }
    }

    void resetAudio(long generation) {
        lock.readLock().lock();
        try {
            if (handle != 0) {
                FfmpegNative.resetAudio(handle, generation);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    void releaseAudio(long generation) {
        lock.readLock().lock();
        try {
            if (handle != 0) {
                FfmpegNative.releaseAudio(handle, generation);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------ writing a clip

    /**
     * Whether {@link #writeClip} can do anything on this build.
     *
     * <p>False for the library that ships, which holds no encoder at all: a player does not encode,
     * and an encoder is not merely bytes; MPEG-4 Visual and AVC are licensed separately for
     * encoding and for decoding, so shipping one would buy patent surface for a capability
     * nothing uses. The build that carries encoders is produced by the limn-ffmpeg-natives
     * repository's {@code scripts/build-ffmpeg.sh --profile full}, never published, and picked
     * up from a sibling clone by this module's tests and the Kitchen Sink so they can make a
     * file to read rather than commit one (ADR 037).
     *
     * <p>A field read, except possibly once: the first call in a process may be the one that links
     * the native library, which on a build carrying the libraries in its jar extracts tens of
     * megabytes under a global lock. Ask it on a worker, or after {@link FfmpegVideoDecoder#warmUp()}
     * has paid for the link somewhere else.
     */
    public static boolean canWriteClip() {
        return FfmpegLibrary.isAvailable() && FfmpegNative.canWrite();
    }

    /**
     * How a written clip's pictures are coded. Neither is H.264, and neither can be: FFmpeg's
     * H.264 encoder is x264 and x264 is GPL, so an LGPL build cannot produce H.264 to read back.
     * What a round trip through either one proves is therefore the seam (the demultiplexer, the
     * packet-to-picture path, the planar handoff, the pool, the release discipline and the
     * timestamp rescale) and not libavcodec's H.264 decoder, which is FFmpeg's to test.
     */
    public enum ClipCodec {

        /**
         * Motion JPEG: every picture independent, no reordering, no decoder delay, and nearly
         * lossless at the quality used, so samples can be asserted tightly. The default.
         */
        MJPEG(0),

        /**
         * MPEG-4 Part 2: a real group of pictures, pictures that need their predecessors, and a
         * decoder that carries state across packets (the <em>shape</em> H.264 has, without being
         * it).
         */
        MPEG4(1);

        private final int id;

        ClipCodec(int id) {
            this.id = id;
        }
    }

    /**
     * Writes a real MP4 (a real encoded video track, and optionally a real AAC one) so that
     * something exists to demultiplex. Nothing this build can generate is committed, which makes
     * producing one the only honest way to have it.
     *
     * <p>The picture is eight flat colour bars in the studio code table, shifting one bar per
     * picture so that a stream which is not advancing is visible without a stopwatch. Flat on
     * purpose: an encoder moves every sample, so an assertion can only be about an area's mean,
     * and large flat areas are what make that tight enough to catch a swapped chroma pair.
     *
     * <p><b>Blocks for the whole encode</b> (every picture, every sample of every soundtrack, and
     * the mux, all through the native library), which is far longer than a frame and grows with
     * {@code frames} and the picture size. So it belongs off the UI thread. There is no
     * asynchronous form of it: a caller that needs one wraps this call in {@code Ui.work} and owns
     * the job that results.
     *
     * @param audioChannels channels in the soundtrack, or 0 for no soundtrack. Above 2 produces a
     *                      track the audio engine will not admit as it stands, which is the case
     *                      the fold to stereo exists for.
     * @throws FfmpegException if this build has no encoder, or the file cannot be written
     */
    public static void writeClip(Path path, ClipCodec codec, int width, int height, int frames,
                                 int rateNum, int rateDen, int audioChannels, int sampleRate) {
        writeClip(path, codec, width, height, frames, rateNum, rateDen,
                audioChannels <= 0 ? List.of() : List.of(new ClipAudioTrack(audioChannels, null)),
                sampleRate);
    }

    /**
     * One audio track of a written clip.
     *
     * @param channels channels in it, at least 1
     * @param language the tag to write, or null to state none, which is what an MP4 records as the
     *                 undetermined code, and therefore what a reader gets back as no language
     */
    public record ClipAudioTrack(int channels, String language) {
    }

    /**
     * Writes a clip with any number of audio tracks, so that there is something to select between.
     *
     * <p><b>Every track sounds different, on purpose.</b> The first track's first channel is 440 Hz,
     * each further channel is an octave up, and each further track is an odd multiple, so no two
     * of the frequencies coincide, and a test that asked for track 1 and is hearing track 0 sees a
     * wrong tone rather than a level it has to interpret. That is what makes a wrong index and a
     * downmix into failures instead of judgement calls.
     *
     * <p>Blocks for the whole encode and has no asynchronous form, for the reason stated on the
     * overload that takes a single soundtrack; more tracks make it longer.
     *
     * @param audio one entry per audio track, in the order they are written; empty for no sound
     * @throws FfmpegException if this build has no encoder, if the file cannot be written, or if a
     *                         track's tones would not fit below half the sample rate
     */
    public static void writeClip(Path path, ClipCodec codec, int width, int height, int frames,
                                 int rateNum, int rateDen, List<ClipAudioTrack> audio,
                                 int sampleRate) {
        writeClip(path, codec, width, height, frames, rateNum, rateDen, audio, sampleRate,
                List.of());
    }

    /**
     * Writes a clip that also carries subtitle tracks, so that there is something to read cues from.
     *
     * <p><b>The cues are generated rather than supplied</b>, the same way the tones are: each one's
     * text names its track and its own index ({@code "T0 C3"}), so a reader holding the wrong
     * track, or a cue from where the film used to be, sees <em>which</em> rather than having to
     * infer it. They are contiguous, one per ten pictures, so at every instant of the clip exactly
     * one cue is on screen and a seek assertion is about a string rather than about an interval.
     *
     * <p>The first cue of each track carries ASS override tags and a hard line break and the rest
     * do not, which is what makes the markup rule assertable in both directions from one file.
     *
     * <p>Blocks for the whole encode and has no asynchronous form, for the reason stated on the
     * overload that takes a single soundtrack; more tracks make it longer.
     *
     * @param subtitleLanguages one entry per subtitle track, each a tag or null to state none
     * @throws FfmpegException if this build has no encoder, if the file cannot be written, or if a
     *                         track's tones would not fit below half the sample rate
     */
    public static void writeClip(Path path, ClipCodec codec, int width, int height, int frames,
                                 int rateNum, int rateDen, List<ClipAudioTrack> audio,
                                 int sampleRate, List<String> subtitleLanguages) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(audio, "audio");
        Objects.requireNonNull(subtitleLanguages, "subtitleLanguages");
        FfmpegLibrary.require();
        if (!FfmpegNative.canWrite()) {
            throw new FfmpegException("this FFmpeg build has no encoder: the published payload "
                    + "decodes only. A 'full' build from limn-ffmpeg-natives "
                    + "(scripts/build-ffmpeg.sh --profile full, in a sibling clone) is what the "
                    + "writer tests use");
        }
        int[] channels = new int[audio.size()];
        String[] languages = new String[audio.size()];
        for (int track = 0; track < audio.size(); track++) {
            channels[track] = audio.get(track).channels();
            languages[track] = audio.get(track).language();
        }
        FfmpegNative.writeClip(path.toAbsolutePath().toString(), codec.id, width, height, frames,
                rateNum, rateDen, channels, languages, sampleRate,
                subtitleLanguages.toArray(new String[0]));
    }

    /**
     * <p>Reads a string out of the linked library, except possibly once: the first call in a
     * process may be the one that links it, which on a build carrying the libraries in its jar
     * extracts tens of megabytes under a global lock. Ask it on a worker, or after
     * {@link FfmpegVideoDecoder#warmUp()} has paid for the link somewhere else.
     *
     * @return the licence, version and configure line of the linked FFmpeg, newline separated:
     *         what {@code LicenceTest} reads to assert that this build is still LGPL and still
     *         opens nothing but files
     * @throws FfmpegException if the native library is not loaded
     */
    public static String identity() {
        FfmpegLibrary.require();
        return FfmpegNative.identity();
    }

    /**
     * Every codec and container the linked libraries actually hold, read out of them rather than
     * recited from the build script, one per line, as {@code decoder:h264}, {@code encoder:mpeg4},
     * {@code demuxer:mov} or {@code muxer:mp4}.
     *
     * <p>A configure flag is a claim and a linked symbol is a fact. A build whose decoder list
     * quietly lost an entry (dropped in an edit, or refused by configure because a dependency was
     * switched off) would still advertise the codec and then fail to open the file, which is the
     * one failure this answers.
     *
     * <p>Enumerates out of the linked library, and carries the same first-call cost as
     * {@link #identity()}: the first consultation in a process may be the one that links it.
     *
     * @throws FfmpegException if the native library is not loaded
     */
    public static String components() {
        FfmpegLibrary.require();
        return FfmpegNative.components();
    }

    /** @return whether {@code file} exists, is readable and begins like an ISO base media file */
    static boolean looksLikeIsoBaseMedia(Path file) {
        byte[] head = new byte[12];
        try (var in = Files.newInputStream(file)) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < 8) {
                return false;
            }
        } catch (Exception unreadable) {
            return false;
        }
        String tag = new String(head, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        // ftyp is what every MP4 and every modern QuickTime file begins with; the rest are the
        // top-level atoms an older QuickTime file may lead with instead. Reading twelve bytes is
        // as far as supports() is allowed to go.
        return switch (tag) {
            case "ftyp", "moov", "mdat", "free", "skip", "wide", "pnot" -> true;
            default -> false;
        };
    }

    /**
     * @return whether {@code file} exists, is readable and begins with the EBML header every
     *         Matroska and WebM file starts with. Four bytes, because a container that is
     *         identified by a magic number should be identified by that and not by its extension:
     *         .webm and .mkv are the same format and both are ordinary Matroska here.
     */
    static boolean looksLikeMatroska(Path file) {
        byte[] head = new byte[4];
        try (var in = Files.newInputStream(file)) {
            if (in.readNBytes(head, 0, head.length) < head.length) {
                return false;
            }
        } catch (Exception unreadable) {
            return false;
        }
        return (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
    }
}
