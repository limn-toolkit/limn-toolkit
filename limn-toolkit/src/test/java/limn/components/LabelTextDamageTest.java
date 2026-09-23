package limn.components;

import limn.graphics.Rect;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A label whose text changes width repaints itself and what the new width moved, not the window
 * (PF-5): it used to ask for a full layout, and a full layout is a full frame, so a status line
 * that ticked repainted every widget of the window each time. It now takes the in-place pass a
 * visibility change takes, which runs each ancestor's own layout, so it must also land every box
 * exactly where a full layout would.
 */
class LabelTextDamageTest extends ComponentTestBase {

    private static final int W = 640;
    private static final int H = 480;

    private Scene scene;
    private AtomicLong nanos;
    private RecordingTestCanvas canvas;
    private final List<Rect> passes = new ArrayList<>();
    private boolean anyFullFrame;

    private Scene mount(Widget root, RecordingTestCanvas into) {
        nanos = new AtomicLong();
        Scene mounted = new Scene(new Padding(Insets.all(40), root), nanos::get);
        mounted.setTextRuler(RULER);
        mounted.setPartialRendering(true);
        settle(mounted, into);
        return mounted;
    }

    private void settle(Scene target, RecordingTestCanvas into) {
        for (int i = 0; i < 300; i++) {
            nanos.addAndGet(200_000_000L);
            into.reset();
            target.renderFrame(into);
            if (into.nothingPainted()) {
                return;
            }
        }
        throw new AssertionError("never settled");
    }

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

    private static Rect sceneBox(Widget w) {
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

    /** The case in the report: a status line in a fixed-width slot, beside a form. */
    @Test
    void aStatusLineInAFixedSlotRepaintsItselfAndNothingElse() {
        TextField name = new TextField();
        Label status = new Label("Saved");
        Column form = new Column();
        form.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        form.add(name);
        form.add(new SizedBox(300, 20, status));
        canvas = new RecordingTestCanvas(W, H);
        scene = mount(form, canvas);

        Rect field = sceneBox(name);
        gesture(() -> status.setText("Saved 3 files to the archive"));

        assertFalse(anyFullFrame, "a label that changed width inside a fixed slot is not a full frame");
        assertTrue(repainted(sceneBox(status)), "the label itself repaints");
        assertFalse(touched(field), "the field above did not move and must not repaint");
    }

    /** A label beside a button in a row: the button moves, and repaints where it was and went. */
    @Test
    void aWiderLabelInARowRepaintsWhatItPushed() {
        Label caption = new Label("Name");
        Button go = new Button("Go");
        Row row = new Row();
        row.gap(8);
        row.add(caption);
        row.add(go);
        TextField far = new TextField();
        Column column = new Column();
        column.gap(40).crossAlignment(Flex.CrossAlignment.START);
        column.add(far);
        column.add(row);
        canvas = new RecordingTestCanvas(W, H);
        scene = mount(column, canvas);

        Rect before = sceneBox(go);
        Rect farBox = sceneBox(far);
        gesture(() -> caption.setText("Full legal name"));
        Rect after = sceneBox(go);

        assertFalse(anyFullFrame);
        assertTrue(after.x() > before.x(), "the button moved right");
        assertTrue(repainted(before), "where the button was");
        assertTrue(repainted(after), "where the button went");
        assertFalse(touched(farBox), "a widget in another row did not move");
    }

    /**
     * Every box lands where a full layout puts it, across the hosts a label sits in, including
     * the composites that measure a child more than once, which a narrower pass over the label
     * alone would leave stale.
     */
    @Test
    void everyBoxLandsWhereAFullLayoutPutsIt() {
        List<Function<Label, Widget>> hosts = List.of(
                label -> { Row r = new Row(); r.add(label); r.add(new Button("B")); return r; },
                label -> { Row r = new Row(); r.add(Expanded.of(label)); r.add(new Button("B")); return r; },
                label -> { Column c = new Column(); c.crossAlignment(Flex.CrossAlignment.STRETCH); c.add(label); c.add(new TextField()); return c; },
                label -> new Padding(Insets.all(6), label),
                label -> { Stack s = new Stack(); s.add(label); s.add(new Label("under")); return s; },
                label -> new ScrollView(label),
                label -> new TabbedPane().addTab("One", label).addTab("Two", new Label("other")),
                label -> { Row r = new Row(); Column c = new Column(); c.add(label); c.add(new Button("deep")); r.add(c); r.add(new Button("B")); return r; });
        for (int h = 0; h < hosts.size(); h++) {
            Label inPlace = new Label("short");
            RecordingTestCanvas a = new RecordingTestCanvas(W, H);
            Scene sceneA = mount(hosts.get(h).apply(inPlace), a);
            inPlace.setText("a considerably longer text than before");
            nanos.addAndGet(16_000_000L);
            a.reset();
            sceneA.renderFrame(a);
            assertFalse(a.cleared, "host " + h + " takes the in-place pass, which is what is compared");
            settle(sceneA, a);

            Label full = new Label("short");
            RecordingTestCanvas b = new RecordingTestCanvas(W, H);
            Scene sceneB = mount(hosts.get(h).apply(full), b);
            full.setText("a considerably longer text than before");
            sceneB.relayout();
            settle(sceneB, b);

            assertEquals(boxes(sceneB.root()), boxes(sceneA.root()), "host " + h);
        }
    }

    private static List<String> boxes(Widget root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Widget w, List<String> out) {
        out.add(w.getClass().getSimpleName() + "@" + w.localToSceneX() + "," + w.localToSceneY()
                + " " + w.width() + "x" + w.height());
        for (Widget child : w.children()) {
            collect(child, out);
        }
    }
}
