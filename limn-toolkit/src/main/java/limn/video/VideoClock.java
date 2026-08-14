package limn.video;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Decides, one candidate picture at a time, whether to show it, keep the current one, or throw it
 * away: a player's whole timing policy, as arithmetic.
 *
 * <p>Pure logic: no threads, no input, and no clock of its own. The wall clock is supplied, so a
 * test drives hours of playback with no real time passing and a player drives it from an audio
 * position instead.
 *
 * <p><b>Timeline.</b> Presentation times and the master's position must be on the <em>same</em>
 * timeline. A producer emitting times on a container's native base paired with a master reporting
 * zero-based positions is out by the whole base and no threshold rescues that. With no master any
 * base works, because the timeline is anchored to the first picture this clock is asked about.
 *
 * <p><b>Presentation times must be non-decreasing</b> across successive calls. That is not
 * enforced: a producer emitting them out of order will have them shown out of order. Reordering
 * here would hide a producer's bug inside the timing policy, which is the last place anyone would
 * look for it.
 *
 * <p><b>Memoryless.</b> Each call is judged only on the position reported at that instant. This
 * does not smooth a noisy master; a master whose reported position jitters by more than the
 * earliness window produces a visibly irregular cadence. Smooth the master, not the policy.
 *
 * <p><b>Not thread-safe.</b> Call it from one thread. It touches no shared state and needs no
 * runtime, which is what lets it be tested with nothing installed.
 *
 * <p><b>Allocation-free after construction.</b> No field is a collection or a boxed value, every
 * decision is an interned constant, and deciding creates nothing, so a 60-per-second loop produces
 * no garbage at all.
 */
public final class VideoClock {

    /**
     * Passed as the successor's presentation time when the caller has nothing queued behind the
     * candidate. Equal to the unknown-timestamp sentinel a frame reports, so a successor whose own
     * time is unknown correctly licenses no drop.
     */
    public static final long NO_PTS = Long.MIN_VALUE;

    /**
     * Half of a 60 Hz refresh interval, in microseconds: the half-width of the window in which a
     * picture counts as due. A picture arriving up to this early is shown now rather than a tick
     * late; at higher refresh rates that is at most one refresh early, which is not visible. It is
     * also well inside the skew at which a viewer can detect audio and video disagreeing, so no
     * value within a few milliseconds of it would look different.
     */
    public static final long EARLY_MICROS = 8_000;

    /**
     * Microseconds of wall time a master may report an unchanged position before it is declared
     * stalled and the wall clock takes over. A live audio position advances by at least one sample
     * between reads, and two display ticks are hundreds of samples apart, so an unchanged reading
     * means nothing advanced. Long enough that no healthy device is declared dead by a scheduling
     * hiccup, short enough that a viewer sees at most a brief hitch.
     */
    public static final long MASTER_STALL_MICROS = 200_000;

    /**
     * How far a master's position may move away from the wall time that elapsed (in either
     * direction, since a scrub backwards is as much a seek as one forwards) before the move is
     * read as a seek rather than an irregularity. Above audio buffering granularity and above any
     * plausible stall of the calling loop; below the smallest step a transport control makes.
     */
    public static final long MASTER_JUMP_MICROS = 500_000;

    /**
     * What the caller must do with the picture it just asked about.
     *
     * <p>A queue is drained with exactly this loop, which terminates because a drop is returned only
     * when the call named a strictly better candidate:
     *
     * <pre>{@code
     * VideoFrame head = queue.peek();
     * while (head != null) {
     *     VideoFrame next = queue.peekSecond();
     *     Decision d = clock.decide(head.ptsMicros(),
     *                               next != null ? next.ptsMicros() : VideoClock.NO_PTS);
     *     if (d == Decision.DROP) { queue.remove(); head.release(); head = queue.peek(); continue; }
     *     if (d == Decision.PRESENT) { show(head); queue.remove(); }
     *     break;
     * }
     * }</pre>
     */
    public enum Decision {

        /** Show this picture. The only decision that costs a repaint. */
        PRESENT,

        /**
         * Do nothing at all (no repaint, no queue advance); the same picture is offered again on
         * the next tick. A periodic callback produces no repaint by itself, so a held tick costs
         * nothing; asking for one anyway turns a still picture into a full-rate repaint of the whole
         * window.
         */
        HOLD,

