package limn.video;

import limn.sound.Playback;

import java.util.Objects;

/**
 * Reads a sounding audio playback's position as a {@link VideoClock.MasterClock}, so pictures are
 * timed by the device that is actually making noise rather than by the wall clock. Audio is the
 * right master because a dropped picture is invisible for a sixtieth of a second and a gap in sound
 * is audible at a millisecond; video therefore follows audio, never the reverse.
 *
 * <pre>{@code
 * Playback track = Sounds.stream(audioSource, PlayOptions.DEFAULTS.withBus(AudioBus.MUSIC));
 * clock.setMaster(new AudioMasterClock(track));
 * }</pre>
 *
 * <p><b>Units.</b> {@link Playback#positionSeconds()} reports seconds as a {@code double} and a
 * master reports whole microseconds, so every reading is rounded to the nearest microsecond. The
 * error is bounded at half a microsecond and does <em>not</em> accumulate: each reading is an
 * absolute position and nothing here adds an interval to a previous one, which is the arithmetic
 * that turns a rate like 30000/1001 into a picture of drift every few minutes.
 *
 * <p><b>Timeline.</b> The position is measured from the start of the audio track. Pictures must be
 * timed on that same timeline: a stream whose presentation times start at a container's native
 * base, paired with this, is out by the whole base and no threshold recovers it.
 *
 * <p><b>What it does when the position is not a position.</b> A handle from a machine with no audio
 * device reports a constant zero, a finished playback reports zero, and a playback that has not
 * started producing one yet reports zero. All three read as a master that is not advancing, which
 * {@link VideoClock} already answers: after {@link VideoClock#MASTER_STALL_MICROS} it declares the
 * master stalled and drives the timeline from the wall clock instead, so the video plays at the
 * right rate rather than not at all. A reading that is not a finite positive number (which no
 * shipped engine produces, and which a broken one would turn into either a frozen picture or a
 * burst of dropped ones) is reported as zero for the same reason.
 *
 * <p><b>A looping track wraps.</b> An engine that rewinds a stream at the end of its data reports
 * in-track time, so the position falls back to near zero at each wrap. That is a backwards move of
 * the whole track, far above {@link VideoClock#MASTER_JUMP_MICROS}, and the clock counts it as a
 * seek and re-anchors, which is correct only if the pictures wrapped at that instant too. Two
 * tracks of different lengths cannot share one timeline across a wrap; whoever loops them decides
 * what to do about it, and doing nothing means the video holds until the audio catches up to it.
 *
 * <p><b>Cost.</b> Reading a position is not free and is not lock-free: a streaming engine composes
 * it from what its service thread has accounted for plus the device's offset into what is still
 * queued, under the engine's own monitor. {@link VideoClock} reads a master exactly once per
 * decision for that reason. An engine may also report in steps coarser than the display refreshes,
 * so a caller will see the same reading twice in a row; the clock's stall and jump timers are dated
 * from the last reading that <em>changed</em> rather than from the last one taken, which is what
 * keeps a coarse-stepping device from being counted as one that seeks.
 *
 * <p>Immutable and stateless, so any thread may read it and two clocks may share one.
 */
public final class AudioMasterClock implements VideoClock.MasterClock {

    private final Playback playback;

    /**
     * @param playback the handle to follow, including {@link Playback#NONE}, which reports zero
     *                 forever and is therefore detected as a stalled master rather than treated as
     *                 an error
     * @throws NullPointerException if {@code playback} is null
     */
    public AudioMasterClock(Playback playback) {
        this.playback = Objects.requireNonNull(playback, "playback");
    }

    /** @return the handle being followed, never null */
    public Playback playback() {
        return playback;
    }

    /**
     * @return the playback's position in microseconds from the start of its track, rounded to
     *         nearest, or 0 when it reports nothing usable
     */
    @Override
    public long positionMicros() {
        double seconds = playback.positionSeconds();
        // The ordering test covers NaN and negatives, because NaN fails every comparison; the
        // finite test covers infinity, which passes the ordering test and would otherwise round to
        // a position at the end of time, a jump the clock could never recover from.
        if (!(seconds > 0) || !Double.isFinite(seconds)) {
            return 0L;
        }
        return Math.round(seconds * 1_000_000.0);
    }

    @Override
    public String toString() {
        return "AudioMasterClock[" + positionMicros() + "us]";
    }
}
