package limn.scene;

import limn.components.ListView;
import limn.components.ScrollView;
import limn.graphics.Canvas;
import limn.graphics.Rect;
import limn.input.Keys;
import limn.scene.event.DragEvent;
import limn.scene.event.DragStartEvent;
import limn.scene.event.DropEvent;
import limn.scene.layout.Transform;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.transfer.DataFormat;
import limn.transfer.Drag;
import limn.transfer.DropAction;
import limn.transfer.Transfer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pointer drag scrolls the viewports it rests near the edge of, so a place to drop that is out
 * of sight comes into it: what scrolls is what the pointer is over and can still move that way,
 * innermost first, toward the edge it approaches, at a speed that grows with how near the edge,
 * or how far past it, the pointer is. Until 2026-10-20 only the widget that accepted the drop was
 * scrolled, by a wheel sent to it every 60 ms when the pointer was near one of ITS edges: on a
 * page whose drop area showed only its top at the bottom of the window, entering that area put the
 * pointer near its top, the wheel "up" went from the area, which does not scroll, to the page, and
 * the page scrolled up and carried the area away from the pointer; and nothing scrolled the page
 * down at its own bottom edge at all.
 */
class DragAutoScrollTest {

    private static final DataFormat<String> CARD = DataFormat.inProcess("application/x-limn-test-scroll-card", String.class);
    private static final float W = 300;
    private static final float H = 400;

    private final List<String> log = new ArrayList<>();
    private final long[] nanos = {1_000_000_000L};
    private HeadlessUi ui;
    private Scene scene;
    private NoopCanvas canvas;
    private float sceneWidth = W;
    private float sceneHeight = H;

    /** A box a fixed size that drags a card when it holds one and takes cards when it is a shelf. */
    private class Box extends Widget<Box> {
        final String name;
        final float w;
        final float h;
        String card;
        boolean takes;
        /** Its drag goes to the system, allowing a move or a copy: a picture of its own hands it over at once. */
        boolean toSystem;
        /** What it accepted each time it was asked. */
        final List<DropAction> accepted = new ArrayList<>();

        Box(String name, float w, float h) {
            this.name = name;
            this.w = w;
            this.h = h;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(w, h);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }

        @Override
        protected Drag onDragStart(DragStartEvent event) {
            if (card == null) {
                return null;
            }
            if (toSystem) {
                return Drag.of(Transfer.of(CARD, card)).allow(DropAction.MOVE, DropAction.COPY)
                        .image(new limn.graphics.Image(1, 1, new byte[4]), 0, 0)
                        .onEnd(action -> log.add(name + ":END:" + action));
            }
            return Drag.of(Transfer.of(CARD, card)).allow(DropAction.MOVE)
                    .onEnd(action -> log.add(name + ":END:" + action));
        }

        @Override
        protected void onDragOver(DragEvent event) {
            if (takes && (event.offer().has(CARD) || event.offer().has(DataFormat.FILES))) {
                event.accept();
                accepted.add(event.acceptedAction());
            }
        }

        @Override
        protected void onDrop(DropEvent event) {
            event.offer().read(CARD).thenAccept(value -> log.add(name + ":DROP:" + value));
        }
    }

    /** A sheet a fixed size, its children where they are put. */
    private static final class Sheet extends Widget<Sheet> {
        final float w;
        final float h;
        final List<float[]> places = new ArrayList<>();

        Sheet(float w, float h) {
            this.w = w;
            this.h = h;
        }

        Sheet put(Widget<?> child, float x, float y) {
            return put(child, x, y, Float.NaN, Float.NaN);
        }

        /** With a size of its own; NaN for the one it measures. */
        Sheet put(Widget<?> child, float x, float y, float width, float height) {
            add(child);
            places.add(new float[] {x, y, width, height});
            return this;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return new Size(w, h);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }

        @Override
        protected void onLayout() {
            for (int i = 0; i < children().size(); i++) {
                Widget<?> child = children().get(i);
                float[] place = places.get(i);
                Size size = Float.isNaN(place[2]) ? child.measure(Constraints.loose(w, h))
                        : child.measure(Constraints.tight(place[2], place[3]));
                child.layoutBox(place[0], place[1], size.width(), size.height());
            }
        }
    }

