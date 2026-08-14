package limn.video;

import limn.concurrent.Progress;

import java.nio.file.Path;

/**
 * Opens a video input for incremental decoding. Several decoders coexist and are asked, in order,
 * which inputs they will take; register one to make it reachable.
 *
 * <p>There is no decode-it-all entry point, unlike audio. A short sound decoded whole is a few
 * hundred kilobytes and must retrigger with no latency; the same treatment of four minutes at
 * 1080p is over twenty gigabytes of decoded pictures. Video is stream-only, by construction.
 */
public interface VideoDecoder {

    /**
     * A short, stable, lower-case identifier used in diagnostics. It appears in the failure raised
     * when nothing accepts an input, which is the only way anyone finds out which decoders existed
     * and in what order they were consulted.
     *
     * <p>It carries no meaning and nothing may branch on it; the wrong edit this prevents is a
     * comparison against a literal name somewhere on a control-flow path, which would put a
     * particular decoder's identity into logic that is supposed to be decoder-neutral.
     *
     * <p>Deliberately without a default: deriving it from the implementing class produces an
     * unreadable synthetic name for a lambda or an anonymous class, in exactly the message that
     * exists to be readable.
     *
     * @return the identifier, never null and never blank
     */
    String name();

    /**
     * Whether this decoder will attempt {@code file}.
     *
     * <p>Cheap and honest. It may look at the extension and read the first few bytes; it must not
     * parse a whole container, build an index, touch a network or decode anything, because it runs
     * on the caller's thread, once per installed decoder, every time an input is opened.
     *
     * <p>It promises only that this decoder claims the input, not that the input opens, is
     * well-formed, or decodes to the end. It must never throw: a missing, unreadable or surprising
     * input is {@code false}, because one bad file that throws here makes the whole probe unusable
     * for every other decoder behind it.
     *
     * <p>Deliberately without a default, because both possible defaults are wrong: claiming
     * everything destroys the ordering, and claiming nothing makes a decoder that forgot to
     * override it silently unreachable.
     */
    boolean supports(Path file);

    /**
     * Opens {@code file} for picture-by-picture decoding. The caller owns the returned source and
     * closes it.
     *
     * <p>Called only after {@link #supports} returned true for the same path. Throwing here is
     * final (no other decoder is tried), so throw with a message that says what was wrong with the
     * input, because that message is strictly better than the generic one a fallthrough would
     * produce.
     *
     * <p><b>Unlike {@link #supports}, this is allowed to be slow, and normally is.</b> Reading a
     * container's headers, probing every stream in it to fill in what those headers do not state,
     * building an index, opening a decoder, attaching a hardware device, and (the first time)
     * loading a native library are all this method's, and together they are far longer than a
     * frame. It runs on whatever thread called it, so a caller on the UI thread loses every frame
     * until it returns. That is what {@link #openStream(Path, Progress)} exists for: it is the
     * overload the asynchronous facade calls, and the one to override when there is anything here
     * worth abandoning early.
     *
     * @throws RuntimeException if the input cannot be opened or holds no usable video
     */
    VideoStreamSource openStream(Path file);

    /**
     * Opens {@code file} exactly as {@link #openStream(Path)} does, with a handle on the request
     * that asked for it.
     *
     * <p>Called on a worker thread and never on the UI thread, and it may block for as long as the
     * input takes; that is the whole reason this overload exists. It must not touch widget or
     * scene state. The source it returns has been touched by no other thread, and is handed to
     * whoever asked for it on the UI thread; from that point the usual rule applies, and the
     * stream belongs to whichever thread decodes it until it is closed.
     *
     * <p><b>Cancellation is only what this method makes of it.</b> Nothing interrupts the call, so
     * a decoder parked inside a native read stays there until that read returns; consulting
     * {@code progress.isCancelled()} is the entire mechanism. Ask wherever abandoning is cheap
     * (after a header read, between index passes, before attaching a hardware device) and return
     * {@code null} when it answers true. Returning null there is correct and is not a failure;
     * anything already opened must be closed first, because a source that is never returned is a
     * source nobody can close. Returning null when it answers false is a programming error and is
     * reported as one.
     *
     * <p>{@code progress.report(fraction)} is optional, allocates nothing, and takes 0 to 1. An
     * open with no measurable interior should report nothing rather than invent a scale; the
     * caller's progress handler simply never hears from it.
     *
     * <p>The default delegates to {@link #openStream(Path)} and consults nothing, which is correct
     * and merely uncancellable; a decoder overrides this when it has a seam to check at.
     *
     * @param progress cancellation and reporting for the job running this open, never null
     * @return the open source, or null if and only if {@code progress.isCancelled()} answers true
     * @throws RuntimeException if the input cannot be opened or holds no usable video
     */
    default VideoStreamSource openStream(Path file, Progress progress) {
        return openStream(file);
    }

    /**
     * Does whatever this decoder would otherwise do lazily on its first real call (load a native
     * library, extract a payload, build a table) so that no caller pays for it by surprise.
     *
     * <p>It exists because {@link #supports} must stay cheap and cannot honour that while also
     * being the call that first links a library: a file chooser merely asking whether a clip is
     * playable would then pay the whole cost, on whichever thread asked. Called on a worker
     * thread, allowed to block, and idempotent: the second call costs nothing, since what it
     * prepares is prepared once per process.
     *
     * <p><b>It must not throw</b>, and nothing is decided by its outcome. A decoder that cannot
     * prepare itself is in exactly the state it would have been in had nobody warmed it, and says
     * so at the next {@link #supports} the way it always did.
     *
     * <p>The default does nothing, which is right for a decoder whose first call is already cheap.
     */
    default void warmUp() {
    }
}
