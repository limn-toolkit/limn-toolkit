package limn.a11y.windows;

import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.SharedLibrary;

/**
 * The one place this module reaches {@code uiautomationcore}, and the one place it can fail to.
 *
 * <p><b>Every entry point is resolved once, at class initialization, and a failure to resolve any
 * of them leaves the whole binding unavailable rather than half of it.</b> This runs on machines
 * that are not Windows — the test suite of this repository, for one — so "the library is not here"
 * is an ordinary answer and not an error: {@link #isAvailable()} says no, {@link UiaBridge} hands
 * back {@link limn.backend.AccessibilityBridge#NONE}, and nothing throws. A partial binding would
 * be worse than none, because the failure would arrive later, on a thread belonging to a screen
 * reader, in a window a user is trying to read.
 *
 * <p>No shim, for ADR 039 §10.2's reason: the awkward parts of this API are a hand-written
 * {@code VARIANT} layout and a couple of call sites where the ABI leaks, and both are answered
 * better by a small typed layer in Java than by a new binary in the build.
 */
final class Uia {

    private Uia() {
    }

    /** The library, or {@code null} on a machine that has none — which is most of them. */
    private static final SharedLibrary CORE = open();

    /** {@code UiaClientsAreListening()}, or {@code 0} when the library did not open. */
    private static final long CLIENTS_ARE_LISTENING =
            CORE == null ? 0L : address("UiaClientsAreListening");

    /** {@code UiaReturnRawElementProvider(HWND, WPARAM, LPARAM, IRawElementProviderSimple*)}. */
    private static final long RETURN_RAW_ELEMENT_PROVIDER =
            CORE == null ? 0L : address("UiaReturnRawElementProvider");

    /** {@code UiaRaiseAutomationEvent(IRawElementProviderSimple*, EVENTID)}. */
    private static final long RAISE_AUTOMATION_EVENT =
            CORE == null ? 0L : address("UiaRaiseAutomationEvent");

    /** {@code UiaDisconnectProvider(IRawElementProviderSimple*)}. */
    private static final long DISCONNECT_PROVIDER =
            CORE == null ? 0L : address("UiaDisconnectProvider");

    private static SharedLibrary open() {
        try {
            return Library.loadNative(Uia.class, "limn.a11y.windows", "uiautomationcore");
        } catch (Throwable absent) {
            // Not Windows, or a Windows without the library: both are "no reader here" and
            // neither is this module's business to complain about.
            return null;
        }
    }

    private static long address(String function) {
        try {
            return APIUtil.apiGetFunctionAddress(CORE, function);
        } catch (Throwable missing) {
            return 0L;
        }
    }

    /**
     * @return whether UI Automation is present on this machine and every entry point this module
     *         needs resolved against it
     */
    static boolean isAvailable() {
        return CORE != null && CLIENTS_ARE_LISTENING != 0L
                && RETURN_RAW_ELEMENT_PROVIDER != 0L && RAISE_AUTOMATION_EVENT != 0L;
    }

    /**
     * Answers a window's {@code WM_GETOBJECT} with this bridge's root provider.
     *
     * @param hwnd     the window the message arrived for
     * @param wparam   the message's own, passed straight back
     * @param lparam   the same
     * @param provider the root element's {@code IRawElementProviderSimple} pointer
     * @return the {@code LRESULT} to return from the window procedure
     */
    static long returnRawElementProvider(long hwnd, long wparam, long lparam, long provider) {
        if (!isAvailable()) {
            return 0;
        }
        return JNI.invokePPPPP(hwnd, wparam, lparam, provider, RETURN_RAW_ELEMENT_PROVIDER);
    }

    /**
     * Tells every listening client that something happened on an element.
     *
     * @param provider the element it happened to
     * @param eventId  one of {@link UiaIds}' event ids
     * @return the {@code HRESULT}
     */
    static int raiseAutomationEvent(long provider, int eventId) {
        if (!isAvailable() || provider == 0) {
            return UiaIds.S_OK;
        }
        return JNI.invokePI(provider, eventId, RAISE_AUTOMATION_EVENT);
    }

    /**
     * Tells UI Automation to drop every client reference to a provider.
     *
     * <p>What a window does on its way out: without it a client holding an element for a window
     * that has closed keeps this process's memory alive for as long as it likes.
     *
     * @param provider the element to disconnect, or {@code 0}
     */
    static void disconnectProvider(long provider) {
        if (DISCONNECT_PROVIDER != 0 && provider != 0) {
            JNI.invokePI(provider, DISCONNECT_PROVIDER);
        }
    }

    /**
     * Whether any client is listening, which is the gate the whole tree hangs on.
     *
     * <p>Asked once per frame on the user-interface thread and nowhere else (§3.4): it is the one
     * piece of this bridge's state that is not state at all, because UI Automation answers it and
     * keeps it. A window on a machine with no screen reader running is meant to pay one call to
     * this and nothing else — no walk, no tree, no element.
     *
     * @return {@code false} on a machine with no UI Automation, which is what a caller that must
     *         not pay for accessibility wants to hear
     */
    static boolean clientsAreListening() {
        return isAvailable() && JNI.callI(CLIENTS_ARE_LISTENING) != 0;
    }
}
