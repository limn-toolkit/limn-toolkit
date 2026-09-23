package limn.video.ffmpeg;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * Hands a picture back to the shim off the UI thread. Returning a decoded picture releases a
 * libavcodec frame, and for a hardware picture its IOSurface; measured while playing 1080p, that
 * was about 3 % of the UI thread's time, spent on work the UI does not wait for.
 *
 * <p>One daemon thread for every container, over a fixed array, so a release allocates nothing on
 * the thread that submits it: the tasks are the ones {@link NativeFrames} built once per slot. A
 * full queue runs the task on the caller instead, which is what happened before.
 */
final class ReleaseQueue {

    private static final ArrayBlockingQueue<Runnable> QUEUE = new ArrayBlockingQueue<>(256);

    static {
        Thread drainer = new Thread(ReleaseQueue::drain, "limn-ffmpeg-release");
        drainer.setDaemon(true);
        drainer.start();
    }

    private ReleaseQueue() {
    }

    /** Runs {@code release} on the release thread, or here when the queue is full. */
    static void submit(Runnable release) {
        if (!QUEUE.offer(release)) {
            release.run();
        }
    }

    private static void drain() {
        while (true) {
            Runnable release;
            try {
                release = QUEUE.take();
            } catch (InterruptedException stop) {
                return;
            }
            try {
                release.run();
            } catch (RuntimeException | Error failed) {
                System.getLogger(ReleaseQueue.class.getName()).log(System.Logger.Level.WARNING,
                        "returning a picture to the shim failed", failed);
            }
        }
    }
}
