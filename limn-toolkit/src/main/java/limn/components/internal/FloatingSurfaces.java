package limn.components.internal;

import limn.backend.NativeWindow;

/**
 * The rule the surfaces anchored inside their owner, a menu, a drop-down list and a calendar
 * hanging from its field, keep before they open a window of their own.
 *
 * <p>One predicate rather than a condition written out in each, because a condition written out in
 * each drifts: one surface learns a case and the others go on opening windows where they cannot.
 * It lives here rather than beside the surfaces because they span two packages, and a
 * package-private helper reaches only one.
 */
public final class FloatingSurfaces {

    private FloatingSurfaces() {
    }

    /**
     * Whether a surface anchored inside {@code owner} can have a window of its own there. Not while
     * the owner is fullscreen: on a monitor a fullscreen window is minimized when another takes the
     * focus, and macOS native fullscreen is a Space of its own that a new window opens outside of.
     * And not where the owner cannot be positioned, as on Wayland, where the window would open
     * away from the anchor wearing whatever frame the compositor puts on toplevels. Either way the
     * surface is drawn in scene instead.
     *
     * <p>Asked when the surface opens, and again on the later turn that makes its window, since
     * the owner can go fullscreen between the two before the change is dispatched; a surface that
     * finds the answer changed there closes instead. A change after the window exists is heard
     * through {@link limn.scene.Scene#observeWindowFullscreen} and closes the surface too.
     *
     * <p>A dialog does not ask this. It has no anchor to line up with, so a window that cannot be
     * positioned is no reason to draw it in scene, and it applies the fullscreen half itself.
     *
     * @param owner the window the surface is anchored in
     * @return whether a window of its own is possible there
     */
    public static boolean anchoredWindowAvailable(NativeWindow owner) {
        return !owner.isFullscreen() && owner.supportsAbsolutePositioning();
    }
}
