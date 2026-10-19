package limn.components.internal;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.Label;
import limn.components.ProgressBar;
import limn.components.TokenColumn;
import limn.components.Tokens;
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
 * selection: the rows' and the grid's contracts read members, and it is not one.
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

    private final Host host;
    private @Nullable Widget<?> emptyContent;
    private @Nullable Label emptyMessage;
    private @Nullable Widget<?> loadingContent;
    private @Nullable Label loadingMessage;
    private @Nullable Widget<?> defaultLoading;
    private @Nullable Label defaultLoadingLine;
    private boolean loading;
    private @Nullable Widget<?> attached;

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
     */
    public void setEmpty(@Nullable Widget<?> content, @Nullable Label message) {
        Ui.checkUiThread();
        this.emptyContent = content;
        this.emptyMessage = content == null ? null : message;
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
     */
    public void setLoadingContent(@Nullable Widget<?> content, @Nullable Label message) {
        Ui.checkUiThread();
        this.loadingContent = content;
        this.loadingMessage = content == null ? null : message;
        changed();
    }

    /** @return the loading content the application gave, or {@code null} for the default */
    public @Nullable Widget<?> loadingContent() {
        return loadingContent;
    }

    /**
     * Says the widget is loading, or has finished. The widget is published busy while it loads,
     * and a reader is told both ends: "Loading Orders" at the start, and at the end how many rows
     * arrived ("Orders, 12 items"), or the empty message when none did. Set the rows before saying
     * the load ended, so the count said is the one shown.
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
        if (scene == null) {
            return; // nobody to say it to
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

    /** @return whether a load is running */
    public boolean isLoading() {
        return loading;
    }

    /** @return whether the empty content is what the widget shows now */
    public boolean isShowingEmpty() {
        return emptyContent != null && wanted() == emptyContent;
    }

    /** @return whether a loading content, the application's or the default, shows now */
    public boolean isShowingLoading() {
        Widget<?> shown = wanted();
        return shown != null && shown != emptyContent;
    }

    /** What should show now: nothing over rows, the loading content while loading, else the empty. */
    private @Nullable Widget<?> wanted() {
        if (host.rowCount() > 0) {
            return null;
        }
        if (loading) {
            return loadingContent != null ? loadingContent : defaultLoading();
        }
        return emptyContent;
    }

    /** The label that describes the widget while what it belongs to shows, or {@code null}. */
    private @Nullable Label shownMessage() {
        Widget<?> shown = wanted();
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
            attached = want;
            host.attach(want);
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
