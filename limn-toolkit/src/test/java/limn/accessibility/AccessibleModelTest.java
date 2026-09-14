package limn.accessibility;

import limn.i18n.I18nString;
import limn.testing.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accessibility model: its enums are closed, its facets refuse what no platform could carry,
 * and its builder produces a tree whose links agree with the order it was walked in.
 *
 * <p>The role check reads the record rather than a copy of it. A role is only worth having when
 * all three platform bridges can name something true for it, so the list lives in one place and
 * this test is what stops the code and that place from drifting apart before the bridges exist to
 * notice.
 */
class AccessibleModelTest {

    /**
     * Resolves the relations and then publishes, which is the order the scene's own walk uses and
     * the order the comparison requires: a relation is declared as a target, and the identifier it
     * resolves to is what a difference reads.
     *
     * @param a       the builder holding a finished walk
     * @param resolve what turns a target into a node identifier
     * @return the tree
     */
    private static AccessibleTree publish(Accessibility a, ToLongFunction<Object> resolve) {
        a.resolveRelations(resolve);
        return a.publish(0, 0, 0, 1, true);
    }

    @Test
    void theRoleListIsTheOneTheRecordDecided() throws IOException {
        List<String> declared = new ArrayList<>();
        for (Accessible.Role role : Accessible.Role.values()) {
            declared.add(role.name());
        }
        assertEquals(rolesFromTheRecord(), declared,
                "the role enum and ADR 039's closed list have drifted apart");
    }

