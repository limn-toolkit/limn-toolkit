package limn.components.tree;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.Accelerator;
import limn.components.ScrollBar;
import limn.components.ScrollGutters;
import limn.components.SizeTokens;
import limn.components.Strokes;
import limn.components.Theme;
import limn.concurrent.Job;
import limn.concurrent.Ui;
import limn.concurrent.Work;
import limn.graphics.Canvas;
import limn.graphics.Path2D;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.lang.Checks;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An outline over children the application provides: one column of rows at a depth, each with an
 * indent, a disclosure triangle where it can open, and the application's own cell widget.
 *
 * <p><b>The application keeps its nodes.</b> A tree is handed a {@link Model}, which answers what
 * the roots are, what a node's children are, and what widget draws one. Nothing here is a node
 * type of this toolkit's: a tree over {@code Path}, over a record, or over a live domain object is
 * the same tree. Identity is the node's own {@code equals}, which gives value semantics to records
 * and reference semantics to mutable objects, and both are what the application that chose them
 * wanted. <b>A node is unique within a tree</b>: two equal nodes in two places would share one
 * selection, one expansion and one accessible identity, so the tree refuses them the moment both
 * are visible, with an {@link IllegalStateException} naming the node (decision 15 of 2026-09-14).
 * A model whose values repeat under different parents — a file named the same in two folders —
 * gives its nodes path identity, the way the guide's example does.
 *
 * <p><b>A row may promise children before it can name them.</b> {@link Model#children} answers
 * {@code null} for a node whose children are not known yet, and {@link Model#load} hands back a
 * {@link Work} that fetches them: the row opens, shows that it is working, and fills in when the
 * job lands on the UI thread. Collapsing a row that is still loading cancels the job, because a
 * result nobody is looking at is a result nobody should pay for. A directory that has not been
 * read is therefore not a leaf — it has a triangle, and pressing it is what reads it — and one
 * that turns out to hold nothing stays open, with a muted "Empty" line where its children would
 * be (decision 45 of 2026-09-14).
 *
 * <p><b>Rows are realized where the viewport reaches</b>, by the anchor-and-walk this toolkit's
 * list and table already use: a row's height is measured when it is first needed, the mean seeds
 * the scroll estimate (never the tree's own preferred height, which is {@link #setVisibleRows}
 * seed rows under an unbounded parent), and two rows are kept mounted even when a scroll carries
 * them outside:
 * the one holding the keyboard focus (ADR 039 §13.29), and the cursor row while the tree itself
 * holds the keyboard, so a reader's cursor survives a wheel, a refresh and a reorder (decision 22
 * of 2026-09-14). The order rows are walked in is a traversal of what is expanded, which is the
 * one thing a tree does that a list cannot.
 *
 * <p><b>What this is not:</b> no columns — a {@code TreeTable} is ADR 044 §9 — no in-place
 * editing, ever (ADR 041 §6), no drag to reorder, and no tri-state checkbox cascade over a data
 * model the toolkit does not own.
 *
 * <p><b>The cursor is not the selection.</b> The row the keyboard is on ({@link #cursorNode()})
 * moves with the arrows in every mode and is what Enter activates; the selection
 * ({@link #selectedNodes()}, led by {@link #leadNode()}) follows it where the mode allows, and in
 * {@code MULTI} a row toggled off keeps the cursor. {@code Table} keeps the same pair. While the
 * tree holds the keyboard the cursor row's cell wears a thin focus ring, which is how the cursor
 * is seen where the selection wash is not on it.
 *
 * <p>To a screen reader this is a {@code TREE} of {@code TREE_ITEM}s, each carrying its expanded
 * state, its selection numbered among its siblings ("2 of 5"), and its depth and flat row index
 * through the hierarchy facet ("level 3"; ADR 039 §1.2, amended 2026-09-14), the cursor row
 * {@code ACTIVE} while the tree holds the keyboard. What ADR 044 §4
 * still owes it is the three platforms carrying those numbers, and the disclosure attributes
 * VoiceOver reads an outline row by.
 */
public class Tree<T> extends Widget implements Scrollable {

    /** How many rows may be selected at once. */
    public enum SelectionMode {
        /**
         * None: the cursor still moves, Enter still activates the row it is on, and nothing is
         * ever selected.
         */
        NONE,
        /** One row. */
        SINGLE,
        /**
         * Any number of rows: the command modifier toggles one, Shift extends a range over the
         * visible rows, and Ctrl+A or Cmd+A takes every open row.
         */
        MULTI
    }

    /**
     * What the tree asks the application about its own data.
     *
     * <p>Five of the eight methods have defaults, and the three that do not are the ones only the
     * application can answer: where the tree starts, what is under a node, and what draws it.
     */
    public interface Model<T> {

        /** @return the top-level nodes, in the order they are shown */
        List<T> roots();

        /**
         * @param node a node the tree is showing
         * @return its children, or {@code null} when they are not known yet and {@link #load}
         *         is what fetches them. An empty list means a node with no children, which is
         *         a leaf; {@code null} is a promise, not an absence. No child may equal any other
         *         node of the tree: a node is unique by {@code equals} within a tree, and the
         *         tree refuses a duplicate as soon as both are visible.
         */
        List<T> children(T node);

        /**
         * @param node a node the tree is showing
         * @return whether it can never have children, so no triangle is drawn. The default reads
         *         {@link #children}: a node whose children are known and empty is a leaf, and one
         *         whose children are not known yet is not — a directory nobody has read is not a
         *         file. A model that answers {@code false} for a node with no children, an empty
         *         folder, gets a branch that opens onto the tree's "Empty" line.
         */
        default boolean isLeaf(T node) {
            List<T> known = children(node);
            return known != null && known.isEmpty();
        }

        /**
         * Fetches the children of a node that answered {@code null} to {@link #children}.
         *
         * <p>Called at most once per node per expansion, and once more after each
         * {@link Tree#refresh} while the row is open, on the UI thread; the job it returns is
         * cancelled if the row is collapsed, the tree refreshed, or the tree taken out of its
         * scene before it lands — a tree merely moved between containers loads its open rows
         * again when it arrives, which is the price of never leaving a row busy over a job that
         * was dropped. The tree caches what arrives, so a second expansion of the same node
         * costs nothing. A load that finds no children leaves the row an open branch with an
         * "Empty" line under it, where the "Loading…" line stood (decision 45 of 2026-09-14);
         * a load that fails closes the row.
         *
         * @param node the node being opened
         * @return the job, or {@code null} when this model has nothing to load
         */
        default Work<List<T>> load(T node) {
            return null;
        }

        /**
         * @param node a node the tree is showing
         * @return the widget that draws it, populated and ready. May be an instance kept from
         *         {@link #recycle}.
         */
        Widget cellFor(T node);

        /** The tree scrolled {@code cell} out of view; pool it for reuse if you like. */
        default void recycle(Widget cell) {
        }

        /**
         * What to call {@code node} for an assistive technology. A name from here wins: it is
         * published over whatever the cell widget says of itself, and over the name the tree
         * would otherwise read off the cell's labels (ADR 044 §4, amended 2026-09-14). Answer
         * {@code null} to let the cell name its row.
         *
         * <p><b>Hand back a string this model holds.</b> The tree compares a name by reference
         * to decide whether it has to be resolved again, so a string built inside this call
         * costs one allocation and one resolution per realized row per walk — which is the
         * zero-allocation promise of a quiet frame broken for that application, though never a
         * republish: the walk compares the resolved text, and a name that reads the same
         * publishes nothing and raises no event. A field or an entry in the application's own
         * data is what belongs here. The rule and the reason are
         * {@code ListView.Adapter#rowName}'s.
         *
         * @return the name, or {@code null} when the model has none to give
         */
        default I18nString nameOf(T node) {
            return null;
        }

        /**
         * How wide a row's cell needs to be, in points, once the tree is deep enough to scroll
         * sideways: the outline is made as wide as its deepest open row's indent and triangle
         * plus this, so the cell at the deepest level gets exactly this width and every
         * shallower cell, laid out to the same far edge, more (decision 50 of 2026-09-14; ADR
         * 044 §1, amended). Where the box is wider than that, the outline is the box and
         * nothing scrolls sideways.
         *
         * <p>A cap, not a demand: no cell is promised more than the box gives a root row past
         * its triangle, so a width wider than the box never makes a flat tree scroll sideways,
         * and a deep tree's outline grows by its indent alone.
         *
         * <p>Declare it when the model knows its cells — a name and a badge, a name and a
         * button — because the tree cannot: a row holding an {@code Expanded} has no width of
         * its own to measure, and measuring the realized rows would move the content, the bar
         * and every cell as a vertical scroll mounted rows of other widths. Read at every layout
         * pass, so a constant or a field, not a computation. The number is in points at every
         * control size; a model that follows the size step derives it from the tree's tokens.
         *
         * @return the width, or zero (the default) to let the tree choose, which is the menu's
         *         minimum width capped by the viewport: the narrowest strip this toolkit reads a
         *         row of text in. A negative or non-finite answer is taken as zero.
         */
        default float maxCellWidth() {
            return 0;
        }
    }

    /** One visible row: a node, how deep it sits, and what its triangle is doing. */
    private static final class Row<T> {
        final T node;
        final int depth;
        final boolean expandable;
        final boolean expanded;
        final boolean loading;
        /**
         * Whether this is a line under an open row rather than a node: the "Loading…" line
         * while the row's children are on their way ({@link #loading}), or the "Empty" line
         * under an open row that holds nothing (decision 45 of 2026-09-14). {@link #node} is the
         * row it belongs to, which is what makes every key and click that lands on the line
         * land on that row instead: it is never itself selected, never the cursor, and never an
         * item to a reader.
         */
        final boolean placeholder;
        /** Where this row stands among the rows that are nodes, from one; zero for the line. */
        final int item;
        /**
         * Where this row stands among its parent's children, from one, and how many of those
         * there are: the "2 of 5" a reader speaks, which counts siblings and not the outline
         * (decision 4 of 2026-09-13). Zero for the line.
         */
        final int position;
        final int siblings;

        Row(T node, int depth, boolean expandable, boolean expanded, boolean loading,
                boolean placeholder, int item, int position, int siblings) {
            this.node = node;
            this.depth = depth;
            this.expandable = expandable;
            this.expanded = expanded;
            this.loading = loading;
            this.placeholder = placeholder;
            this.item = item;
            this.position = position;
            this.siblings = siblings;
        }
    }

    /**
     * The key a line under a row is mounted under. It shares its node with the row above it, and
     * a cell following its node must not follow that one onto the row; the loading and the empty
     * line of one row are two keys, so the one replaces the other when a load lands on nothing.
     */
    private record LineKey(Object node, boolean loading) {
    }

    /**
     * The cell of a line under a row: the tree's own, and mounted under a {@link LineKey}, which
     * is how a release knows not to hand it to the model to recycle. "Loading…" while the row's
     * children are on their way; "Empty" under an open row that holds nothing, so an open
     * triangle over nothing does not read as a row that never loaded (decision 45).
     */
    private static Widget lineUnderRow(boolean loading) {
        limn.components.Label line = new limn.components.Label(
                loading ? TreeStrings.LOADING : TreeStrings.EMPTY);
        line.setMuted(true);
        // The row it belongs to says it is busy, on every platform, or open over no children (a
        // TREE_ITEM with the expanded state and nothing under it); a line of text saying so again
        // would be an item a reader can walk onto that is not a node.
        line.setAccessibleIgnored(true);
        return line;
    }

    /**
     * Rows of intrinsic height when the height axis is unbounded, until {@link #setVisibleRows}
     * says otherwise; a count, not a length: it multiplies the step's seed row height, so it
     * must not move with the step. The table's number.
     */
    private static final int VISIBLE_ROWS_HINT = 8;
    /** Two presses on one row closer than this are a double click; the table's window. */
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    /**
     * How many changed rows are still worth damaging one at a time before the whole tree is
     * cheaper: the table's number, for the table's reason (the damage list merges past a
     * handful of bands into the box anyway).
     */
    private static final int MAX_DAMAGED_ROWS = 6;

    /** One turn of a loading spinner, in seconds of wall time. */
    private static final double SPIN_SECONDS = 1.0;
    /** How much of the circle the spinner's arc covers: three quarters, so its turning shows. */
    private static final double SPIN_SWEEP = 1.5 * Math.PI;

    private final Model<T> model;
    private final ScrollBar vBar;
    private final ScrollBar hBar;
    private final ScrollGutters gutters = new ScrollGutters();
    private final Path2D twisty = new Path2D();

    /** The visible rows, in traversal order; rebuilt when what is open changes. */
    private final List<Row<T>> rows = new ArrayList<>();
    private final Set<T> expanded = new LinkedHashSet<>();
    /** Children that arrived from {@link Model#load}, kept so a re-expansion costs nothing. */
    private final Map<T, List<T>> loaded = new HashMap<>();
    /** Jobs in flight, so collapsing a row that is still loading cancels it. */
    private final Map<T, Job> loading = new HashMap<>();
    /**
     * Where each node the tree keeps a fact about — selected, expanded, or the cursor — was last
     * seen as a row, from the root down, while it is not a row: what a refresh verifies instead of
     * walking the model (decision 35 of 2026-09-14). Recorded when a collapse or a refresh takes
     * the row away, dropped when the node is a row again or the model no longer has it.
     */
    private final Map<T, List<T>> hiddenPaths = new HashMap<>();
    /**
     * The selected row's wash, memoised per palette: {@code withAlpha} builds a colour, and this
     * is read once per painted row per frame.
     */
    private limn.graphics.Color selectionTint;
    private Theme tintedFor;
    /**
     * A stable identifier per node, for the accessible tree. A row's position is not one: opening
     * a node renumbers every row below it, and a reader standing on one would be told it had
     * become a different node.
     */
    private final Map<T, Long> ids = new HashMap<>();
    private long nextId = 1;

    /**
     * The realized rows, in traversal order: {@code mountedRows[i]} is the row index
     * {@code mountedCells[i]} is bound to, ascending, and {@code children()} holds the bar and
     * then exactly these cells in exactly this order — which is reading order and Tab order both,
     * and is why they are mounted by position rather than appended (see {@code ListView}).
     */
    private int[] mountedRows = new int[16];
    private Widget[] mountedCells = new Widget[16];
    /**
     * The node each mounted cell draws. A row index is only an address until the rows move, and
     * opening, closing or loading a row moves every row below it; this is what lets a cell follow
     * its node to the new address instead of drawing whichever node arrived at the old one.
     */
    private Object[] mountedNodes = new Object[16];
    /**
     * The name the tree derived for each mounted cell that names itself no other way — the text
     * of its labels, in order — kept so a quiet frame compares and allocates nothing
     * (TREE-ROW-NAME); {@code null} where the cell or the model names the row.
     */
    private String[] mountedNames = new String[16];
    private int mountedCount;
    /** Reused per walk to assemble a row's derived name before comparing it with the kept one. */
    private final StringBuilder nameBuilder = new StringBuilder();
    /**
     * Cells whose row vanished between two layout passes — a collapse, a load landing, a
     * reorder — kept as children until the tree's next pass releases them. Taking a child out
     * of a widget outside a layout pass declares a global layout, which is a full frame (ADR
     * 002's invariant), and the scene absorbs the same removal inside a pass over this subtree:
     * a collapse that released its cells on the spot repainted the window for one gesture on
     * one widget, which the damage ratchet's LEFT caught (2026-09-14).
     */
    private final List<Widget> orphanCells = new ArrayList<>();
    private final List<Object> orphanKeys = new ArrayList<>();

    /** The half-open run of rows the last pass laid out, which is what a recycle keeps. */
    private int placedFrom;
    private int placedTo;

    // Anchor scroll state: the top edge of row `anchorIndex` sits at y = anchorTop.
    private int anchorIndex;
    private float anchorTop;
    /** Mean measured row height, or 0 until a pass has measured one; the step's seed stands in. */
    private float measuredRowHeight;
    /**
     * The node {@link #revealNode} left for the next pass to bring into the box, or null: a row
     * outside the viewport is revealed where the pass can measure the rows between, see
     * {@link #settleReveal}.
     */
    private T revealPending;
    /**
     * How far the outline is scrolled sideways, in points from its leading edge.
     *
     * <p>Depth is what makes this necessary and a list never needs it: every level charges an
     * indent and nothing gives it back, so past some depth the cell carrying the name would begin
     * beyond the far edge of the box.
     */
    private float offsetX;
    /** The deepest row in the traversal, recomputed with {@link #rows} and read at every layout. */
    private int maxDepth;
    /** How many of {@link #rows} are nodes, which is every row but the loading lines. */
    private int itemCount;
    /**
     * How far round the loading spinners are, in turns. One phase for every loading row, so that
     * two rows loading at once turn together rather than drifting apart.
     */
    private double spinPhase;
    private boolean spinning;
    /** Bumped on detach, so a ticker still registered with the scene it left stops itself. */
    private int spinGeneration;
    /** The content width the last pass settled on, which is what the horizontal bar reports. */
    private float contentWidth;
    /**
     * How many seed rows tall this tree prefers to be under an unbounded height (decision 44 of
     * 2026-09-14). Multiplied by the token's seed and never by the realized average: the average
     * moves as rows of other heights scroll in, and a preference that moved with it re-laid out
     * the parent on every such scroll and made a tree inside a scroll pane jitter (T5).
     */
    private int visibleRows = VISIBLE_ROWS_HINT;

    private SelectionMode selectionMode = SelectionMode.SINGLE;
    private final Set<T> selected = new LinkedHashSet<>();
    /**
     * The row the keyboard is on: what the arrows move, what Enter and a reader's {@code PRESS}
     * act on, and the row that is {@code ACTIVE} while the tree holds the focus. It moves in
     * every mode, {@link SelectionMode#NONE} included, and it is not the selection: in
     * {@code MULTI} the cursor can stand on a row that was just toggled off, and in {@code NONE}
     * nothing is ever selected (decision 14 of 2026-09-14; {@code Table} keeps the same two
     * fields as {@code focusRow} and {@code lead}).
     */
    private T cursor;
    /**
     * The selection's lead: the node the user selected last that is still selected, or
     * {@code null} with nothing selected. Never a node outside {@link #selected}.
     */
    private T lead;
    /**
     * The node a Shift range extends from: where the last plain click, plain arrow or toggle
     * put the cursor. A node and not a row index, because opening a row renumbers every row
     * below it and the anchor has to survive that; hidden by a collapse it stands for nothing,
     * and the next range starts at its own target.
     */
    private T rangeAnchor;
    /**
     * Whether the selection moving now is a pointer press's, which reveals its row vertically
     * and never sideways; see {@link #revealNode}.
     */
    private boolean pointerPress;
    /** The last row pressed and when, for the double click. */
    private T lastPressNode;
    private long lastPressNanos;
    /** The node whose expansion a handler is about to be told of; see {@link #handleUserChange}. */
    private T toggled;

    private Runnable onSelect;
    private Consumer<T> onActivate;
    private Consumer<T> onExpand;
    private Consumer<T> onCollapse;

    /** A tree over {@code model}, which supplies the nodes and the widgets that draw them. */
    public Tree(Model<T> model) {
        // Here and not where the loading line first needs its words: registering a bundle
        // invalidates the language, which lays out and repaints every scene, and the first line is
        // built in the middle of the tree's own contained layout. Left to that moment, the first
        // row to load in a process repainted the whole window, which the damage ratchet caught.
        TreeStrings.ensureRegistered();
        this.model = Objects.requireNonNull(model, "model");
        setFocusable(true);
        vBar = new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return estimatedContentHeight(tokens());
            }

            @Override
            public float viewportLength() {
                return viewportHeight();
            }

            @Override
            public float offset() {
                return estimatedOffset(tokens());
            }

            @Override
            public void setOffset(float value) {
                scrollBy(value - estimatedOffset(tokens()));
            }
        });
        add(vBar);
        hBar = new ScrollBar(ScrollBar.Orientation.HORIZONTAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return contentWidth;
            }

            @Override
            public float viewportLength() {
                return gutters.viewportWidth(width());
            }

            @Override
            public float offset() {
                return offsetX;
            }

            @Override
            public void setOffset(float value) {
                scrollHorizontallyBy(value - offsetX);
            }
        });
        add(hBar);
        rebuildRows();
    }

    // ----------------------------------------------------------------- model and expansion

    /**
     * Re-reads the model from the roots down, keeping what is expanded and dropping every cached
     * child list: the application changed its data and the tree owns none of it. An open row whose
     * children the model has to fetch therefore fetches them again, cancelling a load still in
     * flight for it. UI thread only.
     *
     * <p>What the tree remembers about a node — that it is selected, that it is open, that the
     * cursor stands on it — outlives the refresh as long as the model still has the node. A node
     * that is a row afterwards is confirmed by that; one that is not (under a closed branch, or
     * under an open row whose children are being fetched again) is verified along the path it
     * was last seen at, and only that path: a step the model no longer has drops the node, a
     * step whose children are still on their way keeps it until they land, when it is verified
     * again and dropped one announcement later if gone, and a step whose children nobody has
     * fetched keeps it, since confirming it would mean a load nobody asked for (decision 35 of
     * 2026-09-14). The identifiers of what the model no longer has are released with it.
     */
    public void refresh() {
        Ui.checkUiThread();
        recordPaths();
        loaded.clear();
        cancelAllLoads();
        releaseOrphans();
        recycleExcept(0, 0, 0);
        rebuildRows();
        forgetRevealedPaths();
        verifyHidden();
        releaseIdentifiers();
        markNeedsLayout();
        invalidate();
        notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.CODE));
    }

    /** @return whether {@code node} is open */
    public boolean isExpanded(T node) {
        return expanded.contains(node);
    }

    /** Opens {@code node}, loading its children if the model has to fetch them. UI thread only. */
    public Tree<T> expand(T node) {
        Ui.checkUiThread();
        setExpanded(node, true, Change.Origin.CODE);
        return this;
    }

    /** Closes {@code node}, cancelling a load still in flight for it. UI thread only. */
    public Tree<T> collapse(T node) {
        Ui.checkUiThread();
        setExpanded(node, false, Change.Origin.CODE);
        return this;
    }

    /**
     * The one place a row opens or closes, and the one seam it announces from: the public setters
     * pass {@code CODE}, a click on the triangle and the arrow keys pass {@code USER}.
     */
    private void setExpanded(T node, boolean open, Change.Origin origin) {
        Objects.requireNonNull(node, "node");
        if (open == expanded.contains(node) || model.isLeaf(node)) {
            return;
        }
        if (open) {
            expanded.add(node);
            startLoadIfNeeded(node);
            announceIfOpeningOnNothing(node);
        } else {
            expanded.remove(node);
            Job job = loading.remove(node);
            if (job != null) {
                // A result nobody is looking at is a result nobody should pay for; the model's
                // own onDiscarded, if it registered one, is what releases whatever it built.
                job.cancel();
            }
        }
        toggled = node;
        T wasCursor = cursor;
        if (!open) {
            recordPaths(); // the rows about to be hidden, and where they stand
        }
        rebuildRows();
        forgetRevealedPaths();
        if (!open && cursor != null && indexOf(cursor) < 0) {
            // The collapse hid the row the cursor was on: the cursor climbs to the row that
            // closed, which is where Explorer, Finder and GTK put it, and the selection stays
            // where it is in every mode (decision 21 of 2026-09-14; ADR 044 §6). Announced with
            // the gesture's origin, so a reader on a child row hears the parent it landed on.
            cursor = node;
            rangeAnchor = node;
            damageCursorMove(wasCursor);
            announceCursor(wasCursor, origin);
        }
        // Nothing is pruned here: opening or closing a row cannot remove a node from the model,
        // and the walk that used to run from every root on each press asked the model for the
        // children of every closed branch — unbounded over a generated model (TREE-NEW-2).
        // Contained, not global: opening a row changes which rows are mounted and where they
        // sit, and both are inside a box this widget clips and whose own size the expansion
        // cannot move. A global layout is a full frame by ADR 002's invariant, which made every
        // twisty press repaint the window — the work ADR 043 exists to avoid, on the one gesture
        // only a tree has.
        markNeedsContainedLayout();
        invalidate();
        notifyChange(Change.of(Change.Aspect.EXPANDED, origin));
    }

    /**
     * Starts the model's load for a node whose children are not known, at most once per node.
     *
     * <p>Delivery is gated on the tree still being in a scene: a job that outlives the window it
     * was opened for must not rebuild rows nobody will paint.
     *
     * <p><b>The start and the end are announced</b> (decision 73 of 2026-09-16), because nothing
     * else tells a reader: {@link #announceLoad} has the measurement.
     */
    private void startLoadIfNeeded(T node) {
        if (loaded.containsKey(node) || loading.containsKey(node) || model.children(node) != null) {
            return;
        }
        Work<List<T>> work = model.load(node);
        if (work == null) {
            return;
        }
        Job job = work
                .onSuccess(children -> {
                    loading.remove(node);
                    loaded.put(node, children == null ? List.of() : List.copyOf(children));
                    // Both ends of the load are announced (decision 83 of 2026-09-17, closing the
                    // first case decision 73 left open): one that found nothing says so, and one
                    // that landed with children says it landed. Until then only the empty end
                    // spoke, so a branch that took a second said "Loading Documents" and then
                    // nothing at all, which a reader cannot tell from a load still running.
                    announceLoad(children == null || children.isEmpty()
                            ? TreeStrings.EMPTY_ANNOUNCEMENT : TreeStrings.LOADED_ANNOUNCEMENT,
                            node);
                    rebuildRows();
                    forgetRevealedPaths();
                    // What a refresh could not confirm under this row is verified now that the
                    // children are known, and dropped one announcement later if gone.
                    verifyHidden();
                    markNeedsContainedLayout();
                    invalidate();
                    notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.ADJUSTMENT));
                })
                .onFailure(error -> {
                    // The row closes again rather than sitting open and empty, which would read
                    // as "this node has nothing in it" — a different statement from "this could
                    // not be read". Through the one seam that announces EXPANDED, as an
                    // adjustment of the tree's own (TREE-NEW-9); the children never changed, so
                    // CHILDREN is not announced.
                    loading.remove(node);
                    setExpanded(node, false, Change.Origin.ADJUSTMENT);
                })
                .deliverIf(() -> {
                    boolean alive = scene() != null;
                    if (!alive) {
                        // Dropped, and forgotten with it: a delivery refused because the tree is
                        // in no scene must not leave the row busy for good. The next rebuild —
                        // the attach, a refresh — starts the load again (TREE-NEW-3).
                        loading.remove(node);
                    }
                    return alive;
                })
                .start();
        loading.put(node, job);
        announceLoad(TreeStrings.LOADING_ANNOUNCEMENT, node);
        startSpinning();
    }

    /**
     * Says "empty" for a branch that opens onto nothing without a load to wait for (decision 83 of
     * 2026-09-17, closing the second case decision 73 left open).
     *
     * <p>Two rows reach this: an <b>eager</b> branch the model calls a non-leaf over an empty
     * list, and a branch whose load already answered nothing and is answering from the cache. Both
     * open instantly onto the unfocusable "Empty" line of decision 45, which the cursor steps over
     * on every platform, so before this a reader pressed Right and heard <em>nothing at all</em> —
     * the same silence decision 73 was written to end, only without the wait that made it visible.
     *
     * <p>A branch whose load is still out is not announced here: its start has just been announced
     * and its end will be.
     */
    private void announceIfOpeningOnNothing(T node) {
        if (loading.containsKey(node)) {
            return;
        }
        List<T> known = loaded.containsKey(node) ? loaded.get(node) : model.children(node);
        if (known != null && known.isEmpty()) {
            announceLoad(TreeStrings.EMPTY_ANNOUNCEMENT, node);
        }
    }

    /**
     * Says one of the load announcements, naming the branch (decision 73 of 2026-09-16, extended
     * by decision 83 of 2026-09-17).
     *
     * <p><b>Why an announcement and not a state.</b> The row already publishes {@code BUSY} while
     * its load is out and the "Loading…" line is already drawn under it, and on 2026-09-16 both
     * were measured saying nothing to anybody. {@code BUSY} reaches Windows as {@code ItemStatus}
     * and NVDA 2024.4.2 has no handler for it — six raises, five received, none spoken — and the
     * placeholder line is not focusable, so the cursor steps over it on every platform. The
     * announcement path is the one route all three readers were measured speaking through on that
     * same day, each saying {@code 'Salvo'} and {@code 'Interrompido, nada foi salvo'} from it. The
     * visual line of decision 45 stays exactly as it is and stays unfocusable; this is beside it,
     * not instead of it.
     *
     * <p>Polite, never assertive: a branch opening is not an interruption, and a tree whose rows
     * load one after another would otherwise cut its own reader off mid-word.
     *
     * <p>Named from the model first and the row's cell second — the order a row's own name follows
     * — because an announcement arrives with no context: "Loading" alone names nothing. A node
     * neither route can name says nothing at all rather than "Loading " with a hole in it.
     */
    private void announceLoad(I18nString what, T node) {
        limn.scene.Scene scene = scene();
        if (scene == null) {
            return; // nobody is listening, and the load is restarted when the tree is bound again
        }
        String name = announcementName(node);
        if (name == null || name.isEmpty()) {
            return;
        }
        scene.announce(what.format(name), Accessible.Politeness.POLITE);
    }

    /** What to call a node in an announcement: the model's name, else its cell's labels. */
    private String announcementName(T node) {
        I18nString named = model.nameOf(node);
        if (named != null) {
            return named.get();
        }
        for (int i = 0; i < mountedCount; i++) {
            if (node.equals(mountedNodes[i])) {
                return derivedName(i, mountedCells[i]);
            }
        }
        return null;
    }

    private void cancelAllLoads() {
        for (Job job : loading.values()) {
            job.cancel();
        }
        loading.clear();
    }

    /**
     * Walks the model from the roots down, following what is open, into {@link #rows}, starting
     * the load of any open row whose children are neither known nor on their way.
     */
    private void rebuildRows() {
        rows.clear();
        itemCount = 0;
        List<T> roots = model.roots();
        for (int i = 0; i < roots.size(); i++) {
            appendRow(roots.get(i), 0, i + 1, roots.size());
        }
        // Here rather than in the layout: the deepest row is what decides how wide the content
        // is, and the only thing that moves it is what is open, which is decided here.
        int deepest = 0;
        Set<T> seen = new HashSet<>(rows.size() * 2);
        for (Row<T> row : rows) {
            deepest = Math.max(deepest, row.depth);
            if (!row.placeholder && !seen.add(row.node)) {
                // Fail fast, and by name: two equal nodes in two places would share a selection,
                // an expansion and one accessible identity, and every one of those would be
                // wrong quietly (decision 15 of 2026-09-14; ADR 044 §2).
                throw new IllegalStateException("a node must be unique within a tree, and "
                        + row.node + " is visible in two places: give such nodes path identity");
            }
        }
        maxDepth = deepest;
        followMountedNodes();
    }

    /**
     * Re-binds every mounted cell to the row its node sits at now, and releases the cells whose
     * node is no longer a row.
     *
     * <p>Without this a cell stays bound to its old index, so opening a row above it made it draw
     * whatever node the shift carried there: the opened row's children were never drawn, the rows
     * below were drawn twice, and every per-row fact published to a reader — the expand state, the
     * name a cell supplies — sat on the wrong row by exactly the number of rows inserted.
     *
     * <p>Order is kept rather than re-sorted: a node that stays visible keeps its place relative
     * to every other node that does, which is what {@code children()} being in traversal order
     * relies on. A cell whose node moved against that order — a model that reordered itself
     * without {@link #refresh} — is released instead, and the next layout mounts it afresh.
     */
    private void followMountedNodes() {
        if (mountedCount == 0) {
            return;
        }
        Map<Object, Integer> slotOf = new HashMap<>(mountedCount * 2);
        for (int i = 0; i < mountedCount; i++) {
            slotOf.putIfAbsent(mountedNodes[i], i);
        }
        int[] now = new int[mountedCount];
        Arrays.fill(now, -1);
        int found = 0;
        for (int r = 0; r < rows.size() && found < mountedCount; r++) {
            Integer slot = slotOf.get(mountKey(rows.get(r)));
            if (slot != null && now[slot] < 0) {
                now[slot] = r;
                found++;
            }
        }
        int kept = 0;
        int last = -1;
        for (int i = 0; i < mountedCount; i++) {
            Widget cell = mountedCells[i];
            if (now[i] <= last) { // gone, or moved against the traversal
                orphanCells.add(cell); // released by the next pass, never here (see the field)
                orphanKeys.add(mountedNodes[i]);
                continue;
            }
            last = now[i];
            mountedRows[kept] = now[i];
            mountedCells[kept] = cell;
            mountedNodes[kept] = mountedNodes[i];
            mountedNames[kept] = mountedNames[i];
            kept++;
        }
        for (int i = kept; i < mountedCount; i++) {
            mountedCells[i] = null;
            mountedNodes[i] = null;
            mountedNames[i] = null;
        }
        mountedCount = kept;
    }

    /**
     * @param position where {@code node} stands among its parent's children, from one
     * @param siblings how many children that parent has, {@code node} included
     */
    private void appendRow(T node, int depth, int position, int siblings) {
        boolean leaf = model.isLeaf(node);
        boolean open = expanded.contains(node);
        if (open && !leaf) {
            // Here and not only where a row opens: a refresh drops what every load brought while
            // keeping the rows open, and an open row with no children and no job reads as a node
            // with nothing in it, which is what a failed load closes the row to avoid. The walk
            // is the one pass that meets every open row, including one under a parent whose own
            // children arrive later, and delivery is always posted, so nothing re-enters it.
            startLoadIfNeeded(node);
        }
        boolean busy = loading.containsKey(node);
        rows.add(new Row<>(node, depth, !leaf, open, busy, false, ++itemCount, position, siblings));
        if (!open || leaf) {
            return;
        }
        List<T> children = childrenOf(node);
        if (children.isEmpty()) {
            // Open, and nothing under it: say why in the row's own place. While the children
            // are on their way the line says so, rather than leaving the row open over nothing,
            // which reads as a node with nothing in it (ADR 044 §2); once a load has found
            // nothing, or for a branch the model calls a non-leaf over an empty list, the row
            // stays an open branch and the line says it is empty (decision 45 of 2026-09-14).
            rows.add(new Row<>(node, depth + 1, false, false, busy, true, 0, 0, 0));
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            appendRow(children.get(i), depth + 1, i + 1, children.size());
        }
    }

    private static Object mountKey(Row<?> row) {
        return row.placeholder ? new LineKey(row.node, row.loading) : row.node;
    }

    /** The children to walk: what a load produced, else what the model already knows. */
    private List<T> childrenOf(T node) {
        List<T> arrived = loaded.get(node);
        if (arrived != null) {
            return arrived;
        }
        List<T> known = model.children(node);
        return known == null ? List.of() : known;
    }

    /**
     * @return how many nodes are visible, which is a traversal of what is open. The "Loading…"
     *         line under a row whose children are on their way is not one of them.
     */
    public int visibleRowCount() {
        return itemCount;
    }

    /**
     * Sets when the scroll bars are shown (default {@link ScrollBar.Policy#AUTO}), the table's
     * setter under the table's name.
     *
     * @param policy the policy
     * @return this tree
     */
    public Tree<T> setScrollbarPolicy(ScrollBar.Policy policy) {
        Ui.checkUiThread();
        vBar.setPolicy(policy);
        hBar.setPolicy(policy);
        markNeedsLayout();
        invalidate();
        return this;
    }

    /**
     * Sets whether the bars float over the rows or reserve strips of their own (default
     * {@link ScrollGutters.Layout#OVERLAY}), the table's setter under the table's name.
     *
     * <p>Reserved is what a tree whose cells end in something usually wants: a count, a badge or
     * a button against a row's trailing edge is exactly what a thumb covers. The strips key on
     * overflow, so a deep tree takes the horizontal one only once it scrolls sideways.
     *
     * @param layout the layout
     * @return this tree
     */
    public Tree<T> setBarLayout(ScrollGutters.Layout layout) {
        Ui.checkUiThread();
        gutters.setLayout(layout);
        markNeedsLayout();
        invalidate();
        return this;
    }

    /** Whether the scroll bars overlay the rows or reserve strips beside them. */
    public ScrollGutters.Layout barLayout() {
        return gutters.layout();
    }

    /**
     * Sets how many seed rows tall the tree prefers to be when its parent gives it no height
     * (default 8, the table's number): inside a column or a scroll pane, that is its height.
     * A count of the size step's seed row, never of the rows realized, so the preference stands
     * whatever scrolls in (decision 44 of 2026-09-14). A bounded height from the parent wins.
     * UI thread only.
     *
     * @param rows the count, at least one
     * @return this tree
     * @throws IllegalArgumentException if {@code rows} is below one
     */
    public Tree<T> setVisibleRows(int rows) {
        Ui.checkUiThread();
        if (rows < 1) {
            throw new IllegalArgumentException("visible rows must be at least 1, not " + rows);
        }
        if (rows != visibleRows) {
            visibleRows = rows;
            markNeedsLayout();
        }
        return this;
    }

    /** How many seed rows tall this tree prefers to be under an unbounded height. */
    public int visibleRows() {
        return visibleRows;
    }

    /**
     * The height rows are placed, scrolled and clipped against: the box, less the horizontal
     * strip when one is reserved. Never {@link #height()} directly, which is the same number only
     * while the bars overlay.
     */
    private float viewportHeight() {
        return gutters.viewportHeight(height());
    }

    /** The viewport's left edge: zero, or past the vertical strip reading right to left. */
    private float viewportLeft() {
        return isRightToLeft() ? width() - gutters.viewportWidth(width()) : 0;
    }

    /** Whether a point is inside the viewport, rather than in a strip a bar has reserved. */
    private boolean inViewport(float localX, float localY) {
        float left = viewportLeft();
        return localX >= left && localX < left + gutters.viewportWidth(width())
                && localY >= 0 && localY < viewportHeight();
    }

    // ------------------------------------------------------------------------ selection

    /**
     * How many rows may be selected at once; the default is {@link SelectionMode#SINGLE}. The
     * cursor stays where it is in every mode: {@code NONE} empties the selection, not the row the
     * keyboard is on.
     */
    public Tree<T> setSelectionMode(SelectionMode mode) {
        Ui.checkUiThread();
        this.selectionMode = Objects.requireNonNull(mode, "mode");
        // Damaged whether or not the selection moves: the mode is published (the tree's
        // multi-selectable flag, the verbs each row carries), and a publish rides on a damaged
        // frame. Found by a reader still offered ADD_TO_SELECTION on a tree set back to SINGLE.
        invalidate();
        if (mode == SelectionMode.NONE && !selected.isEmpty()) {
            selected.clear();
            lead = null;
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        } else if (mode == SelectionMode.SINGLE && selected.size() > 1) {
            T keep = lead != null && selected.contains(lead) ? lead : selected.iterator().next();
            selected.clear();
            selected.add(keep);
            lead = keep;
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        }
        return this;
    }

    public SelectionMode selectionMode() {
        return selectionMode;
    }

    /** @return the selected nodes, in the order they were selected */
    public List<T> selectedNodes() {
        return List.copyOf(selected);
    }

    /**
     * @return the row the keyboard is on, or {@code null} before the keyboard was in the tree:
     *         what the arrows move, what Enter activates, and the row a reader's cursor stands
     *         on. It moves in every mode, {@link SelectionMode#NONE} included, and is not the
     *         selection; see {@link #leadNode()} for that.
     */
    public T cursorNode() {
        return cursor;
    }

    /**
     * @return the selection's lead — the node the user selected last that is still selected —
     *         or {@code null} when nothing is selected. Until 2026-09-14 this was also the row the
     *         keyboard was on, which {@link #cursorNode()} answers now (decision 14): the two part
     *         in {@code MULTI}, where a row toggled off keeps the cursor and loses the lead, and in
     *         {@code NONE}, where the cursor moves and this is always {@code null}.
     */
    public T leadNode() {
        return lead;
    }

    /**
     * Selects exactly {@code node}, moving the cursor onto it and revealing it when it is a
     * visible row. A node under a closed branch is selected where it is — re-opening its parent
     * finds it selected — and neither revealed nor made the cursor, which stays on a row the user
     * can see (decision 21 of 2026-09-14). {@code null} clears the selection and leaves the
     * cursor where it is.
     */
    public Tree<T> setSelected(T node) {
        Ui.checkUiThread();
        selectOnly(node, true, Change.Origin.CODE);
        return this;
    }

    /**
     * Selects exactly {@code nodes}, in that order, with the last as the lead and under the
     * cursor: what an application restoring a saved selection calls. In
     * {@link SelectionMode#SINGLE} only one node may be named. Announces {@code SELECTION}/{@code
     * CODE} once when the set changed. UI thread only.
     *
     * @param nodes the nodes; none clears the selection
     * @return this tree
     * @throws IllegalStateException if the mode is {@link SelectionMode#NONE}, or SINGLE and
     *                               more than one node is named
     */
    public Tree<T> setSelectedNodes(Collection<? extends T> nodes) {
        Ui.checkUiThread();
        Objects.requireNonNull(nodes, "nodes");
        if (nodes.isEmpty()) {
            return clearSelection();
        }
        if (selectionMode == SelectionMode.NONE) {
            throw new IllegalStateException("selection mode is NONE");
        }
        if (selectionMode == SelectionMode.SINGLE && nodes.size() > 1) {
            throw new IllegalStateException("selection mode is SINGLE");
        }
        Set<T> before = new LinkedHashSet<>(selected);
        T wasCursor = cursor;
        selected.clear();
        T last = null;
        for (T node : nodes) {
            selected.add(Objects.requireNonNull(node, "node"));
            last = node;
        }
        boolean same = selected.equals(before) && Objects.equals(lead, last);
        lead = last;
        if (indexOf(last) >= 0) {
            cursor = last; // a hidden node is selected where it is, and the cursor stays visible
            rangeAnchor = last;
            revealNode(last);
        }
        damageSelectionChange(before, wasCursor);
        announceCursor(wasCursor, Change.Origin.CODE);
        if (!same) {
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.CODE));
        }
        return this;
    }

    /**
     * Drops the selection, announced as {@code SELECTION}/{@code CODE}; nothing happens when
     * nothing is selected. The cursor stays where it is. UI thread only.
     *
     * @return this tree
     */
    public Tree<T> clearSelection() {
        Ui.checkUiThread();
        if (selected.isEmpty()) {
            return this;
        }
        Set<T> before = new LinkedHashSet<>(selected);
        selected.clear();
        lead = null;
        damageSelectionChange(before, cursor);
        notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.CODE));
        return this;
    }

    /**
     * Selects every open row, in {@link SelectionMode#MULTI}: what is visible, so a node under
     * a closed branch is not taken (decision 31 of 2026-09-14). The lead is kept where it is in
     * the selection, or becomes the first row; the cursor does not move. A caller's write,
     * announced as {@code SELECTION}/{@code CODE}; Ctrl+A or Cmd+A enters the same seam as the
     * user's. UI thread only.
     *
     * @return this tree
     */
    public Tree<T> selectAll() {
        Ui.checkUiThread();
        selectAll(Change.Origin.CODE);
        return this;
    }

    private void selectAll(Change.Origin origin) {
        if (selectionMode != SelectionMode.MULTI || rows.isEmpty()) {
            return;
        }
        Set<T> before = new LinkedHashSet<>(selected);
        for (Row<T> row : rows) {
            if (!row.placeholder) {
                selected.add(row.node);
            }
        }
        if (selected.equals(before)) {
            return;
        }
        if (lead == null || !selected.contains(lead)) {
            lead = rows.get(0).node;
        }
        damageSelectionChange(before, cursor);
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    /**
     * The one place a single selection moves, and the one seam it announces from: the public
     * setter passes {@code CODE}, and every key and click passes {@code USER}. The cursor moves
     * first, in every mode, and is announced first as {@code ACTIVE} when it moved; the selection
     * follows where the mode allows one, announced only when it moved — the order {@code Table}
     * settled under ADR 040 §7.2.
     */
    private void selectOnly(T node, boolean reveal, Change.Origin origin) {
        selectOnly(node, reveal, true, origin);
    }

    /**
     * The same, with the cursor held back.
     *
     * @param moveCursor whether the cursor and the range anchor land on the node: a gesture's
     *                   answer is yes, because the pointer and the key are where the user is; a
     *                   reader's {@code SELECT} is no, because a client write is not (decision 79
     *                   of 2026-09-17). Measured cause: on AppKit a selection write leaves the
     *                   keyboard focus where it is, so VoiceOver's cursor sync — writing
     *                   {@code setAccessibilitySelectedRows:} with its own one-step-stale row —
     *                   dragged this cursor back nine times in nine (P5M-1). The reveal is not the
     *                   cursor and still happens: bringing the selected row into view is what the
     *                   write asks for, and what {@code ListView}'s own {@code SELECT} already did.
     */
    private void selectOnly(T node, boolean reveal, boolean moveCursor, Change.Origin origin) {
        T wasCursor = cursor;
        boolean visible = node != null && indexOf(node) >= 0;
        if (visible && moveCursor) {
            cursor = node;
        }
        if (reveal && visible) {
            revealNode(node);
        }
        if (selectionMode == SelectionMode.NONE) {
            damageCursorMove(wasCursor);
            announceCursor(wasCursor, origin);
            return;
        }
        if (visible && moveCursor) {
            rangeAnchor = node;
        }
        boolean same = node == null ? selected.isEmpty()
                : selected.size() == 1 && selected.contains(node);
        if (same) {
            lead = node;
            damageCursorMove(wasCursor);
            announceCursor(wasCursor, origin);
            return;
        }
        // The rows whose highlight moved, and only those: an arrow key that repainted the whole
        // tree would be correct and out of all proportion, which is the failure mode partial
        // rendering exists to catch (ADR 043 §9.2, and the same fix ListView took).
        for (T was : selected) {
            damageNode(was);
        }
        selected.clear();
        if (node != null) {
            selected.add(node);
        }
        lead = node;
        damageNode(node);
        damageCursorMove(wasCursor);
        announceCursor(wasCursor, origin);
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    /**
     * Adds or removes one node, which is what the command modifier and Space do in {@code MULTI}.
     * The lead leaves a node toggled off and falls back to the most recently selected node still
     * in the selection, as {@code Table}'s does, so a handler reading the lead is never handed
     * the row that was just deselected.
     *
     * @param moveCursor whether the cursor and the range anchor land on the node, revealed: the
     *                   gesture's answer (a click is where the user is), and not the reader
     *                   verbs' — {@code ADD_TO_SELECTION} and {@code DESELECT} change the
     *                   selection and leave the cursor and the anchor where they were (decision 20
     *                   of 2026-09-14, semantics 5: only {@code SELECT} and {@code FOCUS} move a
     *                   cursor)
     */
    private void toggleSelection(T node, boolean moveCursor, Change.Origin origin) {
        if (selectionMode != SelectionMode.MULTI) {
            selectOnly(node, true, origin);
            return;
        }
        T wasCursor = cursor;
        if (moveCursor) {
            cursor = node;
            rangeAnchor = node;
        }
        if (!selected.remove(node)) {
            selected.add(node);
            lead = node;
        } else if (Objects.equals(lead, node)) {
            lead = lastSelected();
        }
        if (moveCursor) {
            revealNode(node);
        }
        damageNode(node);
        if (moveCursor) {
            damageCursorMove(wasCursor);
            announceCursor(wasCursor, origin);
        }
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    /**
     * Replaces the selection with the visible rows between the range anchor and the row at
     * {@code index}, in traversal order, which is what Shift does in {@code MULTI} (decision 31
     * of 2026-09-14: parity with {@code Table}). A selected node hidden under a closed branch is
     * not between two visible rows and leaves, as {@code selectOnly} drops it; an anchor hidden
     * the same way stands for nothing, and the range is the target alone. The cursor and the
     * lead land on the target; the anchor stays.
     */
    private void selectRange(int index) {
        int to = Math.min(Math.max(0, index), rows.size() - 1);
        int from = rangeAnchor == null ? -1 : indexOf(rangeAnchor);
        if (from < 0) {
            from = to;
        }
        Set<T> before = new LinkedHashSet<>(selected);
        T wasCursor = cursor;
        selected.clear();
        for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
            Row<T> row = rows.get(i);
            if (!row.placeholder) {
                selected.add(row.node);
            }
        }
        T target = rows.get(to).node; // a loading line's node is its row's, as a click's is
        lead = target;
        cursor = target;
        revealNode(target);
        damageSelectionChange(before, wasCursor);
        announceCursor(wasCursor, Change.Origin.USER);
        if (!selected.equals(before)) {
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.USER));
        }
    }

    /**
     * Damages what a selection move changed: every row that entered or left the selection, and
     * the cursor's old and new bands — or the whole tree past {@link #MAX_DAMAGED_ROWS}, which
     * is the honest answer for a select-all (the table's rule, ADR 043 §9.2).
     */
    private void damageSelectionChange(Set<T> before, T wasCursor) {
        int changed = 0;
        for (T node : before) {
            if (!selected.contains(node)) {
                changed++;
            }
        }
        for (T node : selected) {
            if (!before.contains(node)) {
                changed++;
            }
        }
        if (changed > MAX_DAMAGED_ROWS) {
            invalidate();
            return;
        }
        for (T node : before) {
            if (!selected.contains(node)) {
                damageNode(node);
            }
        }
        for (T node : selected) {
            if (!before.contains(node)) {
                damageNode(node);
            }
        }
        damageCursorMove(wasCursor);
    }

    /** The node selected most recently among those still selected, or {@code null}. */
    private T lastSelected() {
        T last = null;
        for (T node : selected) {
            last = node;
        }
        return last;
    }

    /** {@code ACTIVE}, with the gesture's origin, when the cursor moved; nothing otherwise. */
    private void announceCursor(T wasCursor, Change.Origin origin) {
        if (!Objects.equals(cursor, wasCursor)) {
            notifyChange(Change.of(Change.Aspect.ACTIVE, origin));
        }
    }

    /** The bands the cursor left and reached, when it moved. */
    private void damageCursorMove(T wasCursor) {
        if (!Objects.equals(cursor, wasCursor)) {
            damageNode(wasCursor);
            damageNode(cursor);
        }
    }

    // ------------------------------------------------------- what outlives a hidden row

    /** Row index by node, for the rows that are nodes; built once per pass that needs it. */
    private Map<T, Integer> rowIndexByNode() {
        Map<T, Integer> at = new HashMap<>(rows.size() * 2);
        for (int i = 0; i < rows.size(); i++) {
            Row<T> row = rows.get(i);
            if (!row.placeholder) {
                at.put(row.node, i);
            }
        }
        return at;
    }

    /**
     * Records, for every selected, expanded or cursor node that is a row now, the path it sits
     * at: called before a collapse or a refresh takes rows away, so what is hidden can later be
     * verified without a walk. Paths of nodes that stay rows are dropped again by
     * {@link #forgetRevealedPaths}.
     *
     * <p>One pass over the rows, carrying the chain of ancestors by depth: the rows are in
     * traversal order, so the row at depth {@code d} is the current ancestor at that depth for
     * every row after it until another at {@code d} or shallower arrives. Each recorded path is
     * read off the chain, which costs the rows once plus the paths' own length — a walk back up
     * from each recorded row to its root cost the rows once per recorded node, and a select-all
     * over an open tree followed by one collapse scanned quadratically.
     */
    private void recordPaths() {
        if (selected.isEmpty() && expanded.isEmpty() && cursor == null) {
            return;
        }
        List<T> chain = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row<T> row = rows.get(i);
            if (row.placeholder) {
                continue; // the loading line is not a node, and its row is the chain already
            }
            while (chain.size() > row.depth) {
                chain.remove(chain.size() - 1);
            }
            chain.add(row.node);
            T node = row.node;
            if (node.equals(cursor) || selected.contains(node) || expanded.contains(node)) {
                hiddenPaths.put(node, new ArrayList<>(chain));
            }
        }
    }

    /** Forgets the path of every node that is a row again: a row is its own confirmation. */
    private void forgetRevealedPaths() {
        if (hiddenPaths.isEmpty()) {
            return;
        }
        Map<T, Integer> at = rowIndexByNode();
        hiddenPaths.keySet().removeIf(at::containsKey);
    }

    /** What verifying a hidden node's path against the model found. */
    private enum Verdict { CONFIRMED, REFUTED, UNKNOWN }

    /**
     * Verifies each hidden node along its recorded path and only that path: from the roots,
     * each step must be among the children of the step before, read from what a load brought
     * or what the model knows. A step the model no longer has refutes the node; a step whose
     * children are not known — a load in flight, or one nobody asked for — leaves it unknown,
     * and unknown is kept (decision 35). A refuted node leaves the selection, the expansion, the
     * cursor and the identifier table, announced as the tree's own adjustment.
     */
    private void verifyHidden() {
        if (hiddenPaths.isEmpty()) {
            return;
        }
        boolean selectionMoved = false;
        boolean cursorMoved = false;
        for (Map.Entry<T, List<T>> entry : List.copyOf(hiddenPaths.entrySet())) {
            if (verify(entry.getValue()) != Verdict.REFUTED) {
                continue;
            }
            T node = entry.getKey();
            hiddenPaths.remove(node);
            selectionMoved |= selected.remove(node);
            expanded.remove(node);
            ids.remove(node);
            if (node.equals(rangeAnchor)) {
                rangeAnchor = null;
            }
            if (node.equals(cursor)) {
                cursor = null;
                cursorMoved = true;
            }
        }
        if (lead != null && !selected.contains(lead)) {
            lead = lastSelected();
        }
        if (cursorMoved) {
            invalidate();
            notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.ADJUSTMENT));
        }
        if (selectionMoved) {
            invalidate();
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        }
    }

    private Verdict verify(List<T> path) {
        List<T> siblings = model.roots();
        for (int i = 0; i < path.size(); i++) {
            if (siblings == null) {
                return Verdict.UNKNOWN;
            }
            T step = path.get(i);
            if (!siblings.contains(step)) {
                return Verdict.REFUTED;
            }
            if (i + 1 < path.size()) {
                List<T> arrived = loaded.get(step);
                siblings = arrived != null ? arrived : model.children(step);
            }
        }
        return Verdict.CONFIRMED;
    }

    /**
     * Releases the identifier of every node the tree no longer keeps anything about: not a
     * row, not selected, not open, not the cursor. An identifier only has to stay stable while
     * its node is published, and none of these is (TREE-NEW-8: a long-lived tree over a
     * changing model retained every node it had ever shown).
     */
    private void releaseIdentifiers() {
        if (ids.isEmpty()) {
            return;
        }
        Map<T, Integer> at = rowIndexByNode();
        ids.keySet().removeIf(node -> !at.containsKey(node) && !selected.contains(node)
                && !expanded.contains(node) && !node.equals(cursor));
    }

    /**
     * Announces that the cursor row was opened, as {@code INVOKED}/{@code CODE}: a caller's verb,
     * which reaches a watcher and <b>not</b> {@link #onActivate}, the way Enter does. Nothing
     * without a cursor row. UI thread only.
     */
    public void activate() {
        Ui.checkUiThread();
        activate(Change.Origin.CODE);
    }

    /**
     * The seam Enter, a double click and a {@code PRESS} on the tree's own node enter at
     * {@code USER}: the cursor row, in every mode (decision 32 of 2026-09-14) — in {@code NONE}
     * the row the keyboard is on is what activates, because it is the one row the user has
     * pointed at. A reader's {@code PRESS} on a row takes {@link #activate(Object, Change.Origin)}
     * instead and names that row (decision 80 of 2026-09-17).
     */
    private void activate(Change.Origin origin) {
        activate(cursor, origin);
    }

    /** The node the last activation opened, read by {@link #handleUserChange}. */
    private T activated;

    /**
     * The same seam, naming the row that was opened rather than the one the cursor is on: a
     * reader's {@code PRESS} arrives addressed to a row, and since decision 79 its {@code SELECT}
     * no longer drags the cursor there, so the two can differ (decision 80 of 2026-09-17). Enter
     * and a double click pass the cursor row and are unchanged.
     */
    private void activate(T node, Change.Origin origin) {
        if (node != null) {
            activated = node;
            notifyChange(Change.of(Change.Aspect.INVOKED, origin));
        }
    }

    // ------------------------------------------------------------------------- handlers

    /**
     * The application's response to the user changing the selection: a click, a key, an
     * assistive technology's select. The selection is read back from {@link #selectedNodes()},
     * and the lead from {@link #leadNode()}; the handler takes no node because a selection is a
     * set, and the node a toggle removed is not one to hand anybody (decision 14 of 2026-09-14).
     * Never for {@link #setSelected}, or a {@link #refresh()} or {@link #setSelectionMode} that
     * moved it, which are the caller's or the tree's own; to hear every change whatever caused
     * it, {@linkplain #observeChanges watch} the tree.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this tree
     * @throws IllegalStateException if a handler is already registered
     */
    public Tree<T> onSelect(Runnable handler) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, handler, "Tree.onSelect");
        return this;
    }

    /**
     * The application's response to the user opening a row: Enter, a double click, an assistive
     * technology's press. Handed <b>the row that was opened</b> — the cursor row for Enter and a
     * double click, which in {@code NONE} is a row that was never selected, and the addressed row
     * for a reader's {@code PRESS}, which since decision 80 of 2026-09-17 need not be the cursor's.
     * Never for {@link #activate()}, which is a caller's verb.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this tree
     * @throws IllegalStateException if a handler is already registered
     */
    public Tree<T> onActivate(Consumer<T> handler) {
        Ui.checkUiThread();
        this.onActivate = Checks.handlerSlot(onActivate, handler, "Tree.onActivate");
        return this;
    }

    /** Runs when the user opens a row. */
    public Tree<T> onExpand(Consumer<T> handler) {
        Ui.checkUiThread();
        this.onExpand = Checks.handlerSlot(onExpand, handler, "Tree.onExpand");
        return this;
    }

    /** Runs when the user closes a row. */
    public Tree<T> onCollapse(Consumer<T> handler) {
        Ui.checkUiThread();
        this.onCollapse = Checks.handlerSlot(onCollapse, handler, "Tree.onCollapse");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        switch (aspect) {
            case SELECTION -> {
                if (onSelect != null) {
                    onSelect.run();
                }
            }
            case INVOKED -> {
                if (onActivate != null) {
                    onActivate.accept(activated);
                }
            }
            case EXPANDED -> {
                T node = toggled;
                if (node == null) {
                    return;
                }
                Consumer<T> handler = expanded.contains(node) ? onExpand : onCollapse;
                if (handler != null) {
                    handler.accept(node);
                }
            }
            default -> super.handleUserChange(aspect);
        }
    }

    // --------------------------------------------------------------------------- layout

    private SizeTokens tokens() {
        return Theme.current().tokensFor(this);
    }

    /** The indent one level costs: the triangle's own width and the gap beside it. */
    private static float indent(SizeTokens t) {
        return t.chevronHalfW() * 2 + t.spacingMedium();
    }

    /** The band a row reserves for its triangle, whether or not it draws one. */
    private static float twistyBand(SizeTokens t) {
        return t.chevronHalfW() * 2 + t.spacingSmall() * 2;
    }

    private float avgRowHeight(SizeTokens t) {
        return measuredRowHeight > 0 ? measuredRowHeight : t.listRowSeed();
    }

    private float estimatedContentHeight(SizeTokens t) {
        return rows.size() * avgRowHeight(t);
    }

    private float estimatedOffset(SizeTokens t) {
        return Math.max(0, anchorIndex * avgRowHeight(t) - anchorTop);
    }

    /**
     * How wide the outline is, which is the viewport itself until the indent outgrows it.
     *
     * <p>The deepest row decides it. The alternative, and what this widget shipped with, is to
     * clamp the indent so a row can never start past the edge — which makes a deep tree lie about
     * its own shape, drawing level twelve where level eight sits and flattening exactly the
     * structure someone navigating deeply is reading. The content grows instead, and the box
     * scrolls over it.
     *
     * <p>The deepest row keeps {@link #deepestCellWidth} of cell: what the model declares
     * through {@link Model#maxCellWidth}, never more than a root row's cell in the box, or else
     * {@code menuMinWidth} — the toolkit's existing floor for the narrowest strip a row of text
     * may be read in, capped by the viewport so a narrow tree never asks for more content than
     * one screenful. Where nothing is deep the maximum is the viewport and this returns exactly
     * that — so a shallow tree has no horizontal bar, no offset, and the cell widths (and the
     * ellipsis) it has always had. (Undeclared, a box narrower than the triangle band plus
     * {@code menuMinWidth} is the exception: the guess, kept as it was, overhangs it by up to the
     * band.)
     */
    private float estimatedContentWidth(SizeTokens t, float viewW) {
        float deepest = maxDepth * indent(t) + twistyBand(t) + deepestCellWidth(t, viewW);
        return Math.max(viewW, deepest);
    }

    /**
     * The width the deepest row's cell is promised: the model's declared width when it gave a
     * usable one (decision 50 of 2026-09-14), capped at what the box leaves a root row past its
     * triangle; else the menu's minimum capped by the viewport, which is what the tree guessed
     * before a model could say, unchanged.
     *
     * <p>The cap is what keeps the declared width a cap. Taken whole, a width wider than the box
     * made a flat tree — one with no depth to show — scroll sideways by the difference, and the
     * outline grow by something other than the indent its depth charges.
     */
    private float deepestCellWidth(SizeTokens t, float viewW) {
        float declared = model.maxCellWidth();
        return declared > 0 && Float.isFinite(declared)
                ? Math.min(declared, Math.max(0, viewW - twistyBand(t)))
                : Math.min(viewW, t.menuMinWidth());
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = tokens();
        float w = constraints.hasBoundedWidth() ? constraints.maxWidth() : t.listWidth();
        // The seed and not the realized average: a preference that moved with the mean of
        // whatever rows happened to be mounted changed the tree's size on every scroll that
        // mounted rows of another height, and a contained layout that moves the widget's size
        // escalates to the parent (decision 44 of 2026-09-14).
        float h = constraints.hasBoundedHeight() ? constraints.maxHeight()
                : visibleRows * t.listRowSeed();
        return constraints.constrain(w, h);
    }

    @Override
    protected void onLayout() {
        float box = width();
        float h = height();
        if (box <= 0 || h <= 0) {
            return;
        }
        boolean rtl = isRightToLeft();
        SizeTokens t = tokens();
        gutters.resolve(box, h, vBar, hBar, (viewW, viewH) ->
                new Size(estimatedContentWidth(tokens(), viewW), estimatedContentHeight(tokens())));
        float w = gutters.viewportWidth(box);
        float viewH = gutters.viewportHeight(h);
        // Settled before anything is placed, because every row's width and every row's x are
        // measured against it, and the offset has to be clamped to whatever it just became.
        contentWidth = estimatedContentWidth(t, w);
        offsetX = Math.max(0, Math.min(offsetX, Math.max(0, contentWidth - w)));
        float barT = ScrollBar.thickness();
        vBar.measure(Constraints.tight(barT, viewH));
        vBar.layoutBox(rtl ? 0 : box - barT, 0, barT, viewH);
        hBar.measure(Constraints.tight(w, barT));
        hBar.layoutBox(rtl ? box - w : 0, h - barT, w, barT);
        float rowX = rtl ? box - w : 0;

        releaseOrphans(); // inside the pass, where the scene absorbs the removal
        int count = rows.size();
        if (count == 0) {
            recycleExcept(0, 0, 0);
            anchorIndex = 0;
            anchorTop = 0;
            vBar.refresh();
            hBar.refresh();
            return;
        }
        anchorIndex = Math.min(anchorIndex, count - 1);

        // The content width and not the viewport: a row is measured at the width it will be laid
        // out at, which past the clamp is wider than the box.
        normalizeUp(contentWidth);
        normalizeDown(count, contentWidth);
        if (revealPending != null) {
            settleReveal(count, viewH);
        }
        float bottom = placeDown(count, rowX, w, viewH);
        if (bottom < viewH && !(anchorIndex == 0 && anchorTop >= 0)) {
            anchorTop += viewH - bottom;
            normalizeUp(contentWidth);
            normalizeDown(count, contentWidth);
            bottom = placeDown(count, rowX, w, viewH);
        }
        recycleExcept(placedFrom, placedTo, count);
        keepCursorRowRealized(count);
        placeKeptOutside(rowX, w, bottom);
        updateAverageHeight();
        vBar.refresh();
        hBar.refresh();
    }

    /**
     * Brings the row {@link #revealNode} deferred into the viewport by the least scroll, from the
     * rows' measured heights: a row above the box, or cut by its top, is placed with its top on
     * the box's top; a row below it, or cut by its foot, with its bottom on the box's bottom — or
     * its top on the top, when it is taller than the box, which is {@link #revealVertically}'s
     * rule. The anchor is set to the row itself, so the distance is exact whatever the rows between
     * measure; only the rows from the anchor to the box's foot are measured to see whether the row
     * is already in it, and those the pass places anyway. A node no longer visible is dropped.
     */
    private void settleReveal(int count, float viewH) {
        int index = indexOf(revealPending);
        revealPending = null;
        if (index < 0 || index >= count) {
            return;
        }
        if (index < anchorIndex || (index == anchorIndex && anchorTop < 0)) {
            anchorIndex = index;
            anchorTop = 0;
            return;
        }
        float top = anchorTop;
        for (int i = anchorIndex; i < index && top < viewH; i++) {
            top += measuredHeight(i, contentWidth);
        }
        float rowH = measuredHeight(index, contentWidth);
        if (top < viewH && top + rowH <= viewH) {
            return;
        }
        anchorIndex = index;
        anchorTop = Math.max(0, viewH - rowH);
        normalizeUp(contentWidth);
    }

    private void normalizeUp(float w) {
        while (anchorTop > 0 && anchorIndex > 0) {
            anchorTop -= measuredHeight(anchorIndex - 1, w);
            anchorIndex--;
        }
        if (anchorIndex == 0 && anchorTop > 0) {
            anchorTop = 0;
        }
    }

    private void normalizeDown(int count, float w) {
        while (anchorIndex < count - 1) {
            float h = measuredHeight(anchorIndex, w);
            if (anchorTop + h <= 0) {
                anchorTop += h;
                anchorIndex++;
            } else {
                break;
            }
        }
    }

    private float placeDown(int count, float rowX, float viewW, float viewport) {
        SizeTokens t = tokens();
        boolean rtl = isRightToLeft();
        float y = anchorTop;
        int i = anchorIndex;
        while (i < count && y < viewport) {
            float rowH = measuredHeight(i, contentWidth);
            Widget cell = cellFor(i);
            float lead = cellLeft(t, i, contentWidth);
            float cellW = Math.max(0, contentWidth - lead);
            cell.layoutBox(cellX(rowX, viewW, lead, cellW, rtl), y, cellW, rowH);
            y += rowH;
            i++;
        }
        placedFrom = anchorIndex;
        placedTo = i;
        return y;
    }

    /**
     * Where a row's cell sits on the width axis once the outline is scrolled.
     *
     * <p>The mirrored form is the table's, for the table's reason: read right to left the content
     * hangs off the box's trailing edge, so the same offset has to walk the cells the other way.
     * With nothing to scroll both arms collapse to what this widget did before there was an
     * offset at all — {@code rowX + lead} one way, {@code rowX} the other.
     */
    private float cellX(float rowX, float viewW, float lead, float cellW, boolean rtl) {
        return rtl ? rowX + viewW - (lead + cellW) + offsetX : rowX + lead - offsetX;
    }

    /** How much of the row's width the indent and the triangle take before the cell starts. */
    private float cellLeft(SizeTokens t, int index, float w) {
        float lead = rows.get(index).depth * indent(t) + twistyBand(t);
        return Math.min(lead, Math.max(0, w - t.chevronHalfW() * 2));
    }

    private float measuredHeight(int index, float w) {
        SizeTokens t = tokens();
        Widget cell = cellFor(index);
        if (cell == null) {
            Row<T> row = rows.get(index);
            cell = row.placeholder ? lineUnderRow(row.loading)
                    : Objects.requireNonNull(model.cellFor(row.node), "Model.cellFor returned null");
            mount(index, cell);
            cell.setVisible(true);
        }
        float available = Math.max(0, w - cellLeft(t, index, w));
        return cell.measure(new Constraints(available, available, 0, Constraints.UNBOUNDED_LIMIT))
                .height();
    }

    private Widget cellFor(int index) {
        for (int i = 0; i < mountedCount; i++) {
            if (mountedRows[i] == index) {
                return mountedCells[i];
            }
        }
        return null;
    }

    /**
     * Mounts {@code cell} as row {@code index}, into the position that keeps both the mounted run
     * and {@code children()} in traversal order — which is reading order and Tab order, and is
     * not the order an upward scroll realizes rows in.
     */
    private void mount(int index, Widget cell) {
        int at = 0;
        while (at < mountedCount && mountedRows[at] < index) {
            at++;
        }
        add(at + 2, cell); // the two bars are children() zero and one
        if (mountedCount == mountedRows.length) {
            mountedRows = Arrays.copyOf(mountedRows, mountedCount * 2);
            mountedCells = Arrays.copyOf(mountedCells, mountedCount * 2);
            mountedNodes = Arrays.copyOf(mountedNodes, mountedCount * 2);
            mountedNames = Arrays.copyOf(mountedNames, mountedCount * 2);
        }
        System.arraycopy(mountedRows, at, mountedRows, at + 1, mountedCount - at);
        System.arraycopy(mountedCells, at, mountedCells, at + 1, mountedCount - at);
        System.arraycopy(mountedNodes, at, mountedNodes, at + 1, mountedCount - at);
        System.arraycopy(mountedNames, at, mountedNames, at + 1, mountedCount - at);
        mountedRows[at] = index;
        mountedCells[at] = cell;
        mountedNodes[at] = mountKey(rows.get(index));
        mountedNames[at] = null;
        mountedCount++;
    }

    /**
     * Recycles every mounted row outside {@code [from, toExclusive)}, sparing the one holding the
     * keyboard focus while its index is still below {@code count} — a reader whose cursor follows
     * the focus loses its place when the node it stands on leaves the tree (ADR 039 §13.29) —
     * and, while the tree itself holds the keyboard, the cursor row: the reader's cursor is that
     * row's {@code ACTIVE} node, and a wheel that recycled it took the cursor away (decision 22
     * of 2026-09-14; TREE-NEW-6). Released by the first pass after the focus leaves.
     */
    private void recycleExcept(int from, int toExclusive, int count) {
        int kept = 0;
        boolean keepCursor = cursor != null && isFocused();
        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            Widget cell = mountedCells[i];
            boolean inRun = row >= from && row < toExclusive;
            boolean hasFocus = !inRun && containsFocus(cell);
            boolean isCursor = !inRun && keepCursor && row < count
                    && !rows.get(row).placeholder && rows.get(row).node.equals(cursor);
            if (inRun || (hasFocus && row < count) || isCursor) {
                mountedRows[kept] = row;
                mountedCells[kept] = cell;
                mountedNodes[kept] = mountedNodes[i];
                mountedNames[kept] = mountedNames[i];
                kept++;
                continue;
            }
            unmount(cell, mountedNodes[i], hasFocus);
        }
        for (int i = kept; i < mountedCount; i++) {
            mountedCells[i] = null;
            mountedNodes[i] = null;
            mountedNames[i] = null;
        }
        mountedCount = kept;
    }

    /** Releases the cells whose rows vanished since the last pass; see {@link #orphanCells}. */
    private void releaseOrphans() {
        for (int i = 0; i < orphanCells.size(); i++) {
            Widget cell = orphanCells.get(i);
            unmount(cell, orphanKeys.get(i), containsFocus(cell));
        }
        orphanCells.clear();
        orphanKeys.clear();
    }

    /**
     * Takes a cell out of the tree and hands it back to the model, bringing the keyboard focus
     * back to the tree when it was inside, so a row that leaves does not take the focus with it.
     */
    private void unmount(Widget cell, Object key, boolean hadFocus) {
        remove(cell);
        if (!(key instanceof LineKey)) {
            model.recycle(cell); // a line under a row is the tree's, and the model never built it
        }
        if (hadFocus) {
            requestFocus();
        }
    }

    /** Whether the keyboard focus is inside {@code cell}; the list's own test, for its reason. */
    private boolean containsFocus(Widget cell) {
        Widget focused = scene() != null ? scene().focusedWidget() : null;
        for (Widget w = focused; w != null; w = w.parent()) {
            if (w == cell) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mounts the cursor row when the tree holds the keyboard and the pass left it unrealized: a
     * refresh releases every cell, because each is bound to data the model may have replaced,
     * and a reorder releases a cell that moved against the traversal; the cursor row comes back
     * fresh from the model either way, placed outside the viewport by {@link #placeKeptOutside}
     * (decision 22 of 2026-09-14). Nothing to do while the row is in the placed run, which is
     * where a scroll that did not spare it would have put it.
     */
    private void keepCursorRowRealized(int count) {
        if (cursor == null || !isFocused()) {
            return;
        }
        int index = indexOf(cursor);
        if (index < 0 || index >= count || cellFor(index) != null) {
            return;
        }
        measuredHeight(index, contentWidth); // mounts it; placeKeptOutside lays it out
    }

    /**
     * Puts a spared row wholly outside the viewport, on the side its index lies, at the height
     * it measures — the placed run's own rule. A cell the pass just mounted has no height of its
     * own yet, and the row laid out at that height stood at zero: a zero-height {@code ACTIVE}
     * node to a reader, and a zero in the average that sizes the scroll estimate.
     */
    private void placeKeptOutside(float rowX, float viewW, float bottom) {
        SizeTokens t = tokens();
        boolean rtl = isRightToLeft();
        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            if (row >= placedFrom && row < placedTo) {
                continue;
            }
            Widget cell = mountedCells[i];
            float rowH = measuredHeight(row, contentWidth);
            float lead = cellLeft(t, row, contentWidth);
            float cellW = Math.max(0, contentWidth - lead);
            float y = row < placedFrom ? Math.min(anchorTop, 0) - rowH
                    : Math.max(bottom, viewportHeight());
            cell.layoutBox(cellX(rowX, viewW, lead, cellW, rtl), y, cellW, rowH);
        }
    }

    /**
     * The mean height of the rows the pass laid out in the viewport's run, and of no other mounted
     * cell. The cursor row the tree keeps while it holds the keyboard, and a cell kept because a
     * control inside it has focus, are mounted wherever they sit; averaged in, a kept row of
     * another height skewed the estimate the scroll clamps and the wheel's hand-off read, so at
     * the real end the estimate sat short of its maximum (a kept row taller than the rest: every
     * detent taken, nothing moved, the wheel walled in a scroll pane) or past it (shorter: the
     * tree stopped short of its last row and the pane moved). Over the placed run alone the
     * estimate's remaining distance is exact once the last row is placed — {@code (count −
     * anchorIndex) × mean} is then the run's own height — and positive while any row is not.
     */
    private void updateAverageHeight() {
        float total = 0;
        int counted = 0;
        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            if (row >= placedFrom && row < placedTo) {
                total += mountedCells[i].height();
                counted++;
            }
        }
        if (counted > 0) {
            measuredRowHeight = total / counted;
        }
    }

    // --------------------------------------------------------------------------- scroll

    /** Scrolls by a delta in logical points (positive = toward the end). UI thread only. */
    public void scrollBy(float dy) {
        Ui.checkUiThread();
        revealPending = null; // a scroll after a reveal the pass has not settled moves from here
        SizeTokens t = tokens();
        float offset = estimatedOffset(t);
        float max = Math.max(0, estimatedContentHeight(t) - viewportHeight());
        float applied = Math.min(Math.max(0, offset + dy), max) - offset;
        if (applied == 0) {
            return;
        }
        anchorTop -= applied;
        for (int i = 0; i < mountedCount; i++) {
            Widget cell = mountedCells[i];
            moveChild(cell, cell.x(), cell.y() - applied);
        }
        markNeedsContainedLayout();
        invalidate();
        vBar.onScrolled();
    }

    /**
     * Whether a scroll of {@code dy} would move anything: not at the end it points to. Read off
     * the estimate, which agrees with the layout at either end, up to rounding, because
     * {@link #updateAverageHeight} averages only the rows the pass placed.
     */
    private boolean canScrollBy(float dy) {
        SizeTokens t = tokens();
        float offset = estimatedOffset(t);
        float max = Math.max(0, estimatedContentHeight(t) - viewportHeight());
        return dy < 0 ? offset > 0 : offset < max;
    }

    /** Whether a sideways scroll of {@code dx} would move anything. */
    private boolean canScrollHorizontallyBy(float dx) {
        float max = Math.max(0, contentWidth - gutters.viewportWidth(width()));
        return dx < 0 ? offsetX > 0 : offsetX < max;
    }

    /**
     * Scrolls sideways by a delta in logical points, positive toward the trailing edge. A tree
     * whose content fits its box has nothing to do here. UI thread only.
     */
    public void scrollHorizontallyBy(float dx) {
        Ui.checkUiThread();
        float max = Math.max(0, contentWidth - gutters.viewportWidth(width()));
        float next = Math.min(Math.max(0, offsetX + dx), max);
        if (next == offsetX) {
            return;
        }
        float applied = next - offsetX;
        offsetX = next;
        float sign = isRightToLeft() ? 1 : -1;
        for (int i = 0; i < mountedCount; i++) {
            Widget cell = mountedCells[i];
            moveChild(cell, cell.x() + sign * applied, cell.y());
        }
        markNeedsContainedLayout();
        invalidate();
        hBar.onScrolled();
    }

    @Override
    public void revealRect(float x, float y, float rectWidth, float rectHeight) {
        Ui.checkUiThread();
        revealVertically(y, rectHeight);
        revealSideways(x, rectWidth);
    }

    private void revealVertically(float y, float rectHeight) {
        float viewH = viewportHeight();
        if (y < 0) {
            scrollBy(y);
        } else if (y + rectHeight > viewH) {
            scrollBy(Math.min(y, y + rectHeight - viewH));
        }
    }

    /**
     * The sideways half of a reveal: the distance the span sits outside the viewport, measured
     * from the viewport's own left edge — past the reserved strip reading right to left, where
     * the box's edge is the bar's — and mirrored it points the other way. Nothing moves while the
     * span is inside.
     */
    private void revealSideways(float x, float rectWidth) {
        float left = viewportLeft();
        float viewW = gutters.viewportWidth(width());
        float dx = 0;
        if (x < left) {
            dx = x - left;
        } else if (x + rectWidth > left + viewW) {
            dx = Math.min(x - left, x + rectWidth - (left + viewW));
        }
        if (dx != 0) {
            scrollHorizontallyBy(isRightToLeft() ? -dx : dx);
        }
    }

    /**
     * Scrolls the minimum so {@code node}'s row is visible, if it is one: its band vertically,
     * and sideways the row's triangle band plus the leading part of its cell — as much of it as
     * the deepest row is promised, never more than the viewport — so the keyboard walking onto a
     * deep row brings its name into view (TREE-NEW-5, decision 50 of 2026-09-14). Minimal both
     * ways: walking Up and Down through rows of mixed depth moves the outline sideways only when
     * a row's start is outside the box, not on every arrow.
     *
     * <p>Not sideways for a press of the pointer, which is set around a press's own selection
     * ({@link #pointerPress}): the pointer is already on a part of the row the user can see, and
     * an outline that jumped under it to show the row's start would move what was just clicked.
     */
    private void revealNode(T node) {
        int index = indexOf(node);
        if (index < 0) {
            return;
        }
        SizeTokens t = tokens();
        Widget cell = cellFor(index);
        if (cell != null && cell.y() + cell.height() > 0 && cell.y() < viewportHeight()) {
            // Nothing moves until a pass settles a deferred reveal, so this box is where the row
            // stands; the later reveal is the one kept (End then Home in one batch ends on top).
            revealPending = null;
            revealVertically(cell.y(), cell.height());
        } else {
            // A row outside the viewport has no box that says how far to scroll: one not mounted
            // has none, and one kept mounted there — the cursor row while the tree holds the
            // keyboard (decision 22), or a cell holding the focus — is laid out at the viewport's
            // edge and not where it stands in the outline. The anchor's estimate counted the
            // rows between in average rows and stopped short over rows of uneven height, so the
            // pass settles it from their measured heights instead (settleReveal).
            revealPending = node;
            markNeedsContainedLayout();
            invalidate();
            vBar.onScrolled();
        }
        if (pointerPress) {
            return;
        }
        float viewW = gutters.viewportWidth(width());
        float band = twistyBand(t);
        float cellW = Math.max(0, contentWidth - cellLeft(t, index, contentWidth));
        float span = Math.min(viewW, band + Math.min(cellW, deepestCellWidth(t, viewW)));
        float bandLeft = twistyLeft(t, rows.get(index).depth);
        revealSideways(isRightToLeft() ? bandLeft + band - span : bandLeft, span);
    }

    /**
     * Damages one row's band rather than the tree, when what changed is that row's highlight.
     *
     * <p>The viewport's width and no outset: the wash is drawn across the row and inside its own
     * box, so it reaches nothing this rectangle does not already hold. Clamped to the viewport
     * and not the box, as the spinner's damage is, because damage is clipped by every ancestor
     * that clips its children and a widget is not its own ancestor: under a reserved strip the
     * band is clipped out of the paint, and damaging the strip repainted the bar for it on
     * every arrow (TREE-MISS-7). A row that is not mounted has nothing on screen to damage, and
     * the reveal that brings it on screen damages the tree on its own.
     */
    private void damageNode(T node) {
        if (node == null) {
            return;
        }
        int index = indexOf(node);
        Widget cell = index < 0 ? null : cellFor(index);
        if (cell == null) {
            return;
        }
        float top = Math.max(0, cell.y());
        float bottom = Math.min(viewportHeight(), cell.y() + cell.height());
        if (bottom > top) {
            invalidate(viewportLeft(), top, gutters.viewportWidth(width()), bottom - top);
        }
    }

    /**
     * The mounted cell drawing {@code node}, or null: a scan of the mounted slots and not of every
     * visible row, because the paint path asks it on every frame the tree holds the keyboard,
     * spinner ticks and bar fades included, and the rows can number thousands where the mounted
     * slots are a screenful.
     */
    private Widget mountedCellOf(T node) {
        for (int i = 0; i < mountedCount; i++) {
            int index = mountedRows[i];
            if (index < rows.size()) {
                Row<T> row = rows.get(index);
                if (!row.placeholder && row.node.equals(node)) {
                    return mountedCells[i];
                }
            }
        }
        return null;
    }

    private int indexOf(T node) {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).placeholder && rows.get(i).node.equals(node)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------ keyboard

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed()) {
            return;
        }
        boolean rtl = isRightToLeft();
        int mods = event.modifiers();
        // Shift extends a range in MULTI, from the anchor to wherever the key lands (decision 31).
        boolean extend = selectionMode == SelectionMode.MULTI && (mods & Keys.MOD_SHIFT) != 0;
        switch (event.key()) {
            case Keys.DOWN -> consumeAnd(event, () -> moveLead(1, extend));
            case Keys.UP -> consumeAnd(event, () -> moveLead(-1, extend));
            case Keys.PAGE_DOWN -> consumeAnd(event, () -> moveLead(rowsPerPage(tokens()), extend));
            case Keys.PAGE_UP -> consumeAnd(event, () -> moveLead(-rowsPerPage(tokens()), extend));
            case Keys.HOME -> consumeAnd(event, () -> selectAt(0, 1, extend));
            case Keys.END -> consumeAnd(event, () -> selectAt(rows.size() - 1, -1, extend));
            case Keys.A -> {
                if ((mods & Accelerator.commandModifier()) != 0
                        && selectionMode == SelectionMode.MULTI) {
                    consumeAnd(event, () -> selectAll(Change.Origin.USER));
                }
            }
            // Right opens a closed row and steps into an open one; Left closes an open row and
            // steps to the parent of a closed one. Reading right to left the two swap, as every
            // other pair of horizontal arrows in this toolkit does.
            case Keys.RIGHT -> consumeAnd(event, () -> stepOut(!rtl));
            case Keys.LEFT -> consumeAnd(event, () -> stepOut(rtl));
            case Keys.ENTER -> {
                if (cursor != null) {
                    consumeAnd(event, () -> activate(Change.Origin.USER));
                }
            }
            case Keys.SPACE -> {
                if (cursor != null && selectionMode == SelectionMode.MULTI) {
                    consumeAnd(event, () -> toggleSelection(cursor, true, Change.Origin.USER));
                }
            }
            default -> {
            }
        }
    }

    private static void consumeAnd(KeyEvent event, Runnable action) {
        event.consume();
        action.run();
    }

    /**
     * The horizontal pair, resolved into the one question they ask: {@code opening} true is the
     * arrow that goes deeper, false the one that comes back.
     */
    private void stepOut(boolean opening) {
        if (cursor == null) {
            selectAt(0);
            return;
        }
        int index = indexOf(cursor);
        if (index < 0) {
            return;
        }
        Row<T> row = rows.get(index);
        if (opening) {
            if (row.expandable && !row.expanded) {
                setExpanded(row.node, true, Change.Origin.USER);
            } else if (row.expanded && index + 1 < rows.size()
                    && rows.get(index + 1).depth > row.depth && !rows.get(index + 1).placeholder) {
                // Into the first child, and only a child: onto a line — still loading, or
                // empty — or onto the next row of the same or a shallower depth there is
                // nothing to step to, and the arrow stays on the row (TREE-MISS-1).
                selectAt(index + 1);
            }
            return;
        }
        if (row.expanded) {
            setExpanded(row.node, false, Change.Origin.USER);
            return;
        }
        for (int i = index - 1; i >= 0; i--) {
            if (rows.get(i).depth < row.depth) {
                selectAt(i);
                return;
            }
        }
    }

    /**
     * Moves the cursor by {@code delta} rows, or lands it on the anchor when there is none.
     *
     * <p>The first arrow press does <b>not</b> step: with nothing selected it lands on the row
     * the viewport starts at, so Down out of nowhere selects the first row rather than the
     * second. {@code ListView} answers the same way for the same reason — a dead-ended arrow is
     * not a programming error, and neither is a first one.
     */
    private void moveLead(int delta, boolean extend) {
        if (rows.isEmpty()) {
            return;
        }
        if (cursor == null) {
            selectAt(anchorIndex, 1, extend);
            return;
        }
        int from = indexOf(cursor);
        selectAt(from < 0 ? anchorIndex : from + delta, delta, extend);
    }

    private void selectAt(int index) {
        selectAt(index, 1, false);
    }

    /**
     * Selects the row at {@code index}, or past a loading line there, the way the cursor was
     * travelling: {@code toward} negative is upward.
     *
     * <p>A line's node is its row's, so without the skip Down from a loading row landed on the
     * line, resolved to the row it was already on, and could never get below it. When no row lies
     * that way (a line at the very end) the row above is taken, which is the line's own row; a
     * root is never a line, so there always is one.
     */
    private void selectAt(int index, int toward, boolean extend) {
        if (rows.isEmpty()) {
            return;
        }
        int at = Math.min(Math.max(0, index), rows.size() - 1);
        int step = toward < 0 ? -1 : 1;
        int found = at;
        while (found >= 0 && found < rows.size() && rows.get(found).placeholder) {
            found += step;
        }
        if (found < 0 || found >= rows.size()) {
            found = at;
            while (rows.get(found).placeholder) {
                found--;
            }
        }
        if (extend) {
            selectRange(found);
        } else {
            selectOnly(rows.get(found).node, true, Change.Origin.USER);
        }
    }

    /** A page is a viewport of rows: a count derived from the current estimate, not a token. */
    private int rowsPerPage(SizeTokens t) {
        return Math.max(1, (int) (viewportHeight() / Math.max(1, avgRowHeight(t))));
    }

    // --------------------------------------------------------------------------- pointer

    @Override
    protected void onMouseEvent(MouseEvent event) {
        switch (event.type()) {
            case WHEEL -> {
                // A detent is a device unit: the same flick travels the same distance in a
                // dense tree and a roomy one, so the step is locked rather than tabled.
                //
                // Sideways is the table's convention, and two devices reach it by different
                // roads: a trackpad sends a horizontal gesture as scrollX, beside whatever
                // scrollY the same flick carries, while a mouse with one wheel says the same
                // thing by holding Shift. Both axes of one event are applied, each where the
                // tree can still move that way: a diagonal flick that scrolled one axis and
                // dropped the other was the table's TABLE-NEW-12, and the same code sat here.
                //
                // Consumed only where something moved, so a detent that finds the tree at
                // either end of its scroll — or a tree whose content fits — reaches the
                // scroller that holds it (decision 44 of 2026-09-14). Before, the wheel was
                // swallowed whenever the tree could scroll at all, and a tree inside a scroll
                // pane was a wall the wheel could not get past.
                //
                // Shift swaps only an event with no sideways half: macOS already turns Shift and
                // a notch into scrollX, and a trackpad swipe with Shift held carries its own, so
                // reading the sideways axis from scrollY there found nothing and let the event
                // through (the scroll pane and the table swap the same way).
                boolean swap = (event.modifiers() & Keys.MOD_SHIFT) != 0 && event.scrollX() == 0;
                float dx = -(swap ? event.scrollY() : event.scrollX()) * Strokes.WHEEL_STEP;
                float dy = swap ? 0 : -event.scrollY() * Strokes.WHEEL_STEP;
                boolean moved = false;
                if (dy != 0 && canScrollBy(dy)) {
                    scrollBy(dy);
                    moved = true;
                }
                if (dx != 0 && canScrollHorizontallyBy(dx)) {
                    scrollHorizontallyBy(dx);
                    moved = true;
                }
                if (moved) {
                    event.consume();
                }
                return;
            }
            // What tells an overlay scroll bar that the pointer is over its host, which is the
            // whole of how it reveals itself without a frame of its own.
            case MOVE, DRAG -> {
                vBar.onHostActivity();
                hBar.onHostActivity();
                return;
            }
            default -> {
            }
        }
        if (event.type() != MouseEvent.Type.PRESS || event.button() != Keys.MOUSE_LEFT) {
            return;
        }
        // The event is in scene coordinates and every row and triangle is in this widget's own,
        // as the table and the list convert them. The two agree only for a tree at the scene's
        // origin, which is where every test of this widget had mounted it.
        float x = sceneToLocalX(event.x());
        float y = sceneToLocalY(event.y());
        // A press on a reserved strip whose bar has faded is still the bar's, as hitTest says.
        if (!inViewport(x, y)) {
            return;
        }
        int index = rowAtLocalY(y);
        if (index < 0) {
            return;
        }
        Row<T> row = rows.get(index);
        event.consume();
        requestFocus();
        if (row.expandable && overTwisty(x, index)) {
            setExpanded(row.node, !row.expanded, Change.Origin.USER);
            return;
        }
        // The platform's command modifier, not a fixed bit (T1): Command on macOS, Control
        // elsewhere, which is what Table reads and what the demo's label promises.
        int mods = event.modifiers();
        boolean command = (mods & Accelerator.commandModifier()) != 0;
        boolean shift = (mods & Keys.MOD_SHIFT) != 0;
        long now = sceneNanos();
        boolean second = row.node.equals(lastPressNode)
                && now - lastPressNanos < DOUBLE_CLICK_NANOS;
        lastPressNode = row.node;
        lastPressNanos = second ? 0 : now;
        pointerPress = true;
        try {
            if (selectionMode == SelectionMode.MULTI && command) {
                toggleSelection(row.node, true, Change.Origin.USER);
            } else if (selectionMode == SelectionMode.MULTI && shift) {
                selectRange(index);
            } else {
                selectOnly(row.node, true, Change.Origin.USER);
            }
        } finally {
            pointerPress = false;
        }
        if (second && !command && !shift) {
            // A double click activates, like Enter (decision 46 of 2026-09-14): an application
            // that wants it to open the branch does so in onActivate.
            activate(Change.Origin.USER);
        }
    }

    /**
     * The leading edge of a row's triangle band on screen: indented for its depth, moved by the
     * sideways offset, and mirrored where the reading direction is. One place, because a triangle
     * that is painted somewhere the hit test does not look is a control that cannot be pressed.
     */
    private float twistyLeft(SizeTokens t, int depth) {
        float lead = depth * indent(t);
        return isRightToLeft()
                ? width() - lead + offsetX - twistyBand(t)
                : lead - offsetX;
    }

    /** Whether a press at {@code localX} landed on the row's triangle rather than on its cell. */
    private boolean overTwisty(float localX, int index) {
        SizeTokens t = tokens();
        float left = twistyLeft(t, rows.get(index).depth);
        return localX >= left && localX <= left + twistyBand(t);
    }

    private int rowAtLocalY(float localY) {
        for (int i = 0; i < mountedCount; i++) {
            Widget cell = mountedCells[i];
            if (localY >= cell.y() && localY < cell.y() + cell.height()) {
                return mountedRows[i];
            }
        }
        return -1;
    }

    @Override
    public Widget hitTest(float localX, float localY) {
        if (!isVisible() || !isEnabled()
                || localX < 0 || localY < 0 || localX >= width() || localY >= height()) {
            return null;
        }
        Widget barHit = vBar.hitTest(localX - vBar.x(), localY - vBar.y());
        if (barHit == null) {
            barHit = hBar.hitTest(localX - hBar.x(), localY - hBar.y());
        }
        if (barHit != null) {
            return barHit;
        }
        // A reserved strip is the bar's even while the bar has faded and answers nothing, and a
        // row can lie under it: the last one runs past the bottom, and a deep tree's cells past
        // the side. Under OVERLAY the viewport is the box and this never fires.
        if (!inViewport(localX, localY)) {
            return this;
        }
        int index = rowAtLocalY(localY);
        if (index >= 0 && rows.get(index).expandable && overTwisty(localX, index)) {
            return this; // the triangle is the tree's, not the cell's
        }
        for (Widget child : children()) {
            if (child == vBar || child == hBar || slotOfCell(child) < 0) {
                continue; // the bars were asked above; a cell awaiting release is nobody's row
            }
            Widget hit = child.hitTest(localX - child.x(), localY - child.y());
            if (hit != null) {
                return hit;
            }
        }
        return this;
    }

    // ----------------------------------------------------------------------------- paint

    @Override
    protected boolean clipsChildren() {
        return true;
    }

    @Override
    protected void paintChildren(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        if (tintedFor != theme) {
            tintedFor = theme;
            selectionTint = theme.primary.withAlpha(0.18f);
        }
        float viewH = viewportHeight();
        canvas.save();
        try {
            // The viewport and not the box, as the table clips: under RESERVED the strips are the
            // bars', and a row painted into one is a row under its bar. The same rectangle as the
            // box while the bars overlay.
            canvas.clipRect(viewportLeft(), 0, gutters.viewportWidth(width()), viewH);
            for (int i = 0; i < mountedCount; i++) {
                int index = mountedRows[i];
                Widget cell = mountedCells[i];
                if (cell.y() >= viewH || cell.y() + cell.height() <= 0) {
                    continue; // the focused row a scroll spared
                }
                Row<T> row = rows.get(index);
                // Not under a loading line: its node is its row's, and a wash across both would
                // read as two rows selected.
                if (!row.placeholder && selected.contains(row.node)) {
                    canvas.fillRect(0, cell.y(), width(), cell.height(), selectionTint);
                }
                if (row.expandable) {
                    paintTwisty(canvas, theme, t, row, cell);
                }
                canvas.save();
                try {
                    canvas.translate(cell.x(), cell.y());
                    cell.paintWidget(canvas);
                } finally {
                    canvas.restore();
                }
            }
            paintCursorRing(canvas, theme, t, viewH);
        } finally {
            canvas.restore();
        }
        for (ScrollBar bar : new ScrollBar[] {vBar, hBar}) {
            canvas.save();
            try {
                canvas.translate(bar.x(), bar.y());
                bar.paintWidget(canvas);
            } finally {
                canvas.restore();
            }
        }
    }

    /**
     * The focus mark: a thin ring in the focus colour around the cursor row's cell, while the
     * tree holds the keyboard (decision 52 of 2026-09-14; TREE-MISS-5). Before, the tree drew
     * the selection wash and nothing else, so focus arriving was invisible, and so was the
     * cursor whenever it was not the one selected row: in {@code NONE}, after Space toggled a row
     * off in {@code MULTI}, or on any row of a multiple selection.
     *
     * <p>Around the cell and not the row: the indent and the triangle are the outline's, and a
     * ring across the full width reads as a second selection wash on a tree whose rows are
     * already a band. Table's weight and corner ({@code FOCUS_RING_THIN}, {@code radiusSmall}),
     * inset by its own weight so it stays inside the band a cursor move damages; clipped to the
     * viewport where the cell runs past it, so a row scrolled sideways keeps a closed ring on
     * what can be seen. Painted over the cell, as the list's ring is. One of three marks
     * rendered for him; his pick may replace it.
     */
    private void paintCursorRing(Canvas canvas, Theme theme, SizeTokens t, float viewH) {
        if (cursor == null || !isFocused()) {
            return;
        }
        Widget cell = mountedCellOf(cursor);
        if (cell == null || cell.y() >= viewH || cell.y() + cell.height() <= 0) {
            return; // not a row, or the cursor row the tree keeps realized outside the box
        }
        float inset = Strokes.FOCUS_RING_THIN;
        float left = viewportLeft();
        float from = Math.max(left, cell.x()) + inset;
        float to = Math.min(left + gutters.viewportWidth(width()), cell.x() + cell.width()) - inset;
        float height = cell.height() - 2 * inset;
        if (to > from && height > 0) {
            canvas.drawRoundRect(from, cell.y() + inset, to - from, height, t.radiusSmall(),
                    Strokes.FOCUS_RING_THIN, theme.focusRing);
        }
    }

    /**
     * The disclosure triangle: the combo box's caret, turned a quarter. Half-height is half the
     * half-width at every step, so the angle is invariant and only the gutter changes size.
     */
    private void paintTwisty(Canvas canvas, Theme theme, SizeTokens t, Row<T> row, Widget cell) {
        float halfW = t.chevronHalfW();
        float halfH = halfW / 2;
        float band = twistyBand(t);
        // Through twistyLeft, which is also what the hit test asks: a triangle painted where the
        // press is not looked for is a control nobody can open, and a sideways offset is exactly
        // the change that pulls the two apart.
        float cx = twistyLeft(t, row.depth) + band / 2;
        float cy = cell.y() + cell.height() / 2;
        twisty.reset();
        if (row.loading && row.expanded) {
            // Loading: a turning arc where the open triangle goes, as wide as the triangle, so the
            // band and the cell beside it do not move when the children land (ADR 044 §2).
            float radius = halfW - Strokes.ARROW_PEN / 2;
            double start = spinPhase * 2 * Math.PI;
            appendArc(twisty, cx, cy, radius, start, start + SPIN_SWEEP);
            startSpinning(); // re-armed here, as the progress bar's sweep is, when the row shows again
        } else if (row.expanded) {
            // Open: pointing down, the same triangle the combo box draws for an open list.
            twisty.moveTo(cx - halfW, cy - halfH).lineTo(cx, cy + halfH).lineTo(cx + halfW, cy - halfH);
        } else if (isRightToLeft()) {
            twisty.moveTo(cx + halfH, cy - halfW).lineTo(cx - halfH, cy).lineTo(cx + halfH, cy + halfW);
        } else {
            twisty.moveTo(cx - halfH, cy - halfW).lineTo(cx + halfH, cy).lineTo(cx - halfH, cy + halfW);
        }
        canvas.drawPath(twisty, Strokes.ARROW_PEN,
                isEnabled() ? theme.textMuted : theme.disabledText);
    }

    /**
     * Appends the arc from {@code a0} to {@code a1} radians around {@code (cx, cy)}, clockwise on
     * screen, in cubic pieces of at most a quarter turn: past that a Bézier visibly flattens the
     * circle. The donut chart's construction.
     */
    private static void appendArc(Path2D path, float cx, float cy, float radius, double a0,
            double a1) {
        int steps = Math.max(1, (int) Math.ceil(Math.abs(a1 - a0) / (Math.PI / 2)));
        double step = (a1 - a0) / steps;
        double kappa = 4.0 / 3.0 * Math.tan(step / 4);
        double angle = a0;
        path.moveTo((float) (cx + radius * Math.cos(angle)), (float) (cy + radius * Math.sin(angle)));
        for (int i = 0; i < steps; i++) {
            double next = angle + step;
            float x0 = (float) (cx + radius * Math.cos(angle));
            float y0 = (float) (cy + radius * Math.sin(angle));
            float x1 = (float) (cx + radius * Math.cos(next));
            float y1 = (float) (cy + radius * Math.sin(next));
            path.cubicTo((float) (x0 - kappa * radius * Math.sin(angle)),
                    (float) (y0 + kappa * radius * Math.cos(angle)),
                    (float) (x1 + kappa * radius * Math.sin(next)),
                    (float) (y1 - kappa * radius * Math.cos(next)), x1, y1);
            angle = next;
        }
    }

    // ------------------------------------------------------------------------- spinner

    /**
     * Turns the spinners while any row is loading, on wall time: the load it stands for does not
     * stop because the application paused its scene's clock, and a spinner that stops reads as a
     * load that hung. The progress bar's sweep, for its reasons.
     *
     * <p>Only the bands of the loading rows are damaged per frame, not the tree: the arc is the
     * only thing that moves.
     */
    private void startSpinning() {
        if (spinning || loading.isEmpty() || scene() == null || !isShowing()) {
            return;
        }
        spinning = true;
        int generation = ++spinGeneration;
        scene().addRealTimeTicker(dt -> {
            if (generation != spinGeneration) {
                return false; // superseded by a detach: a newer ticker owns the spin, or none does
            }
            if (loading.isEmpty() || !isShowing()) {
                spinning = false; // re-armed by the next load, attach or paint of a loading row
                return false;
            }
            spinPhase = (spinPhase + dt / SPIN_SECONDS) % 1.0;
            damageSpinners();
            return true;
        });
    }

    /** Damages the triangle band of every loading row on screen, and nothing else. */
    private void damageSpinners() {
        SizeTokens t = tokens();
        float band = twistyBand(t);
        for (int i = 0; i < mountedCount; i++) {
            Row<T> row = rows.get(mountedRows[i]);
            if (!row.loading || !row.expanded || row.placeholder) {
                continue;
            }
            Widget cell = mountedCells[i];
            // Clamped to the viewport and not the box: a band scrolled under a reserved bar strip
            // is clipped out of the paint, and damaging the strip would repaint the bar for it.
            float viewLeft = viewportLeft();
            float top = Math.max(0, cell.y());
            float bottom = Math.min(viewportHeight(), cell.y() + cell.height());
            float left = Math.max(viewLeft, twistyLeft(t, row.depth));
            float right = Math.min(viewLeft + gutters.viewportWidth(width()),
                    twistyLeft(t, row.depth) + band);
            if (bottom > top && right > left) {
                invalidate(left, top, right - left, bottom - top);
            }
        }
    }

    @Override
    protected void onAttached() {
        // A load dropped while the tree was in no scene left its row open with nothing under
        // it and no job; the walk that meets every open row starts it again (TREE-NEW-3), and a
        // pass is owed only when it did, since the loading line is a row.
        int wasLoading = loading.size();
        rebuildRows();
        if (loading.size() != wasLoading) {
            markNeedsContainedLayout();
            invalidate();
        }
        startSpinning(); // a tree opened onto loading rows before it joined a scene still turns
    }

    @Override
    protected void onFocusGained() {
        // The cursor row is kept realized only while the tree holds the keyboard, and a pass is
        // what mounts or releases it; contained, because nothing outside the box moves. The
        // cursor's band is damaged for its focus ring, which is drawn only while focused.
        markNeedsContainedLayout();
        damageNode(cursor);
    }

    @Override
    protected void onFocusLost() {
        markNeedsContainedLayout();
        damageNode(cursor);
    }

    @Override
    protected void onDetached() {
        spinGeneration++; // the ticker left behind in the old scene stops on its next frame
        spinning = false;
        // A result nobody will paint is a result nobody should pay for, and a row left busy over
        // a job that was never going to deliver is a row that spins for good: the loads are
        // dropped here and started again by the attach (TREE-NEW-3).
        cancelAllLoads();
    }

    // --------------------------------------------------------------------- accessibility

    /**
     * The tree itself.
     *
     * <p>{@code TREE}, since 2026-09-13, when the AT-SPI number ADR 044 §4 was waiting on came off
     * the Fedora guest. Until then it published {@code LIST}, because a role with no number
     * announces as "invalid" on Linux and a list of items that can open was the truthful answer
     * on all three platforms.
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        SizeTokens t = tokens();
        float viewport = viewportHeight();
        float content = estimatedContentHeight(t);
        a.role(Accessible.Role.TREE);
        a.selection(selectionMode == SelectionMode.MULTI, false);
        float viewW = gutters.viewportWidth(width());
        a.scrollFrom(offsetX, Math.max(0, contentWidth - viewW), viewW, contentWidth,
                estimatedOffset(t), Math.max(0, content - viewport), viewport, content);
        if (cursor != null) {
            // PRESS stays on the tree and acts on the cursor row, in every mode (decision 32 of
            // 2026-09-14). Every other verb is a row's own, delegated below (decision 20): the
            // tree carried EXPAND and COLLAPSE for the cursor row until then, and a reader that
            // addresses a row now finds them on the row.
            a.action(Accessible.Action.PRESS);
        }
    }

    /** The mounted slot a cell sits in, or {@code -1} for the bars. */
    private int slotOfCell(Widget child) {
        for (int i = 0; i < mountedCount; i++) {
            if (mountedCells[i] == child) {
                return i;
            }
        }
        return -1;
    }

    /** The row a mounted cell shows, or {@code null} for the bars and the loading line. */
    private Row<T> rowOfCell(Widget child) {
        return rowOfSlot(slotOfCell(child));
    }

    private Row<T> rowOfSlot(int slot) {
        int index = slot < 0 ? -1 : mountedRows[slot];
        if (index < 0 || index >= rows.size()) {
            return null;
        }
        Row<T> row = rows.get(index);
        return row.placeholder ? null : row; // a line ignores itself; its row is BUSY, or open
    }

    /**
     * The name a row gets when neither the model nor the cell gives one: the text of the cell's
     * labels, in reading order, separated by a space — an icon, a name and a count read as the
     * name and the count. A composite cell has no name of its own, and a screen reader that is
     * handed a nameless tree item speaks nothing for it: Orca's name generator yields nothing
     * for such a row and never spoke one on the Fedora guest (TREE-ROW-NAME, 2026-09-14).
     *
     * <p>Assembled into one reused builder and compared with the string kept for the slot, so a
     * quiet frame allocates nothing; a new string is built only when the text moved, and the
     * string's identity is then the witness the builder wants.
     *
     * @return the name, or {@code null} when the cell holds no label text
     */
    private String derivedName(int slot, Widget cell) {
        nameBuilder.setLength(0);
        appendLabelText(cell);
        String kept = mountedNames[slot];
        if (nameBuilder.length() == 0) {
            mountedNames[slot] = null;
            return null;
        }
        if (kept != null && kept.contentEquals(nameBuilder)) {
            return kept;
        }
        String fresh = nameBuilder.toString();
        mountedNames[slot] = fresh;
        return fresh;
    }

    private void appendLabelText(Widget widget) {
        if (!widget.isVisible()) {
            return;
        }
        if (widget instanceof limn.components.Label label) {
            String text = label.text();
            if (!text.isEmpty()) {
                if (nameBuilder.length() > 0) {
                    nameBuilder.append(' ');
                }
                nameBuilder.append(text);
            }
            return;
        }
        List<Widget> children = widget.children();
        for (int i = 0; i < children.size(); i++) { // indexed: an iterator is an allocation
            appendLabelText(children.get(i));
        }
    }

    /**
     * A row's identity is its node's stable identifier (ADR 039 §1.3), answered before the cell
     * describes itself so that a cell recycled to another node carries nothing of the old one.
     */
    @Override
    protected void onAccessibilityChildIdentity(Widget child, Accessibility a) {
        Row<T> row = rowOfCell(child);
        if (row != null) {
            a.key(idOf(row.node));
        }
    }

    @Override
    protected void onAccessibilityChild(Widget child, Accessibility a) {
        int slot = slotOfCell(child);
        Row<T> row = rowOfSlot(slot);
        if (row == null) {
            return;
        }
        a.role(Accessible.Role.TREE_ITEM);
        // The model's name first; else the cell's own, which a Label cell has said already; else
        // the text of the cell's labels, so a composite row is never a nameless item.
        I18nString name = model.nameOf(row.node);
        if (name != null) {
            a.name(name);
        } else if (!a.hasName()) {
            String derived = derivedName(slot, child);
            if (derived != null) {
                a.name(derived, System.identityHashCode(derived), Accessible.NameFrom.CONTENT);
            }
        }
        // Numbered among its siblings, which is the "2 of 5" a reader speaks of a tree item
        // (decision 4 of 2026-09-13; ADR 044 §4, amended 2026-09-14): a loading line is not a
        // sibling, so it is never counted. Until 2026-09-14 this was the row's place in the
        // whole outline, which the hierarchy facet below carries instead.
        a.selectionItem(selected.contains(row.node), row.position, row.siblings);
        // The depth and the flat row index (ADR 039 §1.2, amended 2026-09-14): what a reader
        // speaks as "level 2" and what the macOS outline addresses its rows by.
        a.hierarchy(row.depth + 1, row.item, itemCount);
        if (row.expandable) {
            a.expand(row.expanded);
        }
        if (row.loading) {
            // What the spinner and the loading line say to a sighted user: this row is open and
            // what it holds has not arrived. Without it an open row with no children reads to a
            // reader as a node with nothing in it (ADR 044 §2).
            a.state(Accessible.State.BUSY);
        }
        if (row.node.equals(cursor) && isFocused()) {
            // The cursor is the focused node's (ADR 039 §1.10, amended 2026-09-14): a tree
            // nobody is in publishes no ACTIVE row, so it cannot hand the widget the user is
            // actually in a cursor it does not have.
            a.state(Accessible.State.ACTIVE);
        }
        // The row's own verbs, published on the cell a reader addresses and performed by the
        // tree through onAccessibilityChildAction (ADR 039 §1.5, amended 2026-09-14; decisions
        // 7 and 20): the cell is the application's widget and knows nothing of selection or
        // expansion. Each is published by the row's current state, so the list a reader sees is
        // exactly what the row accepts (semantics 5).
        if (selectionMode != SelectionMode.NONE) {
            a.delegate(Accessible.Action.SELECT);
            if (selectionMode == SelectionMode.MULTI) {
                a.delegate(selected.contains(row.node)
                        ? Accessible.Action.DESELECT : Accessible.Action.ADD_TO_SELECTION);
            }
        }
        if (row.expandable) {
            a.delegate(row.expanded ? Accessible.Action.COLLAPSE : Accessible.Action.EXPAND);
        }
        // PRESS opens this row and not the cursor's (decision 80 of 2026-09-17). It is published
        // on every row because decision 79 stopped a reader's SELECT from moving the cursor: a
        // reader that selected row 5 and pressed the tree would otherwise open row 2, and both
        // VoiceOver and NVDA activate the element their own cursor is on rather than a container.
        // The tree keeps its own PRESS, which still opens the cursor row, for Enter's sake and
        // for a client that addresses the container (decision 32, amended).
        a.delegate(Accessible.Action.PRESS);
        if (!child.isFocusable()) {
            // FOCUS moves the cursor without selecting, which is what decision 11 lets an item
            // publish where the cursor and the selection are separate; SCROLL_INTO_VIEW reveals
            // the row. Both are the walk's own on a focusable widget, and a cell that is one
            // keeps them as the scene's free pair (Accessibility.freeVerbs refuses the claim).
            a.delegate(Accessible.Action.FOCUS);
            a.delegate(Accessible.Action.SCROLL_INTO_VIEW);
        }
    }

    /**
     * A verb the tree claimed on a row's cell, each through the seam the equivalent gesture
     * takes at {@code USER}: {@code SELECT} is a click on the row; {@code ADD_TO_SELECTION} and
     * {@code DESELECT} toggle it as the command-click does, accepted only in the state that
     * published them, but leave the cursor and the range anchor where they were (decision 20:
     * only {@code SELECT} and {@code FOCUS} move the cursor; the click moves it because the
     * pointer is where the user is); {@code EXPAND} and {@code COLLAPSE} are the triangle, which
     * never moves the cursor; {@code FOCUS} moves the cursor onto the row and nothing else,
     * taking the keyboard so the cursor is published; {@code SCROLL_INTO_VIEW} reveals the row. A
     * verb the row did not publish is refused, which the platform never learns of (the published
     * list is the only refusal it sees, ADR 039 §1.5).
     */
    @Override
    protected boolean onAccessibilityChildAction(Widget child, long key, Accessible.Action action,
                                                 Accessible.Argument arg) {
        Row<T> row = rowOfCell(child);
        if (row == null) {
            return false;
        }
        switch (action) {
            case SELECT -> {
                if (selectionMode == SelectionMode.NONE) {
                    return false;
                }
                selectOnly(row.node, true, false, Change.Origin.USER);
                return true;
            }
            case PRESS -> {
                activate(row.node, Change.Origin.USER);
                return true;
            }
            case ADD_TO_SELECTION, DESELECT -> {
                boolean member = selected.contains(row.node);
                if (selectionMode != SelectionMode.MULTI
                        || member != (action == Accessible.Action.DESELECT)) {
                    return false;
                }
                toggleSelection(row.node, false, Change.Origin.USER);
                return true;
            }
            case EXPAND, COLLAPSE -> {
                boolean open = action == Accessible.Action.EXPAND;
                if (!row.expandable || row.expanded == open) {
                    return false;
                }
                setExpanded(row.node, open, Change.Origin.USER);
                return true;
            }
            case FOCUS -> {
                requestFocus();
                moveCursor(row.node, Change.Origin.USER);
                return true;
            }
            case SCROLL_INTO_VIEW -> {
                revealNode(row.node);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Moves the cursor onto {@code node} and nothing else: revealed, damaged, announced. */
    private void moveCursor(T node, Change.Origin origin) {
        T wasCursor = cursor;
        cursor = node;
        revealNode(node);
        damageCursorMove(wasCursor);
        announceCursor(wasCursor, origin);
    }

    /** The tree's one verb of its own: {@code PRESS} activates the cursor row (decision 32). */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        if (cursor == null || action != Accessible.Action.PRESS) {
            return false;
        }
        activate(Change.Origin.USER);
        return true;
    }

    /** A node's identifier, stable for as long as the tree holds the node. */
    private long idOf(T node) {
        Long id = ids.get(node);
        if (id == null) {
            id = nextId++;
            ids.put(node, id);
        }
        return id;
    }
}
