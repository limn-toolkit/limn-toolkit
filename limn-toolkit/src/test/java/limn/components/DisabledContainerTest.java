package limn.components;

import limn.accessibility.Accessible;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.input.Keys;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * A control inside a disabled or hidden container is out of reach on every path, and not only on
 * the pointer's and Tab's: {@code isEnabled()} answers for the whole ancestry, so code moving the
 * focus, a dialog giving the focus back as it closes, the control's own paint and what assistive
 * technologies are told all read the same fact. Each case below was a workaround hunt's probe
 * before it was a test: the button took the focus and ran its action on Enter.
 */
class DisabledContainerTest extends AccessibleComponentTestBase {

    private final int[] fired = {0};
    private Button save;
    private TextField field;
    private Column section;

    /** A section holding a button and a field, bound to a scene, with the button focusable. */
    private void bindForm() {
        save = new Button("Save").onAction(() -> fired[0]++);
        field = new TextField();
        section = new Column();
        section.add(save);
        section.add(field);
        Column root = new Column();
        root.add(section);
        bind(root);
    }

    @Test
    void theEffectiveStateFollowsTheContainerAndTheOwnFlagComesBack() {
        bindForm();
        save.setEnabled(false);
        section.setEnabled(false);
        assertFalse(field.isEnabled(), "a field inside a disabled section is disabled");
        section.setEnabled(true);
        assertTrue(field.isEnabled(), "and enabled again with it");
        assertFalse(save.isEnabled(), "while a button disabled by its own flag stays disabled");
    }

    @Test
    void codeCannotFocusAControlInsideADisabledSection() {
        bindForm();
        section.setEnabled(false);

        save.requestFocus();
        assertFalse(save.isFocused(), "requestFocus refuses a button whose section is disabled");
        drive(scene).press(Keys.SPACE);
        drive(scene).press(Keys.ENTER);
        assertEquals(0, fired[0], "and the keyboard cannot run its action");

        field.requestFocus();
        assertFalse(field.isFocused(), "nor a field there");
        drive(scene).type("abc");
        assertEquals("", field.text(), "so typing changes nothing in it");
    }

    @Test
    void codeCannotFocusAControlInsideAHiddenSection() {
        bindForm();
        section.setVisible(false);
        frame();

        field.requestFocus();
        assertFalse(field.isFocused(), "requestFocus refuses a field whose section is hidden");
        drive(scene).type("xyz");
        assertEquals("", field.text(), "so typing changes nothing in it");
    }

    @Test
    void aClosingDialogDoesNotGiveTheFocusBackIntoASectionItsAnswerDisabled() {
        bindForm();
        save.requestFocus();
        assertTrue(save.isFocused());
        Button ok = new Button("OK");
        Column dialog = new Column();
        dialog.add(ok);
        scene.pushOverlay(dialog);
        frame();

        section.setEnabled(false); // the dialog's answer locks the form
        scene.removeOverlay(dialog);
        assertNotSame(save, scene.focusedWidget(),
                "the focus does not go back to a button whose section the answer disabled");
        drive(scene).press(Keys.ENTER);
        assertEquals(0, fired[0], "and Enter does not run it a second time");
    }

    @Test
    void aControlInsideADisabledSectionPaintsDisabledAndIsPublishedNotEnabled() {
        bindForm();
        section.setEnabled(false);
        frame();

        InkCanvas ink = new InkCanvas();
        save.invalidate();
        scene.renderFrame(ink);
        int at = ink.texts.indexOf("Save");
        assertTrue(at >= 0, "the caption was drawn: " + ink.texts);
        assertEquals(Theme.current().disabledText(), ink.paints.get(at),
                "a button inside a disabled section draws its caption in the disabled ink");
        assertFalse(node("Save").has(Accessible.State.ENABLED),
                "and is published as not enabled" + describe(tree()));

        section.setEnabled(true);
        frame();
        ink = new InkCanvas();
        save.invalidate();
        scene.renderFrame(ink);
        at = ink.texts.indexOf("Save");
        assertTrue(at >= 0, "the caption was drawn: " + ink.texts);
        assertNotEquals(Theme.current().disabledText(), ink.paints.get(at),
                "the section enabled again, the caption takes its ink back");
        assertTrue(node("Save").has(Accessible.State.ENABLED), describe(tree()));
    }

