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
                    Node.of("thumbnails", Node.leaf("01.png"), Node.leaf("02.png"))));

    static Scene create() {
        Node docs = Node.of("Documents",
                Node.of("Reports", Node.leaf("2025.pdf"), Node.leaf("2026.pdf")),
                Node.leaf("notes.md"));
        Node media = Node.of("Media", Node.leaf("clip.mp4"), Node.leaf("cover.png"));
        Node remote = new Node("Remote", List.of());
        List<Node> roots = List.of(docs, media, remote);

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
            public Work<List<Node>> load(Node node) {
                List<Node> fetched = FETCHED.get(node.name());
                return Ui.work(progress -> {
                    // A beat, so the busy state is something a person can actually see.
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return fetched == null ? List.of() : fetched;
                });
            }

            @Override
            public Widget cellFor(Node node) {
                return new Label(node.name());
            }
        });
        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        tree.expand(docs);

        Column page = new Column();
        page.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(new Label("Tree").setRole(Label.Role.TITLE));
        page.add(new Label("Arrows walk it: Right opens a row and steps into it, Left closes one "
                + "and steps out. The command modifier adds a row to the selection.")
                .setMuted(true));
        page.add(new SizedBox(SizedBox.UNSET, 320, tree));
        Widget root = new Padding(Insets.all(24), page);
        Scene scene = new Scene(root);
        scene.setBackground(Theme.current().background);
        return scene;
    }
}
