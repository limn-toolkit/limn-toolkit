package limn.components;

import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.Rect;
import limn.graphics.RoundRect;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scroll view clips its content exactly on an axis it scrolls and lets it paint past the
 * viewport on an axis it does not, by what it declares.
 *
 * <p>A dialog's body scrolls vertically and never sideways, and its view clipped the content to
 * the viewport on both axes. So whatever a control at the body's left or right edge drew outside
 * its own box was cut: the focus ring of a checkbox flush with the leading edge lost its outer
 * side, and the colour picker's cursor, centred on the exact colour, showed half a ring on white.
 * Nothing ever scrolls sideways there, so nothing had to be clipped there.
 *
 * <p>Every ink box here is read off what was stroked, pen included, together with the clip in
 * force when it was stroked, by following the translations and clips the paint walk pushes.
 */
class ScrollAxisClipTest extends ComponentTestBase {

    private static final int W = 400;
    private static final int H = 240;
    /** Room round the view under test, so that whatever it lets past it is inside the window. */
    private static final float INSET = 40;
    /** The pixel of analytic antialiasing any shape may feather past its own geometry. */
    private static final float FEATHER = 1;
    private static final float EPS = 1e-3f;

    private Scene scene;
    private AtomicLong nanos;
    private InkRecorder canvas;

    private void mount(Widget<?> root, int width, int height) {
        nanos = new AtomicLong();
        scene = new Scene(root, nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        canvas = new InkRecorder(width, height);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 300; i++) {
            nanos.addAndGet(200_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                return;
            }
        }
        throw new AssertionError("never settled: still repainting " + canvas.passes
                + (canvas.cleared ? " and clearing" : ""));
    }

    private void frame() {
        nanos.addAndGet(16_000_000L);
        canvas.reset();
        scene.renderFrame(canvas);
    }

    /** A frame that repaints the whole scene, so that no culling decides what is recorded. */
    private void wholeFrame() {
        scene.root().invalidate();
        frame();
    }

    private static Rect sceneBox(Widget<?> w) {
        return new Rect(w.localToSceneX(), w.localToSceneY(), w.width(), w.height());
    }

    private static boolean inside(Rect inner, Rect outer) {
        return inner.x() >= outer.x() - EPS && inner.y() >= outer.y() - EPS
                && inner.right() <= outer.right() + EPS && inner.bottom() <= outer.bottom() + EPS;
    }

    private static boolean meets(Rect a, Rect b) {
        Rect both = a.intersect(b);
        return both.width() > 0 && both.height() > 0;
    }

    /** The focus rings the frame stroked with {@code pen}, each with the clip it was drawn in. */
    private List<InkRecorder.Ink> ringsOf(float pen) {
        List<InkRecorder.Ink> found = new ArrayList<>();
        for (InkRecorder.Ink ink : canvas.rings) {
            if (ink.pen() == pen) {
                found.add(ink);
            }
        }
        return found;
    }

    private static void assertWhole(List<InkRecorder.Ink> inks, String what) {
        assertFalse(inks.isEmpty(), "the frame drew no " + what);
        for (InkRecorder.Ink ink : inks) {
            assertTrue(inside(ink.ink(), ink.clip()), "the " + what + " " + ink.ink()
                    + " is cut by the clip " + ink.clip() + " it was drawn in");
        }
    }

    // ------------------------------------------------------------------ fixtures

    private ScrollView view;
    private Button button;

    /**
     * A focused button flush with the leading edge of a view scrolling on the axes given, with
     * room above and below it, so that the ring crosses the view's side and only its side. The
     * content fits the view.
     */
    private void mountAButtonAtTheLeadingEdge(boolean horizontal, boolean vertical,
                                              LayoutDirection direction, boolean focused) {
        mountAButtonAtTheLeadingEdge(horizontal, vertical, direction, focused, false);
    }

    /**
     * The same, with a block after the button that makes the content overflow the view on every
     * axis it scrolls when {@code overflowing}: past its trailing side and past its bottom, so
     * that the button stays where it was.
     */
    private void mountAButtonAtTheLeadingEdge(boolean horizontal, boolean vertical,
                                              LayoutDirection direction, boolean focused,
                                              boolean overflowing) {
        button = new Button("Save");
        Row row = new Row();
        row.add(button);
        Widget<?> content = new Padding(Insets.symmetric(12, 0), row);
        if (overflowing) {
            Column longer = new Column();
            longer.add(content);
            longer.add(new SizedBox(horizontal ? 400 : 1, vertical ? 200 : 1));
            content = longer;
        }
        view = new ScrollView(content, horizontal, vertical);
        Stack centre = new Stack().alignment(Stack.Alignment.CENTER);
        centre.add(new SizedBox(200, 80, view));
        Padding root = new Padding(Insets.all(INSET), centre);
        root.setLayoutDirection(direction);
        mount(root, W, H);
        if (focused) {
            button.requestFocus();
            settle();
        }
    }