    @BeforeEach
    void build() {
        ui = new HeadlessUi(() -> nanos[0]);
    }

    @AfterEach
    void close() {
        ui.close();
    }

    private void show(Widget<?> root) {
        show(root, W, H);
    }

    /** A window that takes a drag, as one with a native drag does, and keeps what it was handed. */
    private static final class SystemWindow extends limn.testing.StubWindow {
        limn.backend.spi.DragRequest handed;

        @Override
        public boolean supportsDrag() {
            return true;
        }

        @Override
        public boolean startDrag(limn.backend.spi.DragRequest request) {
            handed = request;
            return true;
        }
    }

    /** Shown in a window whose drags the system drives. */
    private void showInSystemWindow(Widget<?> root, SystemWindow window) {
        show(root, W, H, window);
    }

    private void show(Widget<?> root, float width, float height) {
        show(root, width, height, null);
    }

    private void show(Widget<?> root, float width, float height, limn.backend.@org.jspecify.annotations.Nullable NativeWindow window) {
        sceneWidth = width;
        sceneHeight = height;
        scene = new Scene(root, () -> nanos[0]);
        if (window != null) {
            scene.bind(window);
        }
        canvas = new NoopCanvas((int) width, (int) height);
        scene.layoutPass(width, height);
        scene.renderFrame(canvas);
    }

    private void press(Widget<?> widget) {
        Rect box = widget.localToSceneBounds(0, 0, widget.width(), widget.height());
        float x = box.x() + box.width() / 2;
        float y = box.y() + box.height() / 2;
        drive(scene).mouseMoved(x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y, 1);
        drive(scene).inputBatchEnded();
        // On its way: a press that has travelled a few points is a drag.
        move(x + 6, y + 6);
        assertTrue(scene.isDragging(), "the press became a drag");
    }

    private void move(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
    }

