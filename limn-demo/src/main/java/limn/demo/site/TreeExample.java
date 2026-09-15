package limn.demo.site;

import limn.components.Label;
import limn.components.tree.Tree;
import limn.concurrent.Ui;
import limn.concurrent.Work;
import limn.scene.Insets;
import limn.scene.Widget;
import limn.scene.layout.Padding;

import java.util.List;

/**
 * The guide's tree example, compiled: the page shows this file's marked region, so what a reader
 * copies is code this repository builds.
 *
 * <p>It is written around a file system on purpose, because that is where the two decisions a
 * tree makes are visible: a folder is not a leaf even before anybody has read it, and reading it
 * is what opening the row does.
 */
public final class TreeExample {

    private TreeExample() {
    }

    /**
     * A directory entry: its path, its children when they are known, and whether it can have
     * any.
     *
     * <p>The path and not only the name, because a tree identifies a node by {@code equals} and
     * refuses the same node in two places: every folder read off a disk has a {@code classes}
     * in it, and two records that read as {@code classes} would be one node to the tree. The
     * path makes each its own.
     */
    public record Entry(String path, boolean folder, List<Entry> children) {

        /** A file: never a leaf by accident, but by declaration. */
        public static Entry file(String name) {
            return new Entry(name, false, List.of());
        }

        /** A folder whose children are already known, each re-rooted under it. */
        public static Entry folder(String name, Entry... children) {
            List<Entry> under = new java.util.ArrayList<>(children.length);
            for (Entry child : children) {
                under.add(child.under(name));
            }
            return new Entry(name, true, List.copyOf(under));
        }

        /** A folder nobody has read yet: it has children, and the tree will have to fetch them. */
        public static Entry unread(String name) {
            return new Entry(name, true, null);
        }

        /** The last segment of the path: what a row shows. */
        public String name() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }

        /** This entry, and everything under it, placed under {@code parentPath}. */
        public Entry under(String parentPath) {
            List<Entry> moved = children == null ? null : children.stream()
                    .map(child -> child.under(parentPath)).toList();
            return new Entry(parentPath + "/" + path, folder, moved);
        }
    }

    /** The sample tree the guide shows. */
    public static Tree<Entry> tree(Label status) {
        List<Entry> roots = List.of(
                Entry.folder("src",
                        Entry.folder("main", Entry.file("App.java"), Entry.file("Window.java")),
                        Entry.folder("test", Entry.file("AppTest.java"))),
                Entry.unread("build"),
                Entry.file("README.md"));
        return tree(roots, status);
    }

    // #region guide:tree
    /**
     * A tree over the application's own objects: roots, a children provider, and a cell widget.
     *
     * @param roots  the top-level entries
     * @param status a label the selection is written into
     */
    public static Tree<Entry> tree(List<Entry> roots, Label status) {
        Tree<Entry> tree = new Tree<>(new Tree.Model<Entry>() {
            @Override
            public List<Entry> roots() {
                return roots;
            }

            @Override
            public List<Entry> children(Entry entry) {
                // null means "not known yet", which keeps the triangle and sends the tree to
                // load() when the row is opened. An empty list is a leaf.
                return entry.children();
            }

            @Override
            public boolean isLeaf(Entry entry) {
                return !entry.folder();
            }

            @Override
            public Work<List<Entry>> load(Entry entry) {
                // Runs off the UI thread and lands on it; collapsing the row cancels it.
                return Ui.work(progress -> readDirectory(entry));
            }

            @Override
            public Widget cellFor(Entry entry) {
                return new Padding(Insets.symmetric(6, 4), new Label(entry.name()));
            }
        });
        tree.setSelectionMode(Tree.SelectionMode.MULTI);
        // The selection is a set, read back from the tree: the handler is told that it moved,
        // not which row, because in MULTI "which row" is not one answer.
        tree.onSelect(() -> {
            List<Entry> chosen = tree.selectedNodes();
            status.setText(chosen.isEmpty() ? "Nothing selected"
                    : chosen.size() + " selected, last " + tree.leadNode().name());
        });
        tree.onExpand(entry -> status.setText("Opened " + entry.name()));
        return tree;
    }
    // #endregion

    /** Stands in for the directory read the guide's text describes: entries under the folder. */
    private static List<Entry> readDirectory(Entry entry) {
        return List.of(Entry.file("classes").under(entry.path()),
                Entry.file("reports").under(entry.path()));
    }
}
