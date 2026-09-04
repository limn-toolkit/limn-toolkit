package limn.scene;

import limn.accessibility.Accessible;
import limn.graphics.Canvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one question reflection cannot answer about a widget that paints: whether what it drew meant
 * anything.
 *
 * <p>ADR 039 §1.6 deletes a widget that declares no role, no name, no action and no state, and
 * warns once per class when the class it deleted draws its own content — because the usual cause is
 * a gauge or a sparkline nobody named, and the interface it drew is then simply absent from the tree
 * with nothing said. The guard behind that warning asks which method the class overrode, which is
 * all a {@code ClassValue} can see. A wash painted behind somebody else's controls overrides exactly
 * the same one, and for it the warning is false three times over: nothing informative is absent, the
 * class it names is the toolkit's rather than the application's, and the fix it recommends —
 * {@code setAccessibleIgnored(true)} — takes the child subtree with it and deletes the controls the
 * wash sits behind.
 *
 * <p>So {@link Widget#paintsDecoration()} lets the widget answer, and this is where the seam is
 * pinned in both directions: a class that says nothing is still named, and a class that says its
 * drawing is decoration is deleted in silence <em>with its children hoisted exactly as before</em>.
 * The second half is the one that matters. A seam that silenced the warning by hiding the subtree
 * would be the ignore flag under a gentler name.
 *
 * <p>Both widgets below are declared here and nowhere else, which is what makes the assertions
 * order-independent: the walk remembers the classes it has named in a set that lives as long as the
 * virtual machine, so a class shared with another test would be warned about at most once across the
 * whole run and whichever test ran second would be asserting nothing.
 *
 * <p>The warning is read where it is actually emitted. {@code System.Logger} is backed by
 * {@code java.util.logging} on every virtual machine this toolkit builds against, so a handler on
 * the walk's own logger sees the record, its level and the class name it carries as a parameter.
 */
class AccessiblePaintWarningTest extends AccessibleTestBase {

    /** A widget that draws its own content and says nothing about itself: the warning's own case. */
    private static final class Gauge extends Widget {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(40, 20);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            // Draws nothing here; what matters to the guard is that the class declared the method.
        }
    }

    /** The other case: a panel whose drawing is background over content it does not own. */
    private static final class Glass extends Widget {
        Glass(Widget child) {
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            children().get(0).measure(constraints);
            return constraints.constrain(60, 40);
        }

        @Override
        protected void onLayout() {
            children().get(0).layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            // As above: the override is the whole of what the guard can see.
        }

        @Override
        protected boolean paintsDecoration() {
            return true;
        }
    }

    /** Every record the walk logged while a test was running. */
    private final List<LogRecord> logged = new ArrayList<>();

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Logger walkLogger;

    @BeforeEach
    void captureTheWalksLog() {
        walkLogger = Logger.getLogger(AccessibleWalk.class.getName());
        walkLogger.addHandler(capture);
    }

    @AfterEach
    void stopCapturing() {
        walkLogger.removeHandler(capture);
    }

    @Test
    void aWidgetThatPaintsAndSaysNothingIsNamedOnce() {
        Gauge gauge = new Gauge();
        bind(gauge);

        assertEquals(1, logged.size(), "the deletion of a drawn interface is worth saying once: "
                + logged);
        assertEquals(java.util.logging.Level.WARNING, logged.get(0).getLevel());
        assertEquals(Gauge.class.getName(), logged.get(0).getParameters()[0],
                "the message names the class an application would have to fix");
        assertEquals(1, tree().nodeCount(),
                "and the node really is gone, which is what the warning is about" + describe(tree()));

        gauge.invalidate();
        frame();
        gauge.invalidate();
        frame();

        assertEquals(1, logged.size(),
                "a window painted every frame is named once and not sixty times: " + logged);
    }

    @Test
    void aWidgetWhosePaintingIsDecorationIsDeletedInSilenceAndKeepsItsChildren() {
        Probe inside = new Probe(Accessible.Role.BUTTON, "Play");
        bind(new Glass(inside));

        assertTrue(logged.isEmpty(),
                "the glass is material, not meaning, and there is nothing to tell anyone: "
                        + logged);
        assertEquals(2, tree().nodeCount(),
                "the window and the control, with no panel in between" + describe(tree()));
        assertEquals(0, node("Play").parent(),
                "silencing the warning must not change the tree by so much as a hoist: the child "
                        + "sits where it sat, which is what tells this seam apart from the ignore "
                        + "flag" + describe(tree()));
    }
}
