package limn.components.internal;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.Label;
import limn.components.ProgressBar;
import limn.components.TokenColumn;
import limn.components.Tokens;
import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import limn.i18n.I18nString;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.internal.SceneAccess;
import limn.scene.layout.Flex;
import org.jspecify.annotations.Nullable;

/**
 * What a list, a table or a tree shows in its rows' viewport while it has no rows: the
 * application's empty content ("No orders match", a button that clears the filter), or while it
 * loads, its loading content. Written once for the three, as the rows engine is: the widget says
 * where its viewport is and how many rows it holds, and this decides what shows, puts it in the
 * widget tree and in the middle of the viewport, describes the widget by its message, and says a
 * load to a reader.
 *
 * <p><b>Shown by itself.</b> The widget knows its count, so the application never says when: it
 * gives the words for "nothing here" or "nothing matches", which only it knows. A load shows its
 * content in place of the empty one, while the rows are none; a load over rows already shown
 * leaves them where they are and marks the widget busy.
 *
 * <p><b>A child, last.</b> The content is the widget's last child while it shows and no child at
 * all otherwise, so a reader finds it after the (no) rows and the Tab key reaches its buttons
 * after the widget itself, and a hidden content holds no node and no focus. It belongs to no
 * selection: the rows' and the grid's contracts read members, and it is not one. So a content is
 * refused when it is already in another container: it would be laid out and drawn by two parents.
 * One given to two widgets is shown by the one that holds it; the other logs it once and shows it
 * at the frame after the first lets it go, told by the first's change of children, so which of two
 * empty widgets shows it never hangs on the order the application emptied them in. A widget that
 * leaves the window lets its content go, so a page rebuilt with a fresh widget can reuse it, and
 * shows it again when it comes back.
 *
 * <p><b>UI thread only</b>, as the widgets that hold one are. Not API: see the package.
 */
public final class RowsPlaceholder {

    /** What a widget answers about itself. */
    public interface Host {

        /** @return the list, the table or the tree */
        Widget<?> widget();

        /**
         * Makes {@code content} the widget's last child.
         *
         * @param content what shows in the viewport now
         */
        void attach(Widget<?> content);

        /**
         * Takes {@code content} out of the widget's children.
         *
         * @param content what showed until now
         */
        void detach(Widget<?> content);

        /** @return how many rows the widget holds now: a list's items, a table's, a tree's roots */
        int rowCount();
    }

    private static final System.Logger LOG = System.getLogger(RowsPlaceholder.class.getName());

    private final Host host;
    private @Nullable Widget<?> emptyContent;
    private @Nullable Label emptyMessage;
    private @Nullable Widget<?> loadingContent;
    private @Nullable Label loadingMessage;
    private @Nullable Widget<?> defaultLoading;
    private @Nullable Label defaultLoadingLine;
    private boolean loading;
    private @Nullable Widget<?> attached;
    /** Whether the log has said that the content to show was put elsewhere; once per content. */
    private boolean saidElsewhere;
    /** The watch on the container that holds the content this widget wants, until it lets go. */
    private @Nullable Subscription waiting;
    /** The content {@link #waiting} waits for, and the container it watches. */
    private @Nullable Widget<?> waitingFor;
    private @Nullable Widget<?> waitingOn;

    /**
     * @param host the widget this shows content for
     */
    public RowsPlaceholder(Host host) {
        this.host = host;
    }

    // ------------------------------------------------------------------ what is shown

    /**
     * Sets the content shown while there are no rows, and the label inside it that describes the
     * widget to a reader then.
     *
     * @param content the content, or {@code null} for none
     * @param message the label that says why there are no rows, or {@code null}: a content with no
     *                message describes nothing, since the toolkit does not read text out of an
     *                arbitrary widget
     * @throws IllegalArgumentException when {@code content} is in another container or holds this
     *                                  widget
     */
    public void setEmpty(@Nullable Widget<?> content, @Nullable Label message) {
        Ui.checkUiThread();
        checkFree(content);
        this.emptyContent = content;
        this.emptyMessage = content == null ? null : message;
        saidElsewhere = false;
        stopWaiting();
        changed();
    }

