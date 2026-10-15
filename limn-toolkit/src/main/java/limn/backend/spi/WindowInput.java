package limn.backend.spi;

import limn.backend.spi.WindowInput;

/**
 * Receives a window's translated native input on the UI thread, in logical
 * points. The backend forwards events as they arrive during the native poll;
 * after each poll (and <em>before</em> draining {@code Ui.post} tasks), it
 * calls {@link #inputBatchEnded()}, which is where the scene dispatches its
 * (coalesced) queue. That yields the deterministic per-frame order: user
 * input → posted tasks → animation → layout → render.
 *
 * <p>Key codes and the modifier bitmask follow {@link limn.input.Keys}
 * (GLFW-compatible values, but defined by the toolkit; no backend types leak).
 */
public interface WindowInput {

    void mouseMoved(float x, float y);

    /**
     * Relative pointer motion while the window is in
     * {@link limn.backend.PointerMode#RELATIVE}: unbounded deltas in logical points
     * (raw/unaccelerated where the platform supports it), replacing
     * {@link #mouseMoved} for the duration of the capture. The receiver may
     * coalesce by summing. Default: ignored.
     */
    default void mouseDelta(float dx, float dy) {
    }

    /**
     * Button press/release at the given cursor position, with how many presses in a row this is,
     * counted by the backend with the platform's own double-click interval and distance:
     * 1 for a single press, 2 for the second of a double click. {@code 0} means the backend does not
     * count, and the receiver counts with its own clock.
     */
    void mouseButton(int button, boolean pressed, int modifiers, float x, float y, int clickCount);

    /** A press or release the backend did not count: {@code clickCount} 0. */
    default void mouseButton(int button, boolean pressed, int modifiers, float x, float y) {
        mouseButton(button, pressed, modifiers, x, y, 0);
    }

    /** Wheel/trackpad scroll; deltas in native notches (positive = up/left). */
    void scrolled(float deltaX, float deltaY, float x, float y);

    /**
     * A scroll the platform says begins a new gesture: the fingers came down on a trackpad or on
     * a mouse whose surface scrolls, inertia from the gesture before still running or not. A
     * receiver keeps a gesture with the scroller it moved, and only the platform can say where one
     * gesture ends and the next begins while inertia keeps the events coming; a backend that
     * cannot say calls {@link #scrolled(float, float, float, float)}, and the receiver tells
     * gestures apart by the pauses between them.
     *
     * @param gestureBegins whether this event is the first of a new gesture
     */
    default void scrolled(float deltaX, float deltaY, float x, float y, boolean gestureBegins) {
        scrolled(deltaX, deltaY, x, y);
    }

    /**
     * A key went down, repeated or came up.
     *
     * @param key             the physical key, a {@code Keys} constant
     * @param layoutCodePoint what the active layout types at that key with no modifier held, as a
     *                        code point, or {@code 0} for a key that types nothing; shortcuts are
     *                        read from it
     * @param pressed         a press or an auto-repeat rather than a release
     * @param repeat          an auto-repeat
     * @param modifiers       the {@code Keys.MOD_*} bits held
     */
    void keyEvent(int key, int layoutCodePoint, boolean pressed, boolean repeat, int modifiers);

    /**
     * A key on a US layout, where the key printed on a keycap is the physical key's name: what a
     * test drives.
     */
    default void keyEvent(int key, boolean pressed, boolean repeat, int modifiers) {
        keyEvent(key, limn.input.Keys.usLayoutCodePoint(key), pressed, repeat, modifiers);
    }

    /** Committed text input, one code point (IME/layout-aware, after key events). */
    void charTyped(int codepoint);

    /**
     * In-progress IME composition ("preedit") for the focused text input: the
     * still-composing text, its caret, and the stretch of it the input method is converting
     * now (the target the user is choosing a candidate for), shown inline but not yet
     * committed (the commit arrives later via {@link #charTyped}). An empty {@code text}
     * clears the composition. Every index is in code points. Default: ignored.
     *
     * <p>What it means, rather than one platform's shape of it: GLFW reports styled blocks and
     * the focused one, SDL a string and a selection; a backend turns either into the target's
     * start and length.
     *
     * @param text         the composing text ({@code ""} clears it)
     * @param caret        caret position within {@code text}
     * @param targetStart  where the stretch being converted starts within {@code text}
     * @param targetLength how long it is; {@code 0} when nothing is being converted
     */
    default void preeditChanged(String text, int caret, int targetStart, int targetLength) {
    }

