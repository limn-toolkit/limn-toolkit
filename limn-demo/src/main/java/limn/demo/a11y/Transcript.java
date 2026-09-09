package limn.demo.a11y;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ActionFacet;
import limn.accessibility.CellFacet;
import limn.accessibility.ExpandFacet;
import limn.accessibility.ScrollFacet;
import limn.accessibility.SelectionFacet;
import limn.accessibility.SelectionItemFacet;
import limn.accessibility.TableFacet;
import limn.accessibility.TextFacet;
import limn.accessibility.ToggleFacet;
import limn.accessibility.ValueFacet;
import limn.accessibility.WindowFacet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a screen reader would say about a published tree, written down: one line per node, in
 * reading order, indented by depth.
 *
 * <p>Reading order is tree order, which the tree defines as paint order, which is the order the
 * Tab key walks — never a sort by geometry. So the transcript walks the links and not the
 * rectangles, and it carries <b>no rectangle at all</b>: a box moves with every font, scale and
 * palette, and a reference answer that churned on each of those would be reviewed once and
 * never again.
 *
 * <p>Each line is the phrase a reader assembles: the role, the name in quotes with where it came
 * from when that is not the widget's own text, the description, the states a reader speaks — the
 * three every node carries are written only when <em>absent</em>, as {@code disabled},
 * {@code hidden} and {@code offscreen}, because that is when a reader mentions them — then each
 * facet in a compact form, the verbs offered, and the relations to other lines by number. A
 * control with an empty name shows as {@code ""}, which is the point: the transcript exists to
 * be read aloud by someone looking for exactly that.
 */
public final class Transcript {

    private Transcript() {
    }

    /**
     * @param tree a published tree
     * @return its transcript, one numbered line per node, ending in a newline
     */
    public static String of(AccessibleTree tree) {
        StringBuilder out = new StringBuilder();
        if (tree.nodeCount() == 0) {
            return out.append("(empty tree)\n").toString();
        }
        int width = String.valueOf(tree.nodeCount()).length();
        Locale treeLocale = tree.locale();
        List<Integer> order = new ArrayList<>();
        collect(tree, 0, order);
        int[] lineOf = new int[tree.nodeCount()];
        for (int line = 0; line < order.size(); line++) {
            lineOf[order.get(line)] = line + 1;
        }
        for (int line = 0; line < order.size(); line++) {
            int index = order.get(line);
            AccessibleNode node = tree.node(index);
            out.append(String.format("%" + width + "d ", line + 1));
            out.append("  ".repeat(depthOf(tree, index)));
            describe(tree, node, treeLocale, lineOf, out);
            out.append('\n');
        }
        return out.toString();
    }

    /** Depth-first through the links, which is tree order. */
    private static void collect(AccessibleTree tree, int index, List<Integer> order) {
        order.add(index);
        for (int child = tree.node(index).firstChild(); child != AccessibleNode.NONE;
             child = tree.node(child).nextSibling()) {
            collect(tree, child, order);
        }
    }

    private static int depthOf(AccessibleTree tree, int index) {
        int depth = 0;
        for (int parent = tree.node(index).parent(); parent != AccessibleNode.NONE;
             parent = tree.node(parent).parent()) {
            depth++;
        }
        return depth;
    }

    private static void describe(AccessibleTree tree, AccessibleNode node, Locale treeLocale,
                                 int[] lineOf, StringBuilder out) {
        out.append(word(node.role().name()));
        out.append(' ').append(quote(node.name()));
        if (node.nameFrom() != Accessible.NameFrom.CONTENT) {
            out.append(" (").append(word(node.nameFrom().name())).append(')');
        }
        if (node.description() != null && !node.description().isEmpty()) {
            out.append(" desc ").append(quote(node.description()));
        }
        if (node.locale() != null && !node.locale().equals(treeLocale)) {
            out.append(" lang ").append(node.locale().toLanguageTag());
        }
        states(node, out);
        facets(node, lineOf, tree, out);
        actions(node.actions(), out);
        relations(node.relations(), tree, lineOf, out);
    }

    private static void states(AccessibleNode node, StringBuilder out) {
        List<String> spoken = new ArrayList<>();
        if (!node.has(Accessible.State.ENABLED)) {
            spoken.add("disabled");
        }
        if (!node.has(Accessible.State.VISIBLE)) {
            spoken.add("hidden");
        }
        if (!node.has(Accessible.State.SHOWING)) {
            spoken.add("offscreen");
        }
        for (Accessible.State state : Accessible.State.values()) {
            if (state == Accessible.State.ENABLED || state == Accessible.State.VISIBLE
                    || state == Accessible.State.SHOWING) {
                continue;
            }
            if (node.has(state)) {
                spoken.add(word(state.name()).replace(' ', '-'));
            }
        }
        if (!spoken.isEmpty()) {
            out.append(" [").append(String.join(", ", spoken)).append(']');
        }
    }

