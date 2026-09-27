package limn.components;

import limn.graphics.Rect;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Grid;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Wrap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A widget shown or hidden repaints itself and what its disappearance moved, not the window.
 *
 * <p>ADR 043 &sect;9.4.4. {@code Widget.setVisible} used to ask for a full layout, and a full
 * layout is a full frame by ADR 002's invariant: a button hidden in a form repainted every field,
 * label and border of the window for the space one button had held. What a visibility change can
 * move is bounded by the nearest ancestor whose size survives it, and the pixels that change are
 * that ancestor's children that moved plus the widget itself; the scene now repaints exactly those.
 *
 * <p>Every assertion reads the scene's repaint passes, not a picture, and each case also checks the
 * one thing a narrower damage could get wrong: that the hidden widget's old box is repainted even
 * though the widget itself is no longer drawn, because a hidden branch damages nothing through
 * itself and a box never erased is a button still on screen.
 */
class VisibilityDamageTest extends ComponentTestBase {

    private static final int W = 640;
    private static final int H = 480;

    private Scene scene;
    private AtomicLong nanos;
    private RecordingTestCanvas canvas;
    /** Every pass the gesture caused, frame by frame, until the scene settled. */
    private final List<Rect> passes = new ArrayList<>();
    private boolean anyFullFrame;

