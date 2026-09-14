package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.backend.CrashHandler;
import limn.backend.Crashes;
import limn.i18n.I18nString;
import limn.scene.Widget;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A caption bound to the control it names.
 *
 * <p>The record's rule is that a {@code LABELLED_BY} relation is declared and never inferred from
 * where two widgets happen to sit, so what these pin is the declaration doing something and
 * proximity doing nothing. The combo is the control under test because it is the one that already
 * names itself from its own content, which makes it the case where the precedence has to be right:
 * a bound caption must beat what the widget derived, and an application's own name must beat both.
 *
 * <p>The caption is a node of its own since the label was described, so the relation resolves to
 * it and a client may walk there. Before that it was deleted by the transparency rule and the
 * relation was dropped, and an earlier version of this file pinned that as if it were the design;
 * it was the state before the label's own step, and the cases here say what the tree does now.
 */
class LabelForAccessibilityTest extends AccessibleComponentTestBase {

    private ComboBox combo;
    private Label caption;

    private void bindPair(boolean link) {
        Column root = new Column();
        caption = new Label("Colour theme");
        combo = new ComboBox(List.of("One", "Two", "Three"));
        root.add(caption);
        root.add(combo);
        if (link) {
            caption.setLabelFor(combo);
        }
        bind(root);
    }

    private AccessibleNode field() {
        return node(Accessible.Role.COMBO_BOX);
    }

    @Test
    void aCaptionThatMerelySitsBesideAControlDoesNotNameIt() {
        bindPair(false);

        assertEquals("One", field().name(),
                "proximity is a layout accident: the combo still names itself from its item"
                        + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, field().nameFrom(), describe(tree()));
    }

    @Test
    void aBoundCaptionNamesTheControlAndSaysWhereTheNameCameFrom() {
        bindPair(true);

        assertEquals("Colour theme", field().name(),
                "the declared link beats what the widget derived for itself" + describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, field().nameFrom(),
                "and the provenance says it came from another node rather than from content, "
                        + "which is what stops a bridge writing it into the wrong attribute"
                        + describe(tree()));
    }

    /** @return the id the field's one {@code LABELLED_BY} relation points at */
    private long labelledBy() {
        long target = AccessibleNode.NONE;
        int count = 0;
        for (var relation : field().relations()) {
            if (relation.kind() == Accessible.Relation.LABELLED_BY) {
                target = relation.target();
                count++;
            }
        }
        assertEquals(1, count, "one LABELLED_BY on the field: " + field().relations());
        return target;
    }

    @Test
    void theRelationResolvesToTheCaptionsOwnNodeWithNoExplicitNameNeeded() {
        bindPair(true);

        AccessibleNode label = node(Accessible.Role.LABEL);
        assertEquals("Colour theme", label.name(),
                "a plain caption is a node of its own now, named by its content" + describe(tree()));
        assertEquals(label.id(), labelledBy(),
                "so a client that would rather read the caption's own node can walk to it, and the "
                        + "relation is never a dangling one that every platform answers with an "
                        + "element that does not resolve" + describe(tree()));
    }

    @Test
    void anExplicitNameRenamesTheCaptionsOwnNodeAndNotTheField() {
        bindPair(true);
        // A different string from the caption's own text on purpose: the combo keeps the text,
        // the caption's node takes this, and the two being distinct is what proves which of the
        // two nodes an application's rename reached.
        caption.setAccessibleName("The caption itself");
        frame();

        AccessibleNode label = node("The caption itself");
        assertEquals(Accessible.Role.LABEL, label.role(), describe(tree()));
        assertEquals(label.id(), labelledBy(),
                "the relation still points at the renamed caption" + describe(tree()));
        assertEquals("Colour theme", field().name(),
                "the field is named from the caption's text, read at publish, and not from what "
                        + "the application called the caption's node" + describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, field().nameFrom(), describe(tree()));
    }

