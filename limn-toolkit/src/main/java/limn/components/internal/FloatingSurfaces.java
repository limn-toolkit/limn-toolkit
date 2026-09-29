package limn.components.internal;

import limn.backend.NativeWindow;
import limn.backend.spi.WindowPeer;

/**
 * The rule the surfaces anchored inside their owner, a menu, a drop-down list and a calendar
 * hanging from its field, keep before they open a window of their own.
 *
 * <p>One predicate rather than a condition written out in each, because a condition written out in
 * each drifts: one surface learns a case and the others go on opening windows where they should
 * not.
 * It lives here rather than beside the surfaces because they span two packages, and a
 * package-private helper reaches only one.
 */
public final class FloatingSurfaces {

    private FloatingSurfaces() {
    }

    /**
     * Whether a surface anchored inside {@code owner} is given a window of its own there. Not while
     * the owner is fullscreen: at a named mode a fullscreen window is minimized as soon as another
     * takes the focus, and at the mode the display is in it already covers the screen, so a surface
     * drawn inside it loses nothing and is spared the cost of opening a window, which on a Mac
     * without a GPU held the UI for seconds when the colour picker opened as one. And not where the
     * owner cannot be positioned, as on Wayland, where the window would open away from the anchor
     * wearing whatever frame the compositor puts on toplevels. Either way the surface is drawn in
     * scene instead.
     *
     * <p>Asked when the surface opens, and again on the later turn that makes its window, since
     * the owner can go fullscreen between the two before the change is dispatched; a surface that
     * finds the answer changed there closes instead. A change after the window exists is heard
     * through {@link limn.scene.Scene#observeWindowFullscreen} and closes the surface too, since it
     * resizes the owner under a surface placed against its old geometry.
     *
     * <p>A dialog does not ask this. It has no anchor to line up with, so a window that cannot be
     * positioned is no reason to draw it in scene, and it applies the fullscreen half itself.
     *
     * @param owner the window the surface is anchored in
     * @return whether a window of its own is given there
     */
    public static boolean anchoredWindowAvailable(NativeWindow owner) {
        return !owner.isFullscreen() && WindowPeer.of(owner).supportsAbsolutePositioning();
    }
}
