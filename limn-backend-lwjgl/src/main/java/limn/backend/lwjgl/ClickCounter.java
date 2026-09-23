package limn.backend.lwjgl;

import limn.backend.lwjgl.internal.ObjC;

import limn.backend.lwjgl.internal.NativeLibraries;

import java.util.function.LongSupplier;

import limn.backend.Platform;

import org.lwjgl.system.JNI;
import org.lwjgl.system.SharedLibrary;

/**
 * Counts presses in a row the way the platform does (ADR 046 §5): the second press of one button
 * within the user's double-click interval, and near the first, is a 2, the third a 3. GLFW reports
 * no click count, so until 2026-09-22 the table and the tree each timed 400 ms on their own, which
 * ignored the interval the user had set in the system settings.
 *
 * <p>The interval is read from the platform where there is a call for it: {@code [NSEvent
 * doubleClickInterval]} on macOS, {@code GetDoubleClickTime} on Windows. Elsewhere, and whenever the
 * read fails, 500 ms, which is GTK's and Windows' default.
 */
final class ClickCounter {

    /** How far apart, in logical points, two presses may be and still count as one gesture. */
    static final float SLOP = 4;

    static final long DEFAULT_INTERVAL_NANOS = 500_000_000L;

    private final LongSupplier clock;
    private final long intervalNanos;
    private int lastButton = -1;
    private long lastNanos;
    private float lastX;
    private float lastY;
    private int count;

    ClickCounter(LongSupplier clock, long intervalNanos) {
        this.clock = clock;
        this.intervalNanos = intervalNanos > 0 ? intervalNanos : DEFAULT_INTERVAL_NANOS;
    }

    /** A counter on the real clock with the platform's interval. */
    static ClickCounter forThisPlatform() {
        return new ClickCounter(System::nanoTime, platformIntervalNanos());
    }

    /**
     * @return the count for a press of {@code button} at {@code (x, y)}, in logical points
     */
    int press(int button, float x, float y) {
        long now = clock.getAsLong();
        boolean again = button == lastButton && count > 0 && now - lastNanos < intervalNanos
                && Math.abs(x - lastX) <= SLOP && Math.abs(y - lastY) <= SLOP;
        count = again ? count + 1 : 1;
        lastButton = button;
        lastNanos = now;
        lastX = x;
        lastY = y;
        return count;
    }

    /** @return the count of the press this release ends */
    int release() {
        return Math.max(1, count);
    }

    long intervalNanos() {
        return intervalNanos;
    }

    private static long platformIntervalNanos() {
        try {
            Platform platform = Platform.current();
            if (platform.isMacOs() && ObjC.isAvailable()) {
                double seconds = JNI.invokePPD(ObjC.cls("NSEvent"), ObjC.sel("doubleClickInterval"),
                        ObjC.msgSend());
                return seconds > 0 ? (long) (seconds * 1e9) : 0;
            }
            if (platform.isWindows()) {
                SharedLibrary user32 = NativeLibraries.optional(ClickCounter.class,
                        "limn.backend.lwjgl", "user32");
                long address = user32 == null ? 0 : NativeLibraries.address(user32, "GetDoubleClickTime");
                return address == 0 ? 0 : JNI.invokeI(address) * 1_000_000L;
            }
        } catch (RuntimeException | LinkageError unreadable) {
            // the default below
        }
        return 0;
    }
}
