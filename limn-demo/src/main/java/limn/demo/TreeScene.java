package limn.demo;

import limn.components.Label;
import limn.components.Theme;
import limn.components.tree.Tree;
import limn.concurrent.Ui;
import limn.concurrent.Work;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;

import java.util.List;
import java.util.Map;

/**
 * The tree, with the three things a still cannot show: a row that opens, a row that opens onto
 * children it has to fetch first, and a cursor that walks the outline with the arrow keys.
 *
 * <p>Run with {@code --scene tree}. The right-hand branch is the deliberate one: "Remote" answers
 * {@code null} to {@code children} and hands back a {@link Work} that takes a beat, so pressing
 * its triangle shows what a tree over a disk or a network actually does — the row opens before
 * it can name what is inside it.
 */
final class TreeScene {

    private TreeScene() {
    }

    /** A node of the demo forest: a name and the children it admits to. */
    private record Node(String name, List<Node> kids) {
        static Node leaf(String name) {
            return new Node(name, List.of());
        }

        static Node of(String name, Node... kids) {
            return new Node(name, List.of(kids));
        }
    }

    /** The names whose children this model refuses to answer until it has fetched them. */
    private static final Map<String, List<Node>> FETCHED = Map.of(
            "Remote", List.of(Node.leaf("index.json"), Node.leaf("manifest.json"),
                    Node.of("thumbnails", Node.leaf("01.png"), Node.leaf("02.png"))),
            // A row that has to be read and turns out to hold nothing: it stays an open branch
            // with the tree's "Empty" line under it (decision 45).
            "Trash", List.of());

    /** A folder the model calls a branch whatever it holds: empty, it opens onto the same line. */
    private static final String EMPTY_FOLDER = "Empty folder";

    /** A scene and what to run once it has been laid out; the capture shape every variant uses. */
    record Built(Scene scene, Runnable afterLayout) {
    }

    /** The scene and the tree inside it, so a capture variant can drive the widget. */
    private record Parts(Scene scene, Tree<Node> tree, Node deep, Node remote, Node trash,
                         Node emptyFolder, Node documents, Node reports, Node pdf) {
    }

    /**
     * A render variant asked for through {@code LIMN_TREE_DEMO}, so a reviewer can reproduce a
     * still without a scene name per state: {@code empty} opens the two rows that hold nothing,
     * and {@code walk} walks the deep scene down its chain with the keyboard.
     */
    private static String variant() {
        String value = System.getenv("LIMN_TREE_DEMO");
        return value == null ? "" : value.trim();
    }

    static Scene create() {
        return parts().scene();
    }

    /**
     * The same tree, scrolled once it has a height.
     *
     * <p>A capture moves no pointer, and an overlay scroll bar reveals itself for activity and
     * fades; scrolling after the first layout is what puts one in a still, and it is the only
     * honest way to photograph the bar this widget actually has.
     */
    static Built scrolled() {
        Parts parts = parts();
        Tree<Node> tree = parts.tree();
        return switch (variant()) {
            case "focus" -> new Built(parts.scene(), () -> {
                // The keyboard in the tree, the cursor and the selection on "Reports".
                tree.requestFocus();
                tree.setSelected(parts.reports());
            });
            case "multi" -> new Built(parts.scene(), () -> {
                // MULTI with the cursor outside the selection: "Documents" and "2026.pdf"
                // selected, then Space toggles "2026.pdf" off and the cursor stays on it.
                tree.requestFocus();
                tree.setSelectedNodes(List.of(parts.documents(), parts.pdf()));
                parts.scene().keyEvent(limn.input.Keys.SPACE, true, false, 0);
                parts.scene().keyEvent(limn.input.Keys.SPACE, false, false, 0);
                parts.scene().inputBatchEnded();
            });
            case "none" -> new Built(parts.scene(), () -> {
                // NONE: nothing is ever selected, and the cursor walks: three Downs from nowhere
                // land on "Q3 regional revenue…".
                tree.setSelectionMode(Tree.SelectionMode.NONE);
                tree.requestFocus();
                for (int i = 0; i < 3; i++) {
                    parts.scene().keyEvent(limn.input.Keys.DOWN, true, false, 0);
                    parts.scene().keyEvent(limn.input.Keys.DOWN, false, false, 0);
                    parts.scene().inputBatchEnded();
                }
            });
            default -> new Built(parts.scene(), () -> tree.scrollBy(120));
        };
    }