    /** The ring's ink box, antialiased edge included, read off a whole frame. */
    private Rect ringBox() {
        wholeFrame();
        Rect box = null;
        for (InkRecorder.Ink ring : ringsOf(Strokes.FOCUS_RING)) {
            Rect inked = ring.ink().inflate(FEATHER);
            box = box == null ? inked : box.union(inked);
        }
        assertNotNull(box, "the focused button drew no focus ring");
        settle();
        return box;
    }

    // ------------------------------------------------------------------ the rule

    @Test
    void aViewScrollingOnlyVerticallyLetsTheRingAtItsLeadingEdgeThrough() {
        mountAButtonAtTheLeadingEdge(false, true, LayoutDirection.LTR, true);
        wholeFrame();
        List<InkRecorder.Ink> rings = ringsOf(Strokes.FOCUS_RING);
        assertTrue(rings.get(0).ink().x() < view.localToSceneX(),
                "the fixture puts the ring across the view's left edge, or it proves nothing");
        assertWhole(rings, "focus ring");
    }

    @Test
    void aViewScrollingOnlyVerticallyLetsTheRingAtItsTrailingEdgeThroughReadingRightToLeft() {
        mountAButtonAtTheLeadingEdge(false, true, LayoutDirection.RTL, true);
        wholeFrame();
        List<InkRecorder.Ink> rings = ringsOf(Strokes.FOCUS_RING);
        assertTrue(rings.get(0).ink().right() > view.localToSceneX() + view.width(),
                "reading right to left the ring crosses the view's right edge");
        assertWhole(rings, "focus ring");
    }

    /**
     * On an axis it scrolls and its content overflows a view clips at the viewport exactly,
     * whatever it lets through on the other: the band is the reach of the theme's focus ring
     * across the axis it does not scroll and nothing along the one it does.
     */
    @Test
    void aViewClipsExactlyOnAnAxisItScrollsWhileItsContentOverflowsIt() {
        mountAButtonAtTheLeadingEdge(false, true, LayoutDirection.LTR, true, true);
        assertTrue(view.maxOffsetY() > 0, "the fixture overflows the view, or it proves nothing");
        wholeFrame();
        Rect clip = ringsOf(Strokes.FOCUS_RING).get(0).clip();
        Rect box = sceneBox(view);
        float reach = Theme.current().focusRingReach();
        assertEquals(new Rect(box.x() - reach, box.y(), box.width() + 2 * reach, box.height()),
                clip, "a view scrolling vertically");

        mountAButtonAtTheLeadingEdge(true, false, LayoutDirection.LTR, true, true);
        assertTrue(view.maxOffsetX() > 0, "the fixture overflows the view, or it proves nothing");
        wholeFrame();
        clip = ringsOf(Strokes.FOCUS_RING).get(0).clip();
        box = sceneBox(view);
        assertEquals(new Rect(box.x(), box.y() - reach, box.width(), box.height() + 2 * reach),
                clip, "a view scrolling horizontally");
        Rect ring = ringsOf(Strokes.FOCUS_RING).get(0).ink();
        assertTrue(ring.x() < clip.x(), "a view scrolling sideways still cuts the ring at its "
                + "leading edge, where content scrolls out of sight");

        mountAButtonAtTheLeadingEdge(true, true, LayoutDirection.LTR, true, true);
        wholeFrame();
        assertEquals(sceneBox(view), ringsOf(Strokes.FOCUS_RING).get(0).clip(),
                "a view scrolling both ways clips at its viewport on both");
    }

    /**
     * On an axis it scrolls and its content fits, nothing can move out of sight either, so a view
     * treats that axis as one it does not scroll: whatever it scrolls, content that fits it is let
     * past every side by the band, and the ring of a button at its leading edge is drawn whole.
     */
    @Test
    void aViewWhoseContentFitsLetsItPastEverySideByTheBand() {
        float reach = Theme.current().focusRingReach();
        boolean[][] axes = {{false, true}, {true, false}, {true, true}};
        for (boolean[] scrolls : axes) {
            String what = "a view scrolling " + (scrolls[0] ? "sideways" : "")
                    + (scrolls[0] && scrolls[1] ? " and " : "") + (scrolls[1] ? "vertically" : "");
            mountAButtonAtTheLeadingEdge(scrolls[0], scrolls[1], LayoutDirection.LTR, true);
            assertEquals(0, view.maxOffsetX(), 0, what + ": the content fits");
            assertEquals(0, view.maxOffsetY(), 0, what + ": the content fits");
            wholeFrame();
            List<InkRecorder.Ink> rings = ringsOf(Strokes.FOCUS_RING);
            assertEquals(sceneBox(view).inflate(reach), rings.get(0).clip(), what);
            assertWhole(rings, what + ": the focus ring");
        }
    }

