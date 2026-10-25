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
 * driver.
 *
 * <p>What two contexts must have in common to share is what {@code GL.createCapabilities()} reads
 * from a context, and nothing else: read from LWJGL 3.4.3's bytecode, it builds the capabilities
 * from the function provider's addresses, {@code GL_VERSION}, the extension list, and the context
 * flags and profile mask that decide forward compatibility, and it never asks about the pixel
 * format. So the {@link Key} is those, the vendor and renderer that say which driver answered,
 * and whether the context is the macOS software one, which is built apart from the windowing
 * library; a transparent framebuffer is not in it. It was until 2026-10-17, and since every popup
 * is transparent and a main window is not, the main window held an entry no popup used, the
 * popups' entry went with each popup as it closed, and a list, a calendar or a menu opened,
 * closed and opened again resolved everything every time: the reuse only ever served popups
 * open together.
 *
 * <p>{@code GlCapabilitiesReuseTest}'s child opens popups one at a time over an opaque owner,
 * each painted, read back and closed before the next, as a combo box's list is. Measured both
 * ways on 2026-10-17 (15 popups a run, two runs each), a popup's {@code createWindow} costs, with
 * the reuse against without: on this Mac 4.7 to 5.4 against 24.3 to 24.8 ms; on the Windows 11
 * ARM64 guest (WGL, a native aarch64 JVM) 19.4 to 19.7 against 20.5 ms, every popup sharing its
 * owner's capabilities and drawing the right pixels. Measured on 2026-10-16 with popups open
 * together: on the Fedora KDE guest (Wayland, EGL) 3.5 to 3.8 against 4.9 to 5.4 ms; on the
 * Ubuntu guest (X11, GLX) 7.9 to 9.0 against 8.3 to 8.5 ms, no gain and no harm; on the Windows
 * guest a fastest popup of 17.8 to 19.8 against 19.0 to 20.8 ms, inside the noise; the right
 * pixels on all of them. Windows reuses them as well, by the owner's decision of 2026-10-16: that
 * guest runs the hypervisor's virtual driver on ARM, which says little about the cost of a real
 * machine's driver; the key names the driver, which is what differs between one machine and
 * another; and {@link #PROPERTY} set to {@code false} is the way out for a driver that
 * misbehaves.
 *
 * <p>An entry lives while a window holds it, and goes with the last: a driver that is unloaded
 * when its last context is (which Windows may do) must not leave a later window with its old
 * addresses. An application's main window holds the entry its popups use.
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
     * What makes two contexts' capabilities interchangeable: everything
     * {@code GL.createCapabilities()} reads from the context, and which driver answered it. Read
     * before the context has capabilities ({@link GraphicsProbe#currentContextKey}).
     *
     * @param softwareGl  whether the context is the macOS software one, made apart from the
     *                    windowing library ({@link MacSoftwareGl})
     * @param vendor      {@code GL_VENDOR}, or null where the context answered none
     * @param renderer    {@code GL_RENDERER}, or null
     * @param version     {@code GL_VERSION}, or null
     * @param flags       {@code GL_CONTEXT_FLAGS}, forward compatibility among them
     * @param profile     {@code GL_CONTEXT_PROFILE_MASK}
     * @param extensions  the context's {@code GL_EXTENSIONS}, in the order it gave them
     */
    record Key(boolean softwareGl, @Nullable String vendor, @Nullable String renderer,
            @Nullable String version, int flags, int profile, String extensions) {
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
     * Whether this platform reuses capabilities when nothing asks otherwise: on macOS, Linux and
     * Windows, for the reasons in the class description; on any other system not, since none was
     * measured.
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
        return os == Platform.Os.MACOS || os == Platform.Os.LINUX || os == Platform.Os.WINDOWS;
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
