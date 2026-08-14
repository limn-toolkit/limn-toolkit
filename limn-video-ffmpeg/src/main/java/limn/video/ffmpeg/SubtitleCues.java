package limn.video.ffmpeg;

import java.util.ArrayList;
import java.util.List;

/**
 * The cues of a container's selected subtitle track, asked for by position: <em>what is on screen
 * at this moment</em>.
 *
 * <p>This is the whole of the subtitle SPI. It hands over text and timing and nothing else: no
 * widget, no style, no placement, and no opinion about whether a viewer wants subtitles at all.
 * Drawing a cue is the application's, with whatever text stack it already uses:
 *
 * <pre>{@code
 * media.selectSubtitles(0);
 * // ... and in the paint, with the time the player is showing:
 * for (SubtitleCues.Cue cue : media.subtitles().activeAt(player.positionMicros())) {
 *     drawCentredNearTheBottom(cue.text());
 * }
 * }</pre>
 *
 * <h2>Why by position, and what follows from it</h2>
 *
 * <p>A picture is presented at an instant; a cue occupies an interval, and "what is on screen at
 * <i>t</i>" is the only question an application actually has. Reading cues the way pictures are
 * read would hand every caller the same thirty lines (hold them, drop them on a seek, pick the
 * current one), and the seek half is the half that is easy to get wrong.
 *
 * <p><b>Cues follow the pictures.</b> This object never demultiplexes: it decodes what the
 * container has already read on the video track's behalf. A subtitle track is silent between lines,
 * so reading forward to find the next cue would read through the whole gap (minutes of a film) to
 * answer a question about now. The consequence is worth stating plainly: <b>a container whose video
 * nobody is reading produces no cues</b>. A player running normally reads far enough ahead that a
 * cue is decoded well before the picture it belongs to is shown.
 *
 * <p><b>A seek empties the window.</b> Whenever the container is really repositioned the cues held
 * here stop describing the film, and they are dropped before the next answer rather than lingering
 * over the new position. One artefact survives that and cannot be removed: a cue that <em>straddles</em>
 * the target (begins before it and ends after it) is only recovered if its packet lies after the
 * point the container landed on. A seek lands at or before its target and decodes forward, so it
 * usually is; when it is not, the next cue is the first one seen.
 *
 * <h2>Costs</h2>
 *
 * <p>{@link #activeAt} returns <b>the same list instance</b> for as long as the active set has not
 * changed, so a paint loop polling every frame allocates nothing in the steady state. The list is
 * unmodifiable and is never mutated in place; a new one appears only when a cue starts or ends.
 *
 * <p>Cues that ended well before the last time asked about are discarded, so a two-hour film does
 * not accumulate its whole script. Asking about a time far behind the one asked about last is
 * therefore answered from what is still held, which is what a seek is for.
 *
 * <h2>Threads</h2>
 *
 * <p>Any thread, one at a time, because every entry point is synchronised on this object. In
 * practice that is whichever thread paints. This is <em>not</em> the video decode thread's: the
 * pictures and the cues are pulled by different callers, and the container is what makes that safe.
 */
public final class SubtitleCues {

    /**
     * What {@link Cue#endMicros()} answers for a cue whose container stated no duration: it is
     * shown until the next cue begins, and no next cue has been read yet. Deliberately the largest
     * long, so that an ordinary {@code micros < cue.endMicros()} treats it as still on screen
     * rather than as already gone.
     */
    public static final long END_UNKNOWN = Long.MAX_VALUE;

    /**
     * How far behind the last time asked about a cue is kept before it is discarded. Generous
     * enough that a scrub of a few seconds backwards is still answered from the window, and small
     * enough that a film's whole script is never held.
     */
    private static final long RETAIN_MICROS = 30_000_000L;

    /**
     * One subtitle cue: its text, and the interval it is on screen for.
     *
     * @param text        the cue, as <b>plain text</b>. Subtitle formats carry markup (an ASS
     *                    dialogue line has override tags like <code>{\an8}</code> for placement),
     *                    and none of it survives to here: the leading dialogue fields, the
     *                    brace-delimited override runs and the escapes are all removed by the
     *                    decoder side, {@code \N} becoming a line break and {@code \n} and
     *                    {@code \h} a space. What arrives is what an ordinary text stack can draw,
     *                    which is the point: a cue handed over with its markup intact is a cue an
     *                    application draws literally. It may contain line breaks and it is never
     *                    empty
     * @param startMicros when it appears, on the same timeline as a picture's presentation time:
     *                    the container's start time is subtracted from both, so a cue and the
     *                    picture it belongs over carry comparable numbers
     * @param endMicros   when it goes, exclusive; {@link #END_UNKNOWN} for a cue the container gave
     *                    no duration, which is resolved to the next cue's start as soon as one is
     *                    read
     */
    public record Cue(String text, long startMicros, long endMicros) {
    }

    private final FfmpegMedia media;

    /** In arrival order, which for a container's own track is start order. */
    private final List<Cue> window = new ArrayList<>();

