package limn.video;

import limn.concurrent.Progress;
import limn.concurrent.Ui;
import limn.concurrent.Work;

import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Video facade, over the installed {@link VideoDecoder}s.
 *
 * <p><b>The application installs them, not the backend</b>, and that is the one thing to know
 * before anything here works: starting a backend supplies an image decoder and an audio engine,
 * and supplies no video decoder at all. Nothing can be opened until something calls
 * {@link #installDecoder}. That is deliberate rather than an omission: an image decoder is a
 * platform capability every backend has, while a video decoder is a dependency with a size and a
 * licence, so which ones a process carries is the application's decision.
 *
 * <pre>{@code
 * Videos.installDecoder(new Y4mDecoder());              // pure Java, no native
 * Videos.installDecoder(new FfmpegVideoDecoder());      // if that module is on the classpath
 * }</pre>
 *
 * <p>Unlike the image and audio facades this holds an ordered <i>list</i>: decoders differ by
 * input rather than by platform, so several coexist and each declines what it cannot take.
 *
 * <pre>{@code
 * try (VideoStreamSource source = Videos.open(Path.of("clip.y4m"))) {
 *     while (source.readFrame() == VideoStreamSource.Read.FRAME) {
 *         VideoFrame frame = source.frame();
 *         // ... use it ...
 *         frame.release();
 *     }
 * }
 * }</pre>
 *
 * <p>Opening throws rather than degrading to a silent no-op, which is the opposite of the audio
 * facade and deliberately so: a feedback sound that does not play is invisible, whereas a video
 * that does not open is a blank rectangle where content was asked for, and a caller that is not
 * told has no way to show a poster or an error instead. There is also nothing coherent to return:
 * a stand-in source would have to invent a width, a height and a layout. Ask first with
 * {@link #isDecoderInstalled()} and {@link #canOpen} rather than catching.
 *
 * <p>The registry is a concurrent list and asking it something ({@link #isDecoderInstalled()},
 * {@link #installedDecoders()}, {@link #canOpen}) is safe from any thread, including a decode
 * thread. Opening is where the thread starts to matter.
 *
 * <p><b>{@link #open} blocks for as long as the decoder that claims the input takes</b>, which for
 * a real container is a header read, a probe of every stream in it, an index, a decoder, and on the
 * first call a native library: far longer than a frame, and a visible freeze when the caller is the
 * UI thread. {@link #openAsync} does the same work on the worker pool and hands the source back on
 * the UI thread, closing it for you if the request was withdrawn before it landed. And because
 * {@link #canOpen} has to stay synchronous (a control asking whether to enable itself cannot wait),
 * {@link #warmUpAsync()} is how an application pays a decoder's first-call cost somewhere other
 * than the first probe.
 *
 * <p>The asynchronous forms need a running backend, since their callbacks land on the UI thread;
 * everything else here works in a process that never started one.
 *
 * <p>The registry is process-wide and outlives any one test class. A test that installs a decoder
 * removes it again in a cleanup block; {@link #uninstallAllDecoders()} clears the list for a test
 * that would rather start from empty.
 */
public final class Videos {

    private static final System.Logger LOG = System.getLogger(Videos.class.getName());

    private static final CopyOnWriteArrayList<VideoDecoder> DECODERS = new CopyOnWriteArrayList<>();

    /**
     * What the synchronous open hands the decoder SPI: a caller already blocked inside {@link #open}
     * has nothing to withdraw with and nowhere to show a fraction, so the flag never trips and the
     * reports go nowhere. Both forms then share one probe loop, which is what keeps their
     * diagnostics from drifting apart.
     */
    private static final Progress NEVER_CANCELLED = new Progress() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void report(double fraction) {
        }
    };

    private Videos() {
    }

    /**
     * Installs {@code decoder} at the <b>end</b> of the probe order. Decoders are asked in the
     * order they were installed, so an application that wants one to win installs it first, or
     * uninstalls what it means to override. Installing an already installed decoder is a no-op
     * that leaves the order untouched, so running startup twice cannot reshuffle priorities.
     *
     * @throws NullPointerException if {@code decoder} is null
     */
    public static void installDecoder(VideoDecoder decoder) {
        Objects.requireNonNull(decoder, "decoder");
        DECODERS.addIfAbsent(decoder);
    }

    /**
     * Removes {@code decoder}. No-op when it was never installed, and a no-op for null rather than a
     * failure; the asymmetry with {@link #installDecoder} is deliberate, so a cleanup block can
     * uninstall whatever it may or may not have installed without a null check of its own.
     */
    public static void uninstallDecoder(VideoDecoder decoder) {
        DECODERS.remove(decoder);
    }

    /** Removes every decoder: backend shutdown, and test cleanup. */
    public static void uninstallAllDecoders() {
        DECODERS.clear();
    }

    /** @return an immutable snapshot of the installed decoders, in the order they are asked */
    public static List<VideoDecoder> installedDecoders() {
        return List.copyOf(DECODERS);
    }

    /**
     * A lock-free peek at the registry and nothing more: no probe, no library, no device.
     *
     * <p>Named for what it answers, the way {@code Images.isDecoderInstalled} and
     * {@code SvgIcon.isRasterizerInstalled} are, and deliberately not {@code isAvailable}:
     * {@code Sounds.isAvailable} asks a different and far more expensive question (engine
     * installed <em>and</em> device answering, whose first call may open the audio hardware), and
     * one name spanning a field read and a device open is a cost a caller cannot infer.
     *
     * @return whether the application has installed any decoder at all; a running backend does
     *         not imply one, because a backend supplies none
     */
    public static boolean isDecoderInstalled() {
        return !DECODERS.isEmpty();
    }

    /**
     * <p>Synchronous and cheap by contract, on any thread: each decoder's claim is an extension
     * comparison and at most a few bytes read, because this is what a control asks before it
     * decides whether to enable itself and it has to be answerable inside a frame. The one cost it
     * cannot promise away is a decoder's own first-call preparation: a native library links once,
     * and whoever asks first pays for it. {@link #warmUpAsync()} is how that is moved off this
     * call.
     *
     * @return whether some installed decoder claims {@code file}: the non-throwing form of
     *         {@link #open}, for choosing a poster or disabling a control. A true here does not
     *         promise that opening succeeds.
     * @throws NullPointerException if {@code file} is null
     */
    public static boolean canOpen(Path file) {
        Objects.requireNonNull(file, "file");
        for (VideoDecoder decoder : DECODERS) {
            if (decoder.supports(file)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens {@code file} with the first installed decoder that claims it. The caller owns the
     * returned source and closes it. If that decoder then fails, the failure propagates and no
     * later decoder is tried: the one that accepted the input is the one that knows what is wrong
     * with it, and replacing that with a generic message would be the worst diagnostic available.
     *
     * <p>Runs on the calling thread and takes as long as that decoder takes: for a container, far
     * longer than a frame, and longer again on a cold or network volume or on the call that first
     * links a native library. On the UI thread that is a freeze the length of the open, so use
     * {@link #openAsync} anywhere but setup code.
     *
     * @throws IllegalStateException         if no decoder is installed at all, or if the decoder
     *                                       that claimed the input returned nothing without having
     *                                       been cancelled, which it cannot have been here
     * @throws UnsupportedOperationException if decoders are installed and none claims the input;
     *                                       the message names every decoder asked, in order
     * @throws NullPointerException          if {@code file} is null
     */
    public static VideoStreamSource open(Path file) {
        Objects.requireNonNull(file, "file");
        return probeAndOpen(file, NEVER_CANCELLED);
    }

    /**
     * The same open as {@link #open}, on the worker pool, delivered on the UI thread, and
     * returned <b>unstarted</b>, so the caller attaches its handlers first:
     *
     * <pre>{@code
     * open = Videos.openAsync(file)
     *              .onSuccess(source -> player.play(source))
     *              .onFailure(error -> status.setText(error.getMessage()))
     *              .deliverIf(this::isAttached)
     *              .start();
     * }</pre>
     *
     * <p>Both of {@link #open}'s failures arrive at {@code onFailure} on the UI thread instead of
     * being thrown here (no decoder installed at all, and decoders installed of which none claims
     * the input, the second still naming every decoder asked in order), as does whatever the
     * decoder that accepted the input threw. Nothing at all happens until {@code start()}: the
     * probe runs inside the body, not at this call, which matters because a decoder's
     * {@code supports} is cheap only after that decoder has been prepared, and the first one may
     * link a native library.
     *
     * <p><b>A withdrawn open closes what it opened.</b> The returned description already carries
     * an {@code onDiscarded} that closes the source, so a job cancelled (or a {@code deliverIf}
     * that answers false) while the container was being opened does not leak it: the source is
     * closed on a worker instead of being delivered. A caller replacing its own {@code onDiscarded}
     * takes that job over. Cancelling does not stop the open, because nothing interrupts a decoder
     * inside a native read; it stops the delivery, and disposes of the result.
     *
     * <p>Progress is whatever the chosen decoder chooses to report, which for most opens is nothing
     * at all; an {@code onProgress} handler that never hears anything is the normal case here and
     * not a sign that the open is stuck.
     *
     * @throws NullPointerException  if {@code file} is null
     * @throws IllegalStateException if no backend is running
     */
    public static Work<VideoStreamSource> openAsync(Path file) {
        Objects.requireNonNull(file, "file");
        return Ui.<VideoStreamSource>work(progress -> probeAndOpen(file, progress))
                .onDiscarded(Videos::closeQuietly);
    }

    /**
     * Prepares every installed decoder on the worker pool (a native library linked, a payload
     * extracted) and is returned <b>unstarted</b>. An application that wants it starts it once,
     * after installing its decoders:
     *
     * <pre>{@code
     * Videos.installDecoder(new SomeDecoder());
     * Videos.warmUpAsync().start();
     * }</pre>
     *
     * <p>Like {@link #openAsync}, this is a description and not a running job: calling it and
     * dropping the result warms nothing at all. It keeps that shape rather than starting itself,
     * even though nothing here waits for a result, because every form in this toolkit whose name
     * ends in {@code Async} is an unstarted description, and one rule that holds across all of
     * them is worth more than the {@code start()} it would save; a facade that started itself
     * would be the exception a reader has to remember, at the one call site where forgetting is
     * silent.
     *
     * <p>It buys nothing but the thread the cost is paid on, and that is the point: the first
     * {@link #canOpen} would otherwise pay it, and {@code canOpen} is synchronous by contract
     * because a control deciding whether to enable itself cannot wait for a frame. Skipping this
     * is not an error and changes no answer this class gives; it changes only when, and on which
     * thread, a decoder's one-off preparation happens.
     *
     * <p>A decoder that fails to prepare itself is left in exactly the state it would have been in
     * had nobody warmed it, and the next decoder is warmed anyway; that is why this delivers no
     * failure of its own. Progress runs from 0 to 1 across the installed decoders, in probe order,
     * for an application that shows a splash. Only the decoders installed when the body starts are
     * warmed; one installed afterwards warms itself at its first call, or takes another
     * {@code warmUpAsync}.
     *
     * <p>What {@code onSuccess} receives is {@link #isDecoderInstalled()} (the same answer, and
     * the same shape, as {@code Sounds.warmUpAsync}), so a control can be gated off either of them
     * the same way: {@code Videos.warmUpAsync().onSuccess(playable::setEnabled).start()}. A
     * cancelled warm-up delivers nothing at all, as every cancelled job does.
     *
     * @return the unstarted work; nothing is prepared until {@code start()}, and {@code onSuccess}
     *         then receives whether any decoder is installed to play with
     * @throws IllegalStateException if no backend is running
     */
    public static Work<Boolean> warmUpAsync() {
        return Ui.work(progress -> {
            List<VideoDecoder> decoders = List.copyOf(DECODERS);
            for (int index = 0; index < decoders.size(); index++) {
                if (progress.isCancelled()) {
                    return isDecoderInstalled();
                }
                VideoDecoder decoder = decoders.get(index);
                try {
                    decoder.warmUp();
                } catch (Throwable failure) {
                    // Deliberately swallowed to DEBUG: a warm-up that fails leaves the decoder
                    // exactly as unprepared as it was, which is a supported state and one the next
                    // real call reports properly. Raising it here would make an optimisation
                    // nobody asked for the loudest thing in the log.
                    LOG.log(Level.DEBUG, "warming the " + decoder.name()
                            + " video decoder failed; it will prepare itself when next used",
                            failure);
                }
                progress.report((index + 1.0) / decoders.size());
            }
            return isDecoderInstalled();
        });
    }

    /**
     * The one probe-and-open path, shared by both forms so that neither can grow a diagnostic the
     * other lacks. Runs on the caller's thread for {@link #open} and on a worker for
     * {@link #openAsync}; {@code progress} is what tells the decoder which of those it is in.
     */
    private static VideoStreamSource probeAndOpen(Path file, Progress progress) {
        if (DECODERS.isEmpty()) {
            throw new IllegalStateException(
                    "No VideoDecoder installed: call Videos.installDecoder before opening video"
                            + " (e.g. new Y4mDecoder(), which ships in this module, or new"
                            + " FfmpegVideoDecoder() from limn-video-ffmpeg). A backend does not"
                            + " supply one.");
        }
        StringBuilder asked = new StringBuilder();
        for (VideoDecoder decoder : DECODERS) {
            if (progress.isCancelled()) {
                return null;
            }
            if (decoder.supports(file)) {
                VideoStreamSource source = decoder.openStream(file, progress);
                if (source == null) {
                    if (progress.isCancelled()) {
                        return null;
                    }
                    // Null is the SPI's way of saying "abandoned, nothing was opened", and it is
                    // only legal while cancelled. Letting it through instead would deliver null to
                    // a success handler, which fails somewhere with nothing to point at.
                    throw new IllegalStateException("The " + decoder.name()
                            + " decoder returned no source for " + file
                            + " and the open was not cancelled.");
                }
                return source;
            }
            if (asked.length() > 0) {
                asked.append(", ");
            }
            asked.append(decoder.name());
        }
        throw new UnsupportedOperationException(
                "No VideoDecoder accepts " + file + " (tried, in order: " + asked + ")");
    }

    /**
     * Closes a source nobody received. On a worker thread, where blocking is allowed, and a close
     * that throws must not become the failure of a job that already succeeded.
     */
    private static void closeQuietly(VideoStreamSource source) {
        if (source == null) {
            return;
        }
        try {
            source.close();
        } catch (Throwable failure) {
            LOG.log(Level.DEBUG, "closing an undelivered video source failed", failure);
        }
    }
}
