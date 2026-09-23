package limn.components;

import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.SizedBox;

import java.util.Objects;

/**
 * A {@link SizedBox} whose fixed dimensions come from the resolved
 * {@link limn.scene.ControlSize} step instead of from a number baked at construction.
 *
 * <p>The fourth of the {@code Token*} family: {@link TokenRow}, {@link TokenColumn} and
 * {@link TokenPadding} do this for gaps and insets, and this does it for an extent. It
 * exists for the size-axis trap the repository keeps running into: a widget has no parent
 * while it is being built, so a size read there resolves against the <em>process</em>
 * default and stays there forever, whatever step the eventual parent declares.
 *
 * <pre>{@code
 * // the colour picker, as wide as the step says a picker in a dialog should be
 * new TokenBox(SizeTokens::colorDialogW, null, picker)
 * }</pre>
 *
 * <p>{@code null} means "unset" on either axis, exactly as {@link SizedBox#UNSET} does:
 * that axis is whatever the child measures to. It is documented against that class and
 * extends {@link limn.scene.Widget} directly; it is not a {@code SizedBox} and shares no
 * code with one.
 *
 * <p><b>To an assistive technology this widget is not there.</b> It declares no role, no
 * name, no action and no state, so the walk's transparency predicate deletes it and its
 * child publishes in its place — and because {@code onLayout} hands that child the whole
 * box at the origin, the rectangle the child publishes is the one this widget would have.
 * The deletion therefore costs a reader nothing at all, which is not true of a padding or
 * a row. Naming an instance materialises a group over that same rectangle, so a name here
 * buys a name and no geometry.
 *
 * <p>What this class guarantees a reader is <em>when</em> the extent is read and not what
 * it returns: the {@link Extent} runs inside the measure pass, against the step resolved
 * with a parent in place, and is then clamped by the incoming constraints like any other
 * request. An {@code Extent} that ignores its argument is a constant, and a tight parent
 * discards the number either way.
 */
public final class TokenBox extends Widget<TokenBox> {

    /** One extent, read off a size step. */
    @FunctionalInterface
    public interface Extent {
        /** @return the extent in points, for the row {@code tokens} */
        float of(SizeTokens tokens);
    }

    private final Extent fixedWidth;
    private final Extent fixedHeight;
    private final Widget<?> child;

    /**
     * @param fixedWidth  the width to impose, or {@code null} to take the child's
     * @param fixedHeight the height to impose, or {@code null} to take the child's
     * @param child       the widget to size; never {@code null}
     */
    public TokenBox(Extent fixedWidth, Extent fixedHeight, Widget<?> child) {
        this.fixedWidth = fixedWidth;
        this.fixedHeight = fixedHeight;
        this.child = Objects.requireNonNull(child, "child");
        add(child);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        float wanted = fixedWidth == null ? -1 : fixedWidth.of(t);
        float tall = fixedHeight == null ? -1 : fixedHeight.of(t);
        float maxW = wanted >= 0 ? constraints.constrainWidth(wanted) : constraints.maxWidth();
        float maxH = tall >= 0 ? constraints.constrainHeight(tall) : constraints.maxHeight();
        Size inner = child.measure(new Constraints(
                wanted >= 0 ? maxW : 0, maxW,
                tall >= 0 ? maxH : 0, maxH));
        return constraints.constrain(wanted >= 0 ? maxW : inner.width(),
                tall >= 0 ? maxH : inner.height());
    }

    @Override
    protected void onLayout() {
        child.layoutBox(0, 0, width(), height());
    }
}