    /**
     * A strip reserved for the bar is never painted into: the band stops at the viewport on the
     * bar's side and opens on the other, whichever side reading puts the bar on.
     */
    @Test
    void aReservedGutterKeepsTheBandOffTheBarsSide() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            Column tall = new Column();
            for (int i = 0; i < 12; i++) {
                tall.add(new SizedBox(40, 30));
            }
            view = new ScrollView(tall, false, true)
                    .setBarLayout(ScrollGutters.Layout.RESERVED);
            Probe probe = new Probe();
            tall.add(probe);
            Stack centre = new Stack().alignment(Stack.Alignment.CENTER);
            centre.add(new SizedBox(200, 80, view));
            Padding root = new Padding(Insets.all(INSET), centre);
            root.setLayoutDirection(direction);
            mount(root, W, H);
            view.scrollTo(0, view.maxOffsetY());
            settle();
            wholeFrame();
            assertNotNull(probe.clip, direction + ": the probe was not painted");
            Rect box = sceneBox(view);
            float reach = Theme.current().focusRingReach();
            float strip = ScrollBar.thickness();
            Rect expected = direction == LayoutDirection.LTR
                    ? new Rect(box.x() - reach, box.y(), box.width() - strip + reach, box.height())
                    : new Rect(box.x() + strip, box.y(), box.width() - strip + reach, box.height());
            assertEquals(expected, probe.clip, direction.name());
        }
    }

    // ------------------------------------------------------------------ damage and culling

    /**
     * A ring appearing at the side of a view that scrolls only vertically is repainted where it
     * hangs past the view: the view's clip, which clamps every descendant's damage, is the clip it
     * paints with, so the band is inside it.
     */
    @Test
    void theRingAppearingAtASideOfAVerticalViewIsRepaintedWhereItHangsPastTheView() {
        mountAButtonAtTheLeadingEdge(false, true, LayoutDirection.LTR, false);
        button.requestFocus();
        List<Rect> passes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            frame();
            if (canvas.nothingPainted()) {
                break;
            }
            assertFalse(canvas.cleared, "a whole frame repaints the band by accident");
            passes.addAll(canvas.passes);
        }
        Rect ring = ringBox();
        assertTrue(ring.x() < view.localToSceneX() - FEATHER, "the ring hangs past the view");
        for (float y = ring.y() + 0.25f; y < ring.bottom(); y += 0.5f) {
            for (float x = ring.x() + 0.25f; x < ring.right(); x += 0.5f) {
                boolean covered = false;
                for (Rect pass : passes) {
                    if (pass.contains(x, y)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    throw new AssertionError("the ring's ink at " + x + "," + y + " was never "
                            + "repainted as it faded in: no pass of " + passes + " covers " + ring);
                }
            }
        }
    }

    /**
     * A pass landing only in the band beside a vertical view, past the view's pixel of
     * antialiasing and inside the ring's reach, paints the view and the ring in it: the view
     * declares the band it lets its content paint into.
     */
    @Test
    void aPassBesideAVerticalViewRedrawsTheRingAtItsEdge() {
        mountAButtonAtTheLeadingEdge(false, true, LayoutDirection.LTR, true);
        Rect ring = ringBox();
        Rect box = sceneBox(view);
        Rect band = new Rect(box.x() - 3, sceneBox(button).y() + 4, 1, 8);
        assertTrue(meets(band, ring), "the band meets the ring");
        assertFalse(meets(band, box.inflate(FEATHER)), "the band misses the view's own box");

        scene.damage(band);
        frame();
        assertFalse(canvas.cleared, "a whole frame paints everything and tests no culling");
        assertEquals(List.of(band), canvas.passes, "the damage is the band alone");
        List<InkRecorder.Ink> rings = ringsOf(Strokes.FOCUS_RING);
        assertFalse(rings.isEmpty(), "a pass over " + band + " wiped the part of the ring " + ring
                + " inside it and did not draw it again");
        for (InkRecorder.Ink redrawn : rings) {
            assertEquals(band, redrawn.clip(), "the view cut the band off the pass");
        }
    }

    // ------------------------------------------------------------------ the declaration

    /**
     * What the scene reads, by reflection because the hook is protected on {@link Widget}: the
     * same call the damage and the culling make.
     */
    private static float declared(Widget<?> widget) {
        try {
            java.lang.reflect.Method hook = Widget.class.getDeclaredMethod("paintOutset");
            hook.setAccessible(true);
            return (Float) hook.invoke(widget);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("paintOutset", e);
        }
    }

    /**
     * The band is the reach of the theme's focus ring by default, and it is declared to partial
     * rendering while an axis is left unclipped: content that fits, as a view not yet laid out
     * holds, is let past every side whatever the view scrolls, and only a view whose content
     * overflows it both ways declares nothing, whatever band it is given, because it lets nothing
     * past.
     */
    @Test
    void aViewDeclaresTheBandWhileAnAxisIsLeftUnclipped() {
        float reach = Theme.current().focusRingReach();
        assertEquals(reach, declared(new ScrollView(new Row())), 0, "the default view");
        assertEquals(reach, declared(new ScrollView(new Row(), false, true)), 0);
        assertEquals(reach, declared(new ScrollView(new Row(), true, false)), 0);
        assertEquals(reach, declared(new ScrollView(new Row(), false, false)), 0);
        assertEquals(reach, declared(new ScrollView(new Row(), true, true)), 0,
                "a view scrolling both ways whose content fits");

        mountAButtonAtTheLeadingEdge(false, true, LayoutDirection.LTR, false, true);
        assertEquals(reach, declared(view), 0, "overflowing downwards, it is let past the sides");
        mountAButtonAtTheLeadingEdge(true, true, LayoutDirection.LTR, false, true);
        assertEquals(0, declared(view), 0, "overflowing both ways");
        assertEquals(0, declared(view.paintOverflow(8)), 0, "overflowing both ways, told 8");
    }

    @Test
    void paintOverflowReplacesTheThemesNumberAndComesBackToIt() {
        ScrollView plain = new ScrollView(new Row(), false, true);
        assertTrue(plain == plain.paintOverflow(8), "chains");
        assertEquals(8, declared(plain), 0);
        plain.paintOverflow(0);
        assertEquals(0, declared(plain), 0, "a view may be told to clip exactly on every side");
        assertTrue(plain == plain.paintOverflowFromTheme(), "chains");
        assertEquals(Theme.current().focusRingReach(), declared(plain), 0);
    }

    /**
     * NaN, a negative number and an infinity are refused where they are given, and a refused one
     * changes nothing: NaN would grow every clip and damage rectangle by NaN, and an infinity
     * would lift the clip off the axis altogether.
     */
    @Test
    void paintOverflowRefusesWhatIsNotADistance() {
        ScrollView plain = new ScrollView(new Row(), false, true).paintOverflow(8);
        for (float bad : new float[] {Float.NaN, -1, -0.5f, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY}) {
            IllegalArgumentException error = assertThrowsIllegal(() -> plain.paintOverflow(bad));
            assertTrue(error.getMessage().contains("paint overflow"), error.getMessage());
            assertEquals(8, declared(plain), 0, "kept its number after " + bad);
        }
    }

    private static IllegalArgumentException assertThrowsIllegal(Runnable call) {
        return org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                call::run);
    }

    /** Both calls check the UI thread before anything is stored. */
    @Test
    void paintOverflowOffTheUiThreadThrowsAndChangesNothing() throws Exception {
        ScrollView plain = new ScrollView(new Row(), false, true);
        IllegalStateException error = ui.workers().submit(() ->
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        () -> plain.paintOverflow(8))).get();
        assertTrue(error.getMessage().contains("UI thread"), error.getMessage());
        assertEquals(Theme.current().focusRingReach(), declared(plain), 0);
        plain.paintOverflow(8);
        ui.workers().submit(() ->
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        plain::paintOverflowFromTheme)).get();
        assertEquals(8, declared(plain), 0);
    }

    /**
     * A declaration already in force repaints nothing. A new one repaints the view and the wider
     * of the band it leaves and the one it takes: widening draws the band at once, and narrowing
     * wipes what the content drew in the band it gave up.
     */
    @Test
    void aNewBandRepaintsTheWiderOfTheOldAndTheNew() {
        mountAButtonAtTheLeadingEdge(false, true, LayoutDirection.LTR, true);
        Rect box = sceneBox(view);

        view.paintOverflowFromTheme();
        frame();
        assertTrue(canvas.nothingPainted(), "the theme's number was already in force");

        view.paintOverflow(8);
        frame();
        assertEquals(List.of(box.inflate(FEATHER + 8)), canvas.passes, "widening");
        settle();

        view.paintOverflow(8);
        frame();
        assertTrue(canvas.nothingPainted(), "8 was already in force");

        view.paintOverflow(0);
        frame();
        assertEquals(List.of(box.inflate(FEATHER + 8)), canvas.passes, "narrowing");
        settle();

        wholeFrame();
        Rect clip = ringsOf(Strokes.FOCUS_RING).get(0).clip();
        assertEquals(box, clip, "told 0, the view clips at its viewport on every side");
    }

    // ------------------------------------------------------------------ the content grows and shrinks

    private Widget<?> extra;

    /**
     * A focused button flush with the bottom of a view scrolling vertically, whose content fits
     * the viewport exactly, so that the ring crosses the view's bottom edge; and a block above it,
     * hidden, which overflows the view once shown.
     */
    private void mountAButtonAtTheBottom() {
        button = new Button("Save");
        Stack bottom = new Stack().alignment(Stack.Alignment.BOTTOM_LEFT);
        bottom.add(button);
        extra = new SizedBox(40, 10);
        extra.setVisible(false);
        Column content = new Column();
        content.add(extra);
        content.add(new SizedBox(200, 80, bottom));
        view = new ScrollView(content, false, true);
        Stack centre = new Stack().alignment(Stack.Alignment.CENTER);
        centre.add(new SizedBox(200, 80, view));
        mount(new Padding(Insets.all(INSET), centre), W, H);
        button.requestFocus();
        settle();
    }

    /** Every point of {@code ink} outside {@code kept} is inside one of the frames' passes. */
    private static void assertRepainted(Rect ink, Rect kept, List<Rect> passes, String what) {
        for (float y = ink.y() + 0.25f; y < ink.bottom(); y += 0.5f) {
            for (float x = ink.x() + 0.25f; x < ink.right(); x += 0.5f) {
                if (kept.contains(x, y)) {
                    continue;
                }
                boolean covered = false;
                for (Rect pass : passes) {
                    if (pass.contains(x, y)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    throw new AssertionError(what + ": the ink at " + x + "," + y + " was never "
                            + "repainted: no pass of " + passes + " covers " + ink);
                }
            }
        }
    }

    /** The frames up to the one that paints nothing, their passes together; none of them whole. */
    private List<Rect> passesUntilSettled() {
        List<Rect> passes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            frame();
            if (canvas.nothingPainted()) {
                return passes;
            }
            assertFalse(canvas.cleared, "a whole frame repaints the band by accident");
            passes.addAll(canvas.passes);
        }
        throw new AssertionError("never settled");
    }

    /**
     * Content that grows past the viewport closes the band along the axis it now scrolls, and the
     * part of the ring the band held is wiped at once, although the frame lays the view out in
     * place and repaints only what moved; content that shrinks back opens it again, and the ring
     * is drawn whole.
     */
    @Test
    void contentGrowingPastTheViewportAndShrinkingBackRepaintsTheBandItClosesAndOpens() {
        mountAButtonAtTheBottom();
        Rect ring = ringBox();
        Rect box = sceneBox(view);
        float reach = Theme.current().focusRingReach();
        assertTrue(ring.bottom() > box.bottom() + FEATHER, "the ring hangs past the view's bottom");
        wholeFrame();
        assertWhole(ringsOf(Strokes.FOCUS_RING), "focus ring over content that fits");
        settle();

        extra.setVisible(true);
        List<Rect> passes = passesUntilSettled();
        assertTrue(view.maxOffsetY() > 0, "the content overflows now");
        assertRepainted(ring, box, passes, "growing past the viewport");
        wholeFrame();
        List<InkRecorder.Ink> rings = ringsOf(Strokes.FOCUS_RING);
        assertFalse(rings.isEmpty(), "the block above pushes the button down, not out of sight");
        assertEquals(new Rect(box.x() - reach, box.y(), box.width() + 2 * reach, box.height()),
                rings.get(0).clip(), "overflowing, the view clips at its top and bottom");
        settle();

        extra.setVisible(false);
        passes = passesUntilSettled();
        assertEquals(0, view.maxOffsetY(), 0, "the content fits again");
        assertRepainted(ring, new Rect(0, 0, 0, 0), passes, "shrinking back");
        wholeFrame();
        rings = ringsOf(Strokes.FOCUS_RING);
        assertEquals(box.inflate(reach), rings.get(0).clip(),
                "fitting again, the band is open on every side");
        assertWhole(rings, "focus ring over content that fits again");
    }

    // ------------------------------------------------------------------ the dialog

    private void showInScene(Dialog dialog) {
        showInScene(dialog, 900);
    }

    /** In a window {@code height} tall, which caps the card when it would stand taller. */
    private void showInScene(Dialog dialog, int height) {
        mount(new Column(), 900, height);
        dialog.setDisplayMode(DisplayMode.IN_SCENE).show(scene);
        settle();
    }

    private static <T> T find(Widget<?> root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        for (Widget<?> child : root.children()) {
            T found = find(child, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * A checkbox focused at the body's leading edge keeps its whole ring, in both directions. A
     * line of text below it keeps the ring off the body's bottom, so that it crosses the body's
     * side and only its side.
     */
    @Test
    void aFocusedCheckboxAtTheDialogBodysEdgeKeepsItsWholeRing() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            Checkbox checkbox = new Checkbox("Remember this choice");
            Column content = new Column();
            content.add(checkbox);
            content.add(new Label("Applies to this folder only."));
            Dialog dialog = new Dialog("Rename", "").setContent(content)
                    .addPrimaryButton("OK", "ok");
            dialog.contentRoot().setLayoutDirection(direction);
            showInScene(dialog);
            checkbox.requestFocus();
            settle();
            wholeFrame();
            List<InkRecorder.Ink> rings = ringsOf(Strokes.FOCUS_RING_THIN);
            ScrollView body = find(dialog.contentRoot(), ScrollView.class);
            assertNotNull(body);
            assertFalse(rings.isEmpty(), direction + ": the checkbox drew no ring");
            // The box's own border is drawn with the same pen; the focus ring is the one around it.
            Rect ring = rings.stream().map(InkRecorder.Ink::ink)
                    .max(java.util.Comparator.comparingDouble(Rect::width)).orElseThrow();
            assertTrue(direction == LayoutDirection.LTR
                            ? ring.x() < body.localToSceneX()
                            : ring.right() > body.localToSceneX() + body.width(),
                    direction + ": the checkbox is flush with the body's leading edge, so its "
                            + "ring hangs past it");
            assertWhole(rings, direction + " checkbox's focus ring");
        }
    }

    /**
     * A checkbox focused at the bottom of a body that fits keeps its whole ring, in both
     * directions: the body scrolls vertically, but nothing in it can scroll out of sight while it
     * fits, so its view lets it past its top and bottom by the band it lets it past its sides.
     */
    @Test
    void aFocusedCheckboxAtTheBottomOfADialogBodyThatFitsKeepsItsWholeRing() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            Checkbox checkbox = new Checkbox("Remember this choice");
            Dialog dialog = new Dialog("Rename", "").setContent(checkbox)
                    .addPrimaryButton("OK", "ok");
            dialog.contentRoot().setLayoutDirection(direction);
            showInScene(dialog);
            checkbox.requestFocus();
            settle();
            wholeFrame();
            ScrollView body = find(dialog.contentRoot(), ScrollView.class);
            assertNotNull(body);
            assertEquals(0, body.maxOffsetY(), 0, direction + ": the body fits");
            List<InkRecorder.Ink> rings = ringsOf(Strokes.FOCUS_RING_THIN);
            assertFalse(rings.isEmpty(), direction + ": the checkbox drew no ring");
            Rect box = sceneBox(body);
            assertTrue(rings.get(0).ink().bottom() > box.bottom(), direction + ": the checkbox is "
                    + "the body's last line, so its ring hangs past the body's bottom");
            assertEquals(box.inflate(declared(body)), rings.get(0).clip(), direction
                    + ": the body's clip is its box grown by the band on every side");
            assertWhole(rings, direction + " checkbox's focus ring");
        }
    }

    /**
     * A body that overflows, the card capped by a short window, clips exactly at its top and
     * bottom, where the content scrolls out of sight, and is still let past its sides by the band:
     * the ring of the checkbox revealed at its bottom edge is cut there, as it should be.
     */
    @Test
    void aDialogBodyThatOverflowsClipsExactlyAtItsTopAndBottom() {
        Checkbox checkbox = new Checkbox("Remember this choice");
        Column content = new Column();
        for (int i = 0; i < 40; i++) {
            content.add(new Label("Line " + i));
        }
        content.add(checkbox);
        Dialog dialog = new Dialog("Rename", "").setContent(content).addPrimaryButton("OK", "ok");
        showInScene(dialog, 300);
        ScrollView body = find(dialog.contentRoot(), ScrollView.class);
        assertNotNull(body);
        assertTrue(body.maxOffsetY() > 0, "the window caps the card, so the body scrolls");
        checkbox.requestFocus();
        settle();
        assertEquals(body.maxOffsetY(), body.offsetY(), 0, "focus revealed the last line");
        wholeFrame();
        List<InkRecorder.Ink> rings = ringsOf(Strokes.FOCUS_RING_THIN);
        assertFalse(rings.isEmpty(), "the checkbox drew no ring");
        Rect box = sceneBox(body);
        float band = declared(body);
        assertEquals(new Rect(box.x() - band, box.y(), box.width() + 2 * band, box.height()),
                rings.get(0).clip(), "clipped exactly at the top and bottom, past the sides");
        assertTrue(rings.get(0).ink().bottom() > rings.get(0).clip().bottom(),
                "the ring of the last line is cut at the bottom, where the content scrolls");
    }

    /**
     * Parks the picker a colour button raises on {@code colour}, reading {@code direction}, and
     * answers the cursor's circles: every one the field drew, each with the clip it was drawn in,
     * after holding that the clip lets the body past its edge by no more than the card's padding.
     */
    private List<InkRecorder.Ink> pickerCursorOn(Color colour, LayoutDirection direction) {
        ColorPickerButton well = new ColorPickerButton(colour);
        well.setPickerDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        root.add(well);
        mount(root, 900, 900);
        well.openPicker();
        Dialog dialog = well.openDialog();
        assertNotNull(dialog, "the button raised no dialog");
        dialog.contentRoot().setLayoutDirection(direction);
        settle();
        ColorPicker picker = find(dialog.contentRoot(), ColorPicker.class);
        assertNotNull(picker);
        body = find(dialog.contentRoot(), ScrollView.class);
        Rect field = sceneBox(picker.saturationValueField());
        wholeFrame();
        List<InkRecorder.Ink> cursor = new ArrayList<>();
        for (InkRecorder.Ink circle : canvas.circles) {
            float cx = circle.ink().centerX();
            float cy = circle.ink().centerY();
            if (cx >= field.x() - EPS && cx <= field.right() + EPS
                    && cy >= field.y() - EPS && cy <= field.bottom() + EPS) {
                cursor.add(circle);
            }
        }
        assertFalse(cursor.isEmpty(), "the field painted no cursor");
        Rect card = sceneBox(dialog.contentRoot()).inflate(-Strokes.BORDER);
        for (InkRecorder.Ink circle : cursor) {
            assertTrue(inside(circle.clip(), card), "the body is let past its edge by no more "
                    + "than the card's padding: " + circle.clip() + " inside " + card);
        }
        return cursor;
    }

    private ScrollView body;

    /** On white the cursor sits on the picker's top leading corner, which is the body's edge. */
    @Test
    void thePickersCursorOnWhiteIsWholeAtTheDialogBodysLeftEdge() {
        List<InkRecorder.Ink> cursor = pickerCursorOn(Color.WHITE, LayoutDirection.LTR);
        assertTrue(cursor.get(0).ink().x() < body.localToSceneX(),
                "on white the cursor hangs past the body's left edge");
        assertWhole(cursor, "cursor ring");
    }

    /**
     * Reading right to left the field is the row's trailing child and sits on the body's right
     * edge, while its own axes stay unmirrored, saturation growing to the right: so the corner on
     * that edge is the pure hue's, top right.
     */
    @Test
    void thePickersCursorOnThePureHueIsWholeAtTheDialogBodysRightEdgeReadingRightToLeft() {
        List<InkRecorder.Ink> cursor = pickerCursorOn(Color.rgb(0xFF0000), LayoutDirection.RTL);
        assertTrue(cursor.get(0).ink().right() > body.localToSceneX() + body.width(),
                "on the pure hue, reading right to left, the cursor hangs past the body's right "
                        + "edge: " + cursor + " against " + sceneBox(body));
        assertWhole(cursor, "cursor ring");
    }

    /**
     * The body is let past its sides by the card's padding less the card's outline, at every
     * step, and the column holding the body declares at least that much, as the body does, so
     * that a repaint landing in the band paints them.
     */
    @Test
    void theDialogBodysBandIsTheCardsPaddingLessItsOutlineAtEveryStep() {
        for (limn.scene.ControlSize step : limn.scene.ControlSize.values()) {
            Dialog dialog = new Dialog("Rename", "").setContent(new Checkbox("Remember"))
                    .addPrimaryButton("OK", "ok");
            dialog.setControlSize(step);
            showInScene(dialog);
            ScrollView scroll = find(dialog.contentRoot(), ScrollView.class);
            TokenPadding padding = find(dialog.contentRoot(), TokenPadding.class);
            assertNotNull(scroll);
            assertNotNull(padding);
            float band = padding.insets().left() - Strokes.BORDER;
            assertEquals(Tokens.spacingFor(padding, Tokens.Role.LARGE) - Strokes.BORDER, band, 0,
                    step.name());
            assertEquals(band, declared(scroll), 0, step.name());
            assertEquals(band, declared(scroll.children().get(0)), 0, step.name() + " body");
            assertTrue(declared(scroll.parent()) >= band, step.name() + " card column");
        }
    }

    /**
     * A pass landing only in the card's padding beside the body, past the reach of a focus ring
     * and inside the cursor's, still draws the cursor on white: every widget between the card and
     * the field declares at least the cursor's reach there, the box the colour button puts the
     * picker in included.
     */
    @Test
    void aPassInTheCardsPaddingBesideTheBodyRedrawsTheCursorHangingIntoIt() {
        List<InkRecorder.Ink> cursor = pickerCursorOn(Color.WHITE, LayoutDirection.LTR);
        Rect ring = cursor.get(0).ink();
        for (InkRecorder.Ink circle : cursor) {
            ring = ring.union(circle.ink());
        }
        float left = body.localToSceneX();
        Rect band = new Rect(left - 7, ring.centerY() - 2, 2, 4);
        assertTrue(meets(band, ring), "the band meets the cursor");
        assertTrue(band.right() < left - Theme.current().focusRingReach() - FEATHER,
                "the band lies past the focus ring's reach, where only the cursor's reaches");

        // Past the frame the whole repaint leaves behind, to a scene that repaints nothing, so
        // that the band is the one pass and the only one that can reach the cursor.
        settle();
        scene.damage(band);
        frame();
        assertFalse(canvas.cleared, "a whole frame paints everything and tests no culling");
        assertEquals(List.of(band), canvas.passes, "the damage is the band alone");
        boolean redrawn = false;
        for (InkRecorder.Ink circle : canvas.circles) {
            if (meets(circle.ink(), band) && meets(circle.clip(), band)) {
                redrawn = true;
                break;
            }
        }
        assertTrue(redrawn, "a pass over " + band + " wiped the part of the cursor " + ring
                + " inside it and did not draw it again");
    }

    // ------------------------------------------------------------------ doubles

    /** A leaf that records the clip it is painted in. */
    private static final class Probe extends Widget<Probe> {
        Rect clip;

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            return constraints.constrain(20, 20);
        }

        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
            if (canvas instanceof InkRecorder recorder) {
                clip = recorder.sceneClip();
            }
        }
    }

    /**
     * Records the scene's own repaint passes, every focus ring stroked, and every circle stroked,
     * each in scene coordinates with the clip in force when it was drawn, by following the
     * translations and clips the paint walk pushes.
     */
    private static final class InkRecorder extends FakeCanvas {

        /** One stroke: its ink box (the pen included) and the clip it was drawn in. */
        record Ink(Rect ink, Rect clip, float pen) {
        }

        private record State(float tx, float ty, Rect clip) {
        }

        final List<Rect> passes = new ArrayList<>();
        final List<Ink> rings = new ArrayList<>();
        final List<Ink> circles = new ArrayList<>();
        boolean cleared;
        private int paints;
        private final Deque<State> saved = new ArrayDeque<>();
        private final Rect whole;
        private float tx;
        private float ty;
        private Rect clip;

        InkRecorder(float width, float height) {
            super(width, height);
            whole = new Rect(0, 0, width, height);
            clip = whole;
        }

        void reset() {
            passes.clear();
            rings.clear();
            circles.clear();
            cleared = false;
            paints = 0;
        }

        boolean nothingPainted() {
            return !cleared && passes.isEmpty() && paints == 0;
        }

        Rect sceneClip() {
            return clip;
        }

        @Override
        public void clear(Color color) {
            cleared = true;
        }

        @Override
        public void save() {
            super.save();
            saved.push(new State(tx, ty, clip));
        }

        @Override
        public void restore() {
            if (!saved.isEmpty()) {
                State outer = saved.pop();
                tx = outer.tx();
                ty = outer.ty();
                clip = outer.clip();
            }
            super.restore();
        }

        @Override
        public void restoreToCount(int count) {
            while (saveCount() > count && !saved.isEmpty()) {
                restore();
            }
            super.restoreToCount(count);
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            if (saveCount() == 1) {
                passes.add(new Rect(tx + x, ty + y, w, h));
            }
            narrow(new Rect(tx + x, ty + y, w, h));
        }

        @Override
        public void clipRoundRect(RoundRect r) {
            narrow(new Rect(tx + r.x(), ty + r.y(), r.width(), r.height()));
        }

        private void narrow(Rect to) {
            float x0 = Math.max(clip.x(), to.x());
            float y0 = Math.max(clip.y(), to.y());
            float x1 = Math.min(clip.right(), to.right());
            float y1 = Math.min(clip.bottom(), to.bottom());
            clip = new Rect(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        }

        @Override
        public Rect clipBounds() {
            return new Rect(clip.x() - tx, clip.y() - ty, clip.width(), clip.height());
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            paints++;
        }

        @Override
        public void fillRoundRect(RoundRect r, Paint paint) {
            paints++;
        }

        @Override
        public void drawRoundRect(RoundRect r, float strokeWidth, Paint paint) {
            paints++;
            if (strokeWidth == Strokes.FOCUS_RING || strokeWidth == Strokes.FOCUS_RING_THIN) {
                float half = strokeWidth / 2;
                rings.add(new Ink(new Rect(tx + r.x() - half, ty + r.y() - half,
                        r.width() + strokeWidth, r.height() + strokeWidth), clip, strokeWidth));
            }
        }

        @Override
        public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
            paints++;
            float reach = radius + strokeWidth / 2;
            circles.add(new Ink(new Rect(tx + cx - reach, ty + cy - reach, 2 * reach, 2 * reach),
                    clip, strokeWidth));
        }
    }
}
