package limn.backend;

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
     * {@link PointerMode#RELATIVE}: unbounded deltas in logical points
     * (raw/unaccelerated where the platform supports it), replacing
     * {@link #mouseMoved} for the duration of the capture. The receiver may
     * coalesce by summing. Default: ignored.
     */
    default void mouseDelta(float dx, float dy) {
    }

    /** Button press/release at the given cursor position. */
    void mouseButton(int button, boolean pressed, int modifiers, float x, float y);

    /** Wheel/trackpad scroll; deltas in native notches (positive = up/left). */
    void scrolled(float deltaX, float deltaY, float x, float y);

    void keyEvent(int key, boolean pressed, boolean repeat, int modifiers);

    /** Committed text input, one code point (IME/layout-aware, after key events). */
    void charTyped(int codepoint);

    /**
     * In-progress IME composition ("preedit") for the focused text input: the
     * still-composing text plus its styled blocks and caret, shown inline but
     * not yet committed (the commit arrives later via {@link #charTyped}). An
     * empty {@code text} clears the composition. Default: ignored.
     *
     * @param text         the composing text ({@code ""} clears it)
     * @param blockSizes   code-point length of each styled block (tiles {@code text})
     * @param focusedBlock index of the block being converted, or {@code -1}
     * @param caret        caret position within {@code text}, in code points
     */
    default void preeditChanged(String text, int[] blockSizes, int focusedBlock, int caret) {
    }

    /** Cursor entered ({@code true}) or left the window's content area. */
    void pointerEntered(boolean entered);

    /** Window content resized (logical points). May be coalesced by the receiver. */
    void windowResized(float logicalWidth, float logicalHeight);

    /**
     * Files dragged from the OS and dropped on the window. The drop lands at
     * the current pointer position (the platform moves the cursor onto the
     * window before dropping). Default: ignored.
     */
    default void filesDropped(java.util.List<java.nio.file.Path> paths) {
    }

    /**
     * The window gained or lost OS input focus. On loss the scene cancels any
     * in-flight press/drag/hover; the matching RELEASE will never arrive (it
     * happens in another app), so it is synthesized. Default: ignored.
     */
    default void windowFocusChanged(boolean focused) {
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
}
