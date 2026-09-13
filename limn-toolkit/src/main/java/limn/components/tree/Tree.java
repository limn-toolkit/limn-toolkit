package limn.components.tree;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
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
import java.util.HashMap;
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
 * wanted.
 *
 * <p><b>A row may promise children before it can name them.</b> {@link Model#children} answers
 * {@code null} for a node whose children are not known yet, and {@link Model#load} hands back a
 * {@link Work} that fetches them: the row opens, shows that it is working, and fills in when the
 * job lands on the UI thread. Collapsing a row that is still loading cancels the job, because a
 * result nobody is looking at is a result nobody should pay for. A directory that has not been
 * read is therefore not a leaf — it has a triangle, and pressing it is what reads it.
 *
 * <p><b>Rows are realized where the viewport reaches</b>, by the anchor-and-walk this toolkit's
 * list and table already use: a row's height is measured when it is first needed, the mean seeds
 * the scroll estimate, and the row holding the keyboard focus is kept mounted even when a scroll
 * carries it outside (ADR 039 §13.29). The order rows are walked in is a traversal of what is
 * expanded, which is the one thing a tree does that a list cannot.
 *
 * <p><b>What this is not:</b> no columns — a {@code TreeTable} is ADR 044 §9 — no in-place
 * editing, ever (ADR 041 §6), no drag to reorder, and no tri-state checkbox cascade over a data
 * model the toolkit does not own.
 *
 * <p>To a screen reader this is a {@code TREE} of {@code TREE_ITEM}s, each carrying its expanded
 * state and its selection, with the verbs on the tree acting on the lead row. What ADR 044 §4
 * still owes it is depth and position-in-level — "level 3, 2 of 5" — which the facet model does
 * not carry yet, and the disclosure attributes VoiceOver reads an outline row by.
 */
public class Tree<T> extends Widget implements Scrollable {

    /** How many rows may be selected at once. */
    public enum SelectionMode {
        /** None: the cursor still moves and nothing is ever selected. */
        NONE,
        /** One row. */
        SINGLE,
        /** Any number of rows: the command modifier toggles one. */
        MULTI
    }

    /**
     * What the tree asks the application about its own data.
     *
     * <p>Three of the six methods have defaults, and the three that do not are the ones only the
     * application can answer: where the tree starts, what is under a node, and what draws it.
     */
    public interface Model<T> {

        /** @return the top-level nodes, in the order they are shown */
        List<T> roots();

        /**
         * @param node a node the tree is showing
         * @return its children, or {@code null} when they are not known yet and {@link #load}
         *         is what fetches them. An empty list means a node with no children, which is
         *         a leaf; {@code null} is a promise, not an absence.
         */
        List<T> children(T node);

        /**
         * @param node a node the tree is showing
         * @return whether it can never have children, so no triangle is drawn. The default reads
         *         {@link #children}: a node whose children are known and empty is a leaf, and one
         *         whose children are not known yet is not — a directory nobody has read is not a
         *         file.
         */
        default boolean isLeaf(T node) {
            List<T> known = children(node);
            return known != null && known.isEmpty();
        }

        /**
         * Fetches the children of a node that answered {@code null} to {@link #children}.
         *
         * <p>Called at most once per node per expansion, on the UI thread, and the job it returns
         * is cancelled if the row is collapsed before it lands. The tree caches what arrives, so
         * a second expansion of the same node costs nothing.
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
         * What to call {@code node} for an assistive technology, when its own cell widget says
         * nothing about itself.
         *
         * <p><b>Hand back a string this model holds.</b> The tree compares a name by reference,
         * so a string built inside this call republishes the whole tree on every damaged frame;
         * a field or an entry in the application's own data is what belongs here. The rule and
         * the reason are {@code ListView.Adapter#rowName}'s.
         *
         * @return the name, or {@code null} when the model has none to give
         */
        default I18nString nameOf(T node) {
            return null;
        }
    }

    /** One visible row: a node, how deep it sits, and what its triangle is doing. */
    private static final class Row<T> {
        final T node;
        final int depth;
        final boolean expandable;
        final boolean expanded;
        final boolean loading;

        Row(T node, int depth, boolean expandable, boolean expanded, boolean loading) {
            this.node = node;
            this.depth = depth;
            this.expandable = expandable;
            this.expanded = expanded;
            this.loading = loading;
        }
    }

    /** Rows of intrinsic height when the height axis is unbounded; a count, not a length. */
    private static final int VISIBLE_ROWS_HINT = 8;

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
    private int mountedCount;

    /** The half-open run of rows the last pass laid out, which is what a recycle keeps. */
    private int placedFrom;
    private int placedTo;

    // Anchor scroll state: the top edge of row `anchorIndex` sits at y = anchorTop.
    private int anchorIndex;
    private float anchorTop;
    /** Mean measured row height, or 0 until a pass has measured one; the step's seed stands in. */
    private float measuredRowHeight;
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
    /** The content width the last pass settled on, which is what the horizontal bar reports. */
    private float contentWidth;

    private SelectionMode selectionMode = SelectionMode.SINGLE;
    private final Set<T> selected = new LinkedHashSet<>();
    /** The row the keyboard is on, and the one a verb without a target acts on. */
    private T lead;
    /** The node whose expansion a handler is about to be told of; see {@link #handleUserChange}. */
    private T toggled;

    private Consumer<T> onSelect;
    private Consumer<T> onActivate;
    private Consumer<T> onExpand;
    private Consumer<T> onCollapse;

    /** A tree over {@code model}, which supplies the nodes and the widgets that draw them. */
    public Tree(Model<T> model) {
        this.model = Objects.requireNonNull(model, "model");
        setFocusable(true);
        vBar = new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return estimatedContentHeight(tokens());
            }

            @Override
            public float viewportLength() {
                return height();
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
     * child list: the application changed its data and the tree owns none of it. UI thread only.
     */
    public void refresh() {
        Ui.checkUiThread();
        loaded.clear();
        cancelAllLoads();
        recycleExcept(0, 0, 0);
        rebuildRows();
        pruneSelection();
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
        rebuildRows();
        pruneSelection();
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
                    rebuildRows();
                    markNeedsContainedLayout();
                    invalidate();
                    notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.ADJUSTMENT));
                })
                .onFailure(error -> {
                    // The row closes again rather than sitting open and empty, which would read
                    // as "this node has nothing in it" — a different statement from "this could
                    // not be read".
                    loading.remove(node);
                    expanded.remove(node);
                    rebuildRows();
                    markNeedsContainedLayout();
                    invalidate();
                    notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.ADJUSTMENT));
                })
                .deliverIf(() -> scene() != null)
                .start();
        loading.put(node, job);
    }

    private void cancelAllLoads() {
        for (Job job : loading.values()) {
            job.cancel();
        }
        loading.clear();
    }

    /** Walks the model from the roots down, following what is open, into {@link #rows}. */
    private void rebuildRows() {
        rows.clear();
        for (T root : model.roots()) {
            appendRow(root, 0);
        }
        // Here rather than in the layout: the deepest row is what decides how wide the content
        // is, and the only thing that moves it is what is open, which is decided here.
        int deepest = 0;
        for (Row<T> row : rows) {
            deepest = Math.max(deepest, row.depth);
        }
        maxDepth = deepest;
    }

    private void appendRow(T node, int depth) {
        boolean leaf = model.isLeaf(node);
        boolean open = expanded.contains(node);
        rows.add(new Row<>(node, depth, !leaf, open, loading.containsKey(node)));
        if (!open || leaf) {
            return;
        }
        for (T child : childrenOf(node)) {
            appendRow(child, depth + 1);
        }
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

    /** @return how many rows are visible, which is a traversal of what is open */
    public int visibleRowCount() {
        return rows.size();
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

    // ------------------------------------------------------------------------ selection

    /** How many rows may be selected at once; the default is {@link SelectionMode#SINGLE}. */
    public Tree<T> setSelectionMode(SelectionMode mode) {
        Ui.checkUiThread();
        this.selectionMode = Objects.requireNonNull(mode, "mode");
        if (mode == SelectionMode.NONE && !selected.isEmpty()) {
            selected.clear();
            invalidate();
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        } else if (mode == SelectionMode.SINGLE && selected.size() > 1) {
            T keep = lead != null && selected.contains(lead) ? lead : selected.iterator().next();
            selected.clear();
            selected.add(keep);
            invalidate();
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

    /** @return the node the keyboard is on, or {@code null} */
    public T leadNode() {
        return lead;
    }

    /** Selects exactly {@code node}, revealing it. {@code null} clears the selection. */
    public Tree<T> setSelected(T node) {
        Ui.checkUiThread();
        selectOnly(node, true, Change.Origin.CODE);
        return this;
    }

    /**
     * The one place the selection moves, and the one seam it announces from: the public setter
     * passes {@code CODE}, a collapse that swallowed a selected row passes {@code ADJUSTMENT},
     * and every key and click passes {@code USER}. Announces only when it moved.
     */
    private void selectOnly(T node, boolean reveal, Change.Origin origin) {
        if (selectionMode == SelectionMode.NONE) {
            return;
        }
        boolean same = node == null ? selected.isEmpty()
                : selected.size() == 1 && selected.contains(node);
        lead = node;
        if (same) {
            if (reveal && node != null) {
                revealNode(node);
            }
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
        if (reveal && node != null) {
            revealNode(node);
        }
        damageNode(node);
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    /** Adds or removes one node, which is what the command modifier does in {@code MULTI}. */
    private void toggleSelection(T node, Change.Origin origin) {
        if (selectionMode != SelectionMode.MULTI) {
            selectOnly(node, true, origin);
            return;
        }
        if (!selected.remove(node)) {
            selected.add(node);
        }
        lead = node;
        revealNode(node);
        damageNode(node);
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    /**
     * Drops from the selection every node that is no longer reachable — a collapse hides
     * descendants, and a refresh may have removed nodes outright.
     *
     * <p>A collapse does <b>not</b> deselect what it hides: the row is still in the tree, and
     * re-opening its parent finds it selected, which is what a file manager does. Only a node the
     * model no longer has is dropped.
     */
    private void pruneSelection() {
        if (selected.isEmpty()) {
            return;
        }
        Set<T> reachable = new LinkedHashSet<>();
        for (T root : model.roots()) {
            collectReachable(root, reachable);
        }
        boolean moved = selected.retainAll(reachable);
        if (lead != null && !reachable.contains(lead)) {
            lead = null;
            moved = true;
        }
        if (moved) {
            invalidate();
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        }
    }

    private void collectReachable(T node, Set<T> into) {
        if (!into.add(node)) {
            return; // a model that returns a node twice must not spin this walk
        }
        for (T child : childrenOf(node)) {
            collectReachable(child, into);
        }
    }

    /** Announces that the lead row was opened, the way Enter does. UI thread only. */
    public void activate() {
        Ui.checkUiThread();
        activate(Change.Origin.CODE);
    }

    private void activate(Change.Origin origin) {
        if (lead != null) {
            notifyChange(Change.of(Change.Aspect.INVOKED, origin));
        }
    }

    // ------------------------------------------------------------------------- handlers

    /** Runs when the user moves the selection; not called for a programmatic change. */
    public Tree<T> onSelect(Consumer<T> handler) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, handler, "Tree.onSelect");
        return this;
    }

    /** Runs when the user opens the lead row with Enter. */
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
                    onSelect.accept(lead);
                }
            }
            case INVOKED -> {
                if (onActivate != null) {
                    onActivate.accept(lead);
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
     * <p>The deepest row keeps {@code menuMinWidth} of cell: the toolkit's existing floor for the
     * narrowest strip a row of text may be read in, and capped by the viewport so a narrow tree
     * never asks for more content than one screenful. Where nothing is deep the maximum is the
     * viewport and this returns exactly that — so a shallow tree has no horizontal bar, no offset,
     * and the cell widths (and the ellipsis) it has always had.
     */
    private float estimatedContentWidth(SizeTokens t, float viewW) {
        float deepest = maxDepth * indent(t) + twistyBand(t) + Math.min(viewW, t.menuMinWidth());
        return Math.max(viewW, deepest);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = tokens();
        float w = constraints.hasBoundedWidth() ? constraints.maxWidth() : t.listWidth();
        float h = constraints.hasBoundedHeight() ? constraints.maxHeight()
                : VISIBLE_ROWS_HINT * avgRowHeight(t);
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
        float bottom = placeDown(count, rowX, w, viewH);
        if (bottom < viewH && !(anchorIndex == 0 && anchorTop >= 0)) {
            anchorTop += viewH - bottom;
            normalizeUp(contentWidth);
            normalizeDown(count, contentWidth);
            bottom = placeDown(count, rowX, w, viewH);
        }
        recycleExcept(placedFrom, placedTo, count);
        placeKeptOutside(rowX, w, bottom);
        updateAverageHeight();
        vBar.refresh();
        hBar.refresh();
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
            cell = Objects.requireNonNull(model.cellFor(rows.get(index).node),
                    "Model.cellFor returned null");
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
        }
        System.arraycopy(mountedRows, at, mountedRows, at + 1, mountedCount - at);
        System.arraycopy(mountedCells, at, mountedCells, at + 1, mountedCount - at);
        mountedRows[at] = index;
        mountedCells[at] = cell;
        mountedCount++;
    }

    /**
     * Recycles every mounted row outside {@code [from, toExclusive)}, sparing the one holding the
     * keyboard focus while its index is still below {@code count} — a reader whose cursor follows
     * the focus loses its place when the node it stands on leaves the tree (ADR 039 §13.29).
     */
    private void recycleExcept(int from, int toExclusive, int count) {
        int kept = 0;
        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            Widget cell = mountedCells[i];
            boolean inRun = row >= from && row < toExclusive;
            boolean hasFocus = !inRun && containsFocus(cell);
            if (inRun || (hasFocus && row < count)) {
                mountedRows[kept] = row;
                mountedCells[kept] = cell;
                kept++;
                continue;
            }
            remove(cell);
            model.recycle(cell);
            if (hasFocus) {
                requestFocus();
            }
        }
        for (int i = kept; i < mountedCount; i++) {
            mountedCells[i] = null;
        }
        mountedCount = kept;
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

    /** Puts a spared row wholly outside the viewport, on the side its index lies. */
    private void placeKeptOutside(float rowX, float viewW, float bottom) {
        SizeTokens t = tokens();
        boolean rtl = isRightToLeft();
        for (int i = 0; i < mountedCount; i++) {
            int row = mountedRows[i];
            if (row >= placedFrom && row < placedTo) {
                continue;
            }
            Widget cell = mountedCells[i];
            float lead = cellLeft(t, row, contentWidth);
            float cellW = Math.max(0, contentWidth - lead);
            float y = row < placedFrom ? Math.min(anchorTop, 0) - cell.height() : Math.max(bottom, height());
            cell.layoutBox(cellX(rowX, viewW, lead, cellW, rtl), y, cellW, cell.height());
        }
    }

    private void updateAverageHeight() {
        if (mountedCount == 0) {
            return;
        }
        float total = 0;
        for (int i = 0; i < mountedCount; i++) {
            total += mountedCells[i].height();
        }
        measuredRowHeight = total / mountedCount;
    }

    // --------------------------------------------------------------------------- scroll

    /** Scrolls by a delta in logical points (positive = toward the end). UI thread only. */
    public void scrollBy(float dy) {
        Ui.checkUiThread();
        SizeTokens t = tokens();
        float offset = estimatedOffset(t);
        float max = Math.max(0, estimatedContentHeight(t) - height());
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
        if (y < 0) {
            scrollBy(y);
        } else if (y + rectHeight > height()) {
            scrollBy(Math.min(y, y + rectHeight - height()));
        }
        // The rectangle is in viewport coordinates, so the sideways correction is the distance it
        // sits outside the viewport, and mirrored it points the other way.
        float viewW = gutters.viewportWidth(width());
        float dx = 0;
        if (x < 0) {
            dx = x;
        } else if (x + rectWidth > viewW) {
            dx = Math.min(x, x + rectWidth - viewW);
        }
        if (dx != 0) {
            scrollHorizontallyBy(isRightToLeft() ? -dx : dx);
        }
    }

    /** Scrolls the minimum so {@code node}'s row is visible, if it is one. */
    private void revealNode(T node) {
        int index = indexOf(node);
        if (index < 0) {
            return;
        }
        SizeTokens t = tokens();
        float rowH = avgRowHeight(t);
        Widget cell = cellFor(index);
        float top = cell != null ? cell.y() : (index - anchorIndex) * rowH + anchorTop;
        revealRect(0, top, 0, cell != null ? cell.height() : rowH);
    }

    /**
     * Damages one row's band rather than the tree, when what changed is that row's highlight.
     *
     * <p>Full width and no outset: the wash is drawn across the row and inside its own box, so it
     * reaches nothing this rectangle does not already hold. Clamped to the tree's own box,
     * because damage is clipped by every ancestor that clips its children and a widget is not its
     * own ancestor. A row that is not mounted has nothing on screen to damage, and the reveal
     * that brings it on screen damages the tree on its own.
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
        float bottom = Math.min(height(), cell.y() + cell.height());
        if (bottom > top) {
            invalidate(0, top, width(), bottom - top);
        }
    }

    private int indexOf(T node) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).node.equals(node)) {
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
        switch (event.key()) {
            case Keys.DOWN -> consumeAnd(event, () -> moveLead(1));
            case Keys.UP -> consumeAnd(event, () -> moveLead(-1));
            case Keys.PAGE_DOWN -> consumeAnd(event, () -> moveLead(rowsPerPage(tokens())));
            case Keys.PAGE_UP -> consumeAnd(event, () -> moveLead(-rowsPerPage(tokens())));
            case Keys.HOME -> consumeAnd(event, () -> selectAt(0));
            case Keys.END -> consumeAnd(event, () -> selectAt(rows.size() - 1));
            // Right opens a closed row and steps into an open one; Left closes an open row and
            // steps to the parent of a closed one. Reading right to left the two swap, as every
            // other pair of horizontal arrows in this toolkit does.
            case Keys.RIGHT -> consumeAnd(event, () -> (rtl ? this : this).stepOut(!rtl));
            case Keys.LEFT -> consumeAnd(event, () -> stepOut(rtl));
            case Keys.ENTER -> {
                if (lead != null) {
                    consumeAnd(event, () -> activate(Change.Origin.USER));
                }
            }
            case Keys.SPACE -> {
                if (lead != null && selectionMode == SelectionMode.MULTI) {
                    consumeAnd(event, () -> toggleSelection(lead, Change.Origin.USER));
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
        if (lead == null) {
            selectAt(0);
            return;
        }
        int index = indexOf(lead);
        if (index < 0) {
            return;
        }
        Row<T> row = rows.get(index);
        if (opening) {
            if (row.expandable && !row.expanded) {
                setExpanded(row.node, true, Change.Origin.USER);
            } else if (row.expanded && index + 1 < rows.size()) {
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
    private void moveLead(int delta) {
        if (rows.isEmpty()) {
            return;
        }
        if (lead == null) {
            selectAt(anchorIndex);
            return;
        }
        int from = indexOf(lead);
        selectAt(from < 0 ? anchorIndex : from + delta);
    }

    private void selectAt(int index) {
        if (rows.isEmpty()) {
            return;
        }
        int clamped = Math.min(Math.max(0, index), rows.size() - 1);
        selectOnly(rows.get(clamped).node, true, Change.Origin.USER);
    }

    /** A page is a viewport of rows: a count derived from the current estimate, not a token. */
    private int rowsPerPage(SizeTokens t) {
        return Math.max(1, (int) (height() / Math.max(1, avgRowHeight(t))));
    }

    // --------------------------------------------------------------------------- pointer

    @Override
    protected void onMouseEvent(MouseEvent event) {
        switch (event.type()) {
            case WHEEL -> {
                // A detent is a device unit: the same flick travels the same distance in a
                // dense tree and a roomy one, so the step is locked rather than tabled. Gated
                // on there being something to scroll, so a short tree lets the wheel through to
                // whatever holds it.
                //
                // Sideways is the table's convention, and two devices reach it by different
                // roads: a trackpad sends a horizontal gesture as scrollX, while a mouse with
                // one wheel says the same thing by holding Shift.
                boolean sideways = event.scrollX() != 0
                        || (event.modifiers() & Keys.MOD_SHIFT) != 0;
                float dx = sideways ? -(event.scrollX() != 0 ? event.scrollX() : event.scrollY())
                        * Strokes.WHEEL_STEP : 0;
                float dy = sideways ? 0 : -event.scrollY() * Strokes.WHEEL_STEP;
                boolean canY = estimatedContentHeight(tokens()) > height();
                boolean canX = contentWidth > gutters.viewportWidth(width());
                if (dy != 0 && canY) {
                    scrollBy(dy);
                    event.consume();
                } else if (dx != 0 && canX) {
                    scrollHorizontallyBy(dx);
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
        if (selectionMode == SelectionMode.MULTI && (event.modifiers() & Keys.MOD_SUPER) != 0) {
            toggleSelection(row.node, Change.Origin.USER);
        } else {
            selectOnly(row.node, true, Change.Origin.USER);
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
        int index = rowAtLocalY(localY);
        if (index >= 0 && rows.get(index).expandable && overTwisty(localX, index)) {
            return this; // the triangle is the tree's, not the cell's
        }
        for (Widget child : children()) {
            if (child == vBar || child == hBar) {
                continue;
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
        canvas.save();
        try {
            canvas.clipRect(0, 0, width(), height());
            for (int i = 0; i < mountedCount; i++) {
                int index = mountedRows[i];
                Widget cell = mountedCells[i];
                if (cell.y() >= height() || cell.y() + cell.height() <= 0) {
                    continue; // the focused row a scroll spared
                }
                Row<T> row = rows.get(index);
                if (selected.contains(row.node)) {
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
        if (row.expanded) {
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
        float viewport = height();
        float content = estimatedContentHeight(t);
        a.role(Accessible.Role.TREE);
        a.selection(selectionMode == SelectionMode.MULTI, false);
        float viewW = gutters.viewportWidth(width());
        a.scrollFrom(offsetX, Math.max(0, contentWidth - viewW), viewW, contentWidth,
                estimatedOffset(t), Math.max(0, content - viewport), viewport, content);
        if (lead != null) {
            a.action(Accessible.Action.PRESS);
            int index = indexOf(lead);
            if (index >= 0 && rows.get(index).expandable) {
                // The verbs sit on the tree and act on the lead row, because a row here is the
                // application's own widget and a child's hook writes facts and never verbs. Per
                // row verbs arrive with the TREE_ITEM step, whose rows are synthetic.
                a.action(rows.get(index).expanded
                        ? Accessible.Action.COLLAPSE : Accessible.Action.EXPAND);
            }
        }
    }

    @Override
    protected void onAccessibilityChild(Widget child, Accessibility a) {
        int index = -1;
        for (int i = 0; i < mountedCount; i++) {
            if (mountedCells[i] == child) {
                index = mountedRows[i];
                break;
            }
        }
        if (index < 0 || index >= rows.size()) {
            return;
        }
        Row<T> row = rows.get(index);
        a.key(idOf(row.node));
        a.role(Accessible.Role.TREE_ITEM);
        I18nString name = model.nameOf(row.node);
        if (name != null) {
            a.name(name);
        }
        a.selectionItem(selected.contains(row.node), index + 1, rows.size());
        if (row.expandable) {
            a.expand(row.expanded);
        }
        if (row.node.equals(lead)) {
            a.state(Accessible.State.ACTIVE);
        }
    }

    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        if (lead == null) {
            return false;
        }
        switch (action) {
            case PRESS -> {
                activate(Change.Origin.USER);
                return true;
            }
            case EXPAND -> {
                setExpanded(lead, true, Change.Origin.USER);
                return true;
            }
            case COLLAPSE -> {
                setExpanded(lead, false, Change.Origin.USER);
                return true;
            }
            default -> {
                return false;
            }
        }
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