    /**
     * Sets a muted, wrapped, centred line as the whole of the empty content, which also describes
     * the widget.
     *
     * @param message the words, or {@code null} for no empty content
     */
    public void setEmptyMessage(@Nullable I18nString message) {
        if (message == null) {
            setEmpty(null, null);
            return;
        }
        Label line = messageLine(message);
        setEmpty(line, line);
    }

    /** @return the content shown while there are no rows, or {@code null} */
    public @Nullable Widget<?> emptyContent() {
        return emptyContent;
    }

    /**
     * Sets the content shown while the widget loads and has no rows; {@code null} brings back the
     * default, a moving bar over a muted "Loading…".
     *
     * @param content the content, or {@code null} for the default
     * @param message the label inside it that describes the widget while it shows, or {@code null}
     * @throws IllegalArgumentException when {@code content} is in another container or holds this
     *                                  widget
     */
    public void setLoadingContent(@Nullable Widget<?> content, @Nullable Label message) {
        Ui.checkUiThread();
        checkFree(content);
        this.loadingContent = content;
        this.loadingMessage = content == null ? null : message;
        saidElsewhere = false;
        stopWaiting();
        changed();
    }

    /**
     * Refuses a content another parent has: added again by this one it would be laid out and drawn
     * by both, and the frame that added it would throw. A content another widget holds but does not
     * show is not refused: whether that widget is still wanted is not this one's to know (a
     * registry of holders made the answer hang on the garbage collector), and {@link #sync} shows
     * it in one place only.
     */
    private void checkFree(@Nullable Widget<?> content) {
        if (content == null) {
            return;
        }
        Widget<?> widget = host.widget();
        Widget<?> parent = content.parent();
        if (parent != null && parent != widget) {
            throw new IllegalArgumentException("the content is already in another container: take it out "
                    + "first, or give the " + widget.getClass().getSimpleName() + " a content of its own");
        }
        for (Widget<?> w = widget; w != null; w = w.parent()) {
            if (w == content) {
                throw new IllegalArgumentException("the content holds the "
                        + widget.getClass().getSimpleName() + " it would show in");
            }
        }
    }

    /**
     * Says the widget is loading, or has finished. The widget is published busy while it loads,
     * and a reader is told both ends: "Loading Orders" at the start, and at the end how many rows
     * arrived ("Orders, 12 items"), or the empty message when none did, the name being the one a
     * reader hears the widget by. A widget in a hidden container, on a tab page not chosen or on a
     * face a transform does not draw is busy and says nothing. Set the rows before saying the load ended, so the count said is the
     * one shown.
     *
     * @param value whether a load is running
     */
    public void setLoading(boolean value) {
        Ui.checkUiThread();
        if (loading == value) {
            return;
        }
        loading = value;
        changed();
        Scene scene = host.widget().scene();
        if (scene == null || !onScreen(host.widget())) {
            return; // nobody to say it to, or a load the user cannot see: busy, and said nothing of
        }
        String name = SceneAccess.applicationName(host.widget());
        String said;
        if (value) {
            said = name == null ? RowsStrings.LOADING.get() : RowsStrings.LOADING_ANNOUNCEMENT.format(name);
        } else {
            int count = host.rowCount();
            if (count == 0 && emptyMessage != null) {
                // What the eye now reads in the viewport, said once: a count of nothing says
                // less than "No orders match".
                said = emptyMessage.text();
            } else {
                said = name == null ? RowsStrings.ITEMS.format(count)
                        : RowsStrings.LOADED_ANNOUNCEMENT.format(count, name);
            }
        }
        if (!said.isEmpty()) {
            scene.announce(said, Accessible.Politeness.POLITE);
        }
    }

    /**
     * Whether the widget is where the user sees it: it and every ancestor shown, and drawn by every
     * transform above it, so not in a hidden container, on a tab page not chosen, nor on a face a
     * transform does not draw (turned away, scaled to nothing, edge-on). Not
     * {@link Widget#isShowing()}, which also asks the clips: a list built and set loading in one
     * turn has no box until the next frame, and inside a scroll view it would count as clipped
     * away, and its load go unsaid.
     */
    private static boolean onScreen(Widget<?> widget) {
        // The flags and the transforms, the clip walk left out by design. An opacity group at 0 is
        // left out too: a page fading in starts there, often in the turn its load starts, and the
        // tree still publishes it showing.
        return SceneAccess.isDrawn(widget);
    }

