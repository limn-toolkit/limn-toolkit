package limn.backend.lwjgl;

import limn.animation.ImageAnimation;
import limn.backend.ImageCursor;
import limn.concurrent.Subscription;
import limn.concurrent.UiRuntime;
import org.jspecify.annotations.Nullable;

import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * Plays a window's animated {@link ImageCursor}: shows each frame of its image at the image's
 * own times, as many turns as it asks for, resting on the last, for as long as the window shows
 * it. Between two frames it sleeps on a delayed task of the UI thread; woken, it moves
 * on by the time that passed and not the time it asked for, so a loop held up shows the frame
 * that is due by then and does not walk through the ones it missed.
 *
 * <p>UI thread only.
 */
final class CursorPlayer {

    private final UiRuntime runtime;
    private final LongSupplier clock;
    /** Shows a frame of the cursor played: sets the window's native cursor to it. */
    private final IntConsumer show;
    private @Nullable ImageAnimation playing;
    private @Nullable Subscription alarm;
    private long sleptAt;

    /**
     * @param runtime what the swaps are scheduled on
     * @param clock   what the time that passed is read on: the clock the runtime's delays are
     *                measured on
     * @param show    shows a frame of the cursor played
     */
    CursorPlayer(UiRuntime runtime, LongSupplier clock, IntConsumer show) {
        this.runtime = runtime;
        this.clock = clock;
        this.show = show;
    }

    /**
     * Plays {@code cursor} from its first frame, which the caller has shown, or plays nothing.
     *
     * @param cursor the cursor the window shows now, or {@code null}
     */
    void play(@Nullable ImageCursor cursor) {
        stop();
        if (cursor != null && cursor.image().isAnimated()) {
            playing = new ImageAnimation(cursor.image());
            sleep();
        }
    }

    /** Stops playing: the window shows another cursor, or none, or is gone. */
    void stop() {
        Subscription waiting = alarm;
        if (waiting != null) {
            waiting.cancel();
            alarm = null;
        }
        playing = null;
    }

    /** @return the frame shown, 0 when nothing plays */
    int frame() {
        ImageAnimation animation = playing;
        return animation == null ? 0 : animation.frameIndex();
    }

    private void sleep() {
        ImageAnimation animation = playing;
        if (animation == null || !animation.isPlaying()) {
            return;
        }
        double due = animation.secondsUntilNextFrame();
        sleptAt = clock.getAsLong();
        alarm = runtime.postDelayed(() -> {
            alarm = null;
            if (playing != animation) {
                return;
            }
            if (animation.advance(Math.max(0, (clock.getAsLong() - sleptAt) / 1e9))) {
                show.accept(animation.frameIndex());
            }
            sleep();
        }, Math.max(1, (long) Math.ceil(due * 1000)));
    }
}