    /** Cursor entered ({@code true}) or left the window's content area. */
    void pointerEntered(boolean entered);

    /** Window content resized (logical points). May be coalesced by the receiver. */
    void windowResized(float logicalWidth, float logicalHeight);

    /**
     * A drag the system drives is over the window, or moved on it: one begun in another program,
     * or one this window handed over with {@link WindowPeer#startDrag}. Answered at once, since
     * the system waits for the answer to show whether a drop is possible there.
     *
     * @param offer   what is dragged: its formats now, its values at the drop
     * @param allowed what the source allows with the modifier keys as they are held, the
     *                source's preference first
     * @param x       the pointer, in logical points
     * @param y       the pointer, in logical points
     * @return the action a widget there would carry out, or null when none takes it
     */
    default limn.transfer.@org.jspecify.annotations.Nullable DropAction dragOver(limn.transfer.Offer offer,
            java.util.Set<limn.transfer.DropAction> allowed, float x, float y) {
        return null;
    }

    /** The drag the system drives left the window, or was called off over it. Default: ignored. */
    default void dragLeft() {
    }

    /**
     * What the system's drag carried is dropped on the window. The values of {@code offer} can
     * be read now and for as long as the offer is kept.
     *
     * @param offer   what was dragged
     * @param allowed what the source allows, as for {@link #dragOver}
     * @param x       the pointer, in logical points
     * @param y       the pointer, in logical points
     * @return the action a widget carried out, or null when none took it
     */
    default limn.transfer.@org.jspecify.annotations.Nullable DropAction dropped(limn.transfer.Offer offer,
            java.util.Set<limn.transfer.DropAction> allowed, float x, float y) {
        return null;
    }

    /**
     * Files dragged from the OS and dropped on the window, at a point of its content area in
     * logical points: the widget under that point receives them. The point is the platform's where
     * it says one (SDL does), and the pointer's at the drop where it does not (GLFW). Default:
     * ignored.
     *
     * @param paths the files, in the order the platform listed them
     * @param x     where they were dropped, in logical points
     * @param y     where they were dropped, in logical points
     */
    default void filesDropped(java.util.List<java.nio.file.Path> paths, float x, float y) {
    }

    /**
     * The window gained or lost OS input focus. On loss the scene cancels any
     * in-flight press/drag/hover; the matching RELEASE will never arrive (it
     * happens in another app), so it is synthesized. Default: ignored.
     */
    default void windowFocusChanged(boolean focused) {
    }

    /**
     * The window entered ({@code true}) or left fullscreen: {@link limn.backend.NativeWindow#isFullscreen()}
     * changed its answer. Every change is told once, whoever made it: the application's
     * {@code enterFullscreen} and {@code exitFullscreen}, and on macOS the person's green button,
     * Control-Command-F or menu item. It is told when the answer changes, which for macOS native
     * fullscreen is when the animation starts rather than when it ends. On the scene it closes a
     * menu, a drop-down list or a calendar that is open in a window of its own, placed against the
     * geometry the change is taking away, and moves a modal dialog's window into the scene.
     *
     * <p>Told even while a modal blocks the window, as a focus change is: the dialog that blocks
     * it is one of the surfaces that has to hear it. Arrives during the native poll, or from
     * inside the call that changed fullscreen, and like the rest of the batch it is acted on when
     * the batch ends. Default: ignored.
     */
    default void windowFullscreenChanged(boolean fullscreen) {
    }

    /** End of one native poll: dispatch the accumulated batch now. */
    default void inputBatchEnded() {
    }

    /**
     * The bound window was destroyed. Lets the receiver flush any work that was
     * waiting on a future frame which will now never come, chiefly a
     * window-fade arrival callback (e.g. a dialog completing its result after
     * fading out), since a destroyed window never ticks again. UI thread.
     */
    default void windowClosed() {
    }

    /**
     * Whether an editing command can be done now by the widget that has the keyboard in this
     * window. Asked for a row of a menu the system draws that is an editing command.
     */
    default boolean canEditFocused(limn.scene.EditCommand command) {
        return false;
    }

    /**
     * Does an editing command in the widget that has the keyboard in this window.
     *
     * @return whether a widget did it
     */
    default boolean editFocused(limn.scene.EditCommand command) {
        return false;
    }
}