    private void release(float x, float y) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y, 1);
        drive(scene).inputBatchEnded();
    }

    /** Frames at {@code fps} for {@code seconds}, as a display's loop renders them. */
    private void frames(double seconds, double fps) {
        int count = (int) Math.round(seconds * fps);
        for (int i = 0; i < count; i++) {
            nanos[0] += Math.round(1e9 / fps);
            scene.layoutPass(sceneWidth, sceneHeight);
            scene.renderFrame(canvas);
        }
    }

    private static Rect onScreen(Widget<?> widget) {
        return widget.localToSceneBounds(0, 0, widget.width(), widget.height());
    }

    @Test
    void aDropAreaShowingOnlyItsTopBelowThePointerScrollsIntoViewAndNeverAway() {
        // The Showcase's drag and drop page: the logo above, the drop area's top at the bottom of
        // the window, the page scrolled down a little already.
        Box logo = new Box("logo", 96, 96);
        logo.card = "logo";
        Box area = new Box("area", W, 150);
        area.takes = true;
        ScrollView page = new ScrollView(new Sheet(W, 1200).put(logo, 12, 200).put(area, 0, 530));
        show(page);
        page.scrollTo(0, 150);
        frames(0.1, 60);
        assertEquals(380, onScreen(area).y(), 0.01, "its top 20 points show at the bottom");

        press(logo);
        move(60, 390); // into the area, and into the page's bottom band
        float lowest = page.offsetY();
        for (int i = 0; i < 180 && onScreen(area).bottom() > H; i++) {
            frames(1 / 60.0, 60);
            assertTrue(page.offsetY() >= lowest, "the page never scrolls up under a drag going down");
            lowest = page.offsetY();
        }
        assertTrue(onScreen(area).bottom() <= H, "the whole area came into view: " + onScreen(area));
        Rect seen = onScreen(area);
        move(seen.x() + seen.width() / 2, seen.y() + seen.height() / 2); // out of the band, onto it
        float settled = page.offsetY();
        frames(0.5, 60);
        assertEquals(settled, page.offsetY(), "out of the band nothing scrolls");
        release(seen.x() + seen.width() / 2, seen.y() + seen.height() / 2);
        assertEquals(List.of("area:DROP:logo", "logo:END:MOVE"), log);
    }

    @Test
    void aDropAreaWhollyBelowTheWindowIsReachedByRestingAtTheBottomEdge() {
        Box logo = new Box("logo", 96, 96);
        logo.card = "logo";
        Box area = new Box("area", W, 150);
        area.takes = true;
        ScrollView page = new ScrollView(new Sheet(W, 1200).put(logo, 12, 20).put(area, 0, 900));
        show(page);
        press(logo);
        move(60, H - 4);
        frames(3, 60);
        assertTrue(page.offsetY() > 0, "the page scrolled down");
        assertTrue(onScreen(area).y() < H, "the area came into view: " + onScreen(area));
    }

    @Test
    void towardTheTopEdgeItScrollsUpAndStopsAtTheStart() {
        Box card = new Box("card", 100, 60);
        card.card = "ace";
        ScrollView page = new ScrollView(new Sheet(W, 1200).put(card, 12, 1100));
        show(page);
        page.scrollTo(0, page.maxOffsetY());
        frames(0.1, 60);
        press(card);
        move(60, 4);
        float last = page.offsetY();
        for (int i = 0; i < 240; i++) {
            frames(1 / 60.0, 60);
            assertTrue(page.offsetY() <= last, "only up");
            last = page.offsetY();
        }
        assertEquals(0, page.offsetY(), "at the start, and not past it");
        assertEquals(0, scene.tickerCounts()[1], "nothing left to scroll: no ticker asks for frames");
    }

    @Test
    void anInnerViewScrollsFirstAndHandsTheEdgeToTheOuterOneAtItsEnd() {
        Box card = new Box("card", 100, 40);
        card.card = "ace";
        ScrollView inner = new ScrollView(new Sheet(W, 600));
        // The inner view stands flush with the outer one's bottom edge: both bands hold the pointer.
        ScrollView outer = new ScrollView(new Sheet(W, 1200).put(card, 12, 20).put(inner, 0, 250, W, 150));
        show(outer);
        assertEquals(250, onScreen(inner).y(), 0.01);
        assertEquals(150, inner.height(), 0.01);
        press(card);
        move(60, 395);
        boolean innerDone = false;
        for (int i = 0; i < 600 && outer.offsetY() == 0; i++) {
            frames(1 / 60.0, 60);
            if (inner.offsetY() < inner.maxOffsetY()) {
                assertEquals(0, outer.offsetY(), "the outer view waits while the inner one can move");
            } else {
                innerDone = true;
            }
        }
        assertTrue(innerDone, "the inner view reached its end");
        assertTrue(outer.offsetY() > 0, "then the outer one took the edge");
        assertEquals(inner.maxOffsetY(), inner.offsetY(), 0.01);
    }

    @Test
    void aViewAtItsLimitInTheMiddleOfThePageLeavesThePageAloneAndAViewThePointerLeftIsNotScrolled() {
        Box card = new Box("card", 100, 40);
        card.card = "ace";
        ScrollView inner = new ScrollView(new Sheet(W, 600));
        ScrollView outer = new ScrollView(new Sheet(W, 1200).put(card, 12, 20).put(inner, 0, 150, W, 150));
        show(outer);
        press(card);
        move(60, 155); // the inner view's top band, the inner view at its start
        frames(1, 60);
        assertEquals(0, inner.offsetY());
        assertEquals(0, outer.offsetY(), "the page's own band does not hold the pointer");

        move(60, 320); // below the inner view, in the middle of the page
        frames(1, 60);
        assertEquals(0, inner.offsetY(), "the inner view is not under the pointer");
        assertEquals(0, outer.offsetY());

        move(60, 290); // the inner view's bottom band
        frames(0.5, 60);
        assertTrue(inner.offsetY() > 0, "near its bottom it scrolls");
        assertEquals(0, outer.offsetY(), "and only it");
    }

    @Test
    void sidewaysItScrollsTowardTheEdgeApproachedInEitherReadingDirection() {
        for (LayoutDirection direction : List.of(LayoutDirection.LTR, LayoutDirection.RTL)) {
            log.clear();
            Box card = new Box("card", 60, 60);
            card.card = "ace";
            Box far = new Box("far", 60, 60);
            Sheet sheet = new Sheet(1200, H - 20);
            // The card where the view opens, the other box at the far end of the content.
            boolean rtl = direction == LayoutDirection.RTL;
            sheet.put(card, rtl ? 1100 : 40, 100).put(far, rtl ? 40 : 1100, 200);
            ScrollView view = new ScrollView(sheet, true, false);
            view.setLayoutDirection(direction);
            show(view);
            assertTrue(onScreen(card).x() >= 0 && onScreen(card).right() <= W, direction + ": the card shows");
            assertTrue(onScreen(far).x() >= W || onScreen(far).right() <= 0, direction + ": the far box does not");
            press(card);
            float awayFromFar = rtl ? W - 4 : 4;
            move(awayFromFar, 200);
            frames(0.5, 60);
            assertEquals(0, view.offsetX(), direction + ": toward the start it is already there");
            float towardFar = rtl ? 4 : W - 4;
            move(towardFar, 200);
            frames(3, 60);
            assertTrue(view.offsetX() > 0, direction + ": it scrolled toward the far end");
            Rect seen = onScreen(far);
            assertTrue(seen.x() < W && seen.right() > 0, direction + ": the far box came into view: " + seen);
            release(towardFar, 200);
        }
    }

    @Test
    void aViewTurnedUpsideDownScrollsTowardTheEdgeOnScreen() {
        Box card = new Box("card", 100, 60);
        card.card = "ace";
        Box far = new Box("far", 100, 60);
        ScrollView view = new ScrollView(new Sheet(W, 1200).put(card, 12, 20).put(far, 12, 1100));
        Transform turned = new Transform(view).setRotation(180);
        show(turned);
        assertTrue(onScreen(card).y() > H / 2, "turned over, the start of the content is at the bottom");
        press(card);
        move(150, 4); // the top of the window: the view's own bottom edge
        frames(3, 60);
        assertTrue(view.offsetY() > 0, "it scrolled toward its end, which is up on screen");
        Rect seen = onScreen(far);
        assertTrue(seen.bottom() > 0 && seen.y() < H, "the far box came into view: " + seen);
    }

    @Test
    void theSpeedFollowsTheDepthIsTheSameAtAnyFrameRateAndIsBounded() {
        float slow = distance(H - 20, 0.5, 60); // 4 points into the band
        float fast = distance(H - 2, 0.5, 60); // 22 points into it
        assertTrue(slow > 0 && fast > 2 * slow, "deeper is faster: " + slow + " then " + fast);

        float at30 = distance(H - 8, 0.5, 30);
        float at144 = distance(H - 8, 0.5, 144);
        assertEquals(at30, at144, at30 * 0.05, "the same half second at 30 and at 144 frames a second");

        float past = distance(H + 100_000, 0.5, 60); // far past the window's edge
        assertTrue(past > fast, "past the edge is faster than at it");
        assertTrue(past <= 2400 * 0.5 + 1, "and never faster than its bound: " + past);
    }

    /** How far the page scrolls in {@code seconds} with the pointer resting at {@code y}. */
    private float distance(float y, double seconds, double fps) {
        log.clear();
        Box card = new Box("card", 100, 60);
        card.card = "ace";
        ScrollView page = new ScrollView(new Sheet(W, 100_000).put(card, 12, 20));
        show(page);
        press(card);
        move(60, y);
        frames(0.1, fps); // under way
        float from = page.offsetY();
        frames(seconds, fps);
        float moved = page.offsetY() - from;
        release(60, y);
        return moved;
    }

    @Test
    void aDragPastTheWindowsEdgeScrollsAndReleasedThereDropsNothing() {
        Box card = new Box("card", 100, 60);
        card.card = "ace";
        ScrollView page = new ScrollView(new Sheet(W, 1200).put(card, 12, 20));
        show(page);
        press(card);
        move(-50, 200); // beside the window, level with its middle: no edge it scrolls along
        frames(0.5, 60);
        assertEquals(0, page.offsetY());
        move(60, H + 40); // below it
        frames(0.5, 60);
        assertTrue(page.offsetY() > 0, "below the window the page scrolls down");
        release(60, H + 40);
        assertEquals(List.of("card:END:null"), log, "nothing there takes it");
        float stopped = page.offsetY();
        frames(0.5, 60);
        assertEquals(stopped, page.offsetY(), "the drag over, nothing scrolls");
    }

    @Test
    void filesDraggedFromAnotherProgramScrollThePaneUntilTheyLeave() {
        Box area = new Box("area", W, 150);
        area.takes = true;
        ScrollView page = new ScrollView(new Sheet(W, 1200).put(area, 0, 900));
        show(page);
        limn.backend.spi.WindowInput input = limn.scene.internal.SceneAccess.input(scene);
        limn.transfer.Offer files = Transfer.of(DataFormat.FILES, List.of(java.nio.file.Path.of("a.txt"))).asOffer();
        input.dragOver(files, Set.of(DropAction.COPY), 60, H - 4);
        frames(3, 60);
        assertTrue(page.offsetY() > 0, "the pane scrolled under the files");
        assertTrue(onScreen(area).y() < H, "and the area came into view");
        Rect seen = onScreen(area);
        DropAction asked = input.dragOver(files, Set.of(DropAction.COPY), 60, seen.y() + seen.height() / 2);
        assertEquals(DropAction.COPY, asked, "the area under the pointer takes them");
        input.dragLeft();
        float stopped = page.offsetY();
        frames(0.5, 60);
        assertEquals(stopped, page.offsetY(), "gone from the window, nothing scrolls");
    }

    @Test
    void aTargetThatThrowsOnceDuringAStepLeavesLaterDragsScrolling() {
        // An application's onDragOver meets a state it did not expect once, while the page
        // scrolls under the pointer: the scene drops the ticker that threw. Until 2026-10-20 the
        // flag that said the ticker was registered stayed set, and no later drag in that window
        // ever scrolled anything again.
        Box logo = new Box("logo", 96, 96);
        logo.card = "logo";
        boolean[] armed = {true};
        ScrollView[] holder = new ScrollView[1];
        Box area = new Box("area", W, 1000) {
            @Override
            protected void onDragOver(DragEvent event) {
                if (armed[0] && holder[0].offsetY() > 0) {
                    armed[0] = false;
                    throw new IllegalStateException("the application did not expect this once");
                }
                super.onDragOver(event);
            }
        };
        area.takes = true;
        ScrollView page = new ScrollView(new Sheet(W, 1200).put(logo, 12, 20).put(area, 0, 200, W, 1000));
        holder[0] = page;
        show(page);
        press(logo);
        move(60, H - 4);
        frames(1, 60);
        assertFalse(armed[0], "the target threw during a step");
        release(60, H - 4);
        page.scrollTo(0, 0);
        frames(0.1, 60);
        assertEquals(0, page.offsetY());

        press(logo);
        move(60, H - 4);
        frames(1, 60);
        assertTrue(page.offsetY() > 0, "the next drag scrolls the page again: " + page.offsetY());
        release(60, H - 4);
    }

    @Test
    void ourOwnDragHandedToTheSystemIsAskedAgainWithWhatTheSystemAllows() {
        // The source allows a move or a copy; the system, with the key for a copy held, allows
        // only the copy. Until 2026-10-20 the ask after a step went through the scene's own
        // drag: the source's set and the scene's modifier keys, stale while the system drives,
        // so the target accepted and drew a move the system would not make.
        Box logo = new Box("logo", 96, 96);
        logo.card = "logo";
        logo.toSystem = true;
        Box area = new Box("area", W, 150);
        area.takes = true;
        ScrollView page = new ScrollView(new Sheet(W, 1200).put(logo, 12, 20).put(area, 0, 900));
        SystemWindow window = new SystemWindow();
        showInSystemWindow(page, window);
        press(logo);
        assertTrue(window.handed != null, "the drag went to the system");
        limn.backend.spi.WindowInput input = limn.scene.internal.SceneAccess.input(scene);
        limn.transfer.Offer offer = Transfer.of(CARD, "logo").asOffer();
        input.dragOver(offer, Set.of(DropAction.COPY), 60, H - 4);
        frames(3, 60);
        assertTrue(onScreen(area).y() < H - 4, "the area came under the pointer: " + onScreen(area));
        assertFalse(area.accepted.isEmpty(), "and was asked as it did");
        assertEquals(Set.of(DropAction.COPY), Set.copyOf(area.accepted), "only ever the copy the system allows");
        Rect seen = onScreen(area);
        assertEquals(DropAction.COPY, input.dropped(offer, Set.of(DropAction.COPY), 60, seen.y() + seen.height() / 2),
                "dropped on it, a copy");
    }

    @Test
    void aListThatTakesTheDropScrollsItselfAndThePageAroundItStays() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add("row " + i);
        }
        ListView<String> list = new ListView<String>(item -> new Box(item, W, 20)).setItems(rows).setDragEnabled(true);
        list.onReorder((items, index) -> log.add("reorder " + items + " at " + index));
        Box spacer = new Box("spacer", W, 1);
        ScrollView page = new ScrollView(new Sheet(W, 1200).put(new SizedList(list), 0, 100).put(spacer, 0, 1199));
        show(page);
        assertEquals(0, list.firstVisibleIndex());
        drive(scene).mouseMoved(60, 110);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 60, 110, 1);
        drive(scene).inputBatchEnded();
        move(60, 130);
        move(60, 295); // the list's bottom band; the list ends at 300
        frames(1, 60);
        assertTrue(list.firstVisibleIndex() > 0, "the list scrolled");
        assertEquals(0, page.offsetY(), "the page did not");
        release(60, 295);
    }

    /**
     * Row heights that differ from their mean almost everywhere: short rows and tall ones, the
     * tall ones of every height, so the estimate a jump lands by is wrong at nearly every row.
     */
    private static float[] mixedHeights(int count) {
        float[] heights = new float[count];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < count; i++) {
            heights[i] = i % 2 == 0 ? 12 : 12 + random.nextInt(100);
        }
        return heights;
    }

    @Test
    void aListOfRowsOfMixedHeightsMovesSteadilyUnderADragAndNeverLeaps() {
        float[] heights = mixedHeights(400);
        java.util.Map<Widget<?>, Integer> rowOf = new java.util.IdentityHashMap<>();
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < heights.length; i++) {
            items.add(i);
        }
        ListView<Integer> list = new ListView<Integer>(i -> {
            Box row = new Box("row " + i, W, heights[i]);
            rowOf.put(row, i);
            return row;
        }).setItems(items);
        assertSteadyUnderADrag(list, rowOf);
    }

    @Test
    void aTreeOfRowsOfMixedHeightsMovesSteadilyUnderADragAndNeverLeaps() {
        float[] heights = mixedHeights(400);
        java.util.Map<Widget<?>, Integer> rowOf = new java.util.IdentityHashMap<>();
        List<Integer> leaves = new ArrayList<>();
        for (int i = 0; i < heights.length; i++) {
            leaves.add(i);
        }
        limn.components.tree.Tree<Integer> tree = new limn.components.tree.Tree<>(
                new limn.components.tree.Tree.Model<Integer>() {
                    @Override
                    public List<Integer> roots() {
                        return leaves;
                    }

                    @Override
                    public List<Integer> children(Integer node) {
                        return List.of();
                    }

                    @Override
                    public Widget<?> cellFor(Integer node) {
                        Box row = new Box("row " + node, W, heights[node]);
                        rowOf.put(row, node);
                        return row;
                    }
                });
        assertSteadyUnderADrag(tree, rowOf);
    }

    @Test
    void aTableOfRowsOfMixedHeightsMovesSteadilyUnderADragAndNeverLeaps() {
        float[] heights = mixedHeights(400);
        java.util.Map<Widget<?>, Integer> rowOf = new java.util.IdentityHashMap<>();
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < heights.length; i++) {
            rows.add(i);
        }
        limn.components.table.Table<Integer> table = limn.components.table.Table.of(
                limn.components.table.Column.<Integer>widget("row", i -> {
                    Box cell = new Box("row " + i, 40, heights[i]);
                    rowOf.put(cell, i);
                    return cell;
                }));
        table.setRows(rows);
        assertSteadyUnderADrag(table, rowOf);
    }

    /**
     * Drags a card into the bottom band of {@code host}, eighteen points deep, for three seconds,
     * and holds that each frame moves every row seen in it and the frame before by the same few
     * points the speed there gives, 7.5 a frame at 60 frames a second, and never by more. Until
     * 2026-10-20 the step went through the bar's absolute offset, which a list of rows lands by
     * the mean row height, building no row on the way: the rows sat on the step and then leapt by
     * twenty to eighty points whenever the walk crossed a row of another height than the mean.
     */
    private void assertSteadyUnderADrag(Widget<?> host, java.util.Map<Widget<?>, Integer> rowOf) {
        Box card = new Box("card", 60, 40);
        card.card = "ace";
        show(new Sheet(W, H).put(card, 0, 0).put(host, 0, 60, W, 300));
        scene.setTextRuler(limn.testing.TestRulers.FIXED);
        press(card);
        move(150, 60 + 300 - 6);
        frames(1 / 60.0, 60); // the first tick of an arming has no time behind it
        java.util.Map<Integer, Float> before = rowsAt(host, rowOf);
        float largest = 0;
        float total = 0;
        int compared = 0;
        for (int frame = 0; frame < 180; frame++) {
            frames(1 / 60.0, 60);
            java.util.Map<Integer, Float> now = rowsAt(host, rowOf);
            float moved = Float.NaN;
            for (java.util.Map.Entry<Integer, Float> row : now.entrySet()) {
                Float was = before.get(row.getKey());
                if (was != null) {
                    float step = was - row.getValue();
                    if (!Float.isNaN(moved)) {
                        assertEquals(moved, step, 0.01f, "every row moved together in frame " + frame);
                    }
                    moved = step;
                }
            }
            assertFalse(Float.isNaN(moved), "a row seen in frame " + frame + " was seen in the one before");
            assertTrue(moved >= 0, "it moves only toward the end: " + moved + " in frame " + frame);
            largest = Math.max(largest, moved);
            total += moved;
            compared++;
            before = now;
        }
        assertEquals(180, compared);
        assertTrue(largest <= 7.5f + 0.05f, "no frame leaps past the speed's step of 7.5 points: " + largest);
        assertTrue(total > 180 * 7.5f * 0.95f, "and it kept moving: " + total + " points in three seconds");
        // On to the end, where the bar and the rows agree there is no room left.
        frames(60, 60);
        assertEquals(0, scene.tickerCounts()[1], "at the end no ticker asks for frames");
        int last = -1;
        for (int index : rowsAt(host, rowOf).keySet()) {
            last = Math.max(last, index);
        }
        assertEquals(399, last, "the last row came into view");
        release(150, 60 + 300 - 6);
    }

    /** Where each row of {@code host} shown in its box stands now, in scene points, by its index. */
    private static java.util.Map<Integer, Float> rowsAt(Widget<?> host, java.util.Map<Widget<?>, Integer> rowOf) {
        Rect box = onScreen(host);
        java.util.Map<Integer, Float> at = new java.util.HashMap<>();
        java.util.ArrayDeque<Widget<?>> walk = new java.util.ArrayDeque<>(host.children());
        while (!walk.isEmpty()) {
            Widget<?> widget = walk.pop();
            walk.addAll(widget.children());
            Integer index = rowOf.get(widget);
            if (index == null || widget.parent() == null || !widget.isVisible()) {
                continue;
            }
            Rect row = onScreen(widget);
            if (row.bottom() > box.y() && row.y() < box.bottom()) {
                at.put(index, row.y());
            }
        }
        return at;
    }

    /** A list two hundred points tall. */
    private static final class SizedList extends Widget<SizedList> {
        SizedList(ListView<String> list) {
            add(list);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return new Size(W, 200);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }

        @Override
        protected void onLayout() {
            Widget<?> list = children().get(0);
            list.measure(Constraints.tight(W, 200));
            list.layoutBox(0, 0, W, 200);
        }
    }
}
