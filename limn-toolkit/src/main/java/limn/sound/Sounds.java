package limn.sound;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Audio facade, backed by the running backend's {@link AudioEngine} and
 * {@link AudioDecoder} (both installed at startup, mirroring {@link
 * limn.graphics.Images}). Load or synthesize an {@link AudioClip}, then
 * {@link #play} it.
 *
 * <p>Playback is best-effort: with no backend or no audio device, {@link #play}
 * is a silent no-op returning {@link Playback#NONE}; feedback sounds never
 * become a hard dependency. Decoding, by contrast, requires a backend (there is
 * no fallback CPU decoder) and throws if none is installed.
 *
 * <pre>{@code
 * AudioClip chime = Sounds.fromResource("/app/sounds/chime.ogg"); // or a .wav
 * Sounds.play(chime);
 * // ...or synthesize without shipping an asset:
 * Sounds.play(AudioClip.tone(880, 0.12f, 0.5f));
 * }</pre>
 */
public final class Sounds {

    private static volatile AudioEngine engine;
    private static volatile AudioDecoder decoder;

    private Sounds() {
    }

    // ------------------------------------------------------------- install

    /** Installs the backend audio engine (called once at backend startup). */
    public static void installEngine(AudioEngine newEngine) {
        engine = Objects.requireNonNull(newEngine, "newEngine");
    }

    /** Uninstalls {@code candidate} if it is the installed engine (backend shutdown). */
    public static void uninstallEngine(AudioEngine candidate) {
        if (engine == candidate) {
            engine = null;
        }
    }

    /** Installs the backend audio decoder (called once at backend startup). */
    public static void installDecoder(AudioDecoder newDecoder) {
        decoder = Objects.requireNonNull(newDecoder, "newDecoder");
    }

    /** Uninstalls {@code candidate} if it is the installed decoder (backend shutdown). */
    public static void uninstallDecoder(AudioDecoder candidate) {
        if (decoder == candidate) {
            decoder = null;
        }
    }

    /**
     * Whether an engine is installed and an audio device is available.
     *
     * <p><b>The first call may open the audio device</b>: an engine is allowed to defer loading the
     * platform audio library and waking the default output until something asks, and that costs
     * tens to hundreds of milliseconds, longer when the output is asleep or on a Bluetooth link.
     * It blocks the calling thread for that time, so on the UI thread it is a visible freeze.
     * {@link #warmUpAsync()} asks the same question on the worker pool; every call once the device
     * is open is effectively a field read.
     *
     * @return whether something played now would be heard
     */
    public static boolean isAvailable() {
        AudioEngine current = engine;
        return current != null && current.isAvailable();
    }

    /**
     * Opens the audio device on the {@code Ui} worker pool so that the first {@link #play} does not
     * pay for it, and hands whatever {@link #isAvailable()} answers from then on to
     * {@code onSuccess} on the UI thread. Started once during startup, well before the first
     * feedback sound:
     *
     * <pre>{@code
     * Sounds.warmUpAsync().onSuccess(audible -> soundToggle.setEnabled(audible)).start();
     * }</pre>
     *
     * <p>Returned <b>unstarted</b>, and dropping it warms nothing at all. It is a description
     * rather than a job already running even though nothing here waits for its answer, because
     * every form in this toolkit whose name ends in {@code Async} is an unstarted description, and
     * one rule that holds across all of them is worth more than the {@code start()} it would save:
     * a facade that started itself would be the exception a reader has to remember, at the one
     * call site where forgetting is silent. The de-duplicated loaders are the other family and
     * say so in their names: {@link #loadShared} and {@link #fromResourceShared} are running
     * before the caller sees them.
     *
     * <p>Idempotent and cheap after the first time (the engine opens the device once), and it
     * never fails: a machine with no audio device, and a process with no engine installed, both
     * deliver {@code false} rather than failing, matching the best-effort silence {@link #play}
     * gives. It reports no progress: waking a device has no fraction anyone can compute. Nothing
     * it produces holds a resource, so it carries no disposer and needs none.
     *
     * @return the unstarted work; the device is not opened until {@code start()}, and
     *         {@code onSuccess} then receives whether something played would be heard
     * @throws IllegalStateException if no backend is running (there is no worker pool to use)
     */
    public static limn.concurrent.Work<Boolean> warmUpAsync() {
        return limn.concurrent.Ui.work(progress -> isAvailable());
    }

    // ------------------------------------------------------------- decode

    /**
     * Decodes an encoded audio file from memory, on the calling thread, in whatever formats the
     * installed {@link AudioDecoder} accepts. Decoding a whole clip walks every sample, so a long
     * track is long enough to drop frames on the UI thread; {@link #decodeAsync} is the same work
     * on the worker pool.
     */
    public static AudioClip decode(byte[] fileBytes) {
        return requireDecoder().decode(fileBytes);
    }

    /**
     * Reads {@code file} whole and decodes it, on the calling thread: one file read plus
     * a full decode, which on the UI thread is a freeze for as long as both take.
     * {@link #loadShared} is the same work on the worker pool.
     */
    public static AudioClip load(Path file) {
        try {
            return decode(Files.readAllBytes(file));
        } catch (IOException error) {
            throw new UncheckedIOException("reading audio " + file, error);
        }
    }

    /**
     * Reads a classpath resource whole and decodes it, on the calling thread; see
     * {@link #load(Path)} for the cost, and {@link #fromResourceShared} for the same work on the
     * worker pool.
     */
    public static AudioClip fromResource(String resource) {
        try (InputStream in = Sounds.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("audio resource missing: " + resource);
            }
            return decode(in.readAllBytes());
        } catch (IOException error) {
            throw new UncheckedIOException("reading audio resource " + resource, error);
        }
    }

    // -------------------------------------------------- background loading

    private static final java.util.concurrent.ConcurrentHashMap<
            String, java.util.concurrent.CompletableFuture<AudioClip>> pending =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Reads and decodes {@code file} on the {@code Ui} worker pool, a bounded pool shared with
     * every other background task, so a stage chained onto the result may block without starving
     * the UI, but a long one delays other loads. The returned future is <b>already running</b> and
     * completes on the UI thread, where {@code thenAccept} may touch widgets directly:
     * {@code loadShared(file).thenAccept(Sounds::play)}.
     *
     * <p><b>Shared, which is why this is not a {@code Work} and not called {@code loadAsync}.</b>
     * Deduplicated by absolute path: concurrent and later calls for the same file return the
     * <em>same</em> future and decode once, and every caller shares one immutable
     * {@link AudioClip}; a result two callers are waiting for is not one either of them may
     * cancel, so there is nothing to start and nothing to withdraw. A load that fails is dropped
     * from that cache, so a later call retries it. {@link #clearSharedCache()} drops the
     * successful ones too.
     *
     * <p>Failures do not throw here; they arrive at {@code exceptionally}/{@code whenComplete} on
     * the UI thread. Requires a running backend, like the synchronous form.
     */
    public static java.util.concurrent.CompletableFuture<AudioClip> loadShared(Path file) {
        return cachedLoad("file:" + file.toAbsolutePath(), () -> load(file));
    }

    /**
     * Reads and decodes a classpath resource on the {@code Ui} worker pool; the returned future is
     * already running and completes on the UI thread. Deduplicated by resource name, retried after
     * a failure and reporting failures through the future exactly as {@link #loadShared(Path)}
     * does.
     */
    public static java.util.concurrent.CompletableFuture<AudioClip> fromResourceShared(String resource) {
        return cachedLoad("resource:" + resource, () -> fromResource(resource));
    }

    /**
     * Decodes in-memory bytes on the {@code Ui} worker pool and hands the clip to
     * {@code onSuccess} on the UI thread; failures arrive at {@code onFailure} rather than being
     * thrown here.
     *
     * <p>Returned <b>unstarted</b>: attach the handlers, then {@code start()}. Uncached (the
     * caller owns the bytes, and two arrays with equal contents are two decodes), and being
     * unshared is what lets this one be a cancellable job where {@link #loadShared} cannot be.
     *
     * @throws IllegalStateException if no backend is running
     */
    public static limn.concurrent.Work<AudioClip> decodeAsync(byte[] fileBytes) {
        return limn.concurrent.Ui.work(progress -> decode(fileBytes));
    }

    /**
     * Drops every shared load, so the next {@link #loadShared} or {@link #fromResourceShared} of a
     * source re-reads it (e.g. after files changed on disk). Already-delivered clips are
     * unaffected.
     */
    public static void clearSharedCache() {
        pending.clear();
    }

    private static java.util.concurrent.CompletableFuture<AudioClip> cachedLoad(
            String key, java.util.function.Supplier<AudioClip> loader) {
        return pending.computeIfAbsent(key, k -> {
            java.util.concurrent.CompletableFuture<AudioClip> future =
                    limn.concurrent.Ui.async(loader).toCompletableFuture();
            future.whenComplete((clip, error) -> {
                if (error != null) {
                    pending.remove(k, future); // failures are retryable
                }
            });
            return future;
        });
    }

    // ------------------------------------------------------------- play

    /**
     * Plays {@code clip} once at full volume (no-op if audio is unavailable): the feedback-sound
     * call, and the one most likely to be the process's first, which is what
     * {@link #play(AudioClip, float, boolean)} warns about.
     */
    public static Playback play(AudioClip clip) {
        return play(clip, 1f, false);
    }

    /** Plays {@code clip} once at {@code gain} in [0..1] (no-op if audio is unavailable). */
    public static Playback play(AudioClip clip, float gain) {
        return play(clip, gain, false);
    }

    /**
     * Plays {@code clip} at {@code gain} in [0..1], optionally looping. Returns
     * a {@link Playback} handle (or {@link Playback#NONE} when no audio device
     * is available). Safe to call from any thread.
     *
     * <p>No asynchronous form, because the work here is a handful of device calls on an already
     * decoded clip and it has to return the handle the caller stops. <b>The first call in the
     * process is the exception</b>: it may be the one that opens the audio device, which costs tens
     * to hundreds of milliseconds and blocks whichever thread makes it, so the first feedback
     * click an application plays is a freeze unless {@link #warmUpAsync()} has already paid for it
     * during startup.
     */
    public static Playback play(AudioClip clip, float gain, boolean loop) {
        Objects.requireNonNull(clip, "clip");
        AudioEngine current = engine;
        return current == null ? Playback.NONE : current.play(clip, gain, loop);
    }

    /**
     * Plays {@code clip} with full {@link PlayOptions}: pitch, pan or 3D
     * position, mixer bus and steal priority. Safe to call from any thread, and
     * the first call in the process carries the device-open cost described on
     * {@link #play(AudioClip, float, boolean)}.
     */
    public static Playback play(AudioClip clip, PlayOptions options) {
        Objects.requireNonNull(clip, "clip");
        Objects.requireNonNull(options, "options");
        AudioEngine current = engine;
        return current == null ? Playback.NONE : current.play(clip, options);
    }

    /**
     * Streams the audio file at {@code file}, in whatever formats the installed
     * {@link AudioDecoder} can stream: decoded incrementally on the engine's streaming thread, so
     * a long music track costs ring buffers instead of a whole decoded clip on the heap.
     * The conventional music setup is
     * {@code DEFAULTS.withBus(AudioBus.MUSIC).withPriority(HIGH).withLoop(true)}.
     *
     * <p><b>Getting a stream started is not incremental, even though playing it is.</b> This call
     * opens the file, and a decoder is allowed to read it whole to be able to seek in it and to
     * decode a first frame to learn the format; the engine then decodes the first buffers before
     * the sound starts. On a several-megabyte track that is a read of every byte plus a decode, all
     * on the calling thread, which for the "start the music as the scene appears" call is the UI
     * thread. Use {@link #streamAsync} there; this form suits a caller already on a worker thread.
     */
    public static Playback stream(Path file, PlayOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        AudioEngine current = engine;
        if (current == null || !current.isAvailable()) {
            return Playback.NONE; // nothing was opened, so there is nothing to close
        }
        return stream(requireDecoder().openStream(file), options);
    }

    /**
     * Opens {@code file} and starts streaming it on the {@code Ui} worker pool, handing the
     * {@link Playback} handle to {@code onSuccess} on the UI thread: the asynchronous form of
     * {@link #stream(Path, PlayOptions)}, and what an application should use to start music while
     * a scene is appearing.
     *
     * <pre>{@code
     * music = Sounds.streamAsync(track, PlayOptions.DEFAULTS.withBus(AudioBus.MUSIC).withLoop(true))
     *               .onSuccess(playback -> this.playback = playback)
     *               .deliverIf(view::isShowing)
     *               .start();
     * }</pre>
     *
     * <p>Returned <b>unstarted</b> and already carrying a disposer, so a caller cannot leak by
     * forgetting one: register {@code onSuccess}/{@code onFailure}/{@code deliverIf} and call
     * {@code start()}. Cancelling the job, or refusing the delivery, stops the stream and closes
     * the file; a cancel that arrives before the engine has admitted the track means nothing ever
     * sounds, and one that arrives after it means a fraction of a second does. Replacing the
     * disposer with one of your own removes that guarantee.
     *
     * <p>Reports no progress: neither opening the file nor priming the device has a fraction anyone
     * can compute, so a registered progress handler would never be called.
     *
     * <p>Everything expensive happens in the body (waking the audio device, the file read, the
     * priming decode), and the handle is produced there too, so it is already playing by the time
     * it is delivered. With no engine, no audio device, or a file the decoder will not stream, the
     * body completes with {@link Playback#NONE} or fails; neither leaves a file open.
     *
     * @param file    the track, opened on the worker pool
     * @param options gain, bus, priority and looping, read once at admission
     * @return the unstarted work; nothing is opened until {@code start()}
     * @throws NullPointerException  if either argument is null
     * @throws IllegalStateException if no backend is running (there is no worker pool to use)
     */
    public static limn.concurrent.Work<Playback> streamAsync(Path file, PlayOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        return limn.concurrent.Ui.<Playback>work(progress -> {
            AudioEngine current = engine;
            if (current == null || !current.isAvailable()) {
                return Playback.NONE; // nothing was opened, so there is nothing to close
            }
            AudioStreamSource source = requireDecoder().openStream(file);
            if (progress.isCancelled()) {
                // The expensive part is behind us and the caller has withdrawn: close here rather
                // than starting a track only to stop it, which would be audible.
                source.close();
                return Playback.NONE;
            }
            Playback playback = stream(source, options);
            if (progress.isCancelled()) {
                playback.stop(); // closes the source, per stream(AudioStreamSource, PlayOptions)
                return Playback.NONE;
            }
            return playback;
        }).onDiscarded(Playback::stop);
    }

    /**
     * Streams PCM frames pulled from {@code source}, for audio that is already open and has no
     * file of its own, such as the audio track of a container something else is demultiplexing.
     * Requires no {@link AudioDecoder}: the caller has already done the decoding this facade would
     * otherwise arrange.
     *
     * <p><b>This call takes ownership of {@code source} and closes it, on every path without
     * exception.</b> The caller must not close it afterwards and must not hand it to anything else:
     * a source closed twice is a decoder torn down under a streaming thread still reading it. That
     * holds when playback ends, when it is {@linkplain Playback#stop() stopped}, and equally when
     * nothing ever sounds: no engine installed, no audio device, a channel count that is neither
     * mono nor stereo, a full admission queue, or a source that yields no frames at all. Every one
     * of those returns {@link Playback#NONE}, and in every one of them the source has been closed
     * before this returns.
     *
     * <p>Which thread does the closing is not the caller's to assume: it is this thread when the
     * stream never starts, and the engine's streaming thread once it has. Implementations of
     * {@link AudioStreamSource#close()} are documented idempotent and must tolerate either.
     *
     * <p>The engine's streaming thread pulls frames from here on, so the source must not be touched
     * by the caller after this call. Safe to call from any thread.
     *
     * <p>No asynchronous form of its own, deliberately: whoever holds an open source opened it
     * somewhere, and that somewhere is where the background work belongs ({@link #streamAsync}
     * for a file, or the caller's own worker for a source demultiplexed out of something else).
     * It is not free, though: the engine primes several device buffers before returning, which is
     * a decode of the first fraction of a second on <em>this</em> thread. Calling it on the UI
     * thread with a source that was opened elsewhere is the one shape that still stalls a frame.
     *
     * @param source  the open source, whose {@link AudioStreamSource#channels()} and
     *                {@link AudioStreamSource#sampleRate()} are read once at admission
     * @param options gain, bus, priority and whether the engine rewinds at the end of data via
     *                {@link AudioStreamSource#reset()}
     * @return a handle to the started stream, or {@link Playback#NONE} when nothing sounds
     * @throws NullPointerException if either argument is null, in which case nothing is closed
     *                              because nothing was accepted
     */
    public static Playback stream(AudioStreamSource source, PlayOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        AudioEngine current = engine;
        if (current == null || !current.isAvailable()) {
            // Ownership transferred at the call, so the no-engine path owes the close that
            // playStream would otherwise have done. Unguarded: a close that throws is a bug in
            // the source, and swallowing it here is how it would stay one.
            source.close();
            return Playback.NONE;
        }
        return current.playStream(source, options);
    }

    /**
     * Sets the global volume in [0..1], applied live to everything sounding. No asynchronous form:
     * it re-applies a gain to the voices currently playing, which is bounded by the voice count and
     * reads nothing, and a volume slider that took effect a frame later would feel broken.
     */
    public static void setMasterGain(float gain) {
        AudioEngine current = engine;
        if (current != null) {
            current.setMasterGain(gain);
        }
    }

    /** Sets {@code bus}'s volume in [0..1], applied live to its playbacks; see {@link #setMasterGain}. */
    public static void setBusGain(AudioBus bus, float gain) {
        Objects.requireNonNull(bus, "bus");
        AudioEngine current = engine;
        if (current != null) {
            current.setBusGain(bus, gain);
        }
    }

    /**
     * Positions the 3D listener; see {@link AudioEngine#setListener}. No asynchronous form: it is
     * a couple of device calls, and it is typically driven per frame from a camera, where a
     * deferred one would arrive behind the picture it belongs to. It can nonetheless be the first
     * call that opens the audio device; see {@link #isAvailable()} for what that costs.
     */
    public static void setListener(limn.math.Vec3 position,
                                   limn.math.Vec3 forward, limn.math.Vec3 up) {
        AudioEngine current = engine;
        if (current != null) {
            current.setListener(position, forward, up);
        }
    }

    private static AudioDecoder requireDecoder() {
        AudioDecoder current = decoder;
        if (current == null) {
            throw new IllegalStateException(
                    "No AudioDecoder installed: start a Backend before loading audio.");
        }
        return current;
    }
}