        /**
         * Release this picture unshown and offer the next one. Returned only when the call named a
         * successor whose own moment has also arrived, so a picture is never dropped in favour of
         * nothing: a queue that has run dry always shows its last picture, however late, and the
         * screen never goes blank.
         */
        DROP
    }

    /** A media position source on the same timeline as the pictures' presentation times. */
    @FunctionalInterface
    public interface MasterClock {

        /** @return the current media position in microseconds; need not be monotonic, since a seek moves it */
        long positionMicros();
    }

    private final LongSupplier nanoClock;

    private MasterClock master;
    private boolean paused;
    private boolean started;

    /** Wall and media readings that the free-running fallback interpolates between. */
    private long anchorWallMicros;
    private long anchorMediaMicros;

    private long lastMasterMicros;

    /**
     * Wall time of the last reading that <em>differed</em> from the one before it, not of the last
     * reading taken. Both the stall test and the jump test measure from here: dating them from the
     * last read instead makes every unchanged poll re-arm them, so a master reporting in coarse
     * steps has each step scored against one poll interval and is counted as seeking.
     */
    private long lastMasterChangeWallMicros;
    private boolean masterStalled;

    /**
     * Set by {@link #seekTo(long)} and cleared by {@link #reset()}: whether the anchor holds a
     * position a caller stated rather than one nothing has established yet. It is what lets
     * {@link #positionMicros()} answer with the seek target before the first picture arrives,
     * instead of re-reading a master that may not have moved yet.
     */
    private boolean seekAnchored;

    private long pauseWallMicros;
    private long pausedPositionMicros;

    private long lastDriftMicros;
    private long presented;
    private long dropped;
    private long held;
    private long jumps;
    private long stalls;

    /** Drives from the system's monotonic clock until a master is installed. */
    public VideoClock() {
        this(System::nanoTime);
    }

