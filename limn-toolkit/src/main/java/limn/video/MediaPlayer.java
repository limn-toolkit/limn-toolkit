package limn.video;

import limn.backend.CrashPhase;
import limn.backend.Crashes;
import limn.concurrent.Ui;
import limn.sound.AudioStreamSource;
import limn.sound.PlayOptions;
import limn.sound.Playback;
import limn.sound.Sounds;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays a video stream: a decode thread ahead of a small ring of pictures, a {@link VideoClock}
 * timing them, and an optional audio track that becomes the clock's master. What a
 * {@link VideoStreamSource} lacks to be watchable (somewhere to decode that is not the thread
 * drawing, somewhere to keep a picture that is not yet due, and something to be in time with) is
 * exactly this.
 *
 * <pre>{@code
 * VideoStreamSource stream = Videos.open(file);       // the application's decoder, and its stream
 * MediaPlayer player = new MediaPlayer(stream);
 * player.setAudio(track, PlayOptions.DEFAULTS.withBus(AudioBus.MUSIC));
 * player.start();
 * // ... and when the application is finished:
 * player.close();
 * stream.close();                                     // in this order, and only in this order
 * }</pre>
 *
 * <h2>Who closes what</h2>
 *
 * <p><b>The video stream is the caller's, opened by the caller and closed by the caller.</b> This
 * player never closes it. That is the same rule a view follows, for the same reason: a stream may
 * outlive a player, be shown in two places, or be handed to a second player afterwards. What the
 * player does promise is that {@link #close()} returns only once its decode thread has stopped
 * touching the stream, so closing it after that is safe and closing it before is a decode against
 * a torn down decoder.
 *
 * <p><b>The audio track is this player's</b> from {@link #setAudio} onwards, and is closed exactly
 * once: by the audio engine when playback ends or is stopped, or by {@link #close()} when playback
 * never started. There is no path on which the caller should close it and no path on which it
 * leaks. That asymmetry with video is not a preference: handing a source to an audio engine
 * transfers it by contract, including on every failure path, so a player that also closed it would
 * be closing it twice.
 *
 * <h2>Threads</h2>
 *
 * <ul>
 *   <li><b>The UI thread</b>: every configuration method, {@link #start()}, {@link #pause()},
 *       {@link #resume()}, {@link #stop()}, {@link #restart()},
 *       {@link #seek(long, VideoStreamSource.SeekMode)}, {@link #close()} and
 *       {@link #takePicture()}. Asserted, because the clock is not thread-safe and a picture handed
 *       to two threads is released twice.</li>
 *   <li><b>The decode thread</b>: {@link #decodeStep()} and nothing else. This player starts one of
 *       its own unless told not to; the stream is read from there and never from a thread that is
 *       drawing.</li>
 *   <li><b>Any thread</b>: {@link #state()}, {@link #isEnded()}, {@link #failure()},
 *       {@link #decodedFrames()} and {@link #bufferedPictures()}. They are reads of volatile or
 *       locked state, for a status line or a log. {@link #positionMicros()}, {@link #hasAudio()},
 *       {@link #audio()} and {@link #underruns()} are the UI thread's, because they read the clock
 *       or state only that thread writes.</li>
 * </ul>
 *
 * <p><b>One decode thread per player</b>, started at {@link #start()} and joined at
 * {@link #close()}. A shared pool was rejected: {@link VideoStreamSource#readFrame()} may block for
 * a whole decode and promises no bound, so a pool of any size lets one stalled stream stop the
 * others, and a page of thumbnails that never appear is a failure nobody can see the cause of. The
 * honest cost is stated instead: twelve videos on a page are twelve threads, and the expensive part
 * of that is the twelve concurrent decodes, not the twelve threads.
 *
 * <h2>Pictures</h2>
 *
 * <p>The player holds at most {@link #ringCapacity()} decoded pictures. It allocates none of them:
 * the pool belongs to the stream that produced them, so the ring holds borrowed references and
 * every one of them is released exactly once: when it is shown and handed on, when the clock drops
 * it, or when the player is stopped or closed.
 *
 * <p><b>Full</b>: the decode thread stops reading and waits. It does not block forever on a
 * condition it might miss; it waits with a bound, so a lost signal costs latency rather than a
 * player that never plays again, and every consumed picture signals it awake. Holding pictures the
 * consumer has not asked for would also starve the stream's own pool, which needs a free slot to
 * decode into: the effective read-ahead is therefore the smaller of this ring and the stream's
 * spare slots, and a stream reporting {@link VideoStreamSource.Read#PENDING} because everything is
 * held is answered the same way as one that is simply not ready.
 *
 * <p><b>Empty</b>: {@link #takePicture()} returns null and the caller keeps showing what it has.
 * The screen never goes blank for want of a picture, and each dry spell is counted once by
 * {@link #underruns()}.
 *
 * <p>Because the ring can name the picture behind the candidate, this player can <em>drop</em>,
 * unlike a view holding a single picture. A player that has fallen behind skips pictures whose
 * moment has already passed rather than showing them late.
 *
 * <h2>Sound, and being in time with it</h2>
 *
 * <p>With an audio track that sounds, its position becomes the clock's master through
 * {@link AudioMasterClock} and the pictures follow it. With no track at all (which is every stream
 * a pure-Java decoder produces, so it is the common case and not the corner), no master is
 * installed and the clock free-runs on the wall clock at the stream's own rate.
 *
 * <p>A track that does not sound is treated as no master rather than as an error: a machine with no
 * audio device yields a handle whose position is a constant zero, which this player detects at
 * admission and declines to follow, so the video plays at the right rate rather than not at all.
 * <p><b>A master is dropped the moment the pictures can no longer follow it</b>, and the wall clock
 * carries on from the position already reached, without a step. Two things cause it: a handle that
 * stops advancing (a device that went away, a track that ended before the pictures did), which the
 * clock's own stall detection catches; and a handle that moves somewhere the pictures are not,
 * which in practice means a looping track wrapping to its start. Neither is recoverable by holding
 * pictures and waiting, which is what a clock left following them would do.
 *
 * <h2>Seeking</h2>
 *
 * <p>{@link #seek(long, VideoStreamSource.SeekMode)} moves the pictures, the soundtrack and the
 * timeline together, and is a request rather than a completed operation: the ring is released and
 * the clock is moved before it returns, and the stream is repositioned by the decode thread, which
 * may be inside a whole decode at that moment. It is cheap enough to drive from a control being
 * dragged, provided the caller drags in {@link VideoStreamSource.SeekMode#KEYFRAME} and lands in
 * {@link VideoStreamSource.SeekMode#EXACT}.
 *
 * @see VideoClock for the timing policy this player supplies a master and a queue to
 */
public final class MediaPlayer implements AutoCloseable {

    /** Pictures held ahead of the one being shown, when nothing says otherwise. */
    public static final int DEFAULT_RING_CAPACITY = 3;

    /**
     * Longest the decode thread waits for room or for a picture before looking again. A consumed
     * picture signals it immediately, so this is the bound on a <em>missed</em> signal and not a
     * polling interval: the difference between a player that hesitates and one that never plays.
     */
    private static final long PARK_MILLIS = 20;

    private static final AtomicInteger THREAD_SERIAL = new AtomicInteger();

    /** Where a player is in its life. */
    public enum State {

        /** Built, or stopped: nothing is decoding and nothing is sounding. */
        IDLE,

        /** Decoding, timing and handing out pictures. */
        PLAYING,

        /** Frozen: the clock is paused, the audio is paused, and the ring keeps filling. */
        PAUSED,

        /** The stream ended and every decoded picture has been handed out. */
        ENDED,

        /** A decode threw. {@link #failure()} has it; {@link #restart()} clears it. */
        FAILED,

        /** Closed. Nothing works afterwards, and the video stream is now the caller's to close. */
        CLOSED
    }

    /** What one pass of the decode loop did: what a host driving it needs to know to pace itself. */
    public enum Step {

        /** A picture reached the ring. Call again straight away. */
        PRODUCED,

        /** Nothing to do this instant: the ring is full, or the stream has no picture ready. Wait. */
        IDLE,

        /**
         * Nothing more is coming from where the stream is now: it ended, the player stopped, or a
         * decode threw. Not permanent (a seek, or looping being switched on, makes production
         * resume), so a host driving this itself waits and asks again rather than giving up its
         * thread. Only {@link #close()} ends it for good.
         */
        DONE
    }

    private final VideoStreamSource video;
    private final Object lock = new Object();

    private VideoClock clock = new VideoClock();
    private AudioStreamSource audio;
    private PlayOptions audioOptions = PlayOptions.DEFAULTS;
    private int ringCapacity = DEFAULT_RING_CAPACITY;
    private boolean ownsDecodeThread = true;

    // ---- guarded by lock ----
    private VideoFrame[] ring = new VideoFrame[0];
    private int[] ringPass = new int[0];
    private int head;
    private int count;
    private int decodePass;
    private boolean decoding;
    private boolean sourceEnded;
    private Thread decodeThread;
    private boolean seekRequested;
    private long seekMicros;
    private VideoStreamSource.SeekMode seekMode = VideoStreamSource.SeekMode.EXACT;
    /**
     * Bumped by every seek request. The decode thread reads it before a read and compares after, so
     * a picture decoded from the position being left is released rather than enqueued into a ring
     * that has already been drained for the new one.
     */
    private long seekEpoch;

    // ---- UI thread only ----
    private Playback playback = Playback.NONE;
    private VideoClock.MasterClock master;
    private int presentPass;
    private boolean starved;
    private long underruns;
    /** A seek has landed and its first picture has not been handed over yet; see {@link #takePicture()}. */
    private boolean seekPresentPending;
    /** The clock's jump tally when the master was installed; a change means it moved off timeline. */
    private long lastJumpCount;

    /** Written by two threads; every transition that has a rival goes through {@link #enterState}. */
    private volatile State state = State.IDLE;
    private volatile RuntimeException failure;
    private volatile boolean looping;
    private volatile long decodedFrames;

    /**
     * @param video the stream to play, which stays the caller's to close, after this player is
     *              {@linkplain #close() closed} and not before
     * @throws NullPointerException if {@code video} is null
     */
    public MediaPlayer(VideoStreamSource video) {
        this.video = Objects.requireNonNull(video, "video");
    }

    // ----------------------------------------------------------------- configuration

    /**
     * Gives this player the video's audio track, which it owns from here on: the audio engine
     * closes it when playback ends or is stopped, and {@link #close()} closes it if playback never
     * started. The caller must not close it and must not stream it itself.
     *
     * <p>Null removes a track that has not been started yet, closing it.
     *
     * @param options gain, bus and priority for the track. Looping is this player's to decide, so
     *                {@link PlayOptions#loop()} is overridden by {@link #setLooping(boolean)}.
     * @throws IllegalStateException if this player has already been started
     */
    public MediaPlayer setAudio(AudioStreamSource newAudio, PlayOptions options) {
        Ui.checkUiThread();
        checkIdle("the audio track");
        if (audio != null && audio != newAudio) {
            audio.close();
        }
        audio = newAudio;
        audioOptions = Objects.requireNonNull(options, "options");
        return this;
    }

    /**
     * How many decoded pictures may be held ahead of the one being shown, at least 1. Larger rides
     * out a slower decoder and a busier machine; smaller costs the stream's pool fewer held slots
     * and shortens the pause a stop has to drain. Beyond a handful it buys nothing a stream's own
     * pool can supply.
     *
     * @throws IllegalArgumentException if {@code pictures} is below 1
     * @throws IllegalStateException    if this player has already been started
     */
    public MediaPlayer setRingCapacity(int pictures) {
        Ui.checkUiThread();
        checkIdle("the ring capacity");
        if (pictures < 1) {
            throw new IllegalArgumentException("ringCapacity must be at least 1, got " + pictures);
        }
        ringCapacity = pictures;
        return this;
    }

    /** @return how many decoded pictures may be held ahead of the one being shown */
    public int ringCapacity() {
        return ringCapacity;
    }

    /**
     * Replaces the clock that times the pictures, for a test driving a whole stream with no real
     * time passing, or a host with a timeline of its own. A master installed on it is replaced when
     * this player starts an audio track that sounds.
     *
     * @throws IllegalStateException if this player has already been started
     */
    public MediaPlayer setClock(VideoClock newClock) {
        Ui.checkUiThread();
        checkIdle("the clock");
        clock = Objects.requireNonNull(newClock, "newClock");
        return this;
    }

    /**
     * The clock timing the pictures. Touch it only from the UI thread: it is not thread-safe, and
     * this player reads it from there on every {@link #takePicture()}.
     */
    public VideoClock clock() {
        return clock;
    }

    /**
     * Whether this player starts a decode thread of its own (the default) or leaves
     * {@link #decodeStep()} to the caller, for a host that owns its threads, or a test that wants
     * decoding to happen exactly when it says so.
     *
     * @throws IllegalStateException if this player has already been started
     */
    public MediaPlayer setOwnsDecodeThread(boolean owns) {
        Ui.checkUiThread();
        checkIdle("the decode thread policy");
        ownsDecodeThread = owns;
        return this;
    }

    /**
     * Whether reaching the end rewinds and plays again (default false). Ignored by a stream that
     * {@linkplain VideoStreamSource#canReset() cannot be rewound}. May be changed while playing.
     *
     * <p><b>With an audio track, the master governs the first pass only.</b> At the first loop the
     * pictures' timeline restarts and the track's does not (or does, at its own length, which is
     * not the video's), and one clock cannot be on two timelines at once. The player therefore
     * drops the master there and paces the remaining passes on the wall clock, which keeps them at
     * the right rate instead of holding until the track catches up or racing to catch it. Aligning
     * a loop across both tracks needs a seek, which this does not have.
     */
    public MediaPlayer setLooping(boolean newLooping) {
        Ui.checkUiThread();
        looping = newLooping;
        synchronized (lock) {
            lock.notifyAll(); // a decode thread parked at the end has something to do again
        }
        return this;
    }

    /** @return whether the end rewinds rather than stops */
    public boolean isLooping() {
        return looping;
    }

    // ----------------------------------------------------------------- lifecycle

    /**
     * Starts decoding, starts the audio track if there is one, and begins timing. Resumes instead
     * when paused, and does nothing at all when already playing, ended or failed;
     * {@link #restart()} is what rewinds one of those.
     *
     * <p>Starting the audio track is where its ownership passes to the engine, so it happens once
     * and only once however many times this is called.
     *
     * @throws IllegalStateException if this player is closed
     */
    public void start() {
        Ui.checkUiThread();
        switch (state) {
            case CLOSED -> throw new IllegalStateException("this MediaPlayer is closed");
            case PAUSED -> {
                resume();
                return;
            }
            case PLAYING, ENDED, FAILED -> {
                return;
            }
            case IDLE -> {
            }
        }
        if (ring.length != ringCapacity) {
            ring = new VideoFrame[ringCapacity];
            ringPass = new int[ringCapacity];
        }
        startAudio();
        state = State.PLAYING;
        synchronized (lock) {
            decoding = true;
            sourceEnded = false;
            lock.notifyAll();
            if (ownsDecodeThread && decodeThread == null) {
                decodeThread = new Thread(this::decodeLoop,
                        "limn-video-decode-" + THREAD_SERIAL.incrementAndGet());
                decodeThread.setDaemon(true);
                decodeThread.start();
            }
        }
    }

    /**
     * Freezes the picture and the sound, keeping both positions. Decoding continues until the ring
     * is full, so resuming shows the next picture immediately rather than after a decode.
     *
     * <p>The clock is told, which is not optional: a paused audio track reports a frozen position,
     * which is indistinguishable from a device that has died, and a clock not told would hand the
     * timeline to the wall clock and run the video straight through the pause.
     *
     * <p>Does nothing unless playing.
     */
    public void pause() {
        Ui.checkUiThread();
        if (!enterState(State.PLAYING, State.PAUSED)) {
            return;
        }
        clock.setPaused(true);
        playback.pause();
    }

    /** Resumes from where {@link #pause()} froze it. Does nothing unless paused. */
    public void resume() {
        Ui.checkUiThread();
        if (!enterState(State.PAUSED, State.PLAYING)) {
            return;
        }
        seekPresentPending = false; // the clock hands pictures out again; nothing owes one
        playback.resume();
        clock.setPaused(false);
    }

    /**
     * Ends this playback: the decode thread is stopped and joined, every held picture is released,
     * the clock is reset and the sound is stopped. The video stream is left open, wherever it had
     * reached, and is not rewound.
     *
     * <p><b>Terminal for the audio track.</b> Stopping a stream hands it to the engine to close, and
     * a track cannot be re-opened by something that never opened it, so a player started again
     * after this plays silently. {@link #pause()} is the resumable one. Idempotent, and harmless on
     * a closed player.
     */
    public void stop() {
        Ui.checkUiThread();
        if (state == State.CLOSED) {
            return;
        }
        stopDecoding();
        playback.stop();
        playback = Playback.NONE;
        if (master != null) {
            clock.setMaster(null);
            master = null;
        }
        clock.setPaused(false);
        clock.reset();
        failure = null;
        starved = false;
        state = State.IDLE;
    }

    /**
     * Rewinds to the first picture and plays from there: {@link #stop()}, then
     * {@link VideoStreamSource#reset()}, then {@link #start()}, which is also how it clears a
     * failed state. Silent afterwards, for the reason {@link #stop()} gives: the audio track went
     * with the stop, and a video rewound under a track that was not would be showing the first
     * pictures against the wrong sound.
     *
     * @throws UnsupportedOperationException if the stream cannot be rewound, which
     *                                       {@link VideoStreamSource#canReset()} answers in advance
     * @throws IllegalStateException         if this player is closed
     */
    public void restart() {
        Ui.checkUiThread();
        if (state == State.CLOSED) {
            throw new IllegalStateException("this MediaPlayer is closed");
        }
        stop();
        video.reset();
        synchronized (lock) {
            decodePass = 0;
        }
        presentPass = 0;
        start();
    }

    /**
     * Moves to {@code micros} and carries on from there: the pictures, the soundtrack and the
     * timeline together.
     *
     * <p>Returns as soon as the request is placed, which is not the same as the picture having
     * changed. What has happened by the time it returns: every picture this player was holding has
     * been released, the clock has been moved to {@code micros}, and the soundtrack has thrown away
     * what it had queued on the device and reports the new position. What has <em>not</em> happened
     * is the decode: the stream is repositioned by this player's decode thread, at the top of its
     * next pass, which is the only thread allowed to touch it and may be inside a whole decode right
     * now. {@link #takePicture()} keeps answering null until the first picture from there arrives.
     *
     * <p><b>{@link #positionMicros()} reports {@code micros} in between</b>, exactly, rather than
     * creeping forward while the pictures are being found. A transport control reading it therefore
     * shows where it was told to go and not where the buffering has got to.
     *
     * <p><b>While paused this hands over exactly one picture</b> and then holds again, because a
     * viewer dragging a paused video is asking to see where they have landed. The pause is not
     * lifted.
     *
     * <p>Clears an ended state (seeking backwards out of the end is how a viewer replays a part)
     * and clears a failure, since a decode that threw at one position says nothing about another.
     * Looping is unaffected, and a seek is not a loop: the pass the pictures are on does not change,
     * so a soundtrack that is still mastering keeps mastering.
     *
     * <p>The audio track is repositioned only if it can be. A soundtrack from the same container as
     * the pictures moves with them; a track from somewhere else, or one on an engine that cannot
     * discard what it has queued, keeps playing where it was, and because that leaves it on a
     * timeline the pictures are not on, this player drops it as its master rather than following it
     * somewhere the pictures will never be. {@link #canSeekAudio()} answers in advance.
     *
     * @param micros where to move to, in microseconds on the pictures' own timeline; not negative
     * @param mode   how close to land, and therefore what this costs. {@link
     *               VideoStreamSource.SeekMode#KEYFRAME} while a control is being dragged and
     *               {@link VideoStreamSource.SeekMode#EXACT} when it is let go is what makes a
     *               scrub bar cheap while it moves and right when it stops.
     * @throws UnsupportedOperationException if the stream cannot be seeked, which
     *                                       {@link VideoStreamSource#canSeek()} answers in advance
     * @throws IllegalArgumentException      if {@code micros} is negative
     * @throws IllegalStateException         if this player is closed
     */
    public void seek(long micros, VideoStreamSource.SeekMode mode) {
        Ui.checkUiThread();
        Objects.requireNonNull(mode, "mode");
        if (state == State.CLOSED) {
            throw new IllegalStateException("this MediaPlayer is closed");
        }
        if (micros < 0) {
            throw new IllegalArgumentException("seek target must not be negative, got " + micros);
        }
        if (!video.canSeek()) {
            throw new UnsupportedOperationException(
                    video.getClass().getName() + " cannot seek; ask canSeek() first");
        }
        synchronized (lock) {
            seekRequested = true;
            seekMicros = micros;
            seekMode = mode;
            seekEpoch++;
            sourceEnded = false;
            // A seek is not a new pass of a loop: the pictures either side of it are the same
            // stream on the same timeline, and letting the passes differ would make the first
            // picture after a seek look like a wrap and drop the master for it.
            decodePass = 0;
            lock.notifyAll();
        }
        drainRing();
        presentPass = 0;
        starved = false;
        failure = null;
        if (state == State.ENDED || state == State.FAILED) {
            state = State.PLAYING;
        }
        seekPresentPending = state == State.PAUSED;
        // Before the clock: this is what makes the master report the new position, and a clock
        // re-anchored against a master still reporting the old one would score the move as a jump
        // the moment the master caught up.
        playback.seek(micros);
        if (master != null && !playback.canSeek()) {
            // A soundtrack that stayed where it was is on a timeline these pictures have left.
            dropMaster();
        }
        clock.seekTo(micros);
    }

    /** As {@link #seek(long, VideoStreamSource.SeekMode)} landing exactly. */
    public void seek(long micros) {
        seek(micros, VideoStreamSource.SeekMode.EXACT);
    }

    /**
     * @return whether {@link #seek(long, VideoStreamSource.SeekMode)} works: the stream's answer,
     *         which is the one that decides whether a transport control is offered at all
     */
    public boolean canSeek() {
        return video.canSeek();
    }

    /**
     * @return whether a seek also moves the soundtrack. False with no track, with a track no device
     *         would take, and with one the engine cannot discard queued audio for, in which case a
     *         seek still moves the pictures and stops timing them by a soundtrack that is now
     *         somewhere else. UI thread, because it reads the playback handle.
     */
    public boolean canSeekAudio() {
        Ui.checkUiThread();
        return playback.canSeek();
    }

    /**
     * Stops everything and releases every picture this player is holding. <b>Blocks until the decode
     * thread has finished the read it is in</b>, which is the whole point of it: only then can the
     * caller close the video stream without tearing a decoder down underneath a decode. A stream
     * that never returns from a read therefore hangs this call, and that is a defect in the stream
     * rather than something to paper over with a timeout that would make the promise a guess.
     *
     * <p>The decode thread is not interrupted. {@link VideoStreamSource} promises nothing about
     * interruption, and a decoder woken out of a read could leave its input at a position it cannot
     * describe.
     *
     * <p>The audio track is closed here if it never reached the engine; if it did, the engine closed
     * it. The video stream is <b>not</b> closed. Idempotent.
     */
    @Override
    public void close() {
        Ui.checkUiThread();
        if (state == State.CLOSED) {
            return;
        }
        stopDecoding();
        playback.stop();
        playback = Playback.NONE;
        if (master != null) {
            clock.setMaster(null);
            master = null;
        }
        if (audio != null) {
            audio.close(); // never handed over, so still this player's
            audio = null;
        }
        state = State.CLOSED;
    }

    // ----------------------------------------------------------------- presentation

    /**
     * The picture whose moment has come, or null to keep showing the current one. The caller owns
     * what it is given and must {@link VideoFrame#release() release} it exactly once.
     *
     * <p>Returns null while paused, before the first start, after the end, after a failure, and
     * whenever nothing in the ring is due yet. Pictures whose moment has already passed are dropped
     * and released here rather than handed over late, which a caller holding a single picture could
     * not do for itself.
     *
     * <p>The one exception to the pause is a seek: a player seeked while paused hands over the first
     * picture from the new position and then holds again, so that a paused scrub shows where it
     * landed.
     *
     * <p>UI thread. Cheap enough for every frame: it reads the master at most once and allocates
     * nothing.
     */
    public VideoFrame takePicture() {
        Ui.checkUiThread();
        if (state == State.FAILED) {
            drainRing(); // the pictures outlived the stream that made them; give the slots back
            return null;
        }
        if (state == State.PAUSED) {
            return seekPresentPending ? takeAfterSeekWhilePaused() : null;
        }
        if (state != State.PLAYING) {
            return null;
        }
        while (true) {
            VideoFrame candidate;
            VideoFrame successor;
            int candidatePass;
            int successorPass;
            boolean ended;
            synchronized (lock) {
                candidate = count > 0 ? ring[head] : null;
                candidatePass = count > 0 ? ringPass[head] : presentPass;
                int second = (head + 1) % ring.length;
                successor = count > 1 ? ring[second] : null;
                successorPass = count > 1 ? ringPass[second] : candidatePass;
                ended = sourceEnded;
            }
            if (candidate == null) {
                if (ended) {
                    // Not an assignment: the decode thread sets sourceEnded when it fails as well
                    // as when the stream ends, so what was read above may be a failure whose own
                    // state write is landing right now.
                    enterState(State.PLAYING, State.ENDED);
                    return null;
                }
                if (!starved) {
                    starved = true;
                    underruns++;
                }
                return null;
            }
            starved = false;
            if (candidatePass != presentPass) {
                presentPass = candidatePass;
                onLooped();
            }
            long pts = candidate.ptsMicros();
            if (pts == VideoFrame.PTS_UNKNOWN) {
                // No timing at all: one picture per ask is the fastest honest answer, and the one
                // the clock would refuse to give rather than do arithmetic on the sentinel.
                return dequeue();
            }
            long nextPts = VideoClock.NO_PTS;
            if (successor != null && successorPass == candidatePass) {
                // A successor from the next pass is not a successor on this timeline, and letting
                // it license a drop would throw away the last picture of every loop.
                nextPts = successor.ptsMicros();
            }
            switch (clock.decide(pts, nextPts)) {
                case PRESENT -> {
                    checkMaster();
                    return dequeue();
                }
                case HOLD -> {
                    checkMaster();
                    return null;
                }
                case DROP -> dequeue().release();
                default -> throw new IllegalStateException("unreachable");
            }
        }
    }

    // ----------------------------------------------------------------- decoding

    /**
     * One pass of the decode loop: at most one picture read from the stream and handed to the ring.
     * Never blocks on the ring and never spins: a full ring and a stream with nothing ready are
     * both {@link Step#IDLE}, which the caller answers by waiting a moment.
     *
     * <p>This player's own decode thread calls it in a loop. A host that
     * {@linkplain #setOwnsDecodeThread(boolean) owns its threads} calls it itself, from anywhere
     * that is not drawing: a read may take a whole decode.
     *
     * @return what this pass did
     */
    public Step decodeStep() {
        boolean full;
        boolean doSeek;
        long seekTo;
        VideoStreamSource.SeekMode mode;
        long epoch;
        synchronized (lock) {
            if (!decoding) {
                return Step.DONE;
            }
            doSeek = seekRequested;
            seekTo = seekMicros;
            mode = seekMode;
            seekRequested = false;
            epoch = seekEpoch;
            if (!doSeek && sourceEnded) {
                return Step.DONE;
            }
            full = count == ring.length;
        }
        if (doSeek) {
            // On this thread and nowhere else: the SPI serializes seeking with reading, and the
            // read this seek interrupted has already returned by the time control reaches here.
            try {
                video.seek(seekTo, mode);
            } catch (RuntimeException error) {
                fail(error);
                return Step.DONE;
            }
            return Step.PRODUCED; // nothing decoded, but there is work to do straight away
        }
        if (full) {
            return Step.IDLE;
        }
        try {
            // Outside the lock: a read may take a whole decode, and holding the lock across it
            // would stall every consumer for exactly as long.
            switch (video.readFrame()) {
                case FRAME -> {
                    return enqueue(video.frame(), epoch);
                }
                case PENDING -> {
                    return Step.IDLE;
                }
                case END -> {
                    if (looping && video.canReset()) {
                        video.reset();
                        synchronized (lock) {
                            decodePass++;
                        }
                        return Step.IDLE;
                    }
                    synchronized (lock) {
                        sourceEnded = true;
                        lock.notifyAll();
                    }
                    return Step.DONE;
                }
                default -> throw new IllegalStateException("unreachable");
            }
        } catch (RuntimeException error) {
            fail(error);
            return Step.DONE;
        }
    }

    private Step enqueue(VideoFrame frame, long epoch) {
        if (frame == null) {
            return Step.IDLE; // a stream reporting a picture it has not got
        }
        synchronized (lock) {
            if (!decoding || count == ring.length || epoch != seekEpoch) {
                // Stopped, drained, or seeked away from while this decode was in flight. The
                // picture is the stream's and goes back now rather than being enqueued into a ring
                // nobody drains, or shown from a position the viewer has already left.
                frame.release();
                return decoding ? Step.IDLE : Step.DONE;
            }
            int tail = (head + count) % ring.length;
            ring[tail] = frame;
            ringPass[tail] = decodePass;
            count++;
            decodedFrames++;
            lock.notifyAll();
        }
        return Step.PRODUCED;
    }

    /**
     * Runs from {@link #start()} until {@link #stopDecoding()}, and parks whenever there is nothing
     * to do: a full ring, a stream with nothing ready, the end, or a decode that threw. It does
     * <b>not</b> exit at the end: a seek reopens all three of those, and a thread that had exited
     * would have to be restarted from another thread, which is a second chance to have two threads
     * reading one stream. One thread, one lifetime, and the parking is the same either way.
     */
    private void decodeLoop() {
        while (true) {
            if (decodeStep() == Step.PRODUCED) {
                continue;
            }
            synchronized (lock) {
                if (!decoding) {
                    return;
                }
                try {
                    lock.wait(PARK_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Moves the state, refusing when it is no longer the one the caller saw a moment ago, which
     * after a decode failure it very often is not.
     *
     * <p>Two threads write {@link #state}: this one and the decode thread's {@link #fail}. Every
     * transition the UI thread makes is a test followed by a write, and the decode thread can put
     * FAILED between them; a plain assignment then loses the failure and leaves the player
     * reporting a clean end, or a pause, with {@link #failure()} set, which {@link State#FAILED}
     * says cannot happen. Both sides take {@link #lock}, which makes the test and the write one
     * step and makes FAILED stick until something deliberately clears it.
     *
     * <p>The assignments outside this method are the ones with no second writer to race:
     * {@link #start()} runs from IDLE, where no decode thread is decoding yet, and {@link #stop()},
     * {@link #close()} and {@link #seek} clear a failure on purpose with the decode thread stopped
     * or parked.
     *
     * <p>Package-private for the test: the losing half of the race cannot be reached through the
     * public methods, because each of them re-reads the state, so the rule is asserted here.
     *
     * @return whether the state moved
     */
    boolean enterState(State from, State to) {
        synchronized (lock) {
            if (state != from) {
                return false;
            }
            state = to;
            return true;
        }
    }

    private void fail(RuntimeException error) {
        failure = error;
        synchronized (lock) {
            // Not `decoding = false`: the thread parks rather than exiting, so a seek out of a
            // position that could not be decoded is answered by the thread that is already there.
            sourceEnded = true;
            // Under the lock, and unconditional: this is the write every transition in
            // enterState is checked against, and outside the lock it could land in the middle
            // of one of them and be overwritten by a state the UI thread chose before it.
            state = State.FAILED;
            lock.notifyAll();
        }
        // Somewhere a user can see it: a decode thread belongs to no event-loop phase, so without
        // this the only trace of a broken stream would be a widget saying it cannot be played.
        Crashes.report(CrashPhase.DECODE, error);
    }

    // ----------------------------------------------------------------- status

    /**
     * @return the stream being played, never null: for a consumer that needs its declared size or
     *         frame rate before a picture exists, which every metadata accessor answers from any
     *         thread. Still the caller's to close, and only after this player is closed.
     */
    public VideoStreamSource video() {
        return video;
    }

    /** @return where this player is in its life; readable from any thread */
    public State state() {
        return state;
    }

    /**
     * @return whether the stream ended and every decoded picture has been handed out. The last one
     *         stays on screen; nothing more is decoded or timed.
     */
    public boolean isEnded() {
        return state == State.ENDED;
    }

    /**
     * @return the exception a decode threw, or null. The player stops reading the stream and hands
     *         out nothing more; {@link #restart()} or {@link #stop()} clears it.
     */
    public RuntimeException failure() {
        return failure;
    }

    /** @return whether an audio track is sounding, or is waiting for {@link #start()} to sound */
    public boolean hasAudio() {
        return audio != null || playback != Playback.NONE;
    }

    /**
     * @return whether the pictures are currently timed by the audio track rather than by the wall
     *         clock. False before {@link #start()}, with no track, with a track no device would
     *         take, and after a track has stopped advancing or moved off the pictures' timeline,
     *         which is what a status line needs to say why a video is pacing the way it is
     */
    public boolean isFollowingAudio() {
        return master != null;
    }

    /**
     * @return the handle to the sounding audio track, or {@link Playback#NONE} when there is none:
     *         because there was no track, because no device would take it, or because playback has
     *         been stopped
     */
    public Playback audio() {
        return playback;
    }

    /**
     * @return the media position in microseconds: the audio track's while it is mastering, and the
     *         wall-clock timeline's otherwise
     */
    public long positionMicros() {
        return clock.positionMicros();
    }

    /** @return decoded pictures handed to the ring since construction, dropped ones included */
    public long decodedFrames() {
        return decodedFrames;
    }

    /**
     * @return how many times a picture was asked for and the ring was empty while playing, counted
     *         once per dry spell rather than once per ask, so it reads as "the decoder fell behind
     *         this many times" rather than as a frame count
     */
    public long underruns() {
        return underruns;
    }

    /** @return decoded pictures held right now, at most {@link #ringCapacity()} */
    public int bufferedPictures() {
        synchronized (lock) {
            return count;
        }
    }

    @Override
    public String toString() {
        return "MediaPlayer[" + state + " buffered=" + bufferedPictures() + "/" + ringCapacity
                + " decoded=" + decodedFrames + " audio=" + (playback != Playback.NONE) + "]";
    }

    // ----------------------------------------------------------------- internals

    private void startAudio() {
        if (audio == null) {
            return; // no track, or one already handed to the engine
        }
        AudioStreamSource handing = audio;
        audio = null; // ownership leaves here, whatever the engine does with it
        playback = Sounds.stream(handing, audioOptions.withLoop(looping));
        if (playback == Playback.NONE) {
            // A handle that reports a constant zero forever is not a timeline. Following it would
            // cost a stall's worth of held pictures before the wall clock took over, on exactly the
            // machines that have no audio device.
            return;
        }
        master = new AudioMasterClock(playback);
        // Read before installing: the tally survives a reset, so a player started a second time
        // would otherwise compare against a jump from the first and drop the master at once.
        lastJumpCount = clock.jumpCount();
        clock.setMaster(master);
    }

    /**
     * Drops a master the pictures can no longer follow, so the wall clock carries on from the
     * position already reached, which is a rate that stays right, where holding for a device that
     * will never advance again is a picture that never changes.
     *
     * <p>Two ways it stops being followable. It stops advancing, which the clock declares after
     * {@link VideoClock#MASTER_STALL_MICROS}: a device that went away, or a track that ended before
     * the pictures did. Only then is the handle asked whether it is still playing, so the engine's
     * monitor is taken once per failure rather than once per picture. Or it moves somewhere the
     * pictures are not: a looping track wrapping to its start reports in-track time, which is the
     * whole track backwards at once. Nothing here can seek the pictures to meet it, and a master on
     * a different timeline holds every picture until the track catches back up to where the video
     * already is, so the timeline is handed back to the wall clock instead. Both are one-way: a
     * track that has diverged does not return to the pictures' timeline on its own.
     */
    private void checkMaster() {
        if (master == null) {
            return;
        }
        long jumps = clock.jumpCount();
        if (jumps != lastJumpCount) {
            lastJumpCount = jumps;
            dropMaster();
            return;
        }
        if (clock.isMasterStalled() && !playback.isPlaying()) {
            dropMaster();
        }
    }

    private void dropMaster() {
        clock.setMaster(null);
        master = null;
    }

    /** A new pass of a looping stream: the pictures restarted, and the audio track did not. */
    private void onLooped() {
        if (master != null) {
            clock.setMaster(null);
            master = null;
        }
        clock.reset();
    }

    /**
     * The one picture a paused player hands over: the first to arrive after a seek, so that a
     * viewer scrubbing a paused video sees where they landed rather than the picture they left.
     * Null until it arrives and null for every ask after it, so the pause is otherwise exactly the
     * pause it was.
     */
    private VideoFrame takeAfterSeekWhilePaused() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
        }
        seekPresentPending = false;
        return dequeue();
    }

    private VideoFrame dequeue() {
        synchronized (lock) {
            VideoFrame frame = ring[head];
            ring[head] = null;
            head = (head + 1) % ring.length;
            count--;
            lock.notifyAll(); // room for one more
            return frame;
        }
    }

    private void stopDecoding() {
        Thread thread;
        synchronized (lock) {
            decoding = false;
            lock.notifyAll();
            thread = decodeThread;
            decodeThread = null;
        }
        if (thread != null && thread != Thread.currentThread()) {
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException error) {
                    interrupted = true; // this thread's business, not the decoder's
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        // Only after the join: a picture decoded during it is released by the enqueue path instead,
        // which sees that decoding has stopped.
        drainRing();
        synchronized (lock) {
            sourceEnded = false;
            // A seek nobody asked to survive the stop: starting again plays from where the stream
            // is, which is what stop() promises, and a request left armed would move it silently.
            seekRequested = false;
        }
        seekPresentPending = false;
    }

    private void drainRing() {
        synchronized (lock) {
            while (count > 0) {
                VideoFrame frame = ring[head];
                ring[head] = null;
                head = (head + 1) % ring.length;
                count--;
                frame.release();
            }
            head = 0;
        }
    }

    private void checkIdle(String what) {
        if (state == State.CLOSED) {
            throw new IllegalStateException("this MediaPlayer is closed");
        }
        if (state != State.IDLE) {
            throw new IllegalStateException(
                    "cannot change " + what + " while a MediaPlayer is " + state);
        }
    }
}