    /**
     * The same tree with the keyboard focus in it, for a screen reader to listen to.
     *
     * <p>A reader speaks what happens to the focus, and nothing injected from outside reaches a
     * Wayland session the same way it reaches the other two desktops, so the demo drives its own
     * arrows through the scene's key path — the path a person's keys take — and every platform
     * hears the same sequence. {@code Main} schedules the steps; this only puts the focus there.
     */
    static Built reader() {
        Parts parts = parts();
        return new Built(parts.scene(), () -> parts.tree().requestFocus());
    }

    /**
     * The same tree with its bars in strips of their own, shown always.
     *
     * <p>The rows here end in a count or a button against the trailing edge, which is what an
     * overlay thumb covers and the reason {@code setBarLayout} exists. ALWAYS rather than the
     * default so a still shows the strip without a pointer to reveal it; the strips key on
     * overflow, not on visibility, so the geometry is the default policy's too.
     */
    static Scene reserved() {
        Parts parts = parts();
        parts.tree().setBarLayout(limn.components.ScrollGutters.Layout.RESERVED)
                .setScrollbarPolicy(limn.components.ScrollBar.Policy.ALWAYS);
        return parts.scene();
    }

    /**
     * The same tree with one branch open all the way down, which is what makes the outline wider
     * than its box: every level charges an indent and nothing gives it back.
     *
     * <p>Its own scene rather than a deeper fixture for {@code --scene tree}, because the two
     * cannot be photographed together: widening the content moves where a cell ellipsizes, from
     * the edge of the box to the edge of the content (ADR 044 §1, amended). The shallow scene is
     * where a name contains itself; this one is where depth runs out of width.
     *
     * <p>With {@code LIMN_TREE_DEMO=walk} the keyboard goes into the tree instead of the scroll:
     * the cursor is put on level one and Down is pressed thirteen times, so it lands on level
     * fourteen and the outline has moved sideways, row by row, by the least that shows each row's
     * triangle and the start of its name (TREE-NEW-5).
     */
    static Built deep() {
        Parts parts = parts();
        for (Node node = parts.deep(); node != null;
                node = node.kids().isEmpty() ? null : node.kids().get(0)) {
            parts.tree().expand(node);
        }
        if (variant().equals("walk")) {
            return new Built(parts.scene(), () -> {
                parts.tree().requestFocus();
                parts.tree().setSelected(parts.deep());
                for (int i = 0; i < 13; i++) {
                    parts.scene().keyEvent(limn.input.Keys.DOWN, true, false, 0);
                    parts.scene().keyEvent(limn.input.Keys.DOWN, false, false, 0);
                    parts.scene().inputBatchEnded();
                }
            });
        }
        return new Built(parts.scene(), () -> parts.tree().scrollHorizontallyBy(140));
    }

    /**
     * The same tree with "Remote" opened once it has a height, and photographed before its load
     * lands: the one moment a tree over a disk or a network owes the reader a busy row. With
     * {@code LIMN_TREE_DEMO=empty} it is "Trash" and "Empty folder" that open instead — the row
     * whose load finds nothing and the branch that never had anything — photographed once the
     * load has landed (run with {@code -Dlimn.demo.treeLoadMillis=1}).
     */
    static Built loading() {
        Parts parts = parts();
        if (variant().equals("empty")) {
            return new Built(parts.scene(), () -> {
                parts.tree().expand(parts.trash());
                parts.tree().expand(parts.emptyFolder());
            });
        }
        return new Built(parts.scene(), () -> parts.tree().expand(parts.remote()));
    }

    private static Parts parts() {
        // Long names on purpose: a row's cell is measured at the width the indent leaves, so
        // this is where a Label either contains itself or writes over the badge beside it.
        Node docs = Node.of("Documents",
                Node.of("Reports",
                        Node.leaf("Q3 regional revenue and headcount, consolidated (final).pdf"),
                        Node.leaf("2026.pdf")),
                Node.leaf("meeting notes from the Tuesday planning session.md"));
        Node media = Node.of("Media",
                Node.leaf("clip.mp4"),
                Node.leaf("cover artwork, 4000 by 4000, before the crop.png"));
        Node remote = new Node("Remote", List.of());
        Node trash = new Node("Trash", List.of());
        Node emptyFolder = new Node(EMPTY_FOLDER, List.of());
        // A chain and not a bush: fourteen levels of one child each is the shape that runs out of
        // width without running out of rows, which is the case horizontal scrolling exists for.
        // Left closed in `--scene tree`, so that scene's content stays exactly its box.
        Node deep = Node.leaf("level-14");
        for (int i = 13; i >= 1; i--) {
            deep = Node.of("level-" + String.format("%02d", i), deep);
        }
        // The two rows that hold nothing come after the chain, so the reader recipe's arrows
        // (Main's `tree-reader` steps) land on the rows they always did.
        List<Node> roots = new java.util.ArrayList<>(
                List.of(docs, media, remote, deep, trash, emptyFolder));
        // Enough rows that the outline is taller than its box: a bar with nothing to scroll
        // does not draw, so a short fixture photographs as "no scroll bar" and proves nothing.
        for (int i = 1; i <= 24; i++) {
            roots.add(Node.leaf("archive-" + String.format("%02d", i) + ".zip"));
        }

        Tree<Node> tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return roots;
            }

