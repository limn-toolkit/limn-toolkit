package limn.demo;

import limn.components.SelectionMode;

import limn.components.Button;
import limn.components.Label;
import limn.components.Theme;
import limn.components.tree.Tree;
import limn.concurrent.Ui;
import limn.concurrent.Work;
import limn.i18n.I18nString;
import limn.icons.tabler.TablerDocument;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static limn.testing.SceneDriver.drive;

/**
 * The tree, with the three things a still cannot show: a row that opens, a row that opens onto
 * children it has to fetch first, and a cursor that walks the outline with the arrow keys.
 *
 * <p>The Showcase shows it as a file explorer ({@link Explorer}); the scenes here are the
 * captures', with a fixture of their own built to photograph widths, depth and loading.
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

    /**
     * The page, its scene and the tree inside it, so a capture variant can drive the widget.
     * The scene is null when the page is built to sit inside another window's tab.
     */
    private record Parts(Widget<?> page, Scene scene, Tree<Node> tree, Node deep, Node remote,
                         Node trash, Node emptyFolder, Node documents, Node reports, Node pdf) {
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
     * The Showcase's Trees page: a file explorer, the tree on one side and what is selected in it
     * on the other. The scenes below are the captures', and keep their own fixture.
     */
    static Widget<?> content() {
        return Explorer.page();
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
                drive(parts.scene()).keyEvent(limn.input.Keys.SPACE, true, false, 0);
                drive(parts.scene()).keyEvent(limn.input.Keys.SPACE, false, false, 0);
                drive(parts.scene()).inputBatchEnded();
            });
            case "none" -> new Built(parts.scene(), () -> {
                // NONE: nothing is ever selected, and the cursor walks: three Downs from nowhere
                // land on "Q3 regional revenue…".
                tree.setSelectionMode(SelectionMode.NONE);
                tree.requestFocus();
                for (int i = 0; i < 3; i++) {
                    drive(parts.scene()).keyEvent(limn.input.Keys.DOWN, true, false, 0);
                    drive(parts.scene()).keyEvent(limn.input.Keys.DOWN, false, false, 0);
                    drive(parts.scene()).inputBatchEnded();
                }
            });
            default -> new Built(parts.scene(), () -> tree.scrollBy(0, 120));
        };
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
     * the edge of the box to the edge of the content. The shallow scene is
     * where a name contains itself; this one is where depth runs out of width.
     *
     * <p>With {@code LIMN_TREE_DEMO=walk} the keyboard goes into the tree instead of the scroll:
     * the cursor is put on level one and Down is pressed thirteen times, so it lands on level
     * fourteen and the outline has moved sideways, row by row, by the least that shows each row's
     * triangle and the start of its name.
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
                    drive(parts.scene()).keyEvent(limn.input.Keys.DOWN, true, false, 0);
                    drive(parts.scene()).keyEvent(limn.input.Keys.DOWN, false, false, 0);
                    drive(parts.scene()).inputBatchEnded();
                }
            });
        }
        return new Built(parts.scene(), () -> parts.tree().scrollBy(140, 0));
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
        return parts(true);
    }

    private static Parts parts(boolean withScene) {
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
        // The two rows that hold nothing come after the chain, where the capture variants put
        // them. (The reader run drives the accessibility gallery's own tree since 2026-09-15.)
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
            public Widget<?> cellFor(Node node) {
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
        tree.setSelectionMode(SelectionMode.MULTI);
        tree.expand(docs);
        // Reports too: its children are the long names carrying a button, and a collapsed
        // branch hides exactly the row this scene exists to show.
        tree.expand(docs.kids().get(0));

        Column page = new Column();
        page.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(new Label("Arrows walk the rows; Right opens one, Left closes it.")
                .setMuted(true));
        // A narrow box on purpose: a cell is measured at the width the indent leaves it, so
        // this is the width at which a long name either contains itself or writes over the
        // control beside it. The Row holds the box to its own width inside a column that
        // stretches, and puts it against whichever edge leads.
        limn.scene.layout.Row treeRow = new limn.scene.layout.Row();
        treeRow.add(new SizedBox(360, 320, tree));
        page.add(treeRow);
        Scene scene = null;
        if (withScene) {
            scene = new Scene(new Padding(Insets.all(24), page));
        }
        return new Parts(page, scene, tree, deep, remote, trash, emptyFolder, docs,
                docs.kids().get(0), docs.kids().get(0).kids().get(1));
    }

    // ------------------------------------------------------------------ the Showcase's explorer

    /**
     * A company's shared drive: folders and files in a tree on the start side, and on the end
     * side what the selection is — one file's details, or how many and how large when several
     * are chosen. One folder lives on a server, and opens onto a beat of loading.
     */
    static final class Explorer {

        private Explorer() {
        }

        /** What the page says. */
        static final class Text {
            static final I18nString HINT = new I18nString("showcase.trees.hint",
                    "Right arrow opens a folder, Left arrow closes it; Ctrl-click or Command-click adds a file.");
            static final I18nString DRIVE = new I18nString("showcase.trees.drive", "Shared drive");
            static final I18nString FOLDER = new I18nString("showcase.trees.folder", "Folder");
            static final I18nString PDF = new I18nString("showcase.trees.pdf", "PDF document");
            static final I18nString SHEET = new I18nString("showcase.trees.spreadsheet", "Spreadsheet");
            static final I18nString DOCUMENT = new I18nString("showcase.trees.document", "Document");
            static final I18nString IMAGE = new I18nString("showcase.trees.image", "Image");
            static final I18nString ARCHIVE = new I18nString("showcase.trees.archive", "Compressed archive");
            static final I18nString TEXT = new I18nString("showcase.trees.text", "Plain text");
            static final I18nString SIZE = new I18nString("showcase.trees.size", "Size");
            static final I18nString MODIFIED = new I18nString("showcase.trees.modified", "Modified");
            static final I18nString LOCATION = new I18nString("showcase.trees.location", "Location");
            static final I18nString KIND = new I18nString("showcase.trees.kind", "Kind");
            static final I18nString OPEN = new I18nString("showcase.trees.open", "Open");
            static final I18nString SHARE = new I18nString("showcase.trees.share", "Share");
            static final I18nString NOTHING = new I18nString("showcase.trees.nothing", "Nothing selected");
            static final limn.i18n.PluralString ITEMS = limn.i18n.PluralString.of(
                    "showcase.trees.items", "{0} item selected", "{0} items selected");
            static final limn.i18n.PluralString CHILDREN = limn.i18n.PluralString.of(
                    "showcase.trees.children", "{0} item", "{0} items");
            static final I18nString OPENED = new I18nString("showcase.trees.opened", "{0} opened.");
            static final limn.i18n.PluralString OPENED_MANY = limn.i18n.PluralString.of(
                    "showcase.trees.openedMany", "{0} file opened.", "{0} files opened.");
            static final I18nString SHARED = new I18nString("showcase.trees.shared", "Link to {0} copied.");

            private Text() {
            }
        }

        /** A file or a folder. A class and not a record: two files may share a name and a size. */
        static final class Entry {
            final String name;
            final long bytes;
            final LocalDate modified;
            final List<Entry> kids;
            final boolean folder;
            /** A folder on a server: its children are fetched when it first opens. */
            final boolean remote;
            Entry parent;

            private Entry(String name, long bytes, LocalDate modified, List<Entry> kids, boolean folder,
                          boolean remote) {
                this.name = name;
                this.bytes = bytes;
                this.modified = modified;
                this.kids = kids;
                this.folder = folder;
                this.remote = remote;
                for (Entry kid : kids) {
                    kid.parent = this;
                }
            }

            static Entry file(String name, long kilobytes, LocalDate modified) {
                return new Entry(name, kilobytes * 1024, modified, List.of(), false, false);
            }

            static Entry folder(String name, Entry... kids) {
                LocalDate latest = LocalDate.of(2026, 1, 1);
                for (Entry kid : kids) {
                    latest = kid.modified.isAfter(latest) ? kid.modified : latest;
                }
                return new Entry(name, 0, latest, List.of(kids), true, false);
            }

            static Entry server(String name, LocalDate modified) {
                return new Entry(name, 0, modified, List.of(), true, true);
            }

            /** The size of a file, or of everything a folder holds that is known. */
            long size() {
                long total = bytes;
                for (Entry kid : kids) {
                    total += kid.size();
                }
                return total;
            }

            String extension() {
                int dot = name.lastIndexOf('.');
                return folder || dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            }

            I18nString kind() {
                return switch (extension()) {
                    case "" -> Text.FOLDER;
                    case "pdf" -> Text.PDF;
                    case "xlsx", "csv" -> Text.SHEET;
                    case "docx" -> Text.DOCUMENT;
                    case "jpg", "png", "svg" -> Text.IMAGE;
                    case "zip" -> Text.ARCHIVE;
                    default -> Text.TEXT;
                };
            }

            limn.graphics.Icon icon() {
                return switch (extension()) {
                    case "" -> TablerDocument.FOLDER.icon();
                    case "pdf" -> TablerDocument.FILE_TYPE_PDF.icon();
                    case "xlsx", "csv" -> TablerDocument.FILE_SPREADSHEET.icon();
                    case "docx" -> TablerDocument.FILE_TYPE_DOCX.icon();
                    case "jpg" -> TablerDocument.FILE_TYPE_JPG.icon();
                    case "png" -> TablerDocument.FILE_TYPE_PNG.icon();
                    case "svg" -> TablerDocument.FILE_TYPE_SVG.icon();
                    case "zip" -> TablerDocument.FILE_ZIP.icon();
                    default -> TablerDocument.FILE_TEXT.icon();
                };
            }

            java.util.function.Function<Theme, limn.graphics.Color> tone() {
                return switch (extension()) {
                    case "" -> Theme::primary;
                    case "pdf" -> Theme::danger;
                    case "xlsx", "csv" -> Theme::success;
                    case "docx" -> Theme::info;
                    case "jpg", "png", "svg" -> Theme::warning;
                    default -> Theme::textMuted;
                };
            }

            /** Where it is, from the top of the drive: "Finance › Invoices". */
            String location() {
                StringBuilder path = new StringBuilder();
                for (Entry at = parent; at != null; at = at.parent) {
                    path.insert(0, path.length() == 0 ? at.name : at.name + " › ");
                }
                return path.toString();
            }
        }

        private static LocalDate day(int month, int day) {
            return LocalDate.of(2026, month, day);
        }

        /** The drive's contents: what an office keeps, by client and by department. */
        private static List<Entry> drive() {
            List<Entry> invoicesOctober = new ArrayList<>();
            for (int n = 1080; n <= 1089; n++) {
                invoicesOctober.add(Entry.file("INV-" + n + ".pdf", 180 + n % 7 * 23, day(10, n - 1079)));
            }
            List<Entry> invoicesSeptember = new ArrayList<>();
            for (int n = 1042; n <= 1047; n++) {
                invoicesSeptember.add(Entry.file("INV-" + n + ".pdf", 170 + n % 5 * 31, day(9, (n - 1041) * 4)));
            }
            return List.of(
                    Entry.folder("Clients",
                            Entry.folder("Halcyon Foods",
                                    Entry.file("Supply contract 2026.pdf", 412, day(3, 2)),
                                    Entry.file("Price list Q4.xlsx", 88, day(9, 30)),
                                    Entry.file("Kick-off notes.docx", 36, day(2, 14))),
                            Entry.folder("Meridian Logistics",
                                    Entry.file("Service agreement.pdf", 655, day(1, 20)),
                                    Entry.file("Delivery schedule.xlsx", 120, day(10, 6))),
                            Entry.folder("Vantage Cycles",
                                    Entry.file("Quote 2026-118.pdf", 210, day(8, 19)))),
                    Entry.folder("Finance",
                            Entry.folder("Invoices",
                                    Entry.folder("2026-09", invoicesSeptember.toArray(Entry[]::new)),
                                    Entry.folder("2026-10", invoicesOctober.toArray(Entry[]::new))),
                            Entry.file("Budget 2027.xlsx", 264, day(10, 9)),
                            Entry.file("Expense policy.pdf", 318, day(4, 1)),
                            Entry.file("Bank statements Q3.zip", 2_840, day(10, 3))),
                    Entry.folder("Human resources",
                            Entry.file("Employee handbook.pdf", 1_920, day(5, 12)),
                            Entry.file("Holiday calendar 2027.xlsx", 44, day(9, 22)),
                            Entry.file("Onboarding checklist.docx", 28, day(7, 8))),
                    Entry.folder("Marketing",
                            Entry.folder("Product photos",
                                    Entry.file("chair-black.jpg", 2_310, day(6, 3)),
                                    Entry.file("desk-oak.jpg", 2_780, day(6, 3)),
                                    Entry.file("dock-pro.png", 940, day(6, 4))),
                            Entry.file("Autumn brochure.pdf", 5_120, day(9, 1)),
                            Entry.file("Logo.svg", 12, day(1, 9)),
                            Entry.file("Press release.docx", 52, day(9, 15))),
                    Entry.server("Archive", day(1, 31)),
                    Entry.file("Read me.txt", 2, day(1, 5)));
        }

        /** What the server answers when its folder opens. */
        private static List<Entry> archive(Entry parent) {
            List<Entry> years = List.of(Entry.file("2024.zip", 48_200, day(1, 31)),
                    Entry.file("2025.zip", 61_700, day(1, 31)),
                    Entry.folder("Old contracts",
                            Entry.file("Lease 2019.pdf", 730, day(1, 31)),
                            Entry.file("Insurance 2021.pdf", 410, day(1, 31))));
            for (Entry year : years) {
                year.parent = parent;
            }
            return years;
        }

        private static String size(long bytes) {
            return Formats.size(bytes);
        }

        private static String date(LocalDate date) {
            return Formats.date(date, java.time.format.FormatStyle.MEDIUM);
        }

        static Widget<?> page() {
            List<Entry> roots = drive();
            Map<Entry, List<Entry>> fetched = new java.util.HashMap<>();
            Tree<Entry> tree = new Tree<>(new Tree.Model<Entry>() {
                @Override
                public List<Entry> roots() {
                    return roots;
                }

                @Override
                public List<Entry> children(Entry node) {
                    // A folder on the server says it has not been read yet by answering null.
                    return node.remote ? fetched.get(node) : node.kids;
                }

                @Override
                public boolean isLeaf(Entry node) {
                    return !node.folder;
                }

                @Override
                public Work<List<Entry>> load(Entry node) {
                    return Ui.work(progress -> {
                        try {
                            Thread.sleep(Long.getLong("limn.demo.treeLoadMillis", 600));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        List<Entry> read = archive(node);
                        Ui.post(() -> fetched.put(node, read));
                        return read;
                    });
                }

                @Override
                public float maxCellWidth() {
                    return 260;
                }

                @Override
                public Widget<?> cellFor(Entry node) {
                    Label name = new Label(node.name).setOverflow(Label.Overflow.ELLIPSIS);
                    limn.scene.layout.Row row = new limn.scene.layout.Row();
                    row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
                    row.add(new Glyph(18).bind(node.icon(), node.tone()));
                    row.add(limn.scene.layout.Expanded.of(name));
                    if (!node.folder) {
                        Label bytes = new Label(size(node.bytes)).setMuted(true);
                        bytes.setRole(Label.Role.LABEL);
                        row.add(bytes);
                    }
                    // Room at the end, the right inset being the trailing one in either direction.
                    return new Padding(new Insets(0, 12, 0, 0), row);
                }
            });
            tree.setSelectionMode(SelectionMode.MULTI);
            tree.setAccessibleName(Text.DRIVE);
            Entry finance = roots.get(1);
            Entry invoices = finance.kids.get(0);
            Entry october = invoices.kids.get(1);
            tree.expand(finance);
            tree.expand(invoices);
            tree.expand(october);
            // Two invoices chosen together, so the page opens on a selection of several.
            tree.setSelectedNodes(List.of(october.kids.get(7), october.kids.get(8)));

            // ---- what is selected, on the end side
            Glyph bigIcon = new Glyph(40);
            Label title = new Label("").setRole(Label.Role.TITLE).setWrap(true);
            Label kind = new Label("").setMuted(true);
            Label sizeValue = new Label("");
            Label modifiedValue = new Label("");
            Label locationValue = new Label("").setWrap(true);
            limn.scene.layout.Grid facts = new limn.scene.layout.Grid(2);
            facts.gap(16).rowGap(10);
            facts.expandedColumn(1);
            facts.add(new Label(Text.SIZE).setMuted(true));
            facts.add(sizeValue);
            facts.add(new Label(Text.MODIFIED).setMuted(true));
            facts.add(modifiedValue);
            facts.add(new Label(Text.LOCATION).setMuted(true));
            facts.add(locationValue);
            Label names = new Label("").setMuted(true).setWrap(true);

            Button open = new Button(Text.OPEN);
            Button share = new Button(Text.SHARE).setSecondary(true);

            Runnable describe = () -> {
                List<Entry> chosen = tree.selectedNodes();
                boolean one = chosen.size() == 1;
                Entry first = chosen.isEmpty() ? null : chosen.get(0);
                long total = 0;
                LocalDate latest = null;
                StringBuilder list = new StringBuilder();
                for (Entry entry : chosen) {
                    total += entry.size();
                    latest = latest == null || entry.modified.isAfter(latest) ? entry.modified : latest;
                    list.append(list.length() == 0 ? "" : ", ").append(entry.name);
                }
                bigIcon.bind(first == null ? TablerDocument.FOLDERS.icon() : one ? first.icon()
                        : TablerDocument.FILES.icon(), first == null || !one ? Theme::textMuted : first.tone());
                title.setText(first == null ? Text.NOTHING.get() : one ? first.name
                        : Text.ITEMS.format(chosen.size()));
                kind.setText(one ? (first.folder && !first.remote
                        ? first.kind().get() + " · " + Text.CHILDREN.format(first.kids.size())
                        : first.kind().get()) : "");
                kind.setVisible(one);
                sizeValue.setText(size(total));
                modifiedValue.setText(latest == null ? "" : date(latest));
                locationValue.setText(first == null ? "" : first.location());
                facts.setVisible(first != null);
                names.setText(one ? "" : list.toString());
                names.setVisible(chosen.size() > 1);
                open.setEnabled(first != null);
                share.setEnabled(first != null);
            };
            tree.observeChanges((source, change) -> {
                if (change.aspect() == limn.scene.Change.Aspect.SELECTION) {
                    describe.run();
                }
            });
            limn.i18n.I18n.observeChanges(describe);
            Page.afterLanguageChange(tree::refresh); // a file's size is written as the language writes a number
            open.onAction(() -> {
                List<Entry> chosen = tree.selectedNodes();
                Page.report(chosen.size() == 1 ? Text.OPENED.format(chosen.get(0).name)
                        : Text.OPENED_MANY.format(chosen.size()));
            });
            share.onAction(() -> {
                List<Entry> chosen = tree.selectedNodes();
                if (!chosen.isEmpty()) {
                    Page.report(Text.SHARED.format(chosen.get(0).name));
                }
            });
            tree.onActivate(entry -> {
                if (!entry.folder) {
                    Page.report(Text.OPENED.format(entry.name));
                }
            });
            describe.run();

            Column details = new Column();
            details.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
            details.add(Page.row(14, bigIcon, limn.scene.layout.Expanded.of(title)));
            details.add(kind);
            details.add(limn.components.Separator.horizontal());
            details.add(facts);
            details.add(names);
            limn.scene.layout.Row actions = Page.row(8, open, share);
            details.add(actions);

            limn.scene.layout.Row panes = new limn.scene.layout.Row();
            panes.gap(16).crossAlignment(Flex.CrossAlignment.STRETCH);
            panes.add(new SizedBox(380, SizedBox.UNSET, new HairlineFrame(tree)));
            // The card as tall as what it says, at the top of its side.
            Column side = new Column();
            side.crossAlignment(Flex.CrossAlignment.STRETCH);
            side.add(Page.card(details));
            panes.add(limn.scene.layout.Expanded.of(side, 1));

            Column page = Page.column();
            page.add(Page.hint(Text.HINT));
            page.add(limn.scene.layout.Expanded.of(panes, 1));
            return page;
        }
    }
}
