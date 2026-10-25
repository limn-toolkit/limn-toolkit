package limn.components;

import limn.backend.NativeWindow;

/**
 * Where a surface that floats above the page is actually drawn: in a window of its own, or as an
 * overlay inside the window that owns it. {@link Dialog}, {@link PopupMenu}, {@link ComboBox},
 * {@link ColorPickerButton} and {@link limn.components.date.DatePicker} all answer this question,
 * and they answer it by the same rules.
 *
 * <p><b>It is a preference, never a guarantee.</b> Over two kinds of window
 * {@link #NATIVE_WINDOW} is not offered at all, and over a third not to a menu:
 *
 * <ul>
 *   <li><b>A {@linkplain NativeWindow#isFullscreen() fullscreen} window</b>, of either kind, for
 *       three reasons. A window fullscreen at a named mode, the fullscreen a game asks for, is
 *       minimized as soon as another window takes the focus, on every platform, and a menu or a
 *       dialog takes it at once, a list or a calendar at the first click. A window fullscreen at
 *       the mode the display is in, the fullscreen an application asks for, stays fullscreen when
 *       it loses the focus, but it already covers the screen, so a surface drawn inside it loses
 *       nothing: the room past the owner's edge that a window of its own is for is not there.
 *       And on a Mac without a GPU opening a window is slow: raising the colour
 *       picker as a window held the UI for 1.4 to 3.7 seconds, against 0.6 to 0.7 in scene.
 *       Every one of these surfaces is drawn in scene there, with one exception: a
 *       <b>non-modal</b> dialog stays a window, because an overlay captures its window's input
 *       and a non-modal dialog exists so that the window behind it stays usable, which leaves it
 *       no in-scene form to fall back to.</li>
 *   <li><b>Wayland</b>, or any window that cannot be
 *       {@linkplain limn.backend.spi.WindowPeer#supportsAbsolutePositioning() positioned}, where a client cannot
 *       place a toplevel at an anchor. A menu window would open near the middle of the display
 *       wearing whatever frame the compositor puts on toplevels, which is worse than any overlay.
 *       This one holds only for the surfaces anchored inside their owner: a menu, a drop-down
 *       list, a calendar hanging from its field. A dialog, including the colour picker's, has no
 *       anchor to line up with, so it stays a window there and the compositor places it, which is
 *       where a dialog belongs.</li>
 *   <li><b>A window that reports no {@linkplain NativeWindow#display() display}</b>, for a
 *       {@link PopupMenu}: one does where the windowing system lists no monitor, the only one
 *       asleep or a session with none. A menu's window is kept inside the display's work area,
 *       each column flipped and clamped against it, and there is no work area to read. A
 *       drop-down list, a calendar and a dialog still open a window of their own there, placed
 *       against their owner with no edge of a display to keep inside.</li>
 * </ul>
 *
 * <p>So a surface asked for {@code NATIVE_WINDOW} may be drawn in-scene anyway, and each of these
 * types answers what actually happened rather than what was requested: {@code displayMode()} on
 * each, and {@link ColorPickerButton#pickerDisplayMode()} on the colour button. Code that branches
 * on presentation must read that one; code that reads back the setter is reading its own wish.
 * The choice is made each time a surface opens, over the window as it is then.
 *
 * <p><b>And a window of its own does not outlive a change that rules it out.</b> When the owner
 * enters or leaves fullscreen while a surface is open in a window of its own, a menu, a drop-down
 * list or a calendar closes as if dismissed, choosing nothing. The change resizes and moves the
 * owner under a surface placed against its old geometry, which is why the platforms dismiss their
 * own menus on a change of their window's state, and a fullscreen at a named mode would also be
 * minimized the moment the surface took the focus. On macOS the person can make that change with
 * the green button or Control-Command-F while a list or a calendar is open, since neither takes
 * the focus from the window. A modal dialog, the colour picker's included, is a question still
 * waiting for its answer, so it moves into the scene instead, with what was typed in it, and is
 * presented as it would have been had it opened there. Two stay windows: a non-modal dialog, for
 * the reason above, and a modal one with another dialog or a modal menu open over it, since
 * moving it would close the window that one belongs to. A surface drawn in scene stays where it
 * is, and nothing moves back into a window when the owner leaves fullscreen.
 *
 * <p><b>Why it is worth choosing rather than inheriting.</b> An in-scene surface is contained by
 * the window: it cannot extend past the edge, it composites with what is behind it, and a
 * screenshot of the window contains it. A native one is none of those things, and the last point
 * is not only a testing convenience: anything that records, streams or captures a window records
 * a native popup as absent. An application that documents itself with screenshots, or one whose
 * window is deliberately the whole of its presentation, wants {@code IN_SCENE} on purpose and on
 * every platform, not only where the platform forces it.
 *
 * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum DisplayMode {

    /**
     * A separate window, positioned against the anchor. The default everywhere it is possible,
     * because it is what the platform's own menus and dialogs do: it can extend past the owner's
     * edge, and the compositor gives it the shadow and the stacking every other application gets.
     */
    NATIVE_WINDOW,

    /**
     * An overlay drawn inside the owner's window. Always available, on every platform, and the
     * only presentation over a fullscreen window and on a platform that cannot position a window
     * at an anchor, and a menu's over a window that reports no display.
     */
    IN_SCENE
}