    /** @return whether a load is running */
    public boolean isLoading() {
        return loading;
    }

    /** @return whether the empty content is what the widget shows now */
    public boolean isShowingEmpty() {
        return emptyContent != null && attached == emptyContent;
    }

    /** @return whether a loading content, the application's or the default, shows now */
    public boolean isShowingLoading() {
        return attached != null && attached != emptyContent;
    }

    /**
     * What should show now: nothing over rows, the loading content while loading, else the empty;
     * and nothing in place of a content the application has since put in another container.
     */
    private @Nullable Widget<?> wanted() {
        Widget<?> want = candidate();
        return want != null && isElsewhere(want) ? null : want;
    }

    private @Nullable Widget<?> candidate() {
        if (host.rowCount() > 0) {
            return null;
        }
        if (loading) {
            return loadingContent != null ? loadingContent : defaultLoading();
        }
        return emptyContent;
    }

    /** Whether {@code content} is a child of something other than the widget. */
    private boolean isElsewhere(Widget<?> content) {
        Widget<?> parent = content.parent();
        return parent != null && parent != host.widget();
    }

    /** The label that describes the widget while what it belongs to shows, or {@code null}. */
    private @Nullable Label shownMessage() {
        Widget<?> shown = attached;
        if (shown == null) {
            return null;
        }
        if (shown == emptyContent) {
            return emptyMessage;
        }
        return shown == loadingContent ? loadingMessage : defaultLoadingLine;
    }

    /**
     * Showing and hiding the content moves nothing outside the widget's own box: a layout of the
     * viewport, contained, and never the window's.
     */
    private void changed() {
        Widget<?> widget = host.widget();
        widget.markNeedsContainedLayout();
        widget.invalidate();
        widget.invalidateAccessible();
    }

    private static Label messageLine(I18nString words) {
        return new Label(words).setMuted(true).setWrap(true)
                .setAlign(Label.HAlign.CENTER, Label.VAlign.CENTER);
    }

    /** A moving bar over "Loading…", made the first time a load shows with no content of its own. */
    private Widget<?> defaultLoading() {
        if (defaultLoading == null) {
            Label line = messageLine(RowsStrings.LOADING);
            ProgressBar bar = new ProgressBar().setIndeterminate(true);
            // The bar says nothing a reader needs: the widget is busy and described by the line.
            bar.setAccessibleIgnored(true);
            TokenColumn column = new TokenColumn(Tokens.Role.SMALL);
            column.crossAlignment(Flex.CrossAlignment.CENTER);
            column.add(line);
            column.add(bar);
            defaultLoadingLine = line;
            defaultLoading = column;
        }
        return defaultLoading;
    }

    // ------------------------------------------------------------------ the widget's passes

    /**
     * Attaches what should show and detaches what should not, then lays it out in the middle of
     * the viewport at its own size, at most the viewport's width less {@code inset} on each side.
     * Called from the widget's layout, where its rows are mounted too.
     *
     * @param x      the viewport's left edge, in the widget's box
     * @param y      its top
     * @param width  its width
     * @param height its height
     * @param inset  the margin kept on either side
     */
    public void layout(float x, float y, float width, float height, float inset) {
        sync();
        Widget<?> shown = attached;
        if (shown == null) {
            return;
        }
        float room = Math.max(0, width - 2 * inset);
        Size size = shown.measure(Constraints.loose(room, Math.max(0, height)));
        float w = Math.min(size.width(), room);
        float h = Math.min(size.height(), Math.max(0, height));
        shown.layoutBox(x + (width - w) / 2, y + (height - h) / 2, w, h);
    }

