package limn.a11y.windows;

import limn.backend.AccessibilityBridge;

/**
 * The UI Automation bridge: what an application hands its window so that a screen reader on
 * Windows can read it.
 *
 * <p><b>This is ADR 039's phase 6, and it is not finished.</b> What exists today is the half that
 * can be settled without a provider: the identifiers, read off a guest rather than recalled
 * ({@link UiaIds}); the map from this toolkit's roles to UI Automation's control types, with every
 * place the two vocabularies disagree written down ({@link UiaRoles}); and the binding to
 * {@code uiautomationcore} with the listening gate the whole tree hangs on ({@link Uia}). What does
 * not exist is the provider — the {@code WM_GETOBJECT} hookup, the element registry, the vtables
 * behind {@code IRawElementProviderSimple} and its fragment interfaces, and the event raises. Until
 * it does, {@link #openIfEnabled} answers {@link AccessibilityBridge#NONE} on every machine.
 *
 * <p>It answers {@code NONE} rather than a bridge that reports itself listening, and the difference
 * is not cosmetic: a bridge that said yes would make the scene walk its whole widget tree, diff it
 * and hold a snapshot on every frame, for a client that has no way to ask for any of it. The seam
 * exists so that a window with nobody reading it pays nothing, and an unfinished bridge is a window
 * with nobody reading it.
 */
public final class UiaBridge implements AccessibilityBridge {

    private UiaBridge() {
    }

    /**
     * Opens a bridge if this machine has UI Automation and a client is listening, and otherwise
     * nothing.
     *
     * <p>The gate is read before anything is allocated, exactly as the Linux bridge reads the
     * desktop's own flag first: a window on a machine with no screen reader is meant to cost one
     * call and never a registry, a provider or a thread. A machine that is not Windows at all is
     * not an error and answers no.
     *
     * @param hwnd the window handle to place the tree under, from the backend's native window
     * @return {@link AccessibilityBridge#NONE}, always, until the provider named in this class's
     *         javadoc exists
     */
    public static AccessibilityBridge openIfEnabled(long hwnd) {
        return AccessibilityBridge.NONE;
    }
}
