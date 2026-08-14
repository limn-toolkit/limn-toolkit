package limn.scene;

import limn.backend.Cursor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The scene resolves the hovered widget's cursor (walking up ancestors to
 * inherit, defaulting to {@link Cursor#DEFAULT}) and pushes it to the window.
 * Driven headlessly through {@link RecordingWindow}.
 */
class CursorResolutionTest extends SceneTestBase {

    /** Fixed-size leaf that requests a cursor (null = inherit). */
    static final class CursorBox extends Widget {
        CursorBox(Cursor cursor) {
            setCursor(cursor);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(100, 40);
        }
    }

    /** Container that stacks its children vertically at their measured sizes. */
    static final class VStack extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            float h = 0;
            float w = 0;
            for (Widget child : children()) {
                Size s = child.measure(Constraints.loose(c.maxWidth(), c.maxHeight()));
                h += s.height();
                w = Math.max(w, s.width());
            }
            return c.constrain(w, h);
        }

        @Override
        protected void onLayout() {
            float y = 0;
            for (Widget child : children()) {
                Size s = child.measure(Constraints.loose(width(), height()));
                child.layoutBox(0, y, s.width(), s.height());
                y += s.height();
            }
        }
    }

    private RecordingWindow win;
    private Scene scene;
    private CursorBox pointer;   // y 0..40, POINTER
    private CursorBox plain;     // y 40..80, no cursor
    private CursorBox inherit;   // y 80..120, no cursor, inside a POINTER group

    private void build() {
        VStack root = new VStack();
        pointer = new CursorBox(Cursor.POINTER);
        plain = new CursorBox(null);
        VStack group = new VStack();
        group.setCursor(Cursor.POINTER);
        inherit = new CursorBox(null);
        group.add(inherit);
        root.add(pointer);
        root.add(plain);
        root.add(group);

        win = new RecordingWindow();
        scene = new Scene(root);
        scene.bind(win);
        scene.layoutPass(200, 400);
    }

    private void moveTo(Widget target) {
        float x = 0;
        float y = 0;
        for (Widget w = target; w != null; w = w.parent()) {
            x += w.x();
            y += w.y();
        }
        scene.mouseMoved(x + target.width() / 2, y + target.height() / 2);
        scene.inputBatchEnded();
    }

    @Test
    void hoveringAWidgetAppliesItsCursor() {
        build();
        moveTo(pointer);
        assertEquals(Cursor.POINTER, win.cursor);
    }

    @Test
    void aWidgetWithNoCursorFallsBackToDefault() {
        build();
        moveTo(pointer);
        moveTo(plain);
        assertEquals(Cursor.DEFAULT, win.cursor, "no cursor anywhere up the chain → arrow");
    }

    @Test
    void cursorInheritsFromAnAncestor() {
        build();
        moveTo(inherit);
        assertEquals(Cursor.POINTER, win.cursor, "the leaf has no cursor; the POINTER group provides it");
    }

    @Test
    void leavingTheWindowResetsToDefault() {
        build();
        moveTo(pointer);
        assertEquals(Cursor.POINTER, win.cursor);
        scene.pointerEntered(false);
        scene.inputBatchEnded();
        assertEquals(Cursor.DEFAULT, win.cursor);
    }

    @Test
    void changingCursorWhileHoveredTakesEffectImmediately() {
        build();
        moveTo(pointer);
        assertEquals(Cursor.POINTER, win.cursor);
        pointer.setCursor(Cursor.TEXT);
        assertEquals(Cursor.TEXT, win.cursor, "setCursor on the hovered widget re-resolves at once");
    }

    @Test
    void disablingTheHoveredWidgetFallsBackToDefault() {
        build();
        moveTo(pointer);
        assertEquals(Cursor.POINTER, win.cursor);
        // Disabling revokes hover (the disabled widget is no longer hit-testable).
        pointer.setEnabled(false);
        assertEquals(Cursor.DEFAULT, win.cursor);
    }
}
