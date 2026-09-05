package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.i18n.I18nString;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Label} becomes in the accessible tree: one node of static text, a heading when
 * its typographic role says title, named by the whole paragraph and never by the lines that fit.
 *
 * <p>The survey row in ADR 039 §7 has the verdict right and is silent on the three things that
 * made this step work. An undescribed label was not merely transparent: it overrides {@code
 * onPaint}, so §1.6's paints-and-says-nothing warning named a toolkit class in every
 * application's log, and the fix is the role and not a decoration flag, because a label's
 * painting is information. The name has to be {@link Label#textSource()} by reference, because
 * the painted lines are derived strings with no witness and a reader must hear what an ellipsis
 * cut off. And the {@code LABEL_FOR} half of the caption relation is not written by the walk,
 * which mirrors only {@code LABELLED_BY} onto the target, so it exists only because the label's
 * own hook declares it.
 *
 * <p>Every case drives the public setters on a bound scene and reads what the scene published;
 * nothing here builds a tree.
 */
class LabelAccessibilityTest extends AccessibleComponentTestBase {

    private Label label;

    /** Everything the walk's logger says while a case runs. */
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
    void listenToTheWalk() {
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
    }

    @AfterEach
    void stopListening() {
        walkLogger.removeHandler(capture);
    }

    // ------------------------------------------------------------------------------ the fixture

    /** One label as the only control in a column, so it keeps its own measured box. */
    private void bindLabel(Label under) {
        label = under;
        Column root = new Column();
        root.add(label);
        bind(root);
    }

    /** @return the label's node, which is the one and only static-text node in the tree */
    private AccessibleNode text() {
        return node(Accessible.Role.LABEL);
    }

    /** @return the one relation of {@code kind} on {@code node}, asserting there is exactly one */
    private static AccessibleRelation only(AccessibleNode node, Accessible.Relation kind) {
        AccessibleRelation found = null;
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == kind) {
                assertNull(found, "declared twice: " + node.relations());
                found = relation;
            }
        }
        assertNotNull(found, "no " + kind + " on " + node + ": " + node.relations());
        return found;
    }

    // ------------------------------------------------------------------------------ what it is

    @Test
    void aLabelPublishesOneNodeOfStaticTextNamedByItsContent() {
        bindLabel(new Label("Ready"));

        AccessibleNode node = text();
        assertEquals("Ready", node.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, node.nameFrom(),
                "the name is the painted text, which is what decides the attribute one platform "
                        + "writes it into" + describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "a label never calls setFocusable and has no key or mouse handler, so it is not a "
                        + "tab stop" + describe(tree()));
        assertNull(node.actions(), "nothing about a label can be operated" + describe(tree()));
        assertNull(node.text(),
                "no text facet: there is no caret and no selection, and the lines are derived"
                        + describe(tree()));
        assertNull(node.value(), describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertEquals(label.localToSceneX(), node.x(), describe(tree()));
        assertEquals(label.localToSceneY(), node.y(), describe(tree()));
        assertEquals(label.width(), node.width(),
                "the box is the widget's own laid-out box, which is what the text is clipped to"
                        + describe(tree()));
        assertEquals(label.height(), node.height(),
                "and it excludes the icon's optical overhang, as the measured row does"
                        + describe(tree()));
    }

    @Test
    void aLabelThatPaintsIsNotWarnedAbout() {
        bindLabel(new Label("Ready"));

        for (LogRecord record : logged) {
            assertFalse(record.getLevel().intValue() >= Level.WARNING.intValue()
                            && String.valueOf(record.getMessage()).contains("says nothing"),
                    "a label overrides onPaint, so before it declared a role the transparency "
                            + "rule deleted it and the walk warned that a toolkit class paints and "
                            + "says nothing. The role is what stops that, not paintsDecoration: a "
                            + "label's painting is information. Logged: " + record.getMessage());
        }
    }

    // ------------------------------------------------------------------------------ the role

    @Test
    void theTitleRoleIsAHeadingAndTheOtherTwoAreStaticText() {
        bindLabel(new Label("Settings"));

        label.setRole(Label.Role.TITLE);
        frame();
        assertEquals(Accessible.Role.HEADING, node("Settings").role(),
                "a dialog's title is what a reader navigates by heading" + describe(tree()));

        label.setRole(Label.Role.LABEL);
        frame();
        assertEquals(Accessible.Role.LABEL, node("Settings").role(),
                "the LABEL typographic token is smaller static text, not a caption role"
                        + describe(tree()));

        label.setRole(Label.Role.BODY);
        frame();
        assertEquals(Accessible.Role.LABEL, node("Settings").role(), describe(tree()));
    }

    @Test
    void aPinnedFontDoesNotDemoteATitle() {
        bindLabel(new Label("Settings"));
        label.setRole(Label.Role.TITLE);
        label.setFont(Font.of(14));
        frame();

        assertEquals(Accessible.Role.HEADING, node("Settings").role(),
                "setFont pins a face as a size escape hatch and effectiveFont ignores the role "
                        + "while it is set, but role() is still the only declared semantic: a "
                        + "title drawn at fourteen points is still the title" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the name

    @Test
    void theNameIsTheWholeParagraphAndNeverTheLineThatFit() {
        // Sixteen glyphs at ten points each in a ninety-five point box: the painted line is cut
        // to eight glyphs and an ellipsis, exactly as LabelTest pins it.
        label = new Label("0123456789ABCDEF");
        Column root = new Column();
        root.add(new SizedBox(95, 12, label));
        bind(root);
        scene.setTextRuler(ComponentTestBase.RULER);
        frame();

        assertEquals(List.of("01234567…"), label.displayedLines(),
                "the fixture has to actually truncate");
        assertEquals("0123456789ABCDEF", text().name(),
                "a reader must hear what the box cut off, and the cut line is a derived string "
                        + "with no witness to compare it by" + describe(tree()));

        label.setWrap(true);
        frame();

        assertTrue(label.displayedLines().size() > 1, "the fixture has to actually wrap");
        assertEquals("0123456789ABCDEF", text().name(),
                "wrapped lines are substrings and the name is still the paragraph"
                        + describe(tree()));
        assertFalse(text().has(Accessible.State.MULTI_LINE),
                "MULTI_LINE is an editable-text state with no text facet here to qualify"
                        + describe(tree()));
    }

    @Test
    void aChangedTextRenamesTheSameNodeOnceAndAnUnchangedOneSaysNothing() {
        bindLabel(new Label("Ready"));
        long id = text().id();

        label.setText("Busy");
        frame();

        assertEquals("Busy", text().name(), describe(tree()));
        assertEquals(id, text().id(), "the node keeps its identifier" + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "one change, one event: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "a rename is not a reshaping: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());

        int published = bridge.published.size();
        bridge.events.clear();

        label.setText("Busy");
        frame();
        label.setText(I18nString.literal("Busy"));
        frame();

        assertEquals(published, bridge.published.size(),
                "setText short-circuits on an equal literal and on an equal value, so the "
                        + "reference the name is compared by never moves and nothing is "
                        + "republished: a status label set every frame is free");
        assertTrue(bridge.events.isEmpty(), "and nothing was said: " + bridge.events);
    }

    @Test
    void anEmptyLabelStillPublishesAndKeepsItsIdentityWhenTextArrives() {
        bindLabel(new Label(""));

        AccessibleNode empty = text();
        assertEquals("", empty.name(),
                "an empty status label is still a node, so that a reader holding it is not told "
                        + "it was destroyed and recreated on every message" + describe(tree()));
        long id = empty.id();

        label.setText("Ready");
        frame();

        assertEquals("Ready", text().name(), describe(tree()));
        assertEquals(id, text().id(), "same node" + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "a label that empties between messages must never churn identity: "
                        + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                bridge.events.toString());
    }

    // ------------------------------------------------------------------------------ the tooltip

    @Test
    void aTooltipDescribesALabelWithTextAndNamesOneWithout() {
        bindLabel(new Label("Ready"));
        label.setTooltip("The build state");
        frame();

        AccessibleNode named = text();
        assertEquals("Ready", named.name(),
                "the tooltip never overwrites the content" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, named.nameFrom(), describe(tree()));
        assertEquals("The build state", named.description(),
                "the walk's free default puts it in the description when a name exists"
                        + describe(tree()));

        Label iconOnly = new Label("");
        iconOnly.setTooltip("Connected");
        bindLabel(iconOnly);

        AccessibleNode fromTooltip = text();
        assertEquals("Connected", fromTooltip.name(),
                "an icon-only label has the tooltip as its only name, which is why an empty text "
                        + "is published rather than ignored" + describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, fromTooltip.nameFrom(), describe(tree()));
    }

    // ------------------------------------------------------------------------------ the relation

    @Test
    void aCaptionCarriesLabelForAndTheFieldCarriesLabelledByBack() {
        Column root = new Column();
        label = new Label("Colour theme");
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three"));
        root.add(label);
        root.add(combo);
        label.setLabelFor(combo);
        bind(root);

        AccessibleNode caption = text();
        AccessibleNode field = node(Accessible.Role.COMBO_BOX);
        assertEquals(1, caption.relations().size(),
                "exactly one relation on the caption: " + caption.relations());
        assertEquals(field.id(), only(caption, Accessible.Relation.LABEL_FOR).target(),
                "the walk mirrors only LABELLED_BY onto the target, so the caption's half comes "
                        + "from the caption's own hook or from nowhere" + describe(tree()));
        assertEquals(caption.id(), only(field, Accessible.Relation.LABELLED_BY).target(),
                describe(tree()));
        assertEquals("Colour theme", field.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, field.nameFrom(), describe(tree()));

        label.setLabelFor(null);
        frame();

        assertEquals(List.of(), text().relations(),
                "null removes the caption's half" + describe(tree()));
        assertEquals(List.of(), node(Accessible.Role.COMBO_BOX).relations(),
                "and the field's" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the states

    @Test
    void aDisabledLabelInheritsTheBitAndDeclaresNoneOfItsOwn() {
        bindLabel(new Label("Ready"));
        label.setEnabled(false);
        frame();

        AccessibleNode node = text();
        assertFalse(node.has(Accessible.State.ENABLED),
                "the walk carries the bit down; the hook reads isEnabled only for ink colour"
                        + describe(tree()));
        assertEquals(Accessible.Role.LABEL, node.role(),
                "the predicate asks about declared facts and the role is one, so a disabled label "
                        + "is not deleted" + describe(tree()));
        for (AccessibleNode focusable : nodesWith(Accessible.State.FOCUSABLE)) {
            assertFalse(focusable.id() == node.id(), "a label is never a tab stop" + describe(tree()));
        }
    }

    // ------------------------------------------------------------------------------ what it costs

    @Test
    void aQuietLabelAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindLabel(new Label("Ready"));
        int published = bridge.published.size();

        label.setColor(Color.BLACK);
        frame();
        label.setMuted(true);
        frame();
        for (int i = 0; i < 20; i++) {
            label.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "colour and damage change nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        // A name formatted inside the hook, or the painted lines joined into one, is a string per
        // damaged frame spent concluding that nothing moved, and this is the only place it shows.
        long withAReaderAttached = AllocationProbe.leastAllocatedBy(() -> {
            label.invalidate();
            frame();
        }, 60);

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        bridge.listening = false;
        long withNobodyListening = AllocationProbe.leastAllocatedBy(() -> {
            label.invalidate();
            frame();
        }, 60);

        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a label that did not move must cost no memory: the name is a held "
                        + "I18nString compared by reference, the role is an enum and the relation "
                        + "slot grew once");
    }

    // ------------------------------------------------------------------------------ nothing else

    @Test
    void theIconIsNotANode() {
        Label withIcon = new Label("Ready");
        withIcon.setIcon(new Icon() {
            @Override
            public Image image(int pixelSize, boolean dark) {
                throw new UnsupportedOperationException("never rasterized");
            }

            @Override
            public void paint(Canvas canvas, float x, float y, float size, Color tint,
                              boolean dark) {
                // Drawn by the frame the fixture renders, and drawing nothing is the point.
            }
        });
        bindLabel(withIcon);

        assertEquals("Ready", text().name(), describe(tree()));
        assertEquals(List.of(), childrenOf(text()),
                "the icon has no name and no operation; an icon-only label is named by its tooltip"
                        + describe(tree()));
    }
}