    /**
     * A radio group chooses its tab stop by each member's own flag: chosen by the effective state
     * while its form was disabled, the stop went to nobody, and nothing tells the group when the
     * form comes back.
     */
    @Test
    void aRadioGroupSelectedWhileItsFormIsDisabledKeepsATabStopForWhenItComesBack() {
        RadioButton small = new RadioButton("Small");
        RadioButton large = new RadioButton("Large");
        ButtonGroup group = new ButtonGroup().add(small).add(large);
        section = new Column();
        section.add(small);
        section.add(large);
        Column root = new Column();
        root.add(section);
        bind(root);

        section.setEnabled(false);
        group.setSelectedIndex(1); // the stop is chosen again while the form is disabled
        section.setEnabled(true);
        frame();

        scene.focusTraverse(false);
        assertSame(large, scene.focusedWidget(), "Tab reaches the selected member again");
    }

    /**
     * A combo made editable inside a disabled form: its field follows the combo by the effective
     * state, and has no flag of its own copied over that would outlast the form's.
     */
    @Test
    void aComboMadeEditableInsideADisabledFormTakesTheKeyboardWhenTheFormComesBack() {
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two"));
        section = new Column();
        section.add(combo);
        Column root = new Column();
        root.add(section);
        bind(root);

        section.setEnabled(false);
        combo.setEditable(true);
        section.setEnabled(true);
        frame();

        scene.focusTraverse(false);
        assertTrue(scene.focusedWidget() != null && scene.focusedWidget().parent() == combo,
                "Tab lands on the combo's field: " + scene.focusedWidget());
    }

    // ------------------------------------------------------------ the own flag and ENABLED

    /**
     * The toggle idiom reads the flag it writes: {@code isEnabled()} answers for the form, so
     * {@code setEnabled(!isEnabled())} inside a disabled form wrote {@code true} over a control
     * the application had meant to disable, and it came back enabled with the form.
     */
    @Test
    void theToggleIdiomInsideADisabledFormFlipsTheControlsOwnFlag() {
        bindForm();
        section.setEnabled(false);
        assertTrue(field.isSelfEnabled(), "the field's own flag is still on");
        assertFalse(field.isEnabled(), "while the form disables it");

        field.setEnabled(!field.isSelfEnabled());
        assertFalse(field.isSelfEnabled());
        section.setEnabled(true);
        assertFalse(field.isEnabled(), "the toggle disabled the field, and the form coming back does not undo it");

        field.setEnabled(!field.isSelfEnabled());
        assertTrue(field.isSelfEnabled() && field.isEnabled(), "toggled back, with the form enabled");
    }

    /** Who heard ENABLED, by name, in the order the scene announced it. */
    private List<String> enabledHeard(Runnable action) {
        List<String> heard = new ArrayList<>();
        limn.concurrent.Subscription watch = scene.observeChanges((source, change) -> {
            if (change.aspect() == limn.scene.Change.Aspect.ENABLED) {
                assertEquals(limn.scene.Change.Origin.CODE, change.origin());
                heard.add(source.id());
            }
        });
        try {
            action.run();
        } finally {
            watch.cancel();
        }
        return heard;
    }

    /** A column named for the test's lists, with the children given. */
    private static Column named(String name, limn.scene.Widget<?>... children) {
        Column column = new Column();
        column.setId(name);
        for (limn.scene.Widget<?> child : children) {
            column.add(child);
        }
        return column;
    }