    private final long[] out = new long[FfmpegNative.CUE_LENGTH];

    /** The epoch the window was filled under. A different one means a seek or a track change. */
    private long epoch = -1;

    /** What the last {@link #activeAt} answered, handed back again while it stays correct. */
    private List<Cue> active = List.of();

    /**
     * The half-open interval over which {@link #active} is the right answer. Half of what makes
     * polling free: inside it, and with nothing new decoded, the answer cannot have changed.
     */
    private long validFrom = 1;
    private long validTo = 0;

    SubtitleCues(FfmpegMedia media) {
        this.media = media;
    }

    /**
     * The cues on screen at {@code micros}.
     *
     * <p>Usually empty or one. More than one is a container showing two lines that were authored
     * separately; they arrive in the order the file states them, which is the order to draw them
     * in.
     *
     * @param micros a presentation time, on the same timeline as {@code VideoFrame.ptsMicros()}
     * @return an unmodifiable list, never null. <b>The same instance</b> as the last call returned
     *         whenever the active set has not changed, so this may be called every frame
     */
    public synchronized List<Cue> activeAt(long micros) {
        boolean changed = pump();
        if (!changed && micros >= validFrom && micros < validTo) {
            return active;
        }
        prune(micros);
        return recompute(micros);
    }

    /**
     * @return every cue currently held, in start order: those still to come, the ones on screen,
     *         and the recent past that has not been discarded yet. For a diagnostic and for a
     *         caller that wants to draw a strip of what is ahead; {@link #activeAt} is what a paint
     *         asks
     */
    public synchronized List<Cue> held() {
        pump();
        return List.copyOf(window);
    }

    /**
     * Drains whatever the container has decoded since the last call and empties the window if the
     * container was repositioned under it.
     *
     * @return whether anything at all changed, which is what makes the cached answer stale
     */
    private boolean pump() {
        boolean changed = false;
        for (;;) {
            String text = media.readCue(out);
            if (out[FfmpegNative.C_EPOCH] != epoch) {
                // A seek, a track change, or the first call. Either way the cues held describe
                // somewhere the film no longer is.
                epoch = out[FfmpegNative.C_EPOCH];
                window.clear();
                changed = true;
            }
            int status = (int) out[FfmpegNative.C_STATUS];
            if (status == FfmpegNative.CUE_READY && text != null) {
                append(new Cue(text, out[FfmpegNative.C_START_MICROS],
                        out[FfmpegNative.C_END_MICROS] == Long.MIN_VALUE
                                ? END_UNKNOWN : out[FfmpegNative.C_END_MICROS]));
                changed = true;
            }
            if (status == FfmpegNative.CUE_NONE) {
                // Nothing more is queued. Not the end of the track: the next packets arrive as the
                // pictures do.
                return changed;
            }
        }
    }

    /**
     * Appends a cue, closing the one before it if the container gave that one no duration.
     *
     * <p>That is the only reading of an open-ended cue that neither invents a length nor shows
     * nothing, and it is why {@link #END_UNKNOWN} is published rather than guessed at: a cue with
     * no successor yet really is on screen, and it stops being so the moment one arrives.
     */
    private void append(Cue cue) {
        if (!window.isEmpty()) {
            int last = window.size() - 1;
            Cue previous = window.get(last);
            if (previous.endMicros() == END_UNKNOWN && cue.startMicros() > previous.startMicros()) {
                window.set(last, new Cue(previous.text(), previous.startMicros(),
                        cue.startMicros()));
            }
        }
        window.add(cue);
    }

    private void prune(long micros) {
        long horizon = micros - RETAIN_MICROS;
        while (!window.isEmpty() && window.get(0).endMicros() <= horizon) {
            window.remove(0);
        }
    }

    /**
     * The active set at {@code micros}, and the interval over which it stays the answer.
     *
     * <p>{@link #validTo} is the earliest moment anything could change: the first end of an active
     * cue, or the first start of one that has not begun. {@link #validFrom} is the same backwards.
     * Together they are what lets a paint loop poll without recomputing.
     */
    private List<Cue> recompute(long micros) {
        List<Cue> found = null;
        long from = Long.MIN_VALUE;
        long to = Long.MAX_VALUE;
        for (Cue cue : window) {
            if (micros >= cue.startMicros() && micros < cue.endMicros()) {
                if (found == null) {
                    found = new ArrayList<>(2);
                }
                found.add(cue);
                from = Math.max(from, cue.startMicros());
                to = Math.min(to, cue.endMicros());
            } else if (micros < cue.startMicros()) {
                to = Math.min(to, cue.startMicros());
            } else {
                from = Math.max(from, cue.endMicros());
            }
        }
        validFrom = from;
        validTo = to;
        active = found == null ? List.of() : List.copyOf(found);
        return active;
    }

    /** Called by the container when a selection changes, so the next answer cannot be the old
     *  track's even if nothing has been decoded on the new one yet. */
    synchronized void reset() {
        window.clear();
        active = List.of();
        validFrom = 1;
        validTo = 0;
        epoch = -1;
    }
}
