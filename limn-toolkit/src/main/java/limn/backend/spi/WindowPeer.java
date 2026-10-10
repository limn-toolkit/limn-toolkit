package limn.backend.spi;

import limn.backend.NativeWindow;
import org.jspecify.annotations.Nullable;

/**
 * What the toolkit asks of a window below the application's view of it: the input it delivers,
 * the frames it asks for, the input method, the popups it owns and whether it is a modal. Every
 * {@link NativeWindow} is one, so the toolkit reaches this with {@link #of(NativeWindow)}; a
 * backend implements it.
 *
 * <p><b>Incubating</b>, as the rest of this package: it may change in a minor release.
 */
public non-sealed interface WindowPeer extends NativeWindow {

    /**
     * @param window any window
     * @return the same window as a peer; every window is one, since {@link NativeWindow} permits no
     *         other implementation
     */
    static WindowPeer of(NativeWindow window) {
        return (WindowPeer) window;
    }

    /**
     * Says what text this window wants from the platform: none, typed characters with the input
     * method off, or characters and the input method's composition. The scene asks for composition
     * while a text-editing widget holds focus and for characters otherwise, so composition keys
     * never leak into a control that is not text, and a list's type-ahead still hears letters.
     * UI thread only.
     *
     * <p>No default, nor for the two IME methods below: a backend that ignored them broke Chinese,
     * Japanese and Korean input without a word. A backend with no input method (headless)
     * implements them as no-ops, on purpose and in writing.
     *
     * @param input what the window wants; never {@code null}
     */
    void setTextInput(TextInput input);

    /**
     * Positions the IME candidate/composition window at the caret, so it follows
     * the text being edited. {@code x}/{@code y}/{@code width}/{@code height} are
     * in logical points relative to the window's content area (the scene feeds
     * the focused widget's {@code caretRect}). UI thread only.
     */
    void setPreeditCaretRect(float x, float y, float width, float height);

    /**
     * Cancels any in-progress IME composition held by the platform for this
     * window. The scene calls it when focus leaves a text-editing widget, so a
     * composition started there can never commit into whatever is focused next.
     * A no-op when nothing is being composed. UI thread only.
     */
    void resetPreedit();

    /**
     * @return whether this window is itself an active modal: one the backend registered through
     *         {@link BackendPeer#pushModal}, blocking the window it was opened over, or every other
     *         window when it was opened over none. The other end of {@link #isModalBlocked()}:
     *         that is the owner's bit and this is the dialog's, and it is this one an accessible
     *         tree publishes on the window's own node, because a client asks a window whether it
     *         blocks what it owns and never whether something else blocks it
     */
    boolean isModal();

    /**
     * What a registered child window <em>is</em>, which decides whether a modal over the owner
     * leaves it usable.
     *
     * <p>Both kinds close and move with the owner; they differ only under an in-scene modal,
     * which exempts its host so the host can keep drawing its own content outside its frame.
     * Without this distinction that exemption reaches anything the host registered, and a
     * non-modal dialog stayed clickable underneath a modal, where a native modal would have
     * frozen it, and where the application had every reason to expect it frozen.
     */
    enum PopupKind {
        /**
         * The owner's own content, drawn outside its frame because the OS gives it no other way:
         * a dropdown's list, a menu cascade, a tooltip. It stays usable under an in-scene modal
         * whose host is this window, because it <em>is</em> that host.
         */
        TRANSIENT,
        /**
         * A window in its own right that happens to be owned for lifetime and movement: a
         * non-modal dialog, a floating palette. A modal over the owner blocks it like any other
         * window.
         */
        OWNED_WINDOW
    }

    /**
     * Registers a child popup to be closed automatically when this window
     * closes, so a combo/menu popup can never outlive its parent.
     *
     * <p>Registers it as {@link PopupKind#TRANSIENT}, which is what a dropdown or a menu is.
     * A window of its own has to say so: see {@link #registerChildPopup(NativeWindow, PopupKind)}.
     */
    default void registerChildPopup(NativeWindow child) {
        registerChildPopup(child, PopupKind.TRANSIENT);
    }

    /**
     * Registers a child popup and says what it is; see {@link PopupKind}, which is the whole
     * reason this overload exists.
     */
    void registerChildPopup(NativeWindow child, PopupKind kind);

    /** Unregisters a child popup that closed on its own. */
    void unregisterChildPopup(NativeWindow child);

    /** Registers the per-frame render callback. */
    void setFrameCallback(FrameCallback callback);

    /** Registers the input sink (usually the scene). See {@link WindowInput}. */
    void setInput(WindowInput input);

    /**
     * Whether {@link #screenX()}, {@link #screenY()} and {@link #setScreenPosition(int, int)}
     * actually work on this platform. When {@code false} the getters report a placeholder and
     * the setter is ignored: a window is placed by the desktop and never learns where it went.
     *
     * <p>Ask this before drawing anything <em>outside</em> a window that has to line up with
     * something inside it: a dropdown's list, a menu cascade, a tooltip. Those are not windows
     * in their own right; they are the owner's content, escaping the frame because most desktops
     * offer no other way to overflow it. Where the answer is {@code false} there is no such way,
     * and the content has to be drawn in the scene instead, clipped to the owner.
     *
     * <p>A window that is genuinely a window (a document, a dialog, a palette) needs no such
     * check. It has no position to agree with, so letting the desktop place it is the right
     * outcome rather than a fallback.
     *
     * <p>No default. It answered {@code true} until 2026-09-22, which is the unsafe
     * answer: a backend that said nothing claimed a power Wayland does not have, and a popup that
     * trusted it opened wherever the compositor put it. Absolute window position is absent from
     * that protocol by design, so a backend has to say.
     */
    boolean supportsAbsolutePositioning();

    /**
     * Says this window's contents are published in another window's accessible tree: a popup the
     * scene that opened it grafted into its own tree, as a native drop-down list is its field's
     * child. The window then opens no bridge of its own, and where the platform would tell an
     * assistive technology that a window appeared, it is kept out of that too: VoiceOver said "has
     * a new window" a few seconds after every opening of a combo's list. Called before the window is
     * shown.
     *
     * <p>{@code default} and doing nothing, which is right for a window with no accessibility.
     */
    default void publishAccessibilityElsewhere() {
    }

    /**
     * @return whether this window can hand a drag to the system, so that it leaves the window:
     *         {@link #startDrag} is worth preparing a picture for
     */
    default boolean supportsDrag() {
        return false;
    }

    /**
     * Hands a drag to the system, while a mouse button is down in this window. From here until
     * {@code request.ended()} is told, the pointer is the system's: the window hears no pointer
     * events, and what is under the pointer in this window or another is asked through
     * {@link WindowInput#dragOver}.
     *
     * @param request what is dragged and who hears the end
     * @return whether the system took it; false leaves the drag to the scene
     */
    default boolean startDrag(DragRequest request) {
        return false;
    }
}