    @Test
    void enabledIsAnnouncedOnEveryDescendantWhoseStateMovedWithTheForm() {
        Button a = new Button("a").setId("a");
        Button b = new Button("b").setId("b").setEnabled(false); // disabled by its own flag: does not move
        Button d = new Button("d").setId("d");
        Button f = new Button("f").setId("f"); // under a disabled container: does not move
        Column e = named("e", f).setEnabled(false);
        Column c = named("c", d, e);
        Column form = named("form", a, b, c);
        bind(named("root", form));

        assertEquals(List.of("form", "a", "c", "d"), enabledHeard(() -> form.setEnabled(false)),
                "the form first, then each descendant whose isEnabled() changed, in tree order");
        assertFalse(a.isEnabled() || d.isEnabled());
        assertEquals(List.of("form", "a", "c", "d"), enabledHeard(() -> form.setEnabled(true)));
        assertTrue(a.isEnabled() && d.isEnabled() && !b.isEnabled() && !f.isEnabled());
    }

    @Test
    void underNestedDisabledAncestorsOnlyTheOwnFlagIsAnnounced() {
        Button x = new Button("x").setId("x");
        Column inner = named("inner", x);
        Column outer = named("outer", inner);
        bind(named("root", outer));
        inner.setEnabled(false);

        assertEquals(List.of("outer"), enabledHeard(() -> outer.setEnabled(false)),
                "inner and x were disabled already, by inner's flag");
        assertEquals(List.of("inner"), enabledHeard(() -> inner.setEnabled(true)),
                "inner's own flag moved; x stays disabled, by outer");
        assertFalse(x.isEnabled());
        assertEquals(List.of("outer", "inner", "x"), enabledHeard(() -> outer.setEnabled(true)));
        assertTrue(x.isEnabled());
    }

    @Test
    void aDisabledWidgetMovedUnderAnEnabledContainerStaysDisabledAndHearsNothingOfIt() {
        Button moved = new Button("moved").setId("moved").setEnabled(false);
        Column from = named("from", moved);
        Column to = named("to");
        bind(named("root", from, to));

        from.remove(moved);
        to.add(moved);
        assertFalse(moved.isEnabled(), "its own flag goes with it");
        assertFalse(moved.isSelfEnabled());
        assertEquals(List.of("to"), enabledHeard(() -> to.setEnabled(false)));
        assertEquals(List.of("to"), enabledHeard(() -> to.setEnabled(true)));
        assertFalse(moved.isEnabled());
    }

    @Test
    void disablingAFormAnnouncesTheLostFocusFirstThenEnabledInTreeOrder() {
        bindForm();
        save.setId("save");
        field.setId("field");
        section.setId("section");
        field.requestFocus();
        assertTrue(field.isFocused());
        List<String> heard = new ArrayList<>();
        scene.observeChanges((source, change) -> {
            if (change.aspect() == limn.scene.Change.Aspect.ENABLED
                    || change.aspect() == limn.scene.Change.Aspect.FOCUS) {
                heard.add(change.aspect() + " " + source.id());
            }
        });

        section.setEnabled(false);
        assertEquals(List.of("FOCUS field", "ENABLED section", "ENABLED save", "ENABLED field"), heard);
    }

    /**
     * The published tree has its own path to a node's ENABLED, the walk's diff, which hears no
     * change announcement: a disabled form still raises one state event per node that moved.
     */
    @Test
    void theBridgeHearsOneEnabledStateEventPerNodeThatMoved() {
        bindForm();
        frame();
        bridge.events.clear();
        section.setEnabled(false);
        frame();

        List<Long> moved = new ArrayList<>();
        for (limn.accessibility.AccessibleEvent event : bridge.eventsOf(limn.accessibility.AccessibleEvent.Type.STATE_CHANGED)) {
            if (event.state() == Accessible.State.ENABLED) {
                moved.add(event.nodeId());
            }
        }
        assertEquals(new java.util.HashSet<>(moved).size(), moved.size(), "no node is told twice: " + moved);
        assertTrue(moved.contains(node("Save").id()), "the button is told: " + moved);
    }

    /** Records every caption drawn and the paint it was drawn with. */
    private static final class InkCanvas extends FakeCanvas {
        final List<String> texts = new ArrayList<>();
        final List<Paint> paints = new ArrayList<>();

        InkCanvas() {
            super(400, 300);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(text);
            paints.add(paint);
        }

        @Override
        public void drawText(ShapedText text, float x, float y, Paint paint) {
            texts.add(text.text());
            paints.add(paint);
        }
    }
}