    /** Makes the widget's children say what should show now. */
    private void sync() {
        Widget<?> want = wanted();
        Widget<?> elsewhere = want == null ? candidate() : null;
        if (elsewhere != null && isElsewhere(elsewhere)) {
            waitFor(elsewhere);
            if (!saidElsewhere) {
                saidElsewhere = true;
                LOG.log(System.Logger.Level.WARNING, "the content a " + host.widget().getClass().getSimpleName()
                        + " shows with no rows is held by a " + elsewhere.parent().getClass().getSimpleName()
                        + ", and shows here only once that lets it go");
            }
        } else {
            stopWaiting();
        }
        if (want == attached) {
            return;
        }
        Widget<?> leaving = attached;
        attached = null;
        if (leaving != null) {
            boolean heldFocus = holdsFocus(leaving);
            host.detach(leaving);
            if (heldFocus) {
                // The rows came back under a "Clear filter" that held the keyboard: it goes to
                // the widget, which is where the rows it brought back are.
                host.widget().requestFocus();
            }
        }
        if (want != null) {
            host.attach(want);
            attached = want; // only once it is the widget's child: a throw leaves nothing believed
        }
    }

    /**
     * Watches the container that holds {@code content} until it lets it go, then asks for this
     * widget's layout: the other widget giving it up after this one's layout of the same frame
     * would otherwise leave this one blank until something else laid it out.
     */
    private void waitFor(Widget<?> content) {
        Widget<?> holder = content.parent();
        if (waiting != null && waitingFor == content && waitingOn == holder) {
            return;
        }
        // Wanted something else, or held by another container, since the watch was set: a list
        // that waited for its empty content and then started loading kept watching the empty
        // content's holder, and stayed blank when the loading content it wanted was let go.
        stopWaiting();
        waitingFor = content;
        waitingOn = holder;
        waiting = holder.observeChanges((widget, change) -> {
            if (change.aspect() == limn.scene.Change.Aspect.CHILDREN && content.parent() != holder) {
                stopWaiting();
                host.widget().markNeedsContainedLayout();
            }
        });
    }

    private void stopWaiting() {
        if (waiting != null) {
            waiting.cancel();
            waiting = null;
        }
        waitingFor = null;
        waitingOn = null;
    }

    /**
     * The widget left the window: it lets its content go, so the content is free for another
     * widget (a page rebuilt with a fresh list reusing its label), and stops waiting for one. The
     * first layout back in a window shows it again if it is still free. Called from the widget's
     * {@code onDetached}.
     */
    public void hostDetached() {
        stopWaiting();
        Widget<?> leaving = attached;
        if (leaving != null) {
            attached = null;
            host.detach(leaving);
        }
    }

    private boolean holdsFocus(Widget<?> content) {
        Scene scene = host.widget().scene();
        Widget<?> focused = scene == null ? null : scene.focusedWidget();
        for (Widget<?> w = focused; w != null; w = w.parent()) {
            if (w == content) {
                return true;
            }
        }
        return false;
    }

    /**
     * Paints the content shown, if any, where the layout put it. The three widgets paint their
     * children themselves, the rows they mounted and nothing else, so the content is painted by
     * this call, from inside the clip they paint their rows in.
     *
     * @param canvas the widget's canvas, in its own coordinates
     */
    public void paint(limn.graphics.Canvas canvas) {
        Widget<?> shown = attached;
        if (shown == null || !shown.isVisible()) {
            return;
        }
        canvas.save();
        try {
            canvas.translate(shown.x(), shown.y());
            shown.paintWidget(canvas);
        } finally {
            canvas.restore();
        }
    }

    /**
     * @param child one of the widget's children
     * @return whether it is the content shown, which belongs to no selection
     */
    public boolean isContent(Widget<?> child) {
        return child == attached;
    }

    /**
     * What the content says about the widget: busy while a load runs, and described by the
     * message while one shows ("table, 0 rows, no orders match").
     *
     * @param a the widget's node
     */
    public void describe(Accessibility a) {
        if (loading) {
            a.state(Accessible.State.BUSY);
        }
        Label message = shownMessage();
        if (message != null) {
            a.description(message.textSource());
            a.relation(Accessible.Relation.DESCRIBED_BY, message);
        }
    }
}