            @Override
            public List<Node> children(Node node) {
                // A node the model has to fetch says so by answering null, which is also what
                // keeps its triangle: a directory nobody has read is not a file.
                return FETCHED.containsKey(node.name()) ? null : node.kids();
            }

            @Override
            public boolean isLeaf(Node node) {
                // A folder is a branch whatever it holds, as a file system's is: empty, it
                // opens onto the tree's "Empty" line rather than losing its triangle.
                return !node.name().equals(EMPTY_FOLDER) && Tree.Model.super.isLeaf(node);
            }

            @Override
            public Work<List<Node>> load(Node node) {
                List<Node> fetched = FETCHED.get(node.name());
                return Ui.work(progress -> {
                    // A beat, so the busy state is something a person can actually see. A reader
                    // run lengthens it with -Dlimn.demo.treeLoadMillis, because a client that
                    // walks the outline takes longer than the beat to reach the row.
                    try {
                        Thread.sleep(Long.getLong("limn.demo.treeLoadMillis", 600));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return fetched == null ? List.of() : fetched;
                });
            }

            @Override
            public float maxCellWidth() {
                // This model knows its cells: an icon, a name and a count or a button. Declared,
                // the deepest open row keeps this much width for them rather than the tree's
                // guess of a menu's minimum, 168 points here (decision 50).
                return 240;
            }

            @Override
            public Widget cellFor(Node node) {
                // The cell is the application's, and it is an ordinary Row: the tree reserves
                // the indent and the triangle and hands the rest of the width to this, so an
                // Expanded in the middle puts the count against the trailing edge.
                Label text = new Label(node.name());
                text.setIcon(limn.graphics.SvgIcon.fromResource(
                        node.kids().isEmpty() && !FETCHED.containsKey(node.name())
                                && !node.name().equals(EMPTY_FOLDER)
                                ? "/limn/components/icons/info.svg"
                                : "/limn/components/icons/settings.svg"));
                limn.scene.layout.Row row = new limn.scene.layout.Row();
                row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
                row.add(limn.scene.layout.Expanded.of(text));
                if (!node.kids().isEmpty()) {
                    // A badge: a count the row carries, against the trailing edge.
                    row.add(new Label(String.valueOf(node.kids().size())).setMuted(true));
                } else if (node.name().endsWith(".pdf")) {
                    // And a real control beside it, because a row is a widget and not a string:
                    // the button takes its own press, and the tree selects only what the cell
                    // lets through.
                    limn.components.Button open =
                            new limn.components.Button("Open").setSecondary(true);
                    // Not chained: setControlSize is Widget's and returns void, so a chain
                    // would hand `add` a void expression.
                    open.setControlSize(limn.scene.ControlSize.SMALL);
                    row.add(open);
                }
                return row;
            }
        });
        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        tree.expand(docs);
        // Reports too: its children are the long names carrying a button, and a collapsed
        // branch hides exactly the row this scene exists to show.
        tree.expand(docs.kids().get(0));

        Column page = new Column();
        page.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(new Label("Tree").setRole(Label.Role.TITLE));
        page.add(new Label("Arrows walk it: Right opens a row and steps into it, Left closes one "
                + "and steps out. The command modifier adds a row to the selection.")
                .setMuted(true));
        // A narrow box on purpose: a cell is measured at the width the indent leaves it, so
        // this is the width at which a long name either contains itself or writes over the
        // control beside it. The Row holds the box to its own width inside a column that
        // stretches, and puts it against whichever edge leads.
        limn.scene.layout.Row treeRow = new limn.scene.layout.Row();
        treeRow.add(new SizedBox(360, 320, tree));
        page.add(treeRow);
        Widget root = new Padding(Insets.all(24), page);
        Scene scene = new Scene(root);
        scene.setBackground(Theme.current().background);
        return new Parts(scene, tree, deep, remote, trash, emptyFolder, docs,
                docs.kids().get(0), docs.kids().get(0).kids().get(1));
    }
}