    private void mount(Widget<?> root) {
        nanos = new AtomicLong();
        scene = new Scene(new Padding(Insets.all(40), root), nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        canvas = new RecordingTestCanvas(W, H);
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
        throw new AssertionError("never settled");
    }

    /** Runs {@code change} and records every repaint pass until the scene is at rest again. */
    private void gesture(Runnable change) {
        passes.clear();
        anyFullFrame = false;
        change.run();
        for (int i = 0; i < 60; i++) {
            nanos.addAndGet(16_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                return;
            }
            anyFullFrame |= canvas.cleared;
            passes.addAll(canvas.passClips());
        }
        throw new AssertionError("the gesture never settled");
    }

    private static Rect sceneBox(Widget<?> w) {
        return new Rect(w.localToSceneX(), w.localToSceneY(), w.width(), w.height());
    }

    private boolean repainted(Rect box) {
        for (Rect pass : passes) {
            if (pass.x() <= box.x() + 0.5f && pass.y() <= box.y() + 0.5f
                    && pass.x() + pass.width() >= box.x() + box.width() - 0.5f
                    && pass.y() + pass.height() >= box.y() + box.height() - 0.5f) {
                return true;
            }
        }
        return false;
    }

    private boolean touched(Rect box) {
        for (Rect pass : passes) {
            if (pass.x() < box.x() + box.width() && box.x() < pass.x() + pass.width()
                    && pass.y() < box.y() + box.height() && box.y() < pass.y() + pass.height()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The case in the report: a form, a button in the middle of it, hidden and shown again.
     *
     * <p>What is above the button does not move and must not repaint; what is below moves up by
     * the button's height and must repaint where it was and where it went; and the button's own
     * box must repaint, because otherwise it is still drawn there.
     */
    @Test
    void aButtonHiddenInAFormRepaintsItselfAndWhatMovedNotTheWindow() {
        Label nameCaption = new Label("Name");
        TextField name = new TextField();
        Label emailCaption = new Label("Email");
        TextField email = new TextField();
        Button advanced = new Button("Advanced");
        Label notesCaption = new Label("Notes");
        TextField notes = new TextField();
        Button save = new Button("Save");
        Column form = new Column();
        form.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        for (Widget<?> w : List.of(nameCaption, name, emailCaption, email, advanced, notesCaption,
                notes, save)) {
            form.add(w);
        }
        mount(form);

        Rect above = sceneBox(email);
        Rect button = sceneBox(advanced);
        Rect belowBefore = sceneBox(notes);

        gesture(() -> advanced.setVisible(false));
        assertFalse(anyFullFrame, "hiding one button repainted the whole window");
        assertTrue(repainted(button), "the hidden button's box was never repainted, so the button "
                + "is still drawn there; passes " + passes);
        Rect belowAfter = sceneBox(notes);
        assertTrue(belowAfter.y() < belowBefore.y(), "nothing below the button moved up: the layout "
                + "did not run");
        assertTrue(repainted(belowBefore) && repainted(belowAfter),
                "a field that moved was not repainted where it was and where it went; passes "
                        + passes);
        assertFalse(touched(above), "the field ABOVE the button did not move and was repainted "
                + "anyway; passes " + passes);

        gesture(() -> advanced.setVisible(true));
        assertFalse(anyFullFrame, "showing one button repainted the whole window");
        assertEquals(button, sceneBox(advanced), "the button came back somewhere else");
        assertTrue(repainted(sceneBox(advanced)), "the shown button was never painted; passes "
                + passes);
        assertEquals(belowBefore, sceneBox(notes), "the field below did not move back");
        assertFalse(touched(above), "the field above was repainted for a change below it");
    }

    /**
     * The last widget of a form, hidden: nothing moves into the space it held, so nothing else's
     * damage covers it by accident.
     *
     * <p>This is the case that keeps the scene honest about the hidden widget's own box. In the
     * middle of a column the next sibling slides up into the space and its damage happens to
     * repaint where the hidden widget was; a test written only there passed with the erasure
     * removed. Here nothing slides, so a scene that forgot the box leaves the button on screen.
     */
    @Test
    void theLastWidgetHiddenIsErasedThoughNothingMovesIntoItsPlace() {
        TextField name = new TextField();
        Button save = new Button("Save");
        Column form = new Column();
        form.gap(8).crossAlignment(Flex.CrossAlignment.START);
        form.add(name);
        form.add(save);
        mount(form);

        Rect nameBox = sceneBox(name);
        Rect button = sceneBox(save);
        gesture(() -> save.setVisible(false));
        assertFalse(anyFullFrame, "hiding the last button repainted the whole window");
        assertTrue(repainted(button), "the hidden button's box was never repainted, so the button "
                + "is still drawn there; passes " + passes);
        assertEquals(nameBox, sceneBox(name), "the field above moved");
        assertFalse(touched(nameBox), "the field above was repainted for a change below it");
    }

    /**
     * Inside a container that clips its children, nothing outside the container repaints, however
     * far its content moved: a row hidden at the top of a scrolled list moves every row under it.
     */
    @Test
    void insideAClippingContainerNothingOutsideItRepaints() {
        Column content = new Column();
        List<Button> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Button row = new Button("row " + i);
            rows.add(row);
            content.add(row);
        }
        ScrollView view = new ScrollView(content);
        Label outside = new Label("below the list");
        Column page = new Column();
        page.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(new SizedBox(SizedBox.UNSET, 200, view));
        page.add(outside);
        mount(page);

        Rect container = sceneBox(view);
        Rect outsideBox = sceneBox(outside);
        gesture(() -> rows.get(1).setVisible(false));
        assertFalse(anyFullFrame, "hiding a row inside a scroll view repainted the window");
        for (Rect pass : passes) {
            assertTrue(pass.y() >= container.y() - 2
                            && pass.y() + pass.height() <= container.y() + container.height() + 2,
                    "a pass reached outside the scroll view: " + pass + " against " + container);
        }
        assertFalse(touched(outsideBox), "the label below the scroll view was repainted");
    }

    /** A page of a label, a wrap of chips 120 wide held to 400 (three to a run), and a label. */
    private Wrap chipsPage(Label top, Label under, List<Widget<?>> chips, int count) {
        Wrap tags = new Wrap().gap(8).runGap(8);
        for (int i = 0; i < count; i++) {
            Widget<?> chip = new SizedBox(120, SizedBox.UNSET, new Button("tag " + i));
            chips.add(chip);
            tags.add(chip);
        }
        Column page = new Column();
        page.gap(8).crossAlignment(Flex.CrossAlignment.START);
        page.add(top);
        page.add(new SizedBox(400, SizedBox.UNSET, tags));
        page.add(under);
        mount(page);
        return tags;
    }

    /**
     * A chip hidden in a wrap re-flows the runs, and the wrap absorbs it: six chips are still two
     * runs with one hidden, so the wrap keeps its size, the chips after the hidden one slide back
     * (the fourth up onto the first run) and are repainted where they were and where they went,
     * and nothing outside the wrap is repainted.
     */
    @Test
    void aChipHiddenInAWrapRepaintsTheChipsThatMovedNotTheWindow() {
        Label top = new Label("top");
        Label under = new Label("under the tags");
        List<Widget<?>> chips = new ArrayList<>();
        Wrap tags = chipsPage(top, under, chips, 6);

        Rect topBox = sceneBox(top);
        Rect underBox = sceneBox(under);
        Rect firstBox = sceneBox(chips.get(0));
        Rect hiddenBox = sceneBox(chips.get(1));
        Rect fourthBefore = sceneBox(chips.get(3));
        float height = tags.height();

        gesture(() -> chips.get(1).setVisible(false));

        assertFalse(anyFullFrame, "hiding one chip repainted the whole window");
        assertEquals(height, tags.height(), "five chips are still two runs");
        Rect fourthAfter = sceneBox(chips.get(3));
        assertTrue(fourthAfter.y() < fourthBefore.y(), "the fourth chip did not move up a run");
        assertTrue(repainted(hiddenBox), "the hidden chip's box was never repainted; passes "
                + passes);
        assertTrue(repainted(fourthBefore) && repainted(fourthAfter),
                "a chip that changed run was not repainted where it was and where it went; passes "
                        + passes);
        assertEquals(firstBox, sceneBox(chips.get(0)), "the chip before the hidden one moved");
        assertEquals(underBox, sceneBox(under), "the label under the wrap moved");
        assertFalse(touched(underBox), "the label under the wrap was repainted; passes " + passes);
        assertFalse(touched(topBox), "the label above the wrap was repainted; passes " + passes);
    }

    /**
     * When the hidden chip takes the wrap's last run with it, the wrap gets shorter and the change
     * climbs to the column that absorbs it: the label under the wrap moves up and is repainted, and
     * the label above is not.
     */
    @Test
    void aWrapThatLosesARunHandsTheChangeUpToWhatAbsorbsIt() {
        Label top = new Label("top");
        Label under = new Label("under the tags");
        List<Widget<?>> chips = new ArrayList<>();
        Wrap tags = chipsPage(top, under, chips, 4);

        Rect topBox = sceneBox(top);
        Rect underBefore = sceneBox(under);
        Rect hiddenBox = sceneBox(chips.get(1));
        Rect fourthBefore = sceneBox(chips.get(3));
        float height = tags.height();

        gesture(() -> chips.get(1).setVisible(false));

        assertFalse(anyFullFrame, "a wrap that lost a run escalated to the whole window");
        assertTrue(tags.height() < height, "three chips are one run");
        assertTrue(repainted(hiddenBox) && repainted(fourthBefore)
                        && repainted(sceneBox(chips.get(3))),
                "what moved inside the wrap was not repainted; passes " + passes);
        assertTrue(sceneBox(under).y() < underBefore.y(), "what is under the wrap did not move up");
        assertTrue(repainted(underBefore) && repainted(sceneBox(under)),
                "the label under the wrap was not repainted where it was and where it went");
        assertFalse(touched(topBox), "the label above the wrap was repainted; passes " + passes);
    }

    /**
     * A page of a label, a form of three captions beside three fields that fill what the captions
     * leave, and a label. The widest caption is on the first row, so hiding anything on the second
     * leaves the caption column as it was.
     */
    private Grid formPage(Label top, Label under, List<Widget<?>> cells) {
        Grid form = new Grid(2).gap(12).rowGap(8).expandedColumn(1)
                .columnAlignment(1, Flex.CrossAlignment.STRETCH);
        for (String caption : List.of("Email address", "Name", "Phone")) {
            Label label = new Label(caption);
            TextField field = new TextField();
            cells.add(label);
            cells.add(field);
            form.add(label);
            form.add(field);
        }
        Column page = new Column();
        page.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(top);
        page.add(form);
        page.add(under);
        mount(page);
        return form;
    }

    /**
     * A caption hidden in a grid leaves its cell empty and moves nothing: the grid keeps its size
     * and its columns, the field beside the caption stays where it was, and the one box repainted
     * is the caption's own.
     */
    @Test
    void aCaptionHiddenInAGridRepaintsItsCellAndNothingElse() {
        Label top = new Label("top");
        Label under = new Label("under the form");
        List<Widget<?>> cells = new ArrayList<>();
        Grid form = formPage(top, under, cells);

        Rect captionBox = sceneBox(cells.get(2));
        Rect fieldBox = sceneBox(cells.get(3));
        Rect lastBox = sceneBox(cells.get(5));
        Rect underBox = sceneBox(under);
        float height = form.height();

        gesture(() -> cells.get(2).setVisible(false));

        assertFalse(anyFullFrame, "hiding one caption repainted the whole window");
        assertEquals(height, form.height(), "the row keeps its field, so it keeps its height");
        assertTrue(repainted(captionBox), "the hidden caption's box was never repainted; passes "
                + passes);
        assertEquals(fieldBox, sceneBox(cells.get(3)), "the field beside it moved");
        assertEquals(lastBox, sceneBox(cells.get(5)), "a later cell moved into the empty one");
        assertFalse(touched(fieldBox), "the field beside it was repainted; passes " + passes);
        assertFalse(touched(underBox), "the label under the form was repainted; passes " + passes);
    }

    /**
     * A whole row hidden takes its height and its gap with it: the rows below it move up and are
     * repainted where they were and where they went, the grid gets shorter, and the change climbs
     * to the column, which moves the label under the form and leaves the one above it alone.
     */
    @Test
    void aRowHiddenInAGridMovesTheRowsBelowItAndHandsTheChangeUp() {
        Label top = new Label("top");
        Label under = new Label("under the form");
        List<Widget<?>> cells = new ArrayList<>();
        Grid form = formPage(top, under, cells);

        Rect topBox = sceneBox(top);
        Rect firstField = sceneBox(cells.get(1));
        Rect captionBox = sceneBox(cells.get(2));
        Rect fieldBox = sceneBox(cells.get(3));
        Rect lastBefore = sceneBox(cells.get(5));
        Rect underBefore = sceneBox(under);
        float height = form.height();

        gesture(() -> {
            cells.get(2).setVisible(false);
            cells.get(3).setVisible(false);
        });

        assertFalse(anyFullFrame, "hiding a row of a form escalated to the whole window");
        assertTrue(form.height() < height, "two rows are shorter than three");
        assertEquals(fieldBox.y(), sceneBox(cells.get(5)).y(), 0.5f,
                "the last row moved up into the hidden row's place");
        assertTrue(repainted(captionBox) && repainted(fieldBox),
                "the hidden row's boxes were never repainted; passes " + passes);
        assertTrue(repainted(lastBefore) && repainted(sceneBox(cells.get(5))),
                "the row that moved was not repainted where it was and where it went; passes "
                        + passes);
        assertEquals(firstField, sceneBox(cells.get(1)), "the row above the hidden one moved");
        assertTrue(sceneBox(under).y() < underBefore.y(), "what is under the form did not move up");
        assertTrue(repainted(underBefore) && repainted(sceneBox(under)),
                "the label under the form was not repainted where it was and where it went");
        assertFalse(touched(topBox), "the label above the form was repainted; passes " + passes);
    }

    /**
     * When hiding a widget changes its parent's size, the change climbs to the ancestor that
     * absorbs it: here a row loses its tallest child, gets shorter, and what is under the row moves.
     */
    @Test
    void aParentThatShrinksHandsTheChangeUpToWhatAbsorbsIt() {
        Button tall = new Button("tall");
        Row row = new Row();
        row.gap(8);
        row.add(new Button("short"));
        row.add(new SizedBox(80, 90, tall));
        Label under = new Label("under the row");
        Label top = new Label("top");
        Column page = new Column();
        page.gap(8).crossAlignment(Flex.CrossAlignment.START);
        page.add(top);
        page.add(row);
        page.add(under);
        mount(page);

        Rect topBox = sceneBox(top);
        Rect underBefore = sceneBox(under);
        float rowHeightBefore = row.height();
        gesture(() -> tall.parent().setVisible(false));
        assertNotEquals(rowHeightBefore, row.height(), "the row should have lost height");
        assertFalse(anyFullFrame, "a parent resize escalated to the whole window");
        assertTrue(sceneBox(under).y() < underBefore.y(), "what is under the row did not move up");
        assertTrue(repainted(underBefore) && repainted(sceneBox(under)),
                "the label under the row was not repainted where it was and where it went");
        assertFalse(touched(topBox), "the label above the row was repainted");
    }
}