    @Test
    void changingTheCaptionsTextRenamesWhatItLabels() {
        bindPair(true);

        caption.setText("Theme");
        frame();

        assertEquals("Theme", field().name(),
                "the text is read at publish and not copied when the link was made, so there is "
                        + "nothing to keep in step" + describe(tree()));
    }

    @Test
    void anApplicationsOwnNameStillWinsOverABoundCaption() {
        bindPair(true);

        combo.setAccessibleName(I18nString.literal("Palette"));
        frame();

        assertEquals("Palette", field().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, field().nameFrom(),
                "the order is derived, then bound caption, then what the application wrote"
                        + describe(tree()));
    }

    @Test
    void aLabelNamesOneControlAtATime() {
        Column root = new Column();
        caption = new Label("Colour theme");
        combo = new ComboBox(List.of("One", "Two", "Three"));
        ComboBox other = new ComboBox(List.of("Alpha", "Beta"));
        root.add(caption);
        root.add(combo);
        root.add(other);
        caption.setLabelFor(combo);
        bind(root);

        // Selected by name and not by role: two combos are in this tree, which is the whole
        // point of the case, and the base's role lookup refuses an ambiguous one.
        assertNotNull(node("Colour theme"), "the caption names the first" + describe(tree()));

        caption.setLabelFor(other);
        frame();

        assertSame(other, caption.labelFor());
        assertNotNull(node("One"),
                "moving the link releases the widget it left, which goes back to naming itself"
                        + describe(tree()));
        assertNotNull(node("Colour theme"), "and names the one it moved to" + describe(tree()));

        caption.setLabelFor(null);
        frame();
        assertNull(caption.labelFor(), "and null removes it altogether");
        assertNotNull(node("Alpha"),
                "so the one it had moved to names itself again too" + describe(tree()));
    }

    // ------------------------------------------------------------- a composite redirects it

    /**
     * A composite the way a date picker is one: a group holding the field the keyboard lands on
     * and a button beside it, which says the field carries a label bound to the group
     * (decision 55; ADR 039 §1.5, amended 2026-09-14).
     */
    private static final class Composite extends Column {
        final TextField field = new TextField();
        final Button button = new Button("Open");
        Widget carrier = field;

        Composite() {
            add(field);
            add(button);
        }

        @Override
        protected Widget accessibleLabelTarget() {
            return carrier;
        }
    }

    /** @return the id the caption's one {@code LABEL_FOR} relation points at */
    private static long labelFor(AccessibleNode label) {
        long target = AccessibleNode.NONE;
        int count = 0;
        for (var relation : label.relations()) {
            if (relation.kind() == Accessible.Relation.LABEL_FOR) {
                target = relation.target();
                count++;
            }
        }
        assertEquals(1, count, "one LABEL_FOR on the caption: " + label.relations());
        return target;
    }

    /** @return the node of that role carrying that name, since a caption shares its name with what it labels */
    private AccessibleNode node(Accessible.Role role, String name) {
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode candidate = tree().node(i);
            if (candidate.role() == role && candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError("no " + role + " named \"" + name + "\" in " + describe(tree()));
    }

    private static long labelledBy(AccessibleNode node) {
        for (var relation : node.relations()) {
            if (relation.kind() == Accessible.Relation.LABELLED_BY) {
                return relation.target();
            }
        }
        return AccessibleNode.NONE;
    }

    @Test
    void aCompositeSendsABoundCaptionToTheChildItSaysCarriesIt() {
        Column root = new Column();
        caption = new Label("Start date");
        Composite picker = new Composite();
        root.add(caption);
        root.add(picker);
        caption.setLabelFor(picker);
        bind(root);

        AccessibleNode field = node(Accessible.Role.TEXT_FIELD);
        assertEquals("Start date", field.name(),
                "the caption names the control a reader arrives at, not the group around it"
                        + describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, field.nameFrom(), describe(tree()));
        AccessibleNode label = node(Accessible.Role.LABEL);
        assertEquals(label.id(), labelledBy(field),
                "and the field carries the relation back" + describe(tree()));
        assertEquals(field.id(), labelFor(label),
                "the caption's own link lands where the name did, on the field" + describe(tree()));
        assertEquals("Open", node(Accessible.Role.BUTTON).name(),
                "the sibling the composite did not name keeps its own" + describe(tree()));
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode other = tree().node(i);
            if (other.role() == Accessible.Role.GROUP) {
                assertNotEquals("Start date", other.name(),
                        "the group is not named by the caption it sent down; declaring nothing "
                                + "else, it is transparent" + describe(tree()));
            }
        }

        picker.carrier = picker;
        picker.invalidateAccessible();
        frame();
        assertEquals("", node(Accessible.Role.TEXT_FIELD).name(),
                "a composite that keeps the caption keeps it" + describe(tree()));
        assertEquals("Start date", node(Accessible.Role.GROUP).name(),
                "and the group is named and published, as before this hook existed"
                        + describe(tree()));
    }

