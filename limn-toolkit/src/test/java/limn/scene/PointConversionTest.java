package limn.scene;

import limn.graphics.Rect;
import limn.math.Mat4;
import limn.math.Vec2;
import limn.math.Vec4;
import limn.scene.internal.TransformAccess;
import limn.scene.layout.Padding;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point conversions and the hit test through a matrix an ancestor draws its children with,
 * proved with {@link Turned} before any container draws one: {@code sceneToLocal} undoes
 * {@code localToScene} under a translation, a scale, a rotation, a turn in perspective and two
 * rotations nested; a point behind the eye, a plane seen edge-on and a collapsed scale answer
 * {@code NaN}; {@code localToSceneBounds} is the bounds of the four corners, each worked out here
 * from the matrix; and a child is hit where it is drawn, through an ancestor's box that does not
 * clip it and not through one that does.
 *
 * <p>The expected answers are computed from the matrix with {@link Mat4#transform} and the
 * layout's offsets, never by the conversions under test.
 */
class PointConversionTest extends SceneTestBase {

    private static final float SCENE_W = 400;
    private static final float SCENE_H = 300;
    /** The turned container's place in the scene, and its child's place in it. */
    private static final float OUTER = 10;
    private static final float INNER = 25;
    /** The turned container's middle, in its own space, which the matrices pivot on. */
    private static final float CX = (SCENE_W - 2 * OUTER) / 2;
    private static final float CY = (SCENE_H - 2 * OUTER) / 2;

    /** A widget that fills what it is given. */
    static final class Fill extends Widget<Fill> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), c.maxHeight());
        }
    }

    private Turned turned;
    private Fill leaf;
    private Padding root;

    private void build(@Nullable Mat4 matrix) {
        leaf = new Fill();
        turned = new Turned(new Padding(Insets.all(INNER), leaf)).setMatrix(matrix);
        root = new Padding(Insets.all(OUTER), turned);
        Scene scene = new Scene(root);
        scene.layoutPass(SCENE_W, SCENE_H);
    }

    /** Where the matrix draws a point of the leaf, worked out from the layout and the matrix. */
    private static float[] expected(Mat4 matrix, float leafX, float leafY) {
        Vec4 p = matrix.transform(new Vec4(INNER + leafX, INNER + leafY, 0, 1));
        return new float[] {p.x() / p.w() + OUTER, p.y() / p.w() + OUTER, p.w()};
    }

    private static List<Mat4> matrices() {
        return List.of(
                Turned.translation(15, -7),
                Turned.scale(CX, CY, 2, 0.5f),
                Turned.rotation(CX, CY, 30),
                Turned.rotation(CX, CY, -125),
                Turned.turn(CX, CY, 50, 600),
                Turned.turn(CX, CY, -35, 900).multiply(Turned.rotation(CX, CY, 20)));
    }

    private static final float[][] POINTS = {{0, 0}, {330, 230}, {17.5f, 101}, {300, 12}};

    @Test
    void aPointGoesToTheSceneThroughTheMatrixAndComesBack() {
        for (Mat4 matrix : matrices()) {
            build(matrix);
            assertEquals(330, leaf.width(), 1e-3f, "the fixture's leaf");
            for (float[] p : POINTS) {
                float[] want = expected(matrix, p[0], p[1]);
                Vec2 scene = leaf.localToScene(p[0], p[1]);
                assertEquals(want[0], scene.x(), 1e-2f, "x of " + p[0] + "," + p[1]);
                assertEquals(want[1], scene.y(), 1e-2f, "y of " + p[0] + "," + p[1]);
                Vec2 back = leaf.sceneToLocal(scene.x(), scene.y());
                assertEquals(p[0], back.x(), 1e-2f, "back to x " + p[0]);
                assertEquals(p[1], back.y(), 1e-2f, "back to y " + p[1]);
            }
        }
    }

    @Test
    void twoTurnsNestedComeBackToo() {
        Fill inner = new Fill();
        Turned innerTurn = new Turned(new Padding(Insets.all(30), inner))
                .setMatrix(Turned.rotation(100, 60, 45));
        Turned outerTurn = new Turned(new Padding(Insets.all(20), new SizedFill(200, 120, innerTurn)))
                .setMatrix(Turned.turn(CX, CY, 30, 800));
        Scene scene = new Scene(new Padding(Insets.all(OUTER), outerTurn));
        scene.layoutPass(SCENE_W, SCENE_H);
        for (float[] p : new float[][] {{0, 0}, {60, 30}, {139, 59}}) {
            Vec2 at = inner.localToScene(p[0], p[1]);
            Vec2 back = inner.sceneToLocal(at.x(), at.y());
            assertEquals(p[0], back.x(), 1e-2f);
            assertEquals(p[1], back.y(), 1e-2f);
            // By hand: into the inner turn's space, its matrix, into the outer's, its matrix.
            float[] once = Turned.drawnAt(Turned.rotation(100, 60, 45), 30 + p[0], 30 + p[1]);
            float[] twice = Turned.drawnAt(Turned.turn(CX, CY, 30, 800),
                    20 + innerTurn.x() + once[0], 20 + innerTurn.y() + once[1]);
            assertEquals(twice[0] + OUTER, at.x(), 1e-2f);
            assertEquals(twice[1] + OUTER, at.y(), 1e-2f);
        }
    }

    /** A box of a fixed size holding one child at its origin, the child's size its own. */
    static final class SizedFill extends Widget<SizedFill> {
        private final float w;
        private final float h;
        private final Widget<?> child;

        SizedFill(float w, float h, Widget<?> child) {
            this.w = w;
            this.h = h;
            this.child = child;
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            child.measure(Constraints.tight(w, h));
            return c.constrain(w, h);
        }

        @Override
        protected void onLayout() {
            child.layoutBox(0, 0, w, h);
        }
    }

    @Test
    void aPointBehindTheEyeIsNaNEitherWay() {
        // Turned 80 degrees with the eye 150 points away: the left edge swings out past the eye.
        Mat4 matrix = Turned.turn(CX, CY, 80, 150);
        build(matrix);
        float[] behind = expected(matrix, 5, 50);
        assertTrue(behind[2] < 0, "the fixture puts this point behind the eye, w = " + behind[2]);
        Vec2 drawn = leaf.localToScene(5, 50);
        assertTrue(Float.isNaN(drawn.x()) && Float.isNaN(drawn.y()), "not drawn: " + drawn);
        // The scene point the plane's behind-the-eye point projects to: the ray from the eye
        // through it meets the plane behind the eye, so no point of the leaf is drawn there.
        Vec2 local = leaf.sceneToLocal(behind[0], behind[1]);
        assertTrue(Float.isNaN(local.x()) && Float.isNaN(local.y()), "nothing drawn there: " + local);
        assertNotSame(leaf, root.hitTest(behind[0], behind[1]));
        // In front, the same turn converts both ways.
        Vec2 front = leaf.localToScene(300, 50);
        Vec2 back = leaf.sceneToLocal(front.x(), front.y());
        assertEquals(300, back.x(), 1e-2f);
        assertEquals(50, back.y(), 1e-2f);
    }

    @Test
    void aPlaneEdgeOnOrCollapsedHoldsNoPoint() {
        for (Mat4 matrix : List.of(Turned.turn(CX, CY, 90, Float.POSITIVE_INFINITY),
                Turned.scale(CX, CY, 0, 1), Turned.scale(CX, CY, 1, 0))) {
            build(matrix);
            Vec2 drawn = leaf.localToScene(100, 100);
            Vec2 local = leaf.sceneToLocal(drawn.x(), drawn.y());
            assertTrue(Float.isNaN(local.x()) && Float.isNaN(local.y()), "collapsed: " + local);
            assertNull(turned.hitTest(drawn.x() - OUTER, drawn.y() - OUTER), "and nothing is hit there");
        }
    }

    @Test
    void theBoundsAreTheFourCornersWorkedOutByHand() {
        float x = 12;
        float y = 7;
        float w = 90;
        float h = 40;
        for (Mat4 matrix : matrices()) {
            build(matrix);
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (float[] c : new float[][] {{x, y}, {x + w, y}, {x + w, y + h}, {x, y + h}}) {
                float[] at = expected(matrix, c[0], c[1]);
                minX = Math.min(minX, at[0]);
                minY = Math.min(minY, at[1]);
                maxX = Math.max(maxX, at[0]);
                maxY = Math.max(maxY, at[1]);
            }
            Rect bounds = leaf.localToSceneBounds(x, y, w, h);
            assertEquals(minX, bounds.x(), 1e-2f);
            assertEquals(minY, bounds.y(), 1e-2f);
            assertEquals(maxX, bounds.right(), 1e-2f);
            assertEquals(maxY, bounds.bottom(), 1e-2f);
        }
        build(null);
        assertEquals(new Rect(OUTER + INNER + x, OUTER + INNER + y, w, h), leaf.localToSceneBounds(x, y, w, h),
                "upright, the rectangle moved by the offsets");
    }

    @Test
    void aRectangleWhollyBehindTheEyeHasNoBounds() {
        build(Turned.turn(CX, CY, 80, 150));
        Rect bounds = leaf.localToSceneBounds(0, 0, 8, 230);
        assertTrue(Float.isNaN(bounds.x()) && Float.isNaN(bounds.y()), "at NaN: " + bounds);
        assertEquals(0, bounds.width());
        assertEquals(0, bounds.height());
        Rect cut = leaf.localToSceneBounds(0, 0, 330, 230);
        assertTrue(!Float.isNaN(cut.x()) && cut.width() > 0, "a rectangle partly in front keeps that part");
    }

    @Test
    void atRestThereIsNoMatrixAndTheAnswerIsTheSumOfOffsets() {
        build(Mat4.IDENTITY);
        assertNull(TransformAccess.childMatrix(turned), "the identity is no matrix");
        assertEquals(new Vec2(OUTER + INNER + 3, OUTER + INNER + 4), leaf.localToScene(3, 4));
        assertEquals(new Vec2(3, 4), leaf.sceneToLocal(OUTER + INNER + 3, OUTER + INNER + 4));
        turned.setMatrix(Turned.rotation(CX, CY, 10));
        assertTrue(TransformAccess.childMatrix(turned) != null);
        turned.setMatrix(null);
        assertNull(TransformAccess.childMatrix(turned));
        assertEquals(new Vec2(OUTER + INNER + 3, OUTER + INNER + 4), leaf.localToScene(3, 4));
    }

    @Test
    void aTurnedChildIsHitWhereItIsDrawnAndNotWhereItWas() {
        for (Mat4 matrix : List.of(Turned.rotation(CX, CY, 90), Turned.turn(CX, CY, 40, 700))) {
            build(matrix);
            for (float[] p : POINTS) {
                float px = Math.min(p[0], 329);
                float py = Math.min(p[1], 229);
                float[] at = expected(matrix, px + 0.5f, py + 0.5f);
                assertSame(leaf, root.hitTest(at[0], at[1]), "drawn at " + at[0] + "," + at[1]);
            }
        }
        // A quarter turn of a box wider than it is tall leaves the middle of its left edge empty.
        build(Turned.rotation(CX, CY, 90));
        assertSame(root, root.hitTest(OUTER + 20, OUTER + CY),
                "where the child was and is no longer drawn, the container answers nothing");
    }

    // ---------------------------------------------------------------- letting the pointer through

    /** A box of a fixed size that lays one child at its origin with the child's own size. */
    static final class Holder extends Widget<Holder> {
        private final Widget<?> child;
        private final boolean clips;

        Holder(Widget<?> child, boolean clips) {
            this.child = child;
            this.clips = clips;
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            child.measure(Constraints.tight(100, 100));
            return c.constrain(100, 100);
        }

        @Override
        protected void onLayout() {
            child.layoutBox(0, 0, 100, 100);
        }

        @Override
        protected boolean clipsChildren() {
            return clips;
        }
    }

    /** The whole scene: something beneath, and a small holder in its corner. */
    static final class Board extends Widget<Board> {
        private final Widget<?> beneath;
        private final Widget<?> holder;

        Board(Widget<?> beneath, Widget<?> holder) {
            this.beneath = beneath;
            this.holder = holder;
            add(beneath);
            add(holder);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            beneath.measure(Constraints.tight(SCENE_W, SCENE_H));
            holder.measure(Constraints.tight(100, 100));
            return c.constrain(SCENE_W, SCENE_H);
        }

        @Override
        protected void onLayout() {
            beneath.layoutBox(0, 0, SCENE_W, SCENE_H);
            holder.layoutBox(0, 0, 100, 100);
        }
    }

    @Test
    void aChildSlidPastItsParentsBoxIsHitThereUnlessTheParentClips() {
        for (boolean clips : new boolean[] {false, true}) {
            Fill beneath = new Fill();
            Fill drawer = new Fill();
            Turned slide = new Turned(drawer).setMatrix(Turned.translation(250, 120));
            Holder holder = new Holder(slide, clips);
            Board board = new Board(beneath, holder);
            Scene scene = new Scene(board);
            scene.layoutPass(SCENE_W, SCENE_H);
            Widget<?> atDrawer = board.hitTest(300, 170);
            if (clips) {
                assertSame(beneath, atDrawer, "a parent that clips draws nothing out there, so nothing is hit");
            } else {
                assertSame(drawer, atDrawer, "drawn out there past two boxes, and hit there");
            }
            assertSame(beneath, board.hitTest(240, 110),
                    "beside the slid drawer, inside the bounds the rule asks about, nothing is drawn");
            Widget<?> whereItWas = board.hitTest(50, 50);
            assertNotSame(drawer, whereItWas, "the place it left is not the drawer's");
            assertNotSame(slide, whereItWas, "nor the container's, which is hit only where its child is");
        }
    }

    @Test
    void theSceneForgetsAMatrixThatLeavesIt() {
        Fill drawer = new Fill();
        Turned slide = new Turned(drawer).setMatrix(Turned.translation(250, 120));
        Holder holder = new Holder(slide, false);
        Fill beneath = new Fill();
        Board board = new Board(beneath, holder);
        Scene scene = new Scene(board);
        scene.layoutPass(SCENE_W, SCENE_H);
        assertEquals(List.of(slide), scene.transformed());
        slide.setMatrix(null);
        assertEquals(List.of(), scene.transformed(), "at rest");
        slide.setMatrix(Turned.translation(250, 120));
        board.remove(holder);
        assertEquals(List.of(), scene.transformed(), "detached");
        board.add(holder);
        assertEquals(List.of(slide), scene.transformed(), "attached again, with its matrix");
    }
}
