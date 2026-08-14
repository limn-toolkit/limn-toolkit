package limn.components;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Size;
import limn.scene.Widget;

import java.util.ArrayList;
import java.util.List;

/**
 * A horizontal strip of controls (buttons, toggles, segmented controls,
 * {@link Separator}s…) on a rounded {@link Theme#surface} background. Lays its
 * items out left to right, vertically centered, with a configurable gap. Purely
 * a styled container: the items handle their own input.
 *
 * <p>Sizes follow the {@link ControlSize} resolved on this widget: the padding on all four
 * edges, the default gap and the inset of the separators {@link #addSeparator()} builds come
 * from the {@link SizeTokens} row; the border weight comes from {@link Strokes} and is the
 * same at every step, which is what makes a bar and the buttons inside it read as one object.
 *
 * <p>The bar has <b>no height table of its own</b>: its height stays
 * {@code tallestChild + 2 * toolBarPad}. Giving it one would let a bar full of MEDIUM buttons
 * disagree with itself about how tall those buttons are, depending only on the bar's own step.
 * Items added through {@link #addItem} are children, so they inherit the bar's step: that
 * propagation is the whole point of setting a step on a toolbar.
 */
public class ToolBar extends Widget {

    /**
     * The gap the app asked for, meaningful only while {@link #gapExplicit}. An explicit
     * {@link #gap(float)} latches: a step change must never stomp a number the app chose,
     * and the flag (not the value) is what records that it chose one. Inferring "explicit"
     * from {@code gap != toolBarGap()} would silently un-latch a caller who happened to pass
     * this step's default.
     */
    private float gap;
    private boolean gapExplicit;

    /** The separators this bar built itself, and whose inset it therefore owns. */
    private final List<Separator> ownSeparators = new ArrayList<>();
    /** Last inset pushed onto {@link #ownSeparators}; NaN forces a sync (never equal). */
    private float appliedSepInset = Float.NaN;

    /** Adds an item to the end of the bar. */
    public ToolBar addItem(Widget item) {
        Ui.checkUiThread();
        add(item);
        markNeedsLayout();
        return this;
    }

    /** Adds a vertical divider between items, inset per the bar's step. */
    public ToolBar addSeparator() {
        Ui.checkUiThread();
        Separator separator = Separator.vertical();
        ownSeparators.add(separator);
        // The inset cannot be applied here: a widget has no parent until add() returns, so
        // its step would resolve to the process default no matter what this bar declares.
        // Clear the memo instead and let the next measure push the resolved value.
        appliedSepInset = Float.NaN;
        return addItem(separator);
    }

    /**
     * Overrides the gap between items, in points. Latches: the value survives every later
     * {@link ControlSize} change. Leave it alone to follow the step's {@code toolBarGap}.
     */
    public ToolBar gap(float newGap) {
        Ui.checkUiThread();
        this.gap = Math.max(0, newGap);
        this.gapExplicit = true;
        markNeedsLayout();
        return this;
    }

    /** The one gap expression: measure, layout and any future hit test must call this. */
    private float gapOf(SizeTokens t) {
        return gapExplicit ? gap : t.toolBarGap();
    }

    /**
     * Pushes {@code toolBarSepInset} onto the separators this bar created. Runs from measure
     * because that is the first point at which the tree is complete and the step is known,
     * and early enough that the new inset paints in the same frame. {@code setInset} marks
     * layout dirty (it cannot know the inset only moves ink), so the memo matters: without it
     * every measure would re-request a render forever.
     */
    private void syncSeparatorInsets(SizeTokens t) {
        // Prune every pass, not only when the value moves: a separator the app removed is no
        // longer ours to touch, and gating the prune on a value change leaked removed children
        // for as long as the step held still.
        ownSeparators.removeIf(separator -> separator.parent() != this);
        appliedSepInset = t.toolBarSepInset();
        for (int i = 0; i < ownSeparators.size(); i++) {
            // The internal form: no thread check, so measuring a ToolBar stays a pure geometry
            // call, and its own equality guard keeps this idempotent inside onMeasure.
            ownSeparators.get(i).setInsetInternal(appliedSepInset);
        }
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        syncSeparatorInsets(t);
        float pad = t.toolBarPad();
        float gapBetween = gapOf(t);
        float x = pad;
        float maxChildHeight = 0;
        var items = children();
        for (int i = 0; i < items.size(); i++) {
            // Height is UNBOUNDED here on purpose: this pass asks every item for its natural
            // height, since the bar's own height is derived from the tallest of them. Handing
            // down the incoming bound instead lets a stretchy item (Separator.vertical() fills
            // its constraint) claim the whole window and drag the bar with it, and it made
            // this pass disagree with onLayout, which offers the inner band. Stretch still
            // happens, in layout, against the band that measure produced.
            Size s = items.get(i).measure(
                    Constraints.loose(constraints.maxWidth(), Constraints.UNBOUNDED_LIMIT));
            x += s.width();
            if (i < items.size() - 1) {
                x += gapBetween;
            }
            maxChildHeight = Math.max(maxChildHeight, s.height());
        }
        return constraints.constrain(x + pad, maxChildHeight + 2 * pad);
    }

    @Override
    protected void onLayout() {
        SizeTokens t = Theme.current().tokensFor(this);
        float pad = t.toolBarPad();
        float gapBetween = gapOf(t);
        float x = pad;
        float innerH = Math.max(0, height() - 2 * pad);
        for (Widget item : children()) {
            Size s = item.measure(Constraints.loose(width(), innerH));
            float cy = pad + (innerH - s.height()) / 2;
            item.layoutBox(x, cy, s.width(), s.height());
            x += s.width() + gapBetween;
        }
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        canvas.fillRoundRect(0, 0, width(), height(), t.radiusMedium(), theme.surface);
        float inset = Strokes.HALF_PIXEL_INSET; // lands the 1pt stroke on one device pixel
        canvas.drawRoundRect(inset, inset, width() - 2 * inset, height() - 2 * inset,
                t.radiusMedium(), Strokes.BORDER, theme.outline);
    }
}