    /**
     * @param nanoClock a monotonic nanosecond source, read exactly once per decision: the seam that
     *                  lets a test run a whole stream instantly by handing over a counter
     * @throws NullPointerException if {@code nanoClock} is null
     */
    public VideoClock(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * Installs the master position source, or null to free-run on the wall clock. Reads the new
     * master's position once, here, which arms the stall timer immediately: a master that is inert on
     * arrival (a handle from a machine with no audio device, whose position is a constant zero) is
     * then detected while buffering rather than partway into the picture. Removing a master hands the
     * timeline to the wall clock from the position reached so far, without a step.
     *
     * <p>While paused the wall references are dated at the instant the pause began rather than now,
     * so resuming moves them forward by the paused span exactly once. Dating them at the live clock
     * instead would have the resume add a span that had already elapsed before the master arrived,
     * and the first reading afterwards would be scored against it and counted as a seek.
     */
    public void setMaster(MasterClock newMaster) {
        long wall = paused ? pauseWallMicros : nanoClock.getAsLong() / 1_000L;
        if (newMaster != null) {
            lastMasterMicros = newMaster.positionMicros();
            lastMasterChangeWallMicros = wall;
            anchorMediaMicros = lastMasterMicros;
        } else if (started) {
            anchorMediaMicros = positionAtWall(wall);
        }
        anchorWallMicros = wall;
        masterStalled = false;
        master = newMaster;
    }

    /**
     * Freezes the timeline. Mandatory around a pause: a paused master reports a frozen position,
     * which is indistinguishable from a stalled device, and without this the wall clock would take
     * over and run the video straight through the pause. While paused every decision is a hold, the
     * master is not read at all, and stall detection is suspended; on resuming, every wall
     * reference moves forward by the paused span, so the pause costs no media time.
     *
     * <p>Reads the wall clock once on each transition, and the master once when a pause begins.
     * Setting the state it already has does nothing at all.
     */
    public void setPaused(boolean newPaused) {
        if (paused == newPaused) {
            return;
        }
        long wall = nanoClock.getAsLong() / 1_000L;
        if (newPaused) {
            pausedPositionMicros = started ? positionAtWall(wall) : anchorMediaMicros;
            pauseWallMicros = wall;
        } else {
            long pausedFor = wall - pauseWallMicros;
            anchorWallMicros += pausedFor;
            lastMasterChangeWallMicros += pausedFor;
        }
        paused = newPaused;
    }

    /**
     * Forgets the timeline, so the next picture offered is shown and re-anchors it. For a change of
     * stream, or a loop, after the caller has flushed its own queue. The cumulative counters
     * survive: starting over is not a reason to lose a session's history.
     */
    public void reset() {
        started = false;
        seekAnchored = false;
        lastDriftMicros = 0;
    }

    /**
     * Declares that the timeline has been moved to {@code positionMicros} (<b>the master's
     * position included</b>) so that the move is not mistaken for a master that has run away.
     *
     * <p>Without this a seek is indistinguishable from the thing this clock is built to survive: a
     * position that suddenly differs from the wall time elapsed by more than
     * {@link #MASTER_JUMP_MICROS} is counted as a jump, and a player watching {@link #jumpCount()}
     * drops its master on the strength of it. Every scrub would then cost the video its audio, once
     * and permanently.
     *
     * <p><b>The position is taken as fact rather than polled.</b> The master is deliberately not
     * read here: an audio engine repositions asynchronously, so reading it at the instant of the
     * request captures the position being left, and the move to the new one would be scored as a
     * jump a few milliseconds later, the exact failure this exists to prevent. The caller is
     * therefore responsible for having moved the master there, or for having moved it at all; a
     * caller that seeks the pictures and leaves a soundtrack where it was has told this clock
     * something untrue and will see it as a jump on the next decision.
     *
     * <p>Afterwards the next picture offered is shown whatever its timestamp, which re-anchors the
     * timeline on it, and {@link #positionMicros()} reports {@code positionMicros} exactly until
     * then; it does not free-run forward while the pictures are still being found. Works while
     * paused, where it moves the frozen position; the pause is not lifted. The cumulative counters
     * survive, and {@link #jumpCount()} is deliberately not incremented, which is what a player
     * distinguishes a seek from a runaway master by.
     */
    public void seekTo(long positionMicros) {
        long wall = paused ? pauseWallMicros : nanoClock.getAsLong() / 1_000L;
        started = false;
        seekAnchored = true;
        anchorWallMicros = wall;
        anchorMediaMicros = positionMicros;
        pausedPositionMicros = positionMicros;
        lastMasterMicros = positionMicros;
        lastMasterChangeWallMicros = wall;
        masterStalled = false;
        lastDriftMicros = 0;
    }

    /**
     * @param ptsMicros     presentation time of the candidate picture; must not be {@link #NO_PTS}
     * @param nextPtsMicros presentation time of the picture queued behind it (or {@link #NO_PTS}),
     *                      the only thing that can license a drop
     * @return what to do with the candidate
     * @throws IllegalArgumentException if {@code ptsMicros} is {@link #NO_PTS}, because a picture
     *                                  with no timestamp cannot be timed and arithmetic on the
     *                                  sentinel would silently produce a plausible wrong answer
     */
    public Decision decide(long ptsMicros, long nextPtsMicros) {
        long wall = nanoClock.getAsLong() / 1_000L;
        if (ptsMicros == NO_PTS) {
            throw new IllegalArgumentException(
                    "ptsMicros must be a real presentation time, not NO_PTS");
        }
        if (paused) {
            held++;
            return Decision.HOLD;
        }
        if (!started) {
            started = true;
            anchorWallMicros = wall;
            anchorMediaMicros = (master != null && !masterStalled) ? lastMasterMicros : ptsMicros;
            lastDriftMicros = 0;
            presented++;
            return Decision.PRESENT;
        }
        long now = positionAt(wall);
        if (now - ptsMicros < -EARLY_MICROS) {
            held++;
            return Decision.HOLD;
        }
        if (nextPtsMicros != NO_PTS && now >= nextPtsMicros - EARLY_MICROS) {
            dropped++;
            return Decision.DROP;
        }
        lastDriftMicros = ptsMicros - now;
        presented++;
        return Decision.PRESENT;
    }

    /** As deciding with no successor named, which therefore never returns a drop. */
    public Decision decide(long ptsMicros) {
        return decide(ptsMicros, NO_PTS);
    }

    /**
     * @return the current media position in microseconds. Before the first decision, the position a
     *         {@link #seekTo(long)} moved to, or failing that the installed master's position, or 0
     *         when none is installed: nothing has anchored the timeline yet, so a stream whose
     *         presentation times start at a non-zero container base reads 0 until its first picture
     *         is judged. While paused, the position the timeline was frozen at.
     *         Otherwise the master's, or the wall-driven fallback's while the master is stalled or
     *         absent. It reads only the master before the first decision, nothing at all while paused,
     *         and otherwise the wall clock plus the master when one is installed and running; it
     *         changes nothing either way, because stall and jump detection happen only in
     *         {@link #decide(long, long)}.
     */
    public long positionMicros() {
        if (paused) {
            return pausedPositionMicros;
        }
        if (!started) {
            if (seekAnchored) {
                return anchorMediaMicros;
            }
            MasterClock current = master;
            return current != null ? current.positionMicros() : 0L;
        }
        return positionAtWall(nanoClock.getAsLong() / 1_000L);
    }

    /**
     * @return whether the master has reported an unchanged position for longer than
     *         {@link #MASTER_STALL_MICROS}; goes false again by itself at the first decision whose
     *         reading has moved
     */
    public boolean isMasterStalled() {
        return masterStalled;
    }

    /**
     * @return presentation time minus position at the last picture shown, in microseconds; positive
     *         means the picture ran ahead of the clock. It stays within one frame interval while
     *         the policy is tracking, and grows without bound when the master's rate is not the
     *         stream's. A detected master jump clears it, as do {@link #reset()} and
     *         {@link #seekTo(long)}, so a player watching drift to decide whether to resync reads 0
     *         right after a seek until the next picture is shown; the jump counter, not this, is
     *         what says a master moved on its own.
     */
    public long driftMicros() {
        return lastDriftMicros;
    }

    /** @return pictures shown since construction; a reset does not clear it */
    public long presentedFrames() {
        return presented;
    }

    /** @return pictures dropped unshown since construction */
    public long droppedFrames() {
        return dropped;
    }

    /** @return decisions to keep the current picture since construction */
    public long heldFrames() {
        return held;
    }

    /**
     * @return times a master reading differed from the wall time elapsed since the previous reading
     *         that moved, in either direction, by more than {@link #MASTER_JUMP_MICROS}; a scrub
     *         backwards counts exactly as a seek forwards does
     */
    public long jumpCount() {
        return jumps;
    }

    /** @return times the master was declared stalled */
    public long stallCount() {
        return stalls;
    }

    /**
     * The position for this decision, reading the master at most once and updating stall and jump
     * state from that single reading. Reading it twice inside one decision would let the two halves
     * of the same decision disagree, which is the wrong edit this shape prevents.
     */
    private long positionAt(long wall) {
        MasterClock current = master;
        if (current == null) {
            return anchorMediaMicros + (wall - anchorWallMicros);
        }
        long position = current.positionMicros();
        if (position != lastMasterMicros) {
            long elapsed = wall - lastMasterChangeWallMicros;
            if (Math.abs(position - lastMasterMicros - elapsed) > MASTER_JUMP_MICROS) {
                jumps++;
                lastDriftMicros = 0;
            }
            masterStalled = false;
            lastMasterMicros = position;
            lastMasterChangeWallMicros = wall;
            anchorWallMicros = wall;
            anchorMediaMicros = position;
            return position;
        }
        if (wall - lastMasterChangeWallMicros >= MASTER_STALL_MICROS) {
            if (!masterStalled) {
                stalls++;
                masterStalled = true;
                // Not back-dated to when the master actually froze: recovering that span would be
                // spent immediately as a burst of dropped pictures, in exactly the common
                // no-audio-device case.
                anchorWallMicros = wall;
                anchorMediaMicros = lastMasterMicros;
            }
            return anchorMediaMicros + (wall - anchorWallMicros);
        }
        return position;
    }

    /** The position at {@code wall} without touching stall, jump or anchor state. */
    private long positionAtWall(long wall) {
        MasterClock current = master;
        if (current == null || masterStalled) {
            return anchorMediaMicros + (wall - anchorWallMicros);
        }
        return current.positionMicros();
    }
}