    /**
     * The fenced block in the section that closes the enum, read as a comma-separated list. Its
     * heading is matched by number so that rewording the title does not silently stop this test
     * from checking anything.
     */
    private static List<String> rolesFromTheRecord() throws IOException {
        Path record = RepositoryRoot.find().resolve("docs/adr")
                .resolve("039-an-accessible-tree-is-a-snapshot-and-the-platform-reads-it-on-its-"
                        + "own-thread.md");
        List<String> lines = Files.readAllLines(record, StandardCharsets.UTF_8);
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("### 1.12 ")) {
                at = i;
                break;
            }
        }
        assertTrue(at >= 0, "ADR 039 has no section 1.12");
        StringBuilder block = new StringBuilder();
        boolean inside = false;
        for (int i = at; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("```")) {
                if (inside) {
                    break;
                }
                inside = true;
                continue;
            }
            if (inside) {
                block.append(line).append(' ');
            }
        }
        assertTrue(block.length() > 0, "ADR 039 section 1.12 has no fenced role list");
        List<String> roles = new ArrayList<>();
        for (String token : block.toString().split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                roles.add(trimmed);
            }
        }
        return roles;
    }


    @Test
    void twelveVerbsArePublishableAndFourTakeAnArgument() {
        int parameterless = 0;
        for (Accessible.Action action : Accessible.Action.values()) {
            if (action.isParameterless()) {
                parameterless++;
            }
        }
        assertEquals(12, parameterless);
        assertEquals(16, Accessible.Action.values().length);
        assertFalse(Accessible.Action.SET_VALUE.isParameterless());
        assertFalse(Accessible.Action.SET_SELECTION.isParameterless());
    }

    @Test
    void anActionListRefusesAVerbThatTakesAnArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActionFacet(java.util.Set.of(Accessible.Action.SET_VALUE), null));
        assertThrows(IllegalArgumentException.class, () -> new ActionFacet(java.util.Set.of(), null));
        ActionFacet facet = new ActionFacet(java.util.Set.of(Accessible.Action.PRESS), "Ctrl+P");
        assertTrue(facet.has(Accessible.Action.PRESS));
        assertFalse(facet.has(Accessible.Action.TOGGLE));
        assertThrows(UnsupportedOperationException.class,
                () -> facet.actions().add(Accessible.Action.TOGGLE));
    }

    @Test
    void aStateAFacetExpressesCannotBeSetBehindTheFacetsBack() {
        Accessibility a = new Accessibility();
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(a.mint(), AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.state(Accessible.State.CHECKED);
        a.state(Accessible.State.ENABLED);
        assertTrue(a.declaresNothing(), "a state a facet owns is not a declaration of its own");
        a.toggle(ToggleFacet.State.ON);
        assertFalse(a.declaresNothing());
        AccessibleTree tree = publish(a, target -> 0);
        assertTrue(tree.root().has(Accessible.State.CHECKED), "CHECKED derives from the facet");
        assertFalse(tree.root().has(Accessible.State.ENABLED),
                "ENABLED is the publish step's, never a widget's");
    }

    @Test
    void theTreeLinksAgreeWithTheOrderItWasWalkedIn() {
        Accessibility a = new Accessibility();
        a.beginWalk(200, 100, Locale.ENGLISH);
        long rootId = a.mint();
        int root = a.begin(rootId, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 200, 100);
        a.role(Accessible.Role.GROUP);
        a.begin(a.mint(), root, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.BUTTON);
        a.end();
        a.begin(a.mint(), root, Locale.ENGLISH, 100, 0, 100, 100);
        a.role(Accessible.Role.LABEL);
        a.end();
        a.end();

        AccessibleTree tree = publish(a, target -> 0);
        assertEquals(3, tree.nodeCount());
        assertEquals(AccessibleNode.NONE, tree.root().parent());
        assertEquals(1, tree.root().firstChild());
        assertEquals(2, tree.root().lastChild());
        assertEquals(2, tree.node(1).nextSibling());
        assertEquals(AccessibleNode.NONE, tree.node(1).previousSibling());
        assertEquals(1, tree.node(2).previousSibling());
        assertEquals(AccessibleNode.NONE, tree.node(2).nextSibling());
        assertEquals(Accessible.Role.BUTTON, tree.node(1).role());
        assertEquals(rootId, tree.find(rootId).id());
    }

    @Test
    void aNameIsResolvedOnceAndCarriedOverWhileItsSourceAndLanguageStand() {
        I18nString source = I18nString.literal("Save");
        Accessibility a = new Accessibility();
        long id = a.mint();

        a.beginWalk(10, 10, Locale.ENGLISH);
        a.begin(id, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 10, 10);
        a.role(Accessible.Role.BUTTON);
        a.name(source);
        a.end();
        AccessibleTree first = publish(a, target -> 0);
        assertEquals("Save", first.root().name());

        a.beginWalk(10, 10, Locale.ENGLISH);
        a.begin(id, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 10, 10);
        a.role(Accessible.Role.BUTTON);
        a.name(source);
        a.end();
        assertFalse(a.changed(), "nothing moved, so nothing differs");
        AccessibleTree second = publish(a, target -> 0);
        assertSame(first.root().name(), second.root().name(),
                "the resolved string is carried over, not resolved again");
    }

    @Test
    void aSyntheticChildKeepsItsIdentifierAcrossWalks() {
        Accessibility a = new Accessibility();
        long ownerId = a.mint();
        long firstRow = describeMenuAndTakeRowIdentifier(a, ownerId);
        long secondRow = describeMenuAndTakeRowIdentifier(a, ownerId);
        assertEquals(firstRow, secondRow,
                "a synthetic child interned by (owner, key) keeps the element a client holds");
    }

    private static long describeMenuAndTakeRowIdentifier(Accessibility a, long ownerId) {
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(ownerId, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.MENU);
        a.selection(false, true);
        a.child(7);
        a.role(Accessible.Role.MENU_ITEM);
        a.bounds(0, 0, 100, 20);
        a.selectionItem(true, 1, 3);
        a.state(Accessible.State.ACTIVE);
        a.endChild();
        a.end();
        AccessibleTree tree = publish(a, target -> 0);
        assertEquals(2, tree.nodeCount());
        AccessibleNode row = tree.node(1);
        assertEquals(Accessible.Role.MENU_ITEM, row.role());
        assertNotNull(tree.root().selection());
        assertEquals(row.id(), tree.root().selection().activeDescendant(),
                "the container's active descendant is the node that declared itself active");
        return row.id();
    }

    /**
     * A relation names a node published in <em>some</em> window of the process, or it is dropped:
     * a native popup's root names the field that opened it, which lives in another scene's tree,
     * and that is kept; a target the resolver finds nowhere is worse than none (ADR 039 §1.11).
     */
    @Test
    void aRelationNamingNothingPublishedIsDroppedRatherThanPublishedDangling() {
        Object opener = new Object();
        Accessibility popup = new Accessibility();
        popup.beginWalk(10, 10, Locale.ENGLISH);
        popup.begin(popup.mint(), AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 10, 10);
        popup.role(Accessible.Role.MENU);
        popup.relation(Accessible.Relation.POPUP_FOR, opener);
        popup.end();
        AccessibleTree dropped = publish(popup, target -> 0);
        assertTrue(dropped.root().relations().isEmpty(), "published nowhere: dropped");

        // The opener is a node of ANOTHER scene of the process: kept, and the identifier says
        // which scene's tree a bridge has to ask for it.
        Accessibility host = new Accessibility();
        host.beginWalk(10, 10, Locale.ENGLISH);
        long combo = host.mint();
        host.begin(combo, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 10, 10);
        host.role(Accessible.Role.COMBO_BOX);
        host.end();
        AccessibleTree hostTree = publish(host, target -> 0);

        popup.beginWalk(10, 10, Locale.ENGLISH);
        popup.begin(popup.mint(), AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 10, 10);
        popup.role(Accessible.Role.MENU);
        popup.relation(Accessible.Relation.POPUP_FOR, opener);
        popup.end();
        AccessibleTree kept = publish(popup, target -> target == opener ? combo : 0);
        assertEquals(1, kept.root().relations().size());
        long target = kept.root().relations().get(0).target();
        assertEquals(combo, target);
        assertFalse(kept.holds(target), "the popup's own tree does not hold the opener");
        assertTrue(hostTree.holds(target), "the host's does");
        assertNotNull(hostTree.find(target));
    }

    /**
     * Identifiers are process-wide: two scenes minting side by side never hand out the same
     * number, and the number alone says which scene minted it, so a bridge holding several
     * windows' trees can route an identifier without a registry (ADR 039 §1.3, amended
     * 2026-09-14).
     */
    @Test
    void twoScenesNeverMintTheSameIdentifierAndTheNumberSaysWhichSceneMintedIt() {
        Accessibility first = new Accessibility();
        Accessibility second = new Accessibility();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            long a = first.mint();
            long b = second.mint();
            assertTrue(seen.add(a), "minted twice: " + a);
            assertTrue(seen.add(b), "minted twice: " + b);
            assertTrue(a != 0 && b != 0, "zero is never an identifier");
            assertEquals(first.sceneTag(), Accessibility.sceneTagOf(a));
            assertEquals(second.sceneTag(), Accessibility.sceneTagOf(b));
        }
        assertTrue(first.sceneTag() != second.sceneTag());
        assertEquals(0, Accessibility.sceneTagOf(1000),
                "an identifier a test wrote by hand belongs to no scene");
        assertFalse(AccessibleTree.EMPTY.holds(first.mint()), "the empty tree holds nothing");

        first.beginWalk(10, 10, Locale.ENGLISH);
        long root = first.mint();
        first.begin(root, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 10, 10);
        first.role(Accessible.Role.WINDOW);
        first.end();
        AccessibleTree tree = publish(first, target -> 0);
        assertEquals(first.sceneTag(), tree.sceneTag());
        assertTrue(tree.holds(root));
        assertFalse(tree.holds(second.mint()), "another scene's identifier is not this tree's");
        assertTrue(tree.holds(first.mint()),
                "holds() answers from the number alone, not from what this snapshot published; "
                        + "find() says whether the node is in it");
    }

    @Test
    void aTextFacetCarriesItsCaretAffinityAndAnEmptyTreeCarriesNothing() {
        assertEquals(0, AccessibleTree.EMPTY.nodeCount());
        assertNull(AccessibleTree.EMPTY.root());
        assertEquals(AccessibleNode.NONE, AccessibleTree.EMPTY.indexOf(1));

        TextFacet facet = new TextFacet("hi", 1, limn.graphics.ShapedText.Affinity.UPSTREAM,
                0, 2, 1, null);
        assertTrue(facet.hasSelection());
        assertThrows(NullPointerException.class,
                () -> new TextFacet(null, 0, limn.graphics.ShapedText.Affinity.DOWNSTREAM,
                        0, 0, 1, null));
    }
}
