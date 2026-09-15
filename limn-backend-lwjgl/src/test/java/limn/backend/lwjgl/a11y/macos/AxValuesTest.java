package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.lwjgl.a11y.ProbeWindow;
import limn.components.date.DateField;
import limn.i18n.I18nString;
import limn.scene.Scene;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code accessibilityValue} answers, and a value's text changing alone (decision 16; CRIT-4's
 * macOS half).
 */
@ExtendWith(PlatformFreeBridges.class)
class AxValuesTest {

    /** WINDOW > [SLIDER 1001 (40, no text); SLIDER 1002 (40, "40%"); SPIN_BUTTON 1003 (empty, "empty");
     *  SPIN_BUTTON 1004 (empty, no text); TEXT_FIELD 1005 ("abc"); CHECK_BOX 1006 (mixed)]. */
    private static AccessibleTree values() {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        open(a, 1001, Accessible.Role.SLIDER);
        a.value(40, 0, 100, 1);
        a.end();
        open(a, 1002, Accessible.Role.SLIDER);
        a.value(40, 0, 100, 1);
        a.valueText("40%", 1);
        a.end();
        open(a, 1003, Accessible.Role.SPIN_BUTTON);
        a.emptyValue(1, 31, 1, false);
        a.valueText("empty", 1);
        a.end();
        open(a, 1004, Accessible.Role.SPIN_BUTTON);
        a.emptyValue(1, 12, 1, false);
        a.end();
        open(a, 1005, Accessible.Role.TEXT_FIELD);
        a.text("abc", 1, 3, limn.graphics.ShapedText.Affinity.DOWNSTREAM, 3, 3, 1, null, false);
        a.end();
        open(a, 1006, Accessible.Role.CHECK_BOX);
        a.toggle(limn.accessibility.ToggleFacet.State.MIXED);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    private static void open(Accessibility a, long id, Accessible.Role role) {
        a.begin(id, 0, Locale.ENGLISH, 0, 0, 40, 20);
        a.role(role);
        a.name(I18nString.literal(role + " " + id), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, true, false);
    }

    @Test
    void anEmptyValueAnswersItsWordAndNeverItsMinimum() {
        AccessibleTree tree = values();
        AccessibleNode plain = tree.find(1001);
        assertNull(AxValues.textOf(plain));
        assertTrue(AxValues.hasNumber(plain));
        assertEquals(40, AxValues.numberOf(plain), "a value with no text is its number");
        assertEquals("40%", AxValues.textOf(tree.find(1002)), "a value's displayed text wins over its number");
        assertFalse(AxValues.hasNumber(tree.find(1002)));

        AccessibleNode word = tree.find(1003);
        assertEquals("empty", AxValues.textOf(word), "decision 16: the text says empty");
        assertFalse(AxValues.hasNumber(word), "and the minimum published for other platforms is not answered");
        AccessibleNode bare = tree.find(1004);
        assertNull(AxValues.textOf(bare));
        assertFalse(AxValues.hasNumber(bare),
                "an empty value with no text answers nothing: AXValue demands no number, so none is invented");

        assertEquals("abc", AxValues.textOf(tree.find(1005)));
        assertTrue(AxValues.hasNumber(tree.find(1006)));
        assertEquals(2, AxValues.numberOf(tree.find(1006)), "a mixed toggle is 2, as AppKit's own check boxes");
    }

    @Test
    void aDateSegmentFilledWithItsMinimumIsAValueChangeAndItsWordGivesWayToTheDigits() {
        AtomicLong nanos = new AtomicLong();
        HeadlessUi ui = new HeadlessUi(nanos::get);
        try {
            DateField field = new DateField();
            field.setDate(null);
            Scene scene = new Scene(new SizedBox(320, 60, field), nanos::get);
            ProbeWindow window = new ProbeWindow();
            AxBridge bridge = PlatformFreeBridges.make();
            List<String> trace = new ArrayList<>();
            bridge.trace(trace::add);
            window.accessibility = bridge;
            scene.bind(window);
            scene.renderFrame(new NoopCanvas(400, 100));
            bridge.entered();

            List<Long> segments = new ArrayList<>();
            AccessibleTree tree = bridge.tree();
            for (int i = 0; i < tree.nodeCount(); i++) {
                AccessibleNode node = tree.node(i);
                if (node.role() != Accessible.Role.SPIN_BUTTON) continue;
                segments.add(node.id());
                bridge.elementFor(node.id());   // a client has read it
                assertTrue(node.value().empty(), "an empty field's segments are empty");
                assertNotNull(AxValues.textOf(node), "and answer their word");
                assertFalse(AxValues.hasNumber(node), "never the minimum they publish for the other platforms");
            }
            assertEquals(3, segments.size(), "day, month and year");

            trace.clear();
            // The first of January: the day and the month are filled with their minimum, so only their
            // text and their emptiness move (CRIT-4); the year's number moves too.
            field.setDate(LocalDate.of(2026, 1, 1));
            scene.renderFrame(new NoopCanvas(400, 100));
            List<String> posted = trace.stream().filter(line -> line.startsWith("posted "))
                    .map(line -> line.substring("posted ".length())).toList();
            assertEquals(3, posted.stream().filter("NSAccessibilityValueChangedNotification"::equals).count(),
                    "each held segment is told its value changed, the two filled with their minimum included: " + trace);
            for (long id : segments) {
                AccessibleNode node = bridge.tree().find(id);
                assertFalse(node.value().empty());
                assertEquals(node.value().text(), AxValues.textOf(node), "the digits now");
            }
        } finally {
            ui.close();
        }
    }
}
