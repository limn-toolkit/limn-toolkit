package limn.backend.lwjgl.a11y;

import limn.backend.AccessibilityBridge;
import limn.backend.lwjgl.a11y.linux.AtspiBridge;
import limn.backend.lwjgl.a11y.macos.AxBridge;
import limn.backend.lwjgl.a11y.windows.UiaBridge;
import org.lwjgl.system.Platform;

/**
 * Picks the accessibility bridge for the machine this window is on, or none.
 *
 * <p><b>The backend installs it, and that is the point of this class existing at all.</b> While the
 * three bridges were artifacts of their own, an application had to add the right jar <em>and</em>
 * hand the bridge to its window — and phase 6 found the second half missing: a window could be
 * asked what its accessibility was and had no way to be told, so an application could add the
 * artifact and never install it, with no symptom but silence. That failure mode is now
 * unreachable: the bridges ship with the backend, and the backend connects them.
 *
 * <p><b>Nothing here opens anything on a machine that is not listening.</b> Each factory reads its
 * own gate first, before a registry, a socket or a thread exists: UI Automation is asked whether it
 * is present, AT-SPI is asked whether the desktop has assistive technology switched on, and macOS —
 * the one platform with no such question (§6) — checks only that AppKit is reachable and that the
 * window has a handle, then costs one tree walk on the scene's first frame and nothing after.
 *
 * <p>A platform with no bridge, or a window with no native handle, answers
 * {@link AccessibilityBridge#NONE}, whose every member is a constant.
 */
public final class Bridges {

    private Bridges() {
    }

    /**
     * <p><b>The handle must be a real one or zero, and there is no third case this can defend
     * against.</b> Two of these factories send a message to the object it names, and a message to a
     * pointer that is neither is undefined behaviour that no {@code catch} reaches — a test that
     * passed {@code 0xDEADBEEF} here took the whole JVM down with it. The contract holds because
     * {@code NativeWindow#nativeHandle()} answers what the windowing library gave it or zero, and
     * nothing else may call this.
     *
     * @param nativeHandle    the window's own handle, or zero on a platform the backend has not
     *                        been taught
     * @param applicationName what the desktop should call this application, for the platforms that
     *                        publish a name
     * @return a bridge for this platform, or {@link AccessibilityBridge#NONE}
     */
    public static AccessibilityBridge openFor(long nativeHandle, String applicationName) {
        try {
            return switch (Platform.get()) {
                case WINDOWS -> UiaBridge.openIfEnabled(nativeHandle);
                case MACOSX -> AxBridge.openIfEnabled(nativeHandle);
                // The one that needs no handle: it addresses nodes by object path over a socket and
                // never touches the window.
                case LINUX -> AtspiBridge.openIfEnabled(applicationName);
                default -> AccessibilityBridge.NONE;
            };
        } catch (Throwable refusedByThePlatform) {
            // A window that cannot be made accessible is still a window. Every one of these
            // factories is the first thing in the process to touch a platform API that may be
            // absent, restricted or a version nobody here has seen -- a missing framework, a
            // library that will not load, a symbol that has moved -- and none of that is a reason
            // for a window not to open. It does not cover a bad handle; see above.
            return AccessibilityBridge.NONE;
        }
    }
}
