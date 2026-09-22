package limn.demo;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.Shape;
import limn.demo.AccessibleGalleryTest.Harness;
import limn.demo.AccessibleGalleryTest.Palette;
import limn.demo.a11y.AccessibilityGallery;
import limn.demo.a11y.AccessibilityGallery.Entry;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every node every gallery entry publishes has a shape, and each role lands in the shapes ADR 045
 * says it does. The first is the ratchet the record asks for: a widget that publishes a
 * combination of role and facets that {@link Shape#of} cannot name fails here with the node and
 * the entry, and the answer is a decision about the shapes, not a new shape invented in the code.
 * The second pins the observed table, so that a change to the precedence, or a widget moving a
 * role into another shape, shows up as a diff of this list and is written down before it is
 * accepted.
 *
 * <p>One palette: the phase-0 dump showed every entry's tree byte-identical between light and
 * dark, and a shape reads no colour.
 */
class ShapeCoverageTest {

    /**
     * Which shapes each role was seen in across the gallery at the time this was pinned, and why
     * three roles appear in more than one. A {@code BUTTON} is a leaf until it owns a popup or
     * discloses content (the calendar's month title). A {@code ROW} is a rows member when it is
     * selectable (a table's) and a grid row when it is not (a calendar's week). A {@code GROUP}
     * is a surface, and a popup owner when it opens something (a date field, a date picker, a
     * context region); until decision 106 (2026-09-22) {@code PopupMenu}'s own panel was a rows
     * container too, carrying a second selection facet above the one its menu column publishes,
     * and the pin here read three shapes for the role. Every other role has exactly one shape. {@code CHECK_MENU_ITEM},
     * {@code RADIO_MENU_ITEM}, {@code TOGGLE_BUTTON} and {@code ALERT} are absent because no
     * gallery entry publishes them; {@code ShapeTest} classifies them bare.
     */
    private static final Map<Accessible.Role, EnumSet<Shape>> PINNED = pinned();

    private static Map<Accessible.Role, EnumSet<Shape>> pinned() {
        Map<Accessible.Role, EnumSet<Shape>> m = new EnumMap<>(Accessible.Role.class);
        m.put(Accessible.Role.WINDOW, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.DIALOG, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.GROUP, EnumSet.of(Shape.SURFACE, Shape.POPUP_OWNER));
        m.put(Accessible.Role.SCROLL_PANE, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.SCROLL_BAR, EnumSet.of(Shape.VALUE));
        m.put(Accessible.Role.SPLIT_PANE, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.SPLITTER, EnumSet.of(Shape.VALUE));
        m.put(Accessible.Role.TOOL_BAR, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.MENU_BAR, EnumSet.of(Shape.MENU));
        m.put(Accessible.Role.MENU, EnumSet.of(Shape.MENU));
        m.put(Accessible.Role.MENU_ITEM, EnumSet.of(Shape.MENU));
        m.put(Accessible.Role.SEPARATOR, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.BUTTON, EnumSet.of(Shape.LEAF_ACTION, Shape.POPUP_OWNER));
        m.put(Accessible.Role.CHECK_BOX, EnumSet.of(Shape.TOGGLE));
        m.put(Accessible.Role.SWITCH, EnumSet.of(Shape.TOGGLE));
        m.put(Accessible.Role.RADIO_BUTTON, EnumSet.of(Shape.ROWS));
        m.put(Accessible.Role.RADIO_GROUP, EnumSet.of(Shape.ROWS));
        m.put(Accessible.Role.LABEL, EnumSet.of(Shape.STATIC));
        m.put(Accessible.Role.HEADING, EnumSet.of(Shape.STATIC));
        m.put(Accessible.Role.IMAGE, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.VIDEO, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.CANVAS, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.CHART, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.CHART_SERIES, EnumSet.of(Shape.TOGGLE));
        m.put(Accessible.Role.PROGRESS_BAR, EnumSet.of(Shape.VALUE));
        m.put(Accessible.Role.SLIDER, EnumSet.of(Shape.VALUE));
        m.put(Accessible.Role.SPIN_BUTTON, EnumSet.of(Shape.VALUE));
        m.put(Accessible.Role.TEXT_FIELD, EnumSet.of(Shape.TEXT));
        m.put(Accessible.Role.TEXT_AREA, EnumSet.of(Shape.TEXT));
        m.put(Accessible.Role.PASSWORD_FIELD, EnumSet.of(Shape.TEXT));
        m.put(Accessible.Role.SEARCH_FIELD, EnumSet.of(Shape.TEXT));
        m.put(Accessible.Role.COMBO_BOX, EnumSet.of(Shape.POPUP_OWNER));
        m.put(Accessible.Role.LIST, EnumSet.of(Shape.ROWS));
        m.put(Accessible.Role.LIST_ITEM, EnumSet.of(Shape.ROWS));
        m.put(Accessible.Role.TAB_LIST, EnumSet.of(Shape.ROWS));
        m.put(Accessible.Role.TAB, EnumSet.of(Shape.ROWS));
        m.put(Accessible.Role.TAB_PANEL, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.COLOR_CHOOSER, EnumSet.of(Shape.SURFACE));
        m.put(Accessible.Role.TABLE, EnumSet.of(Shape.GRID));
        m.put(Accessible.Role.COLUMN_HEADER, EnumSet.of(Shape.GRID));
        m.put(Accessible.Role.ROW, EnumSet.of(Shape.ROWS, Shape.GRID));
        m.put(Accessible.Role.CELL, EnumSet.of(Shape.GRID));
        m.put(Accessible.Role.TREE, EnumSet.of(Shape.ROWS));
        m.put(Accessible.Role.TREE_ITEM, EnumSet.of(Shape.ROWS));
        return m;
    }

    @Test
    void everyNodeOfEveryEntryHasAShape() {
        List<String> unclassified = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            try (Harness harness = new Harness(Palette.LIGHT)) {
                for (HeadlessWindow window : harness.show(entry)) {
                    AccessibleTree tree = window.bridge().tree();
                    for (int i = 0; i < tree.nodeCount(); i++) {
                        AccessibleNode node = tree.node(i);
                        if (Shape.of(node) == Shape.UNCLASSIFIED) {
                            unclassified.add(entry.name() + " / window \"" + window.title()
                                    + "\" / node " + i + " " + node.role() + " \"" + node.name()
                                    + "\" " + node.states());
                        }
                    }
                }
            }
        }
        assertTrue(unclassified.isEmpty(), "these published nodes have no shape; a shape is a "
                + "decision (ADR 045 §1), not something to add here:\n  "
                + String.join("\n  ", unclassified));
    }

    @Test
    void eachRoleLandsInTheShapesTheRecordSays() {
        Map<Accessible.Role, EnumSet<Shape>> seen = new EnumMap<>(Accessible.Role.class);
        Map<String, String> firstExample = new TreeMap<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            try (Harness harness = new Harness(Palette.LIGHT)) {
                for (HeadlessWindow window : harness.show(entry)) {
                    AccessibleTree tree = window.bridge().tree();
                    for (int i = 0; i < tree.nodeCount(); i++) {
                        AccessibleNode node = tree.node(i);
                        Shape shape = Shape.of(node);
                        seen.computeIfAbsent(node.role(), r -> EnumSet.noneOf(Shape.class))
                                .add(shape);
                        firstExample.putIfAbsent(node.role() + " -> " + shape,
                                entry.name() + ": " + node.role() + " \"" + node.name() + "\" "
                                        + node.states());
                    }
                }
            }
        }
        StringBuilder table = new StringBuilder();
        firstExample.forEach((pair, example) ->
                table.append("  ").append(pair).append("   e.g. ").append(example).append('\n'));
        assertEquals(PINNED, seen, "the shapes the gallery's roles land in moved; if a decision "
                + "moved them, pin the new table, and if not, the precedence or a widget did:\n"
                + table);
    }
}
