package limn.backend.lwjgl;

import limn.backend.Platform;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The GL entry points a window's context resolved, kept for the next window whose context is of
 * the same kind, so that opening a popup does not resolve every one of them again.
 *
 * <p>Resolving them is what {@code GL.createCapabilities()} does, one symbol lookup per GL
 * function, and it was two thirds of opening a popup: on the Mac of 2026-10-16 it took 19.6 to
 * 20.1 ms of a 25 to 44 ms UI task (a combo list, a calendar, a menu), against 4 to 7 ms for the
 * window and its context together, while {@code GL.setCapabilities} with the first window's
 * capabilities took 0.02 ms and drew in the new context without a GL error. Every popup is a new
 * unshared context, so no warm-up helps; the lookup is paid per window unless it is kept.
 *
 * <p>Keeping it is sound where the addresses are the same for every context of a process: macOS
 * resolves them in its OpenGL framework, and Linux in a libGL that dispatches to whichever context
 * is current (GLX and EGL alike, GLVND or Mesa). On Windows {@code wglGetProcAddress} answers for
 * the driver of the current context, so the addresses are the same only between contexts of one
 * driver. {@code GlCapabilitiesReuseTest}'s child, run both ways on 2026-10-16 (20 popups a run,
 * each painted and read back), measured what a popup's {@code createWindow} costs with and
 * without the reuse: on this Mac 5.8 against 27.9 ms; on the Fedora KDE guest (Wayland, EGL) 3.5
 * to 3.8 against 4.9 to 5.4 ms; on the Ubuntu guest (X11, GLX) 7.9 to 9.0 against 8.3 to 8.5 ms,
 * no gain and no harm; and on the Windows 11 ARM64 guest (WGL, a native aarch64 JVM, three runs
 * each way) a fastest popup of 17.8 to 19.8 against 19.0 to 20.8 ms with medians from 18.8 to
 * 27.5 ms either way, a difference inside the noise. Every popup drew the right pixels with
 * shared capabilities on all four, but Windows gains nothing for the risk of a driver that
 * differs, so it keeps resolving per window. The capabilities also say what
 * the context has (its version, its extensions), so contexts that could differ in that never share:
 * the {@link Key} names the kind of window (transparent framebuffer or not, the macOS software
 * context or not) and what the context itself says it is (vendor, renderer, version).
 *
 * <p>An entry lives while a window holds it, and goes with the last: a driver that is unloaded
 * when its last context is (which Windows may do) must not leave a later window with its old
 * addresses. In practice an application's main window holds the entry its popups use.
 *
 * <p>Used on the UI thread only, where windows are made and destroyed.
 *
 * @param <C> what is kept: {@code GLCapabilities} in the backend, anything in a test
 */
final class GlCapabilitiesCache<C> {

    /**
     * Turns the reuse on ({@code true}) or off ({@code false}) whatever this platform's default:
     * the way out if a driver turns out to give different addresses to contexts that say they are
     * the same.
     */
    static final String PROPERTY = "limn.backend.reuseGlCapabilities";

    /**
     * What makes two contexts' capabilities interchangeable. The strings are each context's own
     * {@code GL_VENDOR}, {@code GL_RENDERER} and {@code GL_VERSION}, read before its capabilities
     * exist, and may be null where a context answered none, which matches only another that
     * answered none.
     */
    record Key(boolean transparent, boolean softwareGl, @Nullable String vendor,
            @Nullable String renderer, @Nullable String version) {
    }

    private static final class Entry<C> {
        final C capabilities;
        int holders;

        Entry(C capabilities) {
            this.capabilities = capabilities;
        }
    }

    private final boolean reuses;
    private final Map<Key, Entry<C>> entries = new HashMap<>();

    /** @param reuses false makes {@link #acquire} create every time and keep nothing */
    GlCapabilitiesCache(boolean reuses) {
        this.reuses = reuses;
    }

    /**
     * Whether this platform reuses capabilities when nothing asks otherwise: on macOS and Linux,
     * for the reasons in the class description; on Windows not, where it was measured to gain
     * nothing.
     *
     * @param os       the platform
     * @param property what {@link #PROPERTY} says, or null when it is not set; {@code true} or
     *                 {@code false} (in any case) win over the platform, anything else is ignored
     */
    static boolean reusesByDefault(Platform.Os os, @Nullable String property) {
        if ("true".equalsIgnoreCase(property)) {
            return true;
        }
        if ("false".equalsIgnoreCase(property)) {
            return false;
        }
        return os == Platform.Os.MACOS || os == Platform.Os.LINUX;
    }

    /** Whether this cache keeps what it creates. */
    boolean reuses() {
        return reuses;
    }

    /**
     * The capabilities for a new window's context, which must be current: those a living window of
     * the same {@code key} holds, or new ones from {@code create}. Each call is one hold, given back
     * with {@link #release} when the window is destroyed.
     */
    C acquire(Key key, Supplier<C> create) {
        if (!reuses) {
            return create.get();
        }
        Entry<C> entry = entries.get(key);
        if (entry == null) {
            entry = new Entry<>(create.get());
            entries.put(key, entry);
        }
        entry.holders++;
        return entry.capabilities;
    }

    /**
     * Gives back one hold of {@code key}, taken by {@link #acquire}; the last one drops the entry,
     * so the next window of that kind resolves its entry points again.
     */
    void release(Key key) {
        if (!reuses) {
            return;
        }
        Entry<C> entry = entries.get(key);
        if (entry != null && --entry.holders <= 0) {
            entries.remove(key);
        }
    }

    /** How many kinds of context are kept now, for tests. */
    int size() {
        return entries.size();
    }
}
