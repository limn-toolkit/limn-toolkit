package limn.scene.layout;

import limn.scene.Widget;

/**
 * A widget whose children are whatever the application puts in it: the one kind of widget that
 * adds and removes children in public (ADR 046 §3).
 *
 * <p>Every other widget arranges children of its own — a table its rows, a tab pane its headers, a
 * button its label — and adding to one of those from outside either did nothing it would show or
 * took a row out from under the widget's own bookkeeping. So {@link Widget#add} and
 * {@link Widget#remove} are protected, and this class is where they become public. {@link Column},
 * {@link Row} and {@link Stack} are containers; a layout an application writes extends this.
 */
public abstract class Container<W extends Container<W>> extends Widget<W> {

    /** Appends a child (UI thread only). */
    @Override
    public void add(Widget<?> child) {
        super.add(child);
    }

    /**
     * Inserts a child at a position in {@link #children()} (UI thread only).
     *
     * @param index where the child goes, in {@code [0, children().size()]}
     * @param child the child to insert; never {@code null}
     */
    @Override
    public void add(int index, Widget<?> child) {
        super.add(index, child);
    }

    /** Removes a child (UI thread only); a widget that is not a child is ignored. */
    @Override
    public void remove(Widget<?> child) {
        super.remove(child);
    }
}
