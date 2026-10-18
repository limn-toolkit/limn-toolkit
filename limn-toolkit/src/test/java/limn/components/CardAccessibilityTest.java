package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.AccessibleTree;
import limn.accessibility.Shape;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link Card} to an assistive technology (ADR 073 §2.7): a titled card is a group named by its
 * title, whose title is a heading; a card with none is transparent; an activated card is a
 * pressable leaf named by the title and described by the subtitle, with nothing else of its area
 * read; with a footer that leaf and the footer's actions are held in a group named by the title;
 * the tree turns from one to the other as the footer comes and goes, the leaf keeping its id; and
 * what the application writes on the card lands on the leaf.
 */
class CardAccessibilityTest extends AccessibleComponentTestBase {

    private final List<String> heard = new ArrayList<>();

    private Card bindCard(Card card) {
        Column root = new Column();
        root.add(new SizedBox(300, SizedBox.UNSET, card));
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        return card;
    }

    private Card invoice() {
        return new Card(new Label("Three items, due on Friday.")).setTitle("Invoice 1042")
                .setSubtitle("Harbor Lane Supplies");
    }

    private AccessibleNode find(Accessible.Role role) {
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                return tree.node(i);
            }
        }
        return null;
    }

    private long relationTo(AccessibleNode node, Accessible.Relation kind) {
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == kind) {
                return relation.target();
            }
        }
        return 0;
    }

    @Test
    void aTitledCardIsAGroupNamedByItsHeading() {
        bindCard(invoice());
        AccessibleNode group = find(Accessible.Role.GROUP);
        assertTrue(group != null, describe(tree()));
        assertEquals("Invoice 1042", group.name());
        assertEquals(Accessible.NameFrom.LABEL, group.nameFrom());
        assertEquals(Shape.SURFACE, Shape.of(group));
        List<AccessibleNode> inside = childrenOf(group);
        assertEquals(Accessible.Role.HEADING, inside.get(0).role(), describe(tree()));
        assertEquals("Invoice 1042", inside.get(0).name());
        assertEquals(inside.get(0).id(), relationTo(group, Accessible.Relation.LABELLED_BY),
                "labelled by its heading");
        assertEquals(Accessible.Role.LABEL, inside.get(1).role(), "the subtitle, plain text after it");
        assertEquals("Harbor Lane Supplies", inside.get(1).name());
        assertEquals(Shape.STATIC, Shape.of(inside.get(1)));
        assertEquals("Three items, due on Friday.", inside.get(2).name(), "then the content");
    }

    @Test
    void aCardWithNoTitleIsTransparent() {
        Button inside = new Button("Export");
        bindCard(new Card(inside).setSubtitle("Q3"));
        assertNull(find(Accessible.Role.GROUP), "no group around what it holds: " + describe(tree()));
        assertEquals(Accessible.Role.BUTTON, node("Export").role());

        Card named = bindCard(new Card(new Label("Chart")));
        named.setAccessibleName("Revenue");
        frame();
        assertEquals(Accessible.Role.GROUP, node("Revenue").role(), "named by the application, it is a group");
    }

    @Test
    void anActivatedCardWithNoFooterIsOnePressableLeaf() throws InterruptedException {
        bindCard(invoice().onActivate(() -> heard.add("card")));
        AccessibleNode button = node("Invoice 1042");
        assertEquals(Accessible.Role.BUTTON, button.role());
        assertEquals(Shape.LEAF_ACTION, Shape.of(button));
        assertEquals(Accessible.NameFrom.CONTENT, button.nameFrom());
        assertEquals("Harbor Lane Supplies", button.description(), "the subtitle describes it");
        assertEquals(AccessibleNode.NONE, button.firstChild(), "a leaf: " + describe(tree()));
        assertTrue(button.has(Accessible.State.FOCUSABLE));
        assertNull(find(Accessible.Role.GROUP), "and nothing around it");
        assertNull(find(Accessible.Role.HEADING));
        assertTrue(perform(button.id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of("card"), heard, "a reader's press is a click");
    }

    @Test
    void anActivatedCardWithAFooterIsAGroupOfItsLeafAndTheActions() {
        Button hold = new Button("Hold");
        Button approve = new Button("Approve");
        bindCard(invoice().setFooter(hold, approve).onActivate(() -> { }));
        AccessibleNode group = find(Accessible.Role.GROUP);
        assertTrue(group != null, describe(tree()));
        assertEquals("Invoice 1042", group.name());
        List<AccessibleNode> inside = childrenOf(group);
        assertEquals(3, inside.size(), describe(tree()));
        assertEquals(Accessible.Role.BUTTON, inside.get(0).role());
        assertEquals("Invoice 1042", inside.get(0).name(), "the card's own leaf first");
        assertEquals(inside.get(0).id(), relationTo(group, Accessible.Relation.LABELLED_BY),
                "the group labelled by the leaf that holds the title");
        assertEquals("Hold", inside.get(1).name(), "then the actions, with no tool bar between");
        assertEquals("Approve", inside.get(2).name());
    }

    @Test
    void theTreeTurnsAsTheFooterComesAndGoesAndTheLeafKeepsItsId() {
        Card card = bindCard(invoice().onActivate(() -> { }));
        long leaf = node("Invoice 1042").id();
        card.setFooter(new Button("Approve"));
        frame();
        AccessibleNode group = find(Accessible.Role.GROUP);
        assertTrue(group != null, describe(tree()));
        assertEquals(leaf, childrenOf(group).get(0).id(), "the same leaf, now inside the group");
        card.setFooter();
        frame();
        assertNull(find(Accessible.Role.GROUP), describe(tree()));
        assertEquals(leaf, node("Invoice 1042").id());
    }

    @Test
    void aNameAndADescriptionWrittenOnTheCardLandOnItsLeaf() {
        for (boolean footer : new boolean[] {false, true}) {
            Card card = invoice().onActivate(() -> { });
            if (footer) {
                card.setFooter(new Button("Approve"));
            }
            card.setAccessibleName("Invoice 1042, Harbor Lane Supplies");
            card.setAccessibleDescription("$1,240.00, due on Friday");
            bindCard(card);
            AccessibleNode leaf = node("Invoice 1042, Harbor Lane Supplies");
            assertEquals(Accessible.Role.BUTTON, leaf.role(), "footer " + footer + ": " + describe(tree()));
            assertEquals(Accessible.NameFrom.EXPLICIT, leaf.nameFrom());
            assertEquals("$1,240.00, due on Friday", leaf.description());
        }
    }

    @Test
    void aCaptionBoundToAnActivatedCardNamesItsLeaf() {
        Card card = new Card(new Label("Due Friday")).onActivate(() -> { });
        Label caption = new Label("Next invoice").setLabelFor(card);
        Column root = new Column();
        root.add(caption);
        root.add(new SizedBox(300, SizedBox.UNSET, card));
        bind(root);
        frame();
        AccessibleNode leaf = find(Accessible.Role.BUTTON);
        assertEquals("Next invoice", leaf.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, leaf.nameFrom());
    }

    @Test
    void aDisabledActivatedCardIsStillAButtonAndOffersNoPress() {
        Card card = bindCard(invoice().onActivate(() -> heard.add("card")));
        card.setEnabled(false);
        frame();
        AccessibleNode button = node("Invoice 1042");
        assertEquals(Accessible.Role.BUTTON, button.role(), "heard as unavailable, not gone");
        assertFalse(button.accepts(Accessible.Action.PRESS));
    }

    @Test
    void whatTheAreaHoldsIsNotReadOnAnActivatedCard() {
        Widget<?> badge = new Label("Overdue");
        bindCard(invoice().setHeaderSuffix(badge).onActivate(() -> { }));
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            assertFalse("Overdue".equals(tree.node(i).name()), "a leaf has no children: " + describe(tree));
            assertFalse("Three items, due on Friday.".equals(tree.node(i).name()));
        }
    }
}
