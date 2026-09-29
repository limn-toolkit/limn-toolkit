package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.graphics.Rect;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import limn.testing.StubWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * Where a {@link DateField} tells the platform to put an input method's candidate window: under
 * the segment the caret is in, as a {@link TextField} puts it under its caret.
 *
 * <p>A date field takes text input, so the scene turns the input method on while it has the
 * focus, and a digit typed through a Japanese or Chinese one is a composition with a candidate
 * list before it is a digit. The scene places that list under the rectangle the focused widget
 * answers, and a field that answered none left it wherever the platform last had it: at the
 * window's corner, or under the caret of the last text field that had the focus.
 *
 * <p>What is asserted is the rectangle the scene handed the window, read off a window that keeps
 * it, against the segment boxes the accessible tree publishes, which come from the same walk
 * over the run the paint and a click use. It must cover the caret's segment and no other, in
 * scene coordinates: the field sits inside padding, so a box left in the field's own coordinates
 * lands short of every segment.
 */
class DateFieldImeAnchorTest extends AccessibleComponentTestBase {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    /** The padding around the field, so the scene's coordinates and the field's differ. */
    private static final float INSET = 20;

    /**
     * How wide the field is laid out: wider than the run of 09/09/2026, so there is room for the
     * run to sit against either edge and reading right to left moves it.
     */
    private static final float WIDTH = 300;

    private static final Clock SEPTEMBER_9 =
            Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);

    /** A window that keeps what the scene told it about the input method. */
    private static final class ImeWindow extends StubWindow {
        boolean imeEnabled;
        Rect anchor;

        @Override
        public void setTextInput(limn.backend.spi.TextInput input) {
            imeEnabled = input == limn.backend.spi.TextInput.COMPOSITION;
        }

        @Override
        public void setPreeditCaretRect(float x, float y, float width, float height) {
            anchor = new Rect(x, y, width, height);
        }
    }

    private ImeWindow ime;

    @AfterEach
    void resetLocale() {
        I18n.setLocale(Locale.US);
    }

    /**
     * Binds {@code content}, {@link #WIDTH} wide inside padding, over a window that keeps the
     * input method's rectangle, with the deterministic ruler, so each segment has a real width and
     * 09/09/2026 is laid out as three boxes apart from each other.
     */
    private void bindPadded(Widget<?> content) {
        I18n.setLocale(PT_BR);
        Column root = new Column();
        root.add(Padding.all(INSET, new SizedBox(WIDTH, SizedBox.UNSET, content)));
        ime = new ImeWindow();
        bind(root, ime);
        scene.setTextRuler(RULER);
        content.markNeedsLayout();
        frame();
    }

    private DateField bindField(LayoutDirection direction) {
        DateField field = new DateField().setClock(SEPTEMBER_9);
        field.setDate(LocalDate.of(2026, 9, 9));
        field.setLayoutDirection(direction);
        bindPadded(field);
        return field;
    }

    private void focus(DateField field) {
        scene.requestFocus(field);
        frame();
        assertTrue(ime.imeEnabled, "a date field takes text input, so the input method is on");
    }

    private void press(int key) {
        drive(scene).press(key);
        frame();
    }

    private List<AccessibleNode> segmentNodes() {
        return childrenOf(node(Accessible.Role.GROUP));
    }

    /**
     * The rectangle last handed to the window covers segment {@code at} across and lies inside the
     * field's height, and touches no other segment: the candidate window goes under the digits
     * being typed and not under their neighbours.
     */
    private Rect assertAnchoredOver(int at) {
        Rect anchor = ime.anchor;
        assertNotNull(anchor, "the scene handed the window no rectangle to put the candidate "
                + "window under" + describe(tree()));
        List<AccessibleNode> segments = segmentNodes();
        AccessibleNode over = segments.get(at);
        assertTrue(anchor.x() <= over.x() && anchor.x() + anchor.width() >= over.x() + over.width(),
                "the rectangle " + anchor + " covers segment " + at + " at " + over.x() + " wide "
                        + over.width() + describe(tree()));
        assertTrue(anchor.y() >= over.y() && anchor.y() + anchor.height() <= over.y() + over.height(),
                "and stays inside the field's height, " + over.y() + " to "
                        + (over.y() + over.height()) + ": " + anchor);
        for (int i = 0; i < segments.size(); i++) {
            AccessibleNode other = segments.get(i);
            if (i != at) {
                assertTrue(anchor.x() + anchor.width() <= other.x()
                                || anchor.x() >= other.x() + other.width(),
                        "the rectangle " + anchor + " reaches into segment " + i + " at " + other.x()
                                + " wide " + other.width());
            }
        }
        return anchor;
    }

    @Test
    void theCandidateWindowGoesUnderTheSegmentTheCaretIsIn() {
        DateField field = bindField(LayoutDirection.LTR);
        focus(field);
        assertEquals(0, field.focusedSegment());
        assertAnchoredOver(0);

        press(Keys.RIGHT);
        assertAnchoredOver(1);
        press(Keys.END);
        assertAnchoredOver(2);
        press(Keys.HOME);
        assertAnchoredOver(0);
    }

    /**
     * What an input method commits arrives as characters, full-width digits included, and two of
     * them fill the day and move the caret on to the month: the next composition is the month's,
     * and its candidate window goes there.
     */
    @Test
    void aCommittedDayMovesTheAnchorOnWithTheCaret() {
        DateField field = bindField(LayoutDirection.LTR);
        focus(field);
        drive(scene).preeditChanged("０３", 2, 0, 2);
        drive(scene).inputBatchEnded();
        frame();
        assertAnchoredOver(0);

        drive(scene).type("０３");
        drive(scene).preeditChanged("", 0, 0, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertEquals("03/09/2026", field.text());
        assertEquals(1, field.focusedSegment(), "a full day moves the caret on");
        assertAnchoredOver(1);
    }

    /**
     * Reading right to left the run of segments keeps its order and sits against the other edge,
     * so the rectangle moves with the run: the day is still the leftmost segment, and it is now
     * far to the right of where it is in a left-to-right field.
     */
    @Test
    void readingRightToLeftTheAnchorFollowsTheRunToTheOtherEdge() {
        DateField ltr = bindField(LayoutDirection.LTR);
        focus(ltr);
        Rect leftToRight = assertAnchoredOver(0);

        DateField rtl = bindField(LayoutDirection.RTL);
        focus(rtl);
        Rect rightToLeft = assertAnchoredOver(0);
        assertTrue(rightToLeft.x() > leftToRight.x() + 100,
                "the run sits against the trailing edge: " + rightToLeft + " against "
                        + leftToRight);

        press(Keys.RIGHT);
        assertAnchoredOver(1);
        assertTrue(ime.anchor.x() > rightToLeft.x(),
                "Right moves to the segment drawn on the right, the month, in both directions");
    }

    /** A picker's field is the widget with the focus, and it anchors the list the same way. */
    @Test
    void aPickersFieldAnchorsTheCandidateWindowToo() {
        DatePicker picker = new DatePicker().setClock(SEPTEMBER_9);
        picker.field().setDate(LocalDate.of(2026, 9, 9));
        bindPadded(picker);
        focus(picker.field());
        assertAnchoredOver(0);

        press(Keys.RIGHT);
        assertAnchoredOver(1);
    }
}