    private static void facets(AccessibleNode node, int[] lineOf, AccessibleTree tree,
                               StringBuilder out) {
        ToggleFacet toggle = node.toggle();
        if (toggle != null) {
            out.append(" toggle=").append(toggle.state().name().toLowerCase(Locale.ROOT));
        }
        ValueFacet value = node.value();
        if (value != null) {
            out.append(" value=").append(number(value.value()))
                    .append(" [").append(number(value.min())).append("..")
                    .append(number(value.max()));
            if (value.step() != 0) {
                out.append(" step ").append(number(value.step()));
            }
            out.append(']');
            if (value.text() != null) {
                out.append(' ').append(quote(value.text()));
            }
            if (value.readOnly()) {
                out.append(" read-only");
            }
        }
        TextFacet text = node.text();
        if (text != null) {
            out.append(" text=").append(quote(text.text()))
                    .append(" caret=").append(text.caretOffset());
            if (text.hasSelection()) {
                out.append(" selection=").append(text.selectionStart())
                        .append("..").append(text.selectionEnd());
            }
            if (text.lineCount() > 1) {
                out.append(" lines=").append(text.lineCount());
            }
        }
        SelectionFacet selection = node.selection();
        if (selection != null) {
            out.append(" selection=").append(selection.multiSelectable() ? "multiple" : "single");
            if (selection.required()) {
                out.append(" required");
            }
            if (selection.activeDescendant() != 0) {
                out.append(" active=").append(reference(selection.activeDescendant(), tree, lineOf));
            }
        }
        SelectionItemFacet item = node.selectionItem();
        if (item != null) {
            out.append(" item ").append(item.positionInSet()).append(" of ").append(item.sizeOfSet())
                    .append(item.selected() ? " selected" : " unselected");
        }
        ExpandFacet expand = node.expand();
        if (expand != null) {
            out.append(expand.expanded() ? " expanded" : " collapsed");
        }
        ScrollFacet scroll = node.scroll();
        if (scroll != null) {
            out.append(" scroll");
            if (scroll.verticallyScrollable()) {
                out.append(" v=").append(percent(scroll.verticalPercent()))
                        .append(" view ").append(percent(scroll.verticalViewSize()));
            }
            if (scroll.horizontallyScrollable()) {
                out.append(" h=").append(percent(scroll.horizontalPercent()))
                        .append(" view ").append(percent(scroll.horizontalViewSize()));
            }
            if (!scroll.verticallyScrollable() && !scroll.horizontallyScrollable()) {
                out.append(" none");
            }
        }
        WindowFacet window = node.window();
        if (window != null) {
            out.append(" window ").append(window.modal() ? "modal" : "non-modal")
                    .append(' ').append(window.state().name().toLowerCase(Locale.ROOT));
        }
        TableFacet table = node.table();
        if (table != null) {
            out.append(" table ").append(table.rowCount()).append('x').append(table.columnCount());
        }
        CellFacet cell = node.cell();
        if (cell != null) {
            out.append(" cell ").append(cell.row() == -1 ? "header"
                    : cell.row() == -2 ? "footer" : String.valueOf(cell.row()))
                    .append(',').append(cell.column());
        }
    }

    private static void actions(ActionFacet actions, StringBuilder out) {
        if (actions == null || actions.actions().isEmpty()) {
            return;
        }
        List<String> verbs = new ArrayList<>();
        for (Accessible.Action action : Accessible.Action.values()) {
            if (actions.actions().contains(action)) {
                verbs.add(word(action.name()));
            }
        }
        out.append(" actions: ").append(String.join(", ", verbs));
        if (actions.keyBinding() != null && !actions.keyBinding().isEmpty()) {
            out.append(" key=").append(quote(actions.keyBinding()));
        }
    }

    private static void relations(List<AccessibleRelation> relations, AccessibleTree tree,
                                  int[] lineOf, StringBuilder out) {
        for (AccessibleRelation relation : relations) {
            out.append(" | ").append(word(relation.kind().name())).append(' ')
                    .append(reference(relation.target(), tree, lineOf));
        }
    }

    /** A line number and the target's name, so a relation reads without a lookup. */
    private static String reference(long id, AccessibleTree tree, int[] lineOf) {
        int index = tree.indexOf(id);
        if (index == AccessibleNode.NONE) {
            return "#? (not in this tree)";
        }
        return "#" + lineOf[index] + " " + quote(tree.node(index).name());
    }

    private static String word(String constant) {
        return constant.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String quote(String text) {
        if (text == null) {
            return "\"\"";
        }
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
    }

    private static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String percent(double fraction) {
        return Math.round(fraction * 100) + "%";
    }
}
