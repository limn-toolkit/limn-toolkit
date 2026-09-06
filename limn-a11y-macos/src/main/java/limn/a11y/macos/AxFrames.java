package limn.a11y.macos;

import limn.graphics.Rect;

/**
 * The one coordinate conversion this bridge performs, and the two ways it differs from the others.
 *
 * <p>The toolkit's boxes are scene-local, in points, with y measured downwards from the top; that
 * is what every widget lays out in. AppKit wants {@code setAccessibilityFrameInParentSpace:} — a
 * box relative to the <b>parent element</b>, with y measured upwards from that parent's bottom.
 *
 * <p><b>"Parent" is literal, and the phase 7 probe run is why this class exists rather than a line
 * of arithmetic at the call site.</b> An earlier draft of §2.2 described the target as "the content
 * view's space", which is true only of the root's own children — the spike had exactly one element
 * and never noticed. Measured through three levels, the offsets add up: a child at (20,20) of a
 * group at (20,60) of the view lands where the view's origin plus (40,80) puts it. So every node's
 * box is converted against <em>its own parent's</em> box, and a bridge that converted everything
 * against the view would put every grandchild in the wrong place — visibly wrong to a hit test, and
 * invisibly wrong to a walk, which is the worse half.
 *
 * <p><b>There is no scale factor here, and that is deliberate.</b> AppKit's accessibility geometry
 * is in points, and so is a Limn scene's; the window stamp's {@code logicalToScreenFactor} converts
 * points to physical pixels, which is what the other two platforms need and this one must not
 * apply. The screen flip is AppKit's too (§1.8): nothing here subtracts from a display height.
 */
final class AxFrames {

    private AxFrames() {
    }

    /**
     * A node's box in its parent's space.
     *
     * @param child  the node's box, scene-local, y downwards from the scene's top
     * @param parent the parent node's box in the same space; for a child of the elided window root
     *               this is the whole scene, {@code (0, 0, sceneWidth, sceneHeight)}
     * @return x, y, width, height as AppKit wants them: relative to {@code parent}, y upwards from
     *         its bottom edge
     */
    static double[] inParentSpace(Rect child, Rect parent) {
        double x = child.x() - parent.x();
        // The flip is local, not global. What y counts up from is the parent's bottom edge, and in
        // scene coordinates that edge is the parent's largest y -- so the child's own bottom edge
        // subtracted from it is the gap between them, which is what AppKit is asking for.
        double y = (parent.y() + parent.height()) - (child.y() + child.height());
        return new double[] { x, y, child.width(), child.height() };
    }
}
