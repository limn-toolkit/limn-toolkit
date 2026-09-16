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
    private static AccessibleTree publish(Accessibility a, Accessibility.RelationResolver resolve) {
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
        return fencedListFromTheRecord("### 1.12 ", null);
    }

    /**
     * The closed state list, from the fenced block that follows "The closed list of states" in
     * §1.2 (amended 2026-09-14): a state added to the enum without the record saying so fails
     * here, as a role does.
     */
    @Test
    void theStateListIsTheOneTheRecordDecided() throws IOException {
        List<String> declared = new ArrayList<>();
        for (Accessible.State state : Accessible.State.values()) {
            declared.add(state.name());
        }
        assertEquals(fencedListFromTheRecord("### 1.2 ", "The closed list of states"), declared,
                "the state enum and ADR 039 §1.2's closed list have drifted apart");
    }

    /**
     * The closed facet list (MODEL-NEW-5): the fenced block after "The closed list of facets" in
     * §1.2 names exactly the {@code *Facet} records in this package, so a facet added to the code
     * without the record saying so fails here, and one the record promises without a class does
     * too.
     */
    @Test
    void theFacetListIsTheOneTheRecordDecided() throws IOException {
        Path sources = RepositoryRoot.find()
                .resolve("limn-toolkit/src/main/java/limn/accessibility");
        List<String> inCode = new ArrayList<>();
        try (var files = Files.list(sources)) {
            files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith("Facet.java"))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .sorted()
                    .forEach(inCode::add);
        }
        List<String> inRecord = new ArrayList<>(
                fencedListFromTheRecord("### 1.2 ", "The closed list of facets"));
        inRecord.sort(null);
        assertEquals(inRecord, inCode,
                "the facet records and ADR 039 §1.2's closed list have drifted apart");
        for (String facet : inCode) {
            boolean accessor = false;
            for (var method : AccessibleNode.class.getMethods()) {
                if (method.getReturnType().getSimpleName().equals(facet)) {
                    accessor = true;
                }
            }
            assertTrue(accessor, facet + " is a facet no node can carry: AccessibleNode has no "
                    + "accessor returning it");
        }
    }

    /**
     * {@code HierarchyFacet} (ADR 039 §1.2, amended 2026-09-14): carried only by a node that
     * declared it, built in the copy and not in the walk, and a level or a row that moves is a
     * difference the quiet-frame comparison sees.
     */
    @Test
    void aHierarchyFacetIsCarriedOnlyWhereDeclaredAndAMoveOfItIsAChange() {
        Accessibility a = new Accessibility();
        long owner = a.mint();
        describeOutline(a, owner, 2);
        AccessibleTree first = publish(a, (kind, target) -> 0);
        assertNull(first.root().hierarchy(), "the outline itself stands nowhere");
        assertEquals(new HierarchyFacet(1, 1, 2), first.node(1).hierarchy());
        assertEquals(new HierarchyFacet(2, 2, 2), first.node(2).hierarchy());
        assertNull(first.node(3).hierarchy(), "a row that said nothing carries nothing");

        describeOutline(a, owner, 2);
        assertFalse(a.changed(), "the same numbers are no difference");
        describeOutline(a, owner, 3);
        assertTrue(a.changed(), "a deeper second row is");
        AccessibleTree second = publish(a, (kind, target) -> 0);
        assertEquals(new HierarchyFacet(3, 2, 2), second.node(2).hierarchy());
    }

    /** One TREE with two TREE_ITEM rows carrying the facet and one carrying none. */
    private static void describeOutline(Accessibility a, long owner, int secondLevel) {
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(owner, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.TREE);
        a.child(1);
        a.role(Accessible.Role.TREE_ITEM);
        a.bounds(0, 0, 100, 20);
        a.hierarchy(1, 1, 2);
        a.endChild();
        a.child(2);
        a.role(Accessible.Role.TREE_ITEM);
        a.bounds(0, 20, 100, 20);
        a.hierarchy(secondLevel, 2, 2);
        a.endChild();
        a.child(3);
        a.role(Accessible.Role.LABEL);
        a.bounds(0, 40, 100, 20);
        a.endChild();
        a.end();
    }

    /**
     * A comma-separated list in a fenced block of ADR 039: the first fenced block of the section
     * whose heading starts with {@code heading}, or, when {@code marker} is given, the first
     * fenced block after the first line of that section containing the marker.
     */
    private static List<String> fencedListFromTheRecord(String heading, String marker)
            throws IOException {
        Path record = RepositoryRoot.find().resolve("docs/adr")
                .resolve("039-an-accessible-tree-is-a-snapshot-and-the-platform-reads-it-on-its-"
                        + "own-thread.md");
        List<String> lines = Files.readAllLines(record, StandardCharsets.UTF_8);
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(heading)) {
                at = i;
                break;
            }
        }
        assertTrue(at >= 0, "ADR 039 has no section " + heading);
        if (marker != null) {
            int found = -1;
            for (int i = at + 1; i < lines.size() && !lines.get(i).startsWith("### "); i++) {
                if (lines.get(i).contains(marker)) {
                    found = i;
                    break;
                }
            }
            assertTrue(found >= 0, "ADR 039 section " + heading + " has no line saying \""
                    + marker + "\"");
            at = found;
        }
        StringBuilder block = new StringBuilder();
        boolean inside = false;
        for (int i = at + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!inside && line.startsWith("### ")) {
                break;          // the next section: the list was not in this one
            }
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
        assertTrue(block.length() > 0, "ADR 039 section " + heading + " has no fenced list"
                + (marker == null ? "" : " after \"" + marker + "\""));
        List<String> items = new ArrayList<>();
        for (String token : block.toString().split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    /**
     * {@code EXPANDABLE} is the expand facet's presence (ADR 039 §1.2, amended 2026-09-14;
     * decisions 27 and 41): a node that declares the facet carries it open or closed, a node
     * without the facet never does, a widget cannot set it, and a facet arriving raises the
     * state's event from the diff like every other bit.
     */
    @Test
    void expandableIsTheExpandFacetsPresenceAndNeverAWidgetsToSet() {
        Accessibility a = new Accessibility();
        long owner = a.mint();
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(owner, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.MENU_BAR);
        a.child(1);
        a.role(Accessible.Role.MENU_ITEM);
        a.bounds(0, 0, 50, 20);
        assertThrows(IllegalArgumentException.class,
                () -> a.state(Accessible.State.EXPANDABLE), "derived, never set");
        a.endChild();
        a.child(2);
        a.role(Accessible.Role.MENU_ITEM);
        a.bounds(50, 0, 50, 20);
        a.expand(false);
        a.endChild();
        a.child(3);
        a.role(Accessible.Role.MENU_ITEM);
        a.bounds(100, 0, 50, 20);
        a.expand(true);
        a.endChild();
        a.end();
        AccessibleTree first = publish(a, (kind, target) -> 0);
        assertFalse(first.node(1).has(Accessible.State.EXPANDABLE), "no facet: cannot open");
        assertNull(first.node(1).expand());
        assertTrue(first.node(2).has(Accessible.State.EXPANDABLE), "closed, and can open");
        assertFalse(first.node(2).has(Accessible.State.EXPANDED));
        assertTrue(first.node(3).has(Accessible.State.EXPANDABLE), "open, and can open");
        assertTrue(first.node(3).has(Accessible.State.EXPANDED));

        // The first title gains a facet: one STATE_CHANGED(EXPANDABLE) on it, from the diff.
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(owner, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.MENU_BAR);
        a.child(1);
        a.role(Accessible.Role.MENU_ITEM);
        a.bounds(0, 0, 50, 20);
        a.expand(false);
        a.endChild();
        a.child(2);
        a.role(Accessible.Role.MENU_ITEM);
        a.bounds(50, 0, 50, 20);
        a.expand(false);
        a.endChild();
        a.child(3);
        a.role(Accessible.Role.MENU_ITEM);
        a.bounds(100, 0, 50, 20);
        a.expand(true);
        a.endChild();
        a.end();
        assertTrue(a.changed());
        AccessibleTree second = publish(a, (kind, target) -> 0);
        List<AccessibleEvent> expandable = new ArrayList<>();
        for (AccessibleEvent event : a.events()) {
            if (event.type() == AccessibleEvent.Type.STATE_CHANGED) {
                assertEquals(Accessible.State.EXPANDABLE, event.state(),
                        "only the presence moved: " + a.events());
                expandable.add(event);
            }
        }
        assertEquals(1, expandable.size(), a.events().toString());
        assertEquals(second.node(1).id(), expandable.get(0).nodeId());
        assertEquals(Boolean.TRUE, expandable.get(0).newValue());
    }


    /**
     * {@code SELECTABLE} is the selection-item facet's presence (ADR 039 §1.2, amended
     * 2026-09-16; P5L-1), exactly as {@code EXPANDABLE} is the expand facet's: declaring that
     * you are one member of a selection is saying that you can be selected, selected or not, and
     * a widget can no more set it than it can set {@code SELECTED}.
     *
     * <p>It was published by <b>nothing</b> until this date. An uncut grep of both modules'
     * {@code main} found two hits in the whole tree, the enum constant and the AT-SPI bit it maps
     * to, so the state never left the model; no headless test could see it, because none asserted
     * a state nobody set. The live cost was measured on Fedora 44 and Ubuntu 24.04 on 2026-09-16:
     * after Ctrl+A, Orca resolved all five selected rows and discarded every one of them,
     * "believed to be layout only: … is not focusable, selectable, or expandable and lacks
     * explicit name", and said nothing.
     */
    @Test
    void selectableIsTheSelectionItemFacetsPresenceAndNeverAWidgetsToSet() {
        Accessibility a = new Accessibility();
        long owner = a.mint();
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(owner, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.LIST);
        a.selection(true, false);
        a.child(1);
        a.role(Accessible.Role.LIST_ITEM);
        a.bounds(0, 0, 100, 20);
        assertThrows(IllegalArgumentException.class,
                () -> a.state(Accessible.State.SELECTABLE), "derived, never set");
        a.endChild();
        a.child(2);
        a.role(Accessible.Role.LIST_ITEM);
        a.bounds(0, 20, 100, 20);
        a.selectionItem(false, 2, 3);
        a.endChild();
        a.child(3);
        a.role(Accessible.Role.LIST_ITEM);
        a.bounds(0, 40, 100, 20);
        a.selectionItem(true, 3, 3);
        a.endChild();
        a.child(4);
        a.role(Accessible.Role.RADIO_BUTTON);
        a.bounds(0, 60, 100, 20);
        a.containerlessSelectionItem(false, 1, 2);
        a.endChild();
        a.end();
        AccessibleTree first = publish(a, (kind, target) -> 0);
        assertFalse(first.node(1).has(Accessible.State.SELECTABLE),
                "no facet, so nothing to select: " + first.node(1).states());
        assertTrue(first.node(2).has(Accessible.State.SELECTABLE), "a member, unselected");
        assertFalse(first.node(2).has(Accessible.State.SELECTED));
        assertTrue(first.node(3).has(Accessible.State.SELECTABLE), "a member, selected");
        assertTrue(first.node(3).has(Accessible.State.SELECTED));
        assertTrue(first.node(4).has(Accessible.State.SELECTABLE),
                "a radio button belongs to a group that is no node, and is selectable all the same");

        // The first row gains the facet: one STATE_CHANGED(SELECTABLE) on it, from the diff.
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(owner, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.LIST);
        a.selection(true, false);
        a.child(1);
        a.role(Accessible.Role.LIST_ITEM);
        a.bounds(0, 0, 100, 20);
        a.selectionItem(false, 1, 3);
        a.endChild();
        a.child(2);
        a.role(Accessible.Role.LIST_ITEM);
        a.bounds(0, 20, 100, 20);
        a.selectionItem(false, 2, 3);
        a.endChild();
        a.child(3);
        a.role(Accessible.Role.LIST_ITEM);
        a.bounds(0, 40, 100, 20);
        a.selectionItem(true, 3, 3);
        a.endChild();
        a.child(4);
        a.role(Accessible.Role.RADIO_BUTTON);
        a.bounds(0, 60, 100, 20);
        a.containerlessSelectionItem(false, 1, 2);
        a.endChild();
        a.end();
        assertTrue(a.changed(), "a facet arrived, so the walk differs");
        AccessibleTree second = publish(a, (kind, target) -> 0);
        List<AccessibleEvent> selectable = new ArrayList<>();
        for (AccessibleEvent event : a.events()) {
            if (event.type() == AccessibleEvent.Type.STATE_CHANGED) {
                assertEquals(Accessible.State.SELECTABLE, event.state(),
                        "only the presence moved: " + a.events());
                selectable.add(event);
            }
        }
        assertEquals(1, selectable.size(), a.events().toString());
        assertEquals(second.node(1).id(), selectable.get(0).nodeId());
        assertEquals(Boolean.TRUE, selectable.get(0).newValue());
    }

    /**
     * Thirteen since 2026-09-14: {@code ADD_TO_SELECTION} joined between {@code SELECT} and
     * {@code DESELECT} (ADR 039 §1.5, amended; decision 10), so that "select" can mean what a click
     * means and the platforms' "add to selection" has a verb of its own.
     */
    @Test
    void thirteenVerbsArePublishableAndFourTakeAnArgument() {
        int parameterless = 0;
        for (Accessible.Action action : Accessible.Action.values()) {
            if (action.isParameterless()) {
                parameterless++;
            }
        }
        assertEquals(13, parameterless);
        assertEquals(17, Accessible.Action.values().length);
        assertTrue(Accessible.Action.ADD_TO_SELECTION.isParameterless());
        assertFalse(Accessible.Action.SET_VALUE.isParameterless());
        assertFalse(Accessible.Action.SET_SELECTION.isParameterless());
        ActionFacet multi = new ActionFacet(java.util.Set.of(Accessible.Action.SELECT,
                Accessible.Action.ADD_TO_SELECTION), null);
        assertTrue(multi.has(Accessible.Action.ADD_TO_SELECTION), "publishable like the rest");
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

    /**
     * A state a facet expresses, or one the publish step inherits down the walk, is refused by
     * the setter rather than dropped (ADR 039 §1.2, amended 2026-09-14): a dropped call is a dead
     * line a widget carries for months with no reader ever hearing it, which is what
     * {@code CalendarView}'s chooser did with {@code CHECKED} until this refusal named it.
     */
    @Test
    void aStateAFacetExpressesCannotBeSetBehindTheFacetsBack() {
        Accessibility a = new Accessibility();
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(a.mint(), AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        for (Accessible.State owned : List.of(Accessible.State.CHECKED, Accessible.State.MIXED,
                Accessible.State.EXPANDED, Accessible.State.SELECTED,
                Accessible.State.SELECTABLE, Accessible.State.READ_ONLY,
                Accessible.State.ENABLED, Accessible.State.VISIBLE, Accessible.State.SHOWING,
                Accessible.State.FOCUSABLE, Accessible.State.FOCUSED)) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> a.state(owned), owned + " is derived, never set");
            assertTrue(refused.getMessage().contains(owned.name()), refused.getMessage());
            assertThrows(IllegalArgumentException.class, () -> a.state(owned, false),
                    "clearing it is refused the same way");
        }
        assertTrue(a.declaresNothing(), "a refused state is not a declaration of its own");
        a.state(Accessible.State.BUSY);
        assertFalse(a.declaresNothing(), "a state that is a widget's own still lands");
        a.state(Accessible.State.BUSY, false);
        assertTrue(a.declaresNothing());
        a.toggle(ToggleFacet.State.ON);
        assertFalse(a.declaresNothing());
        AccessibleTree tree = publish(a, (kind, target) -> 0);
        assertTrue(tree.root().has(Accessible.State.CHECKED), "CHECKED derives from the facet");
        assertFalse(tree.root().has(Accessible.State.ENABLED),
                "ENABLED is the publish step's, never a widget's");
    }

    /**
     * The identity hook answers a child's key and host and nothing else (ADR 039 §1.5, amended
     * 2026-09-14). While it runs the node open in the builder is the <em>parent's</em>, so a role
     * or a name written from it would land on the parent silently; the builder refuses every
     * describe setter there, in the voice {@code key()} uses when called from the wrong hook,
     * and the two identity calls still go through.
     */
    @Test
    void theIdentityHookMayAnswerOnlyAKeyAndAHostAndNothingLandsOnTheParent() {
        Accessibility a = new Accessibility();
        a.beginWalk(100, 100, Locale.ENGLISH);
        long parent = a.mint();
        a.begin(parent, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.LIST);
        a.child(7);
        a.role(Accessible.Role.LIST_ITEM);
        a.endChild();

        a.beginChildIdentity();
        assertThrows(IllegalStateException.class, () -> a.role(Accessible.Role.BUTTON),
                "a role from the identity hook");
        assertThrows(IllegalStateException.class,
                () -> a.name(I18nString.literal("stray"), Accessible.NameFrom.CONTENT),
                "a name from the identity hook");
        assertThrows(IllegalStateException.class, () -> a.state(Accessible.State.ACTIVE),
                "a state from the identity hook");
        assertThrows(IllegalStateException.class, () -> a.action(Accessible.Action.PRESS),
                "a verb from the identity hook");
        assertThrows(IllegalStateException.class, () -> a.selectionItem(true, 1, 1),
                "a facet from the identity hook");
        a.key(3);
        a.under(7);
        assertTrue(a.hasPendingKey());
        assertEquals(3, a.pendingKey());
        assertTrue(a.hasPendingHost());
        assertEquals(1, a.pendingHostIndex(0));
        a.endChildIdentity();

        a.role(Accessible.Role.LIST);   // the parent's again, once the window is closed
        a.end();
        AccessibleTree tree = publish(a, (kind, target) -> 0);
        assertEquals(Accessible.Role.LIST, tree.root().role(), "nothing stray landed on the parent");
        assertEquals("", tree.root().name());
        assertNull(tree.root().actions());
        assertNull(tree.root().selectionItem());
    }

    /**
     * A container delegates a verb on a widget child from the describe-a-child hook and nowhere
     * else, and never one the child claimed for itself (ADR 039 §1.5, amended 2026-09-14;
     * decision 7). The delegated verb is published on the child like any other, marked as the
     * container's for the walk's routing table, and the free verbs stay the walk's: a container
     * that delegated {@code FOCUS} on a focusable child is refused when the publish step comes to
     * hand the node its free pair.
     */
    @Test
    void aDelegatedVerbIsPublishedOnTheChildAndAVerbBothClaimIsRefused() {
        Accessibility a = new Accessibility();
        a.beginWalk(100, 100, Locale.ENGLISH);
        long parent = a.mint();
        a.begin(parent, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.LIST);
        assertThrows(IllegalStateException.class, () -> a.delegate(Accessible.Action.SELECT),
                "a container's own hook is not where it speaks about a child");

        int child = a.begin(a.mint(), 0, Locale.ENGLISH, 0, 0, 100, 20);
        a.role(Accessible.Role.BUTTON);
        a.action(Accessible.Action.PRESS);
        assertThrows(IllegalStateException.class, () -> a.delegate(Accessible.Action.SELECT),
                "nor is the child's own hook");

        a.beginChildDescription();
        assertThrows(IllegalArgumentException.class, () -> a.delegate(Accessible.Action.SET_VALUE),
                "a setter is never in an action list, delegated or not");
        IllegalStateException both = assertThrows(IllegalStateException.class,
                () -> a.delegate(Accessible.Action.PRESS),
                "the child claimed PRESS in its own hook: one verb, two performers, refused");
        assertTrue(both.getMessage().contains("PRESS"), both.getMessage());
        a.delegate(Accessible.Action.SELECT);
        a.endChildDescription();
        assertEquals(1 << Accessible.Action.SELECT.ordinal(), a.delegatedVerbsAt(child),
                "the routing table says SELECT is the container's");
        a.freeVerbs();
        a.end();
        a.end();
        AccessibleTree tree = publish(a, (kind, target) -> 0);
        AccessibleNode row = tree.node(1);
        assertTrue(row.actions().has(Accessible.Action.SELECT),
                "published on the child, where the platform addresses it: " + row.actions());
        assertTrue(row.actions().has(Accessible.Action.PRESS), "the child keeps its own");
        assertTrue(row.actions().has(Accessible.Action.FOCUS), "and the walk's free pair");

        Accessibility b = new Accessibility();
        b.beginWalk(100, 100, Locale.ENGLISH);
        b.begin(b.mint(), AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        b.begin(b.mint(), 0, Locale.ENGLISH, 0, 0, 100, 20);
        b.role(Accessible.Role.BUTTON);
        b.beginChildDescription();
        b.delegate(Accessible.Action.FOCUS);
        b.endChildDescription();
        IllegalStateException free = assertThrows(IllegalStateException.class, b::freeVerbs,
                "FOCUS on a focusable widget is the scene's, and a container claimed it");
        assertTrue(free.getMessage().contains("FOCUS"), free.getMessage());
    }

    /**
     * {@code under(key)} names one of the owner's <em>direct</em> synthetic children, and the
     * lookup steps over each child's subtree rather than through it: a table asking for a row
     * visits its rows, not every cell of the rows before. A cell nested under an earlier row
     * that happens to carry the wanted key is not the row, and the row after a deep subtree is
     * still found.
     */
    @Test
    void aHostIsFoundAmongTheOwnersDirectSyntheticChildrenAcrossTheirSubtrees() {
        Accessibility a = new Accessibility();
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(a.mint(), AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.TABLE);
        int[] rowIndex = new int[3];
        for (int row = 0; row < 3; row++) {
            a.child(row);
            rowIndex[row] = a.nodeCount() - 1;
            a.role(Accessible.Role.ROW);
            for (int column = 0; column < 40; column++) {
                a.child(column);          // keys 0..39: row 1 and row 2 recur as cell keys here
                a.role(Accessible.Role.CELL);
                a.child(100);             // and one level deeper still
                a.role(Accessible.Role.LABEL);
                a.endChild();
                a.endChild();
            }
            a.endChild();
        }
        for (int row = 0; row < 3; row++) {
            a.beginChildIdentity();
            a.under(row);
            assertEquals(rowIndex[row], a.pendingHostIndex(0),
                    "row " + row + " is the owner's own child with that key");
            a.endChildIdentity();
        }
        a.beginChildIdentity();
        a.under(100);
        assertThrows(IllegalStateException.class, () -> a.pendingHostIndex(0),
                "a key only a nested child carries names no host");
        a.endChildIdentity();
        a.end();
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

        AccessibleTree tree = publish(a, (kind, target) -> 0);
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
        AccessibleTree first = publish(a, (kind, target) -> 0);
        assertEquals("Save", first.root().name());

        a.beginWalk(10, 10, Locale.ENGLISH);
        a.begin(id, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 10, 10);
        a.role(Accessible.Role.BUTTON);
        a.name(source);
        a.end();
        assertFalse(a.changed(), "nothing moved, so nothing differs");
        AccessibleTree second = publish(a, (kind, target) -> 0);
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
        a.inherited(true, true, true, true, true);
        a.child(7);
        a.role(Accessible.Role.MENU_ITEM);
        a.bounds(0, 0, 100, 20);
        a.selectionItem(true, 1, 3);
        a.state(Accessible.State.ACTIVE);
        a.endChild();
        a.end();
        a.resolveRelations((kind, target) -> 0);
        AccessibleTree tree = a.publish(ownerId, 0, 0, 1, true);
        assertEquals(2, tree.nodeCount());
        AccessibleNode row = tree.node(1);
        assertEquals(Accessible.Role.MENU_ITEM, row.role());
        assertNotNull(tree.root().selection());
        assertEquals(row.id(), tree.activeDescendant(),
                "the focused menu's cursor is the node below it that declared itself active");
        return row.id();
    }

    /**
     * Semantics 4 and decision 6 (ADR 039 §1.10, amended 2026-09-14): the cursor is the first
     * {@code ACTIVE} node below the <em>focused</em> node, through any number of containers,
     * and exactly one event names the focused node when it moves. A container nobody is in
     * publishes no cursor and announces nothing, however many active descendants it holds; a
     * newly focused node whose cursor differs from the last focused node's announces its own.
     */
    @Test
    void theCursorIsTheFocusedNodesAndOneEventNamesItWhenItMoves() {
        Accessibility a = new Accessibility();
        long layer = a.mint();
        long list = a.mint();
        long elsewhere = a.mint();

        // An unfocused list with an active row: no cursor, no event.
        AccessibleTree first = describeNestedContainers(a, layer, list, elsewhere, 0, 0);
        assertEquals(0, first.activeDescendant(), "nothing is focused, so nothing has a cursor");
        assertEquals(0, first.effectiveFocus());
        assertEquals(0, countOf(a, AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "an unfocused container announces no cursor: " + a.events());

        // The layer takes the focus: its cursor is resolved through the list it holds, and the
        // one event names the layer, not the list.
        AccessibleTree second = describeNestedContainers(a, layer, list, elsewhere, layer, 0);
        long rowOne = second.node(3).id();
        assertEquals(rowOne, second.activeDescendant(),
                "resolved through the nested container: " + describe(second));
        assertEquals(rowOne, second.effectiveFocus());
        List<AccessibleEvent> moved = eventsOf(a, AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED);
        assertEquals(1, moved.size(),
                "one event on the focused node, not one per container: " + a.events());
        assertEquals(layer, moved.get(0).nodeId());
        assertEquals(0L, moved.get(0).oldValue());
        assertEquals(rowOne, moved.get(0).newValue());

        // The cursor moves to the other row: one event again, carrying both.
        AccessibleTree third = describeNestedContainers(a, layer, list, elsewhere, layer, 1);
        long rowTwo = third.node(4).id();
        assertEquals(rowTwo, third.activeDescendant());
        moved = eventsOf(a, AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED);
        assertEquals(1, moved.size(), a.events().toString());
        assertEquals(layer, moved.get(0).nodeId());
        assertEquals(rowOne, moved.get(0).oldValue());
        assertEquals(rowTwo, moved.get(0).newValue());

        // The focus leaves for a widget outside the layer: the list's rows are still active in
        // the walk, but the focused node has nothing active below it, so the cursor is gone
        // and the event that says so names the newly focused node.
        AccessibleTree fourth = describeNestedContainers(a, layer, list, elsewhere, elsewhere, 1);
        assertEquals(0, fourth.activeDescendant(), describe(fourth));
        assertEquals(elsewhere, fourth.effectiveFocus(),
                "with no cursor the focused node itself is where the user is");
        moved = eventsOf(a, AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED);
        assertEquals(1, moved.size(), a.events().toString());
        assertEquals(elsewhere, moved.get(0).nodeId());
        assertEquals(rowTwo, moved.get(0).oldValue());
        assertEquals(0L, moved.get(0).newValue());

        // And nothing focused at all: no event, whatever is still active below the layer.
        AccessibleTree fifth = describeNestedContainers(a, layer, list, elsewhere, 0, 1);
        assertEquals(0, fifth.activeDescendant());
        assertEquals(0, countOf(a, AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "a scene with no focused node announces no cursor: " + a.events());
    }

    /**
     * A layer holding a list of two rows, one of them active, and a button beside the layer;
     * whichever of the three widget nodes {@code focused} names is published focused.
     */
    private static AccessibleTree describeNestedContainers(Accessibility a, long layer, long list,
                                                           long elsewhere, long focused,
                                                           int activeRow) {
        a.beginWalk(200, 200, Locale.ENGLISH);
        long root = a.mint();
        a.begin(root, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 200, 200);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(layer, 0, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.GROUP);
        a.selection(false, true);
        a.inherited(true, true, true, true, focused == layer);
        a.begin(list, 1, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.LIST);
        a.selection(false, true);
        a.inherited(true, true, true, false, false);
        for (int i = 0; i < 2; i++) {
            a.child(i);
            a.role(Accessible.Role.LIST_ITEM);
            a.bounds(0, i * 20, 100, 20);
            a.selectionItem(i == activeRow, i + 1, 2);
            if (i == activeRow) {
                a.state(Accessible.State.ACTIVE);
            }
            a.endChild();
        }
        a.end();
        a.end();
        a.begin(elsewhere, 0, Locale.ENGLISH, 100, 0, 40, 20);
        a.role(Accessible.Role.BUTTON);
        a.inherited(true, true, true, true, focused == elsewhere);
        a.end();
        a.end();
        a.resolveRelations((kind, target) -> 0);
        return a.publish(focused, 0, 0, 1, true);
    }

    /**
     * Semantics 1 and decision 9 (ADR 039 §1.10, amended 2026-09-14): a member's container is
     * the nearest ancestor with a {@code SelectionFacet}, climbed to through synthetic ancestors
     * only, and one {@code SELECTION_CHANGED} per container per publish carries the members that
     * entered and left — a member new in this publish and one that left the tree included
     * (MODEL-NEW-6). A member whose climb reaches a widget without the facet has no container
     * and raises nothing but its own state change.
     */
    @Test
    void aSelectionChangeNamesTheMembersContainerAndCarriesTheMembersThatMoved() {
        Accessibility a = new Accessibility();
        long grid = a.mint();
        long plain = a.mint();
        long loner = a.mint();

        AccessibleTree first = describeGrid(a, grid, plain, loner, 0, false, false);
        assertEquals(0, countOf(a, AccessibleEvent.Type.SELECTION_CHANGED),
                "a member that arrives selected under a container that is itself new is read "
                        + "on discovery, like the container's other facts: " + a.events());
        AccessibleNode dayOne = first.node(2);
        assertEquals(0, dayOne.selectionContainer(),
                "a day belongs to the grid, climbed to through the week row: " + describe(first));
        assertEquals(grid, first.node(dayOne.selectionContainer()).id());
        assertEquals(AccessibleNode.NONE, first.node(5).selectionContainer(),
                "a member under a widget that holds no selection has no container");
        assertEquals(AccessibleNode.NONE, first.node(1).selectionContainer(),
                "the week row is no member of anything");

        AccessibleTree second = describeGrid(a, grid, plain, loner, 1, true, false);
        List<AccessibleEvent> moved = eventsOf(a, AccessibleEvent.Type.SELECTION_CHANGED);
        assertEquals(1, moved.size(), "one per container: " + a.events());
        assertEquals(grid, moved.get(0).nodeId(), "on the grid, not the week row");
        assertEquals(List.of(second.node(3).id()), moved.get(0).addedMembers());
        assertEquals(List.of(second.node(2).id()), moved.get(0).removedMembers());
        assertFalse(moved.get(0).multiSelectable());
        assertTrue(a.events().stream().anyMatch(event ->
                        event.type() == AccessibleEvent.Type.STATE_CHANGED
                                && event.state() == Accessible.State.SELECTED
                                && event.nodeId() == second.node(5).id()),
                "the containerless member's own state change is the whole of its announcement: "
                        + a.events());

        // Day two leaves the tree selected and a third day arrives selected: both are the
        // grid's, and the removed one is named though it is gone.
        long dayTwo = second.node(3).id();
        AccessibleTree third = describeGrid(a, grid, plain, loner, 2, true, true);
        moved = eventsOf(a, AccessibleEvent.Type.SELECTION_CHANGED);
        assertEquals(1, moved.size(), a.events().toString());
        assertEquals(grid, moved.get(0).nodeId());
        assertEquals(List.of(third.node(2).id()), moved.get(0).addedMembers(),
                "the day that arrived selected: " + a.events());
        assertEquals(List.of(dayTwo), moved.get(0).removedMembers(),
                "the day that left the tree selected: " + a.events());
        assertTrue(moved.get(0).multiSelectable());
    }

    /**
     * A grid with a selection facet holding one synthetic week row, which holds one or two
     * synthetic days; beside it a plain widget without a facet holding one widget member.
     *
     * @param selectedDay which day is selected, from zero; {@code 2} selects a third day and
     *                    drops the second
     * @param lonerSelected whether the member under the plain widget is selected
     * @param multi         whether the grid selects a band
     */
    private static AccessibleTree describeGrid(Accessibility a, long grid, long plain, long loner,
                                               int selectedDay, boolean lonerSelected,
                                               boolean multi) {
        a.beginWalk(200, 200, Locale.ENGLISH);
        a.begin(grid, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 200, 200);
        a.role(Accessible.Role.TABLE);
        a.selection(multi, false);
        a.child(100);
        a.role(Accessible.Role.ROW);
        a.bounds(0, 0, 200, 20);
        for (int day = selectedDay == 2 ? 1 : 0; day < (selectedDay == 2 ? 3 : 2); day++) {
            if (selectedDay == 2 && day == 1) {
                continue; // day two is gone
            }
            a.child(day);
            a.role(Accessible.Role.CELL);
            a.bounds(day * 20, 0, 20, 20);
            a.selectionItem(day == selectedDay, day + 1, 3);
            a.endChild();
        }
        a.endChild();
        a.begin(plain, 0, Locale.ENGLISH, 0, 100, 200, 40);
        a.role(Accessible.Role.GROUP);
        a.begin(loner, 1 + (selectedDay == 2 ? 2 : 3), Locale.ENGLISH, 0, 100, 40, 20);
        a.role(Accessible.Role.LIST_ITEM);
        a.selectionItem(lonerSelected, 1, 1);
        a.end();
        a.end();
        a.end();
        a.resolveRelations((kind, target) -> 0);
        return a.publish(0, 0, 0, 1, true);
    }

    /**
     * MODEL-NEW-8 (ADR 039 §1.10, amended 2026-09-14): a subtree that appears is one added
     * child on its nearest surviving ancestor, never one event per node inside it; and the
     * first publish, whose every node is new under a root nobody published before, raises no
     * structure change at all — the window's opening is the bridge's own event.
     */
    @Test
    void aContainerAppearingWithChildrenIsOneStructureChangeOnItsSurvivingParent() {
        Accessibility a = new Accessibility();
        long root = a.mint();
        long box = a.mint();
        describeShape(a, root, box, "");
        assertEquals(0, countOf(a, AccessibleEvent.Type.STRUCTURE_CHANGED),
                "the first publish has no surviving parent to report to: " + a.events());

        AccessibleTree second = describeShape(a, root, box, "BCD");
        List<AccessibleEvent> moved = eventsOf(a, AccessibleEvent.Type.STRUCTURE_CHANGED);
        assertEquals(1, moved.size(),
                "one event on the root, not four (the box and its three children): " + a.events());
        assertEquals(root, moved.get(0).nodeId());
        assertEquals(List.of(new AccessibleEvent.Child(box, 0, 0)), moved.get(0).addedChildren(),
                "the box, at index zero, new in the tree: " + a.events());
        assertEquals(List.of(), moved.get(0).removedChildren());
        assertEquals(List.of(), moved.get(0).reorderedChildren());
        assertEquals(5, second.nodeCount());
    }

    /**
     * MODEL-NEW-4 (ADR 039 §1.10, amended 2026-09-14): a node that keeps its identifier and
     * moves raises a structure change — reordered among its siblings, with its index now; or
     * removed from one parent and added under another, each end naming the other — and a node
     * that leaves is removed from its surviving former parent with its former index, beside
     * its own {@code NODE_DESTROYED}.
     */
    @Test
    void aChildThatKeepsItsIdentifierAndMovesIsAStructureChangeOnTheParentsItMovedBetween() {
        Accessibility a = new Accessibility();
        long root = a.mint();
        long box = a.mint();
        long other = a.mint();
        AccessibleTree first = describeShape(a, root, box, "BCD", other, "");
        long b = first.node(2).id();
        long c = first.node(3).id();
        long d = first.node(4).id();

        // The same three children, C before B: two ranks moved, one stood.
        describeShape(a, root, box, "CBD", other, "");
        List<AccessibleEvent> moved = eventsOf(a, AccessibleEvent.Type.STRUCTURE_CHANGED);
        assertEquals(1, moved.size(), a.events().toString());
        assertEquals(box, moved.get(0).nodeId());
        assertEquals(List.of(), moved.get(0).addedChildren());
        assertEquals(List.of(), moved.get(0).removedChildren());
        assertEquals(List.of(new AccessibleEvent.Child(c, 0, 0), new AccessibleEvent.Child(b, 1, 0)),
                moved.get(0).reorderedChildren(), "C and B, at their indices now: " + a.events());

        // D leaves the tree: removed from the box at its former index, and destroyed.
        describeShape(a, root, box, "CB", other, "");
        moved = eventsOf(a, AccessibleEvent.Type.STRUCTURE_CHANGED);
        assertEquals(1, moved.size(), a.events().toString());
        assertEquals(box, moved.get(0).nodeId());
        assertEquals(List.of(new AccessibleEvent.Child(d, 2, 0)), moved.get(0).removedChildren());
        assertEquals(List.of(), moved.get(0).reorderedChildren(),
                "a removal moves no rank among the survivors: " + a.events());
        assertEquals(1, countOf(a, AccessibleEvent.Type.NODE_DESTROYED));

        // B moves from the box to the other container: the box lost it to the other, the other
        // gained it from the box, and neither treats the move as an insertion elsewhere.
        describeShape(a, root, box, "C", other, "B");
        moved = eventsOf(a, AccessibleEvent.Type.STRUCTURE_CHANGED);
        assertEquals(2, moved.size(), "one per parent: " + a.events());
        assertEquals(box, moved.get(0).nodeId());
        assertEquals(List.of(new AccessibleEvent.Child(b, 1, other)),
                moved.get(0).removedChildren(), "B left the box for the other: " + a.events());
        assertEquals(other, moved.get(1).nodeId());
        assertEquals(List.of(new AccessibleEvent.Child(b, 0, box)),
                moved.get(1).addedChildren(), "and arrived under the other from the box: " + a.events());
        assertEquals(0, countOf(a, AccessibleEvent.Type.NODE_DESTROYED),
                "a move destroys nothing: " + a.events());
    }

    private static AccessibleTree describeShape(Accessibility a, long root, long box,
                                                String children) {
        return describeShape(a, root, box, children, 0, "");
    }

    /**
     * A root holding a box with the synthetic children named by the letters of {@code children}
     * (keyed by letter, so a letter keeps its identifier across walks), and, when {@code other}
     * is not zero, a second box holding the letters of {@code otherChildren}. An empty first
     * string leaves the box out altogether.
     */
    private static AccessibleTree describeShape(Accessibility a, long root, long box,
                                                String children, long other,
                                                String otherChildren) {
        a.beginWalk(200, 200, Locale.ENGLISH);
        a.begin(root, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 200, 200);
        a.role(Accessible.Role.WINDOW);
        if (!children.isEmpty() || other != 0) {
            int boxIndex = a.begin(box, 0, Locale.ENGLISH, 0, 0, 100, 100);
            a.role(Accessible.Role.GROUP);
            describeLetters(a, boxIndex, children);
            a.end();
        }
        if (other != 0) {
            int otherIndex = a.begin(other, 0, Locale.ENGLISH, 100, 0, 100, 100);
            a.role(Accessible.Role.GROUP);
            describeLetters(a, otherIndex, otherChildren);
            a.end();
        }
        a.end();
        a.resolveRelations((kind, target) -> 0);
        return a.publish(0, 0, 0, 1, true);
    }

    /**
     * One widget child per letter under {@code parent}, identified by the letter alone through
     * the intern table's {@code (owner, key)} pair under a fixed owner, so a letter keeps its
     * identifier across walks and across boxes: interned under its box it would be a new node
     * under the other box, which is a different (and also correct) story from the move this
     * fixture tells.
     */
    private static void describeLetters(Accessibility a, int parent, String letters) {
        for (int i = 0; i < letters.length(); i++) {
            a.begin(a.identify(1, letters.charAt(i)), parent, Locale.ENGLISH, i * 20, 0, 20, 20);
            a.role(Accessible.Role.BUTTON);
            a.end();
        }
    }

    /**
     * Semantics 7 (ADR 039 §1.10, amended 2026-09-14): past the budget the per-node events
     * become one INVALIDATED, and the reserved tail follows it whole, in its order — the
     * per-parent structure changes, the final focus change, the single cursor change, the
     * per-container selection changes, the window's activation — because those are what a
     * reader is directed by and a bridge that swept would otherwise be left standing nowhere.
     */
    @Test
    void aPublishOverTheBudgetCollapsesToInvalidatedAndKeepsTheReservedTailInOrder() {
        Accessibility a = new Accessibility();
        long window = a.mint();
        long list = a.mint();
        long button = a.mint();
        describeWideDifference(a, window, list, button, false, 0, 0);
        assertEquals(0, countOf(a, AccessibleEvent.Type.INVALIDATED), a.events().toString());

        AccessibleTree second = describeWideDifference(a, window, list, button, true, 1, button);
        List<AccessibleEvent> events = a.events();
        assertEquals(AccessibleEvent.Type.INVALIDATED, events.get(0).type(),
                "the per-node events were past the budget: " + events.size());
        assertEquals(0, countOf(a, AccessibleEvent.Type.STATE_CHANGED),
                "and are gone with the collapse: " + events);
        List<AccessibleEvent.Type> tail = events.subList(1, events.size()).stream()
                .map(AccessibleEvent::type).toList();
        assertEquals(List.of(AccessibleEvent.Type.STRUCTURE_CHANGED,
                        AccessibleEvent.Type.FOCUS_CHANGED,
                        AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                        AccessibleEvent.Type.SELECTION_CHANGED,
                        AccessibleEvent.Type.WINDOW_ACTIVATED), tail,
                "the reserved tail, whole and in order: " + events);
        assertEquals(window, events.get(1).nodeId(), "the structure change is the window's");
        assertEquals(1, events.get(1).addedChildren().size(), "the button that arrived");
        assertEquals(button, events.get(2).nodeId(), "the focus arrived on the new button");
        assertEquals(button, events.get(3).nodeId(),
                "the cursor event names the focused node, with no cursor below it: " + events);
        assertEquals(0L, events.get(3).newValue());
        assertEquals(list, events.get(4).nodeId());
        assertEquals(window, events.get(5).nodeId(), "activation names the window node");
        assertTrue(second.root().has(Accessible.State.ACTIVE));
    }

    /**
     * A window over a list of three hundred rows, whose every row's HORIZONTAL bit is set or
     * not (a state change per row), the list's cursor on {@code selectedRow} — the list is
     * focused unless a {@code focused} button beside it arrives holding the focus — and the
     * window active or not.
     */
    private static AccessibleTree describeWideDifference(Accessibility a, long window, long list,
                                                         long button, boolean flipped,
                                                         int selectedRow, long focused) {
        a.beginWalk(400, 400, Locale.ENGLISH);
        a.begin(window, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 400);
        a.role(Accessible.Role.WINDOW);
        if (flipped) {
            a.state(Accessible.State.ACTIVE);
        }
        a.inherited(true, true, true, false, false);
        a.begin(list, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.LIST);
        a.selection(false, false);
        a.inherited(true, true, true, true, focused == 0);
        for (int i = 0; i < 300; i++) {
            a.child(i);
            a.role(Accessible.Role.LIST_ITEM);
            a.bounds(0, i, 400, 1);
            a.selectionItem(i == selectedRow, i + 1, 300);
            if (i == selectedRow && focused == 0) {
                a.state(Accessible.State.ACTIVE);
            }
            if (flipped) {
                a.state(Accessible.State.HORIZONTAL);
            }
            a.endChild();
        }
        a.end();
        if (focused == button) {
            a.begin(button, 0, Locale.ENGLISH, 0, 300, 40, 20);
            a.role(Accessible.Role.BUTTON);
            a.inherited(true, true, true, true, true);
            a.end();
        }
        a.end();
        a.resolveRelations((kind, target) -> 0);
        return a.publish(focused == 0 ? list : focused, 0, 0, 1, true);
    }

    private static long countOf(Accessibility a, AccessibleEvent.Type type) {
        return a.events().stream().filter(event -> event.type() == type).count();
    }

    private static List<AccessibleEvent> eventsOf(Accessibility a, AccessibleEvent.Type type) {
        return a.events().stream().filter(event -> event.type() == type).toList();
    }

    private static String describe(AccessibleTree tree) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tree.nodeCount(); i++) {
            out.append(i).append(": ").append(tree.node(i)).append(' ')
                    .append(tree.node(i).states()).append('\n');
        }
        return out.toString();
    }

    /**
     * A relation names a node published in <em>some</em> window of the process, or it is dropped:
     * a native popup's root names the field that opened it, which lives in another scene's tree,
     * and that is kept; a target the resolver finds nowhere is worse than none (ADR 039 §1.11).
     *
     * <p>What this pins of the builder is the shape of the answer: it stores whatever identifier
     * the resolver gives it verbatim, so a target published in another scene survives as that
     * scene's number, and a resolver that answers {@code 0} leaves no relation. <b>Which</b> of
     * the two a target gets is the walk's decision and not the builder's — {@code
     * AccessibleWalk.resolve} climbs into the other scene's last walk and answers {@code 0} when
     * the climb reaches nothing published anywhere; the stub resolver here stands in for it, and
     * {@code NativePopupRelationTest} in the demo drives the real one over two windows.
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
        AccessibleTree dropped = publish(popup, (kind, target) -> 0);
        assertTrue(dropped.root().relations().isEmpty(), "published nowhere: dropped");

        // The opener is a node of ANOTHER scene of the process: kept, and the identifier says
        // which scene's tree a bridge has to ask for it.
        Accessibility host = new Accessibility();
        host.beginWalk(10, 10, Locale.ENGLISH);
        long combo = host.mint();
        host.begin(combo, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 10, 10);
        host.role(Accessible.Role.COMBO_BOX);
        host.end();
        AccessibleTree hostTree = publish(host, (kind, target) -> 0);

        popup.beginWalk(10, 10, Locale.ENGLISH);
        popup.begin(popup.mint(), AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 10, 10);
        popup.role(Accessible.Role.MENU);
        popup.relation(Accessible.Relation.POPUP_FOR, opener);
        popup.end();
        AccessibleTree kept = publish(popup, (kind, target) -> target == opener ? combo : 0);
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
        AccessibleTree tree = publish(first, (kind, target) -> 0);
        assertEquals(first.sceneTag(), tree.sceneTag());
        assertTrue(tree.holds(root));
        assertFalse(tree.holds(second.mint()), "another scene's identifier is not this tree's");
        assertTrue(tree.holds(first.mint()),
                "holds() answers from the number alone, not from what this snapshot published; "
                        + "find() says whether the node is in it");
    }

    /**
     * MODEL-NEW-7: a walk with more keyed children than the intern table held used to evict the
     * pairs seen in the previous publish, which were exactly the pairs this walk had not reached
     * yet, so every frame minted fresh identifiers and every frame published. The table now drops
     * only what neither this walk nor the last one asked for, and grows when that is nothing.
     */
    @Test
    void aWalkWithMoreKeyedChildrenThanTheInternTableHoldsKeepsEveryIdentifier() {
        Accessibility a = new Accessibility();
        long ownerId = a.mint();
        long[] first = describeManyRows(a, ownerId, 5000);
        publish(a, (kind, target) -> 0);
        long[] second = describeManyRows(a, ownerId, 5000);
        assertFalse(a.changed(), "the same five thousand rows, the same identifiers");
        AccessibleTree tree = publish(a, (kind, target) -> 0);
        assertEquals(5001, tree.nodeCount());
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], second[i], "row " + i + " changed identifier");
        }
        for (AccessibleEvent event : a.events()) {
            assertTrue(event.type() != AccessibleEvent.Type.STRUCTURE_CHANGED,
                    "nothing is new on the second walk: " + a.events());
        }
    }

    /** One owner with {@code rows} synthetic children keyed 0..rows-1; returns their ids. */
    private static long[] describeManyRows(Accessibility a, long ownerId, int rows) {
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(ownerId, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.LIST);
        long[] ids = new long[rows];
        for (int i = 0; i < rows; i++) {
            a.child(i);
            a.role(Accessible.Role.LIST_ITEM);
            a.bounds(0, i, 100, 1);
            ids[i] = a.idAt(a.nodeCount() - 1);
            a.endChild();
        }
        a.end();
        return ids;
    }

    /**
     * CRIT-4's first half: a value whose text moved while its number stood is a value change. An
     * empty date segment publishes its minimum, so typing the minimum into it moved nothing a
     * number-only comparison could see, and a reader heard nothing.
     */
    @Test
    void aValueWhoseTextMovedWhileItsNumberStoodIsAValueChange() {
        Accessibility a = new Accessibility();
        long id = a.mint();
        describeSpinner(a, id, 7, "7", false);
        publish(a, (kind, target) -> 0);
        describeSpinner(a, id, 7, "07", false);
        assertTrue(a.changed(), "the text moved");
        publish(a, (kind, target) -> 0);
        List<AccessibleEvent> valueEvents = new ArrayList<>();
        for (AccessibleEvent event : a.events()) {
            if (event.type() == AccessibleEvent.Type.VALUE_CHANGED) {
                valueEvents.add(event);
            }
        }
        assertEquals(1, valueEvents.size(), "one value change, for the text: " + a.events());
        assertEquals(id, valueEvents.get(0).nodeId());
        assertEquals(7.0, valueEvents.get(0).newValue(), "the numbers ride along unchanged");
    }

    /**
     * A value can say it holds no number (decision 16): the range and the text stand, the number
     * published is the minimum for the platforms that must have one, and filling it in -- even
     * with that same minimum -- is a value change.
     */
    @Test
    void anEmptyValueKeepsItsRangeAndFillingItIsAValueChange() {
        Accessibility a = new Accessibility();
        long id = a.mint();
        describeSpinner(a, id, 1, "empty", true);
        AccessibleTree blank = publish(a, (kind, target) -> 0);
        ValueFacet facet = blank.root().value();
        assertNotNull(facet);
        assertTrue(facet.empty());
        assertEquals(1.0, facet.value(), "the minimum, for a platform that must have a number");
        assertEquals(1.0, facet.min());
        assertEquals(31.0, facet.max());
        assertEquals("empty", facet.text());
        assertEquals(new ValueFacet(1, 1, 31, 1, "empty", false, true), facet);
        assertFalse(new ValueFacet(1, 1, 31, 1, "empty", false).empty(),
                "the six-argument shape is a value that has a number");

        describeSpinner(a, id, 1, "1", false);
        assertTrue(a.changed(), "the same number, but now there is one");
        AccessibleTree filled = publish(a, (kind, target) -> 0);
        assertFalse(filled.root().value().empty());
        int valueChanges = 0;
        for (AccessibleEvent event : a.events()) {
            if (event.type() == AccessibleEvent.Type.VALUE_CHANGED) {
                valueChanges++;
            }
        }
        assertEquals(1, valueChanges, a.events().toString());
    }

    /** One SPIN_BUTTON over 1..31 with a value, its text, and whether it is empty. */
    private static void describeSpinner(Accessibility a, long id, double value, String text,
                                        boolean empty) {
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(id, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.SPIN_BUTTON);
        if (empty) {
            a.emptyValue(1, 31, 1, false);
        } else {
            a.value(value, 1, 31, 1);
        }
        a.valueText(text, text.hashCode());
        a.end();
    }

    /**
     * A synthetic child may be published not-ENABLED inside an enabled owner and never the other
     * way round (decision 30): {@code disabled()} narrows the inherited bit as {@code offScreen()}
     * narrows SHOWING, is cleared for every child, and is refused on a widget's own node.
     */
    /**
     * The narrowing reaches through the nesting (the phase-1 critic's finding, 2026-09-14): a
     * synthetic child declared inside a disabled synthetic child is no more enabled than that
     * parent, so a cell of a refused row is refused with it, whether or not it said so itself.
     */
    @Test
    void aSyntheticChildInsideADisabledOneIsDisabledWithIt() {
        Accessibility a = new Accessibility();
        long owner = a.mint();
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(owner, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.TABLE);
        a.child(1);
        a.role(Accessible.Role.ROW);
        a.bounds(0, 0, 100, 20);
        a.disabled();
        a.child(11);
        a.role(Accessible.Role.CELL);
        a.bounds(0, 0, 20, 20);
        a.child(111);
        a.role(Accessible.Role.LABEL);
        a.bounds(0, 0, 10, 20);
        a.endChild();
        a.endChild();
        a.endChild();
        a.child(2);
        a.role(Accessible.Role.ROW);
        a.bounds(0, 20, 100, 20);
        a.child(21);
        a.role(Accessible.Role.CELL);
        a.bounds(0, 0, 20, 20);
        a.endChild();
        a.endChild();
        a.end();
        for (int i = 1; i < a.nodeCount(); i++) {
            a.inheritedAt(i, true, true, true);
        }
        AccessibleTree tree = publish(a, (kind, target) -> 0);

        assertFalse(tree.node(1).has(Accessible.State.ENABLED), "the refused row");
        assertFalse(tree.node(2).has(Accessible.State.ENABLED),
                "its cell, which declared nothing, is no more enabled than the row it is in: "
                        + tree.node(2).states());
        assertFalse(tree.node(3).has(Accessible.State.ENABLED),
                "and so on down: " + tree.node(3).states());
        assertTrue(tree.node(4).has(Accessible.State.ENABLED), "the next row is untouched");
        assertTrue(tree.node(5).has(Accessible.State.ENABLED),
                "and so is its cell: " + tree.node(5).states());
        for (int i = 1; i <= 5; i++) {
            assertTrue(tree.node(i).has(Accessible.State.VISIBLE), "only the one bit narrows");
            assertTrue(tree.node(i).has(Accessible.State.SHOWING));
        }
    }

    @Test
    void aSyntheticChildMayBeDisabledAndIsNeverMoreEnabledThanItsOwner() {
        Accessibility a = new Accessibility();
        long owner = a.mint();
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(owner, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        assertThrows(IllegalStateException.class, a::disabled,
                "a widget's own enabled bit is the walk's");
        a.role(Accessible.Role.TABLE);
        a.child(1);
        a.role(Accessible.Role.CELL);
        a.bounds(0, 0, 20, 20);
        a.disabled();
        a.endChild();
        a.child(2);
        a.role(Accessible.Role.CELL);
        a.bounds(20, 0, 20, 20);
        a.action(Accessible.Action.SELECT);
        a.endChild();
        a.end();
        for (int i = 1; i < a.nodeCount(); i++) {
            a.inheritedAt(i, true, true, true);
        }
        AccessibleTree enabledOwner = publish(a, (kind, target) -> 0);
        assertFalse(enabledOwner.node(1).has(Accessible.State.ENABLED), "the refused day");
        assertTrue(enabledOwner.node(2).has(Accessible.State.ENABLED),
                "cleared for the next child: " + enabledOwner.node(2).states());
        assertTrue(enabledOwner.node(1).has(Accessible.State.VISIBLE), "only the one bit narrows");
        assertTrue(enabledOwner.node(1).has(Accessible.State.SHOWING));

        // The owner disabled: neither child is enabled, whatever it declared -- narrowing only.
        a.beginWalk(100, 100, Locale.ENGLISH);
        a.begin(owner, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 100, 100);
        a.role(Accessible.Role.TABLE);
        a.child(1);
        a.role(Accessible.Role.CELL);
        a.bounds(0, 0, 20, 20);
        a.endChild();
        a.child(2);
        a.role(Accessible.Role.CELL);
        a.bounds(20, 0, 20, 20);
        a.action(Accessible.Action.SELECT);
        a.endChild();
        a.end();
        for (int i = 1; i < a.nodeCount(); i++) {
            a.inheritedAt(i, false, true, true);
        }
        AccessibleTree disabledOwner = publish(a, (kind, target) -> 0);
        assertFalse(disabledOwner.node(1).has(Accessible.State.ENABLED));
        assertFalse(disabledOwner.node(2).has(Accessible.State.ENABLED));
        int enabledEvents = 0;
        for (AccessibleEvent event : a.events()) {
            if (event.type() == AccessibleEvent.Type.STATE_CHANGED
                    && event.state() == Accessible.State.ENABLED) {
                enabledEvents++;
                assertEquals(disabledOwner.node(2).id(), event.nodeId(),
                        "only the second child moved; the first was disabled both times");
            }
        }
        assertEquals(1, enabledEvents, a.events().toString());
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