    /**
     * A composite inside a composite: the outer names the inner as its carrier, the inner names
     * its field, and the caption follows the chain to the field. A field with a caption of its
     * own keeps its own over one sent down to it.
     */
    @Test
    void aRedirectFollowsAChainAndAChildsOwnCaptionWinsOverOneSentDown() {
        Column root = new Column();
        caption = new Label("Stay");
        Composite inner = new Composite();
        Column outer = new Column() {
            @Override
            protected Widget accessibleLabelTarget() {
                return inner;
            }
        };
        outer.add(inner);
        root.add(caption);
        root.add(outer);
        caption.setLabelFor(outer);
        bind(root);

        assertEquals("Stay", node(Accessible.Role.TEXT_FIELD).name(),
                "outer names inner, inner names its field, the caption arrives at the field"
                        + describe(tree()));
        assertEquals(node(Accessible.Role.TEXT_FIELD).id(), labelFor(node(Accessible.Role.LABEL)),
                describe(tree()));

        Label own = new Label("Check-in");
        root.add(own);
        own.setLabelFor(inner.field);
        frame();
        assertEquals("Check-in", node(Accessible.Role.TEXT_FIELD).name(),
                "the field's own caption wins over the one the composites sent down"
                        + describe(tree()));
        assertEquals(node(Accessible.Role.LABEL, "Check-in").id(),
                labelledBy(node(Accessible.Role.TEXT_FIELD)), describe(tree()));
    }

    /**
     * A composite that names a stranger — a widget outside its own subtree — is refused: the
     * walk throws, the scene contains it as an accessibility crash and keeps the previous tree,
     * so the caption never lands on the stranger.
     */
    @Test
    void aCompositeThatNamesAStrangerAsItsCarrierIsRefusedLoudly() {
        Column root = new Column();
        caption = new Label("Start date");
        Composite picker = new Composite();
        TextField stranger = new TextField();
        stranger.setAccessibleName("Stranger");
        root.add(caption);
        root.add(picker);
        root.add(stranger);
        caption.setLabelFor(picker);
        bind(root);
        assertNotNull(node(Accessible.Role.TEXT_FIELD, "Start date"),
                "the caption arrived at the field" + describe(tree()));

        List<Throwable> contained = new ArrayList<>();
        CrashHandler recorder = (phase, error) -> {
            contained.add(error);
            return true;
        };
        Crashes.install(recorder);
        try {
            picker.carrier = stranger;
            picker.invalidateAccessible();
            frame();
        } finally {
            Crashes.uninstall(recorder);
        }

        assertEquals(1, contained.size(), "one refusal, dispatched as a crash: " + contained);
        assertTrue(contained.get(0) instanceof IllegalStateException, contained.get(0).toString());
        assertTrue(contained.get(0).getMessage().contains("outside its own subtree"),
                contained.get(0).getMessage());
        assertEquals("Start date", node(Accessible.Role.TEXT_FIELD, "Start date").name(),
                "the previous tree stands: the caption is still on the field" + describe(tree()));
        assertNotNull(node(Accessible.Role.TEXT_FIELD, "Stranger"),
                "and the stranger keeps its own name" + describe(tree()));
    }
}
