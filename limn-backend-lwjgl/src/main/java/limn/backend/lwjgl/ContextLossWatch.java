package limn.backend.lwjgl;

import limn.backend.Platform;

import java.util.List;

/**
 * Notices that the windows' contexts stopped drawing and makes them again in the same windows
 * (ADR 082 §1.3 and §1.4), once a turn of the event loop.
 *
 * <p><b>Noticing.</b> The driver that loses its contexts at a virtual machine's resume says nothing
 * of it: no error, no reset status. What says the machine was away is the wall clock moving further
 * than the monotonic one between two turns of the loop (310 s against no gap, measured at one
 * resume). On that signal, and only then, each window is asked whether its context still draws,
 * which stalls the GPU once.
 *
 * <p><b>Making them again.</b> Every window lets go of its context, LWJGL's binding is destroyed,
 * {@code opengl32.dll} and the driver's library leave the process, and each window gets a new
 * context on a child surface, verified by drawing. A rebuild that does not verify is tried again a
 * tenth of a second later, since a machine suspended again in the middle of one is the case that
 * needs it; the process never ends for it. Windows only for now: elsewhere a loss is noticed and
 * said.
 */
final class ContextLossWatch {

    private static final System.Logger LOG = System.getLogger(ContextLossWatch.class.getName());

    /** A gap between the two clocks larger than any a loop turn's own scheduling makes. */
    private static final long JUMP_MILLIS = 2_000;
    /** How long a rebuild that does not verify is tried, a tenth of a second apart. */
    private static final int MAX_ATTEMPTS = 100;
    /** How many attempts the system's OpenGL is given before the process moves onto ANGLE. */
    private static final int NATIVE_ATTEMPTS = 10;
    /** The wait between two attempts. */
    static final long RETRY_NANOS = 100_000_000L;

    private static final boolean WINDOWS = Platform.current().os() == Platform.Os.WINDOWS;

    /**
     * {@code -Dlimn.backend.rebuildContextAfter=<seconds>}: makes every window's context again once,
     * that long after the first turn, by the path a loss takes, with no loss; for checking a build,
     * a native image above all, without suspending a machine. Unset, nothing happens.
     */
    static final String REBUILD_PROPERTY = "limn.backend.rebuildContextAfter";

    private final long rebuildAfterNanos = rebuildAfterNanos();
    private long firstTurn = -1;

    private static long rebuildAfterNanos() {
        String value = System.getProperty(REBUILD_PROPERTY);
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return (long) (Double.parseDouble(value.trim()) * 1_000_000_000L);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private long wall = System.currentTimeMillis();
    private long monotonic = System.nanoTime();
    private boolean rebuilding;
    private int attempts;
    private long lostAt;

    /** Whether a rebuild is under way, so that the loop wakes for the next attempt rather than sleeping. */
    boolean rebuilding() {
        return rebuilding;
    }

    /** Called once a turn of the event loop, after the poll and before any window draws. */
    void afterPoll(List<LwjglWindow> windows) {
        long wallNow = System.currentTimeMillis();
        long monotonicNow = System.nanoTime();
        long jump = (wallNow - wall) - (monotonicNow - monotonic) / 1_000_000L;
        wall = wallNow;
        monotonic = monotonicNow;
        if (firstTurn < 0) {
            firstTurn = monotonicNow;
        }
        if (rebuildAfterNanos >= 0 && !rebuilding && !windows.isEmpty()
                && monotonicNow - firstTurn >= rebuildAfterNanos
                && windows.stream().allMatch(LwjglWindow::contextRemakeable)) {
            firstTurn = Long.MAX_VALUE / 2; // once
            LOG.log(System.Logger.Level.WARNING, "making every window''s context again, as -D{0} asks",
                    REBUILD_PROPERTY);
            rebuildNow(windows, false);
            return;
        }
        if (!rebuilding) {
            boolean reported = false;
            for (LwjglWindow window : windows) {
                reported |= window.contextReportedLost();
            }
            if (!reported && (Math.abs(jump) <= JUMP_MILLIS || windows.isEmpty())) {
                return;
            }
            boolean lost = reported;
            if (!reported) {
                LOG.log(System.Logger.Level.INFO, "the wall clock moved {0} ms more than the monotonic one, "
                        + "as after a suspension: asking each window''s context whether it still draws", jump);
                for (LwjglWindow window : windows) {
                    if (!window.contextDraws()) {
                        lost = true;
                        break;
                    }
                }
            }
            if (!lost) {
                return;
            }
            if (!windows.stream().allMatch(LwjglWindow::contextRemakeable)) {
                LOG.log(System.Logger.Level.ERROR, "a window's OpenGL context no longer draws, and on this "
                        + "system the toolkit does not make it again yet (ADR 082)");
                return;
            }
            LOG.log(System.Logger.Level.WARNING, "a window's OpenGL context no longer draws: the device was "
                    + "lost; making every window's context again");
            rebuilding = true;
            attempts = 0;
            lostAt = monotonicNow;
        }
        attempt(windows);
    }

    /**
     * Makes every window's context again now, as a loss would; for tests.
     *
     * @param onAngle whether to go straight to ANGLE, as after the system's OpenGL failed every attempt
     */
    void rebuildNow(List<LwjglWindow> windows, boolean onAngle) {
        rebuilding = true;
        attempts = onAngle ? NATIVE_ATTEMPTS : 0;
        lostAt = System.nanoTime();
        attempt(windows);
    }

    private void attempt(List<LwjglWindow> windows) {
        attempts++;
        for (LwjglWindow window : windows) {
            window.forgetContext();
        }
        String unloaded = "nothing";
        String route = "";
        if (WINDOWS && !AngleFallback.taken()) {
            // A context of the system's OpenGL does not come back while the driver's library stays
            // loaded (§0.2); on Linux nothing is unloaded, and a new context is enough.
            Gl.api().destroyBinding();
            unloaded = WglContext.unloadDriver();
            if (attempts > NATIVE_ATTEMPTS && AngleFallback.takeAfterLoss()) {
                route = ", on ANGLE since the system's OpenGL did not come back";
            }
        } else if (WINDOWS) {
            // ANGLE's contexts went with its Direct3D device: a new display makes a new one.
            EglContext.resetAngleDisplay();
            route = ", on ANGLE";
        }
        boolean drawing = true;
        for (LwjglWindow window : windows) {
            drawing &= window.remakeContext() && window.contextDraws();
        }
        if (drawing) {
            rebuilding = false;
            LOG.log(System.Logger.Level.WARNING, "every window''s OpenGL context was made again, {0} ms "
                    + "after the loss and on attempt {1}{2}", (System.nanoTime() - lostAt) / 1_000_000L, attempts,
                    route.isEmpty() && WINDOWS ? ", on a surface of its own, having unloaded " + unloaded : route);
        } else if (attempts >= MAX_ATTEMPTS) {
            rebuilding = false;
            LOG.log(System.Logger.Level.ERROR, "the windows'' OpenGL contexts could not be made again in "
                    + "{0} attempts; they are tried again at the next sign that the machine was away", attempts);
        }
    }
}
