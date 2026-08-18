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
 * that axis is whatever the child measures to.
 */
public final class TokenBox extends Widget {

    /** One extent, read off a size step. */
    @FunctionalInterface
    public interface Extent {
        /** @return the extent in points, for the row {@code tokens} */
        float of(SizeTokens tokens);
    }

    private final Extent fixedWidth;
    private final Extent fixedHeight;
    private final Widget child;

    /**
     * @param fixedWidth  the width to impose, or {@code null} to take the child's
     * @param fixedHeight the height to impose, or {@code null} to take the child's
     * @param child       the widget to size; never {@code null}
     */
    public TokenBox(Extent fixedWidth, Extent fixedHeight, Widget child) {
        this.fixedWidth = fixedWidth;
        this.fixedHeight = fixedHeight;
        this.child = Objects.requireNonNull(child, "child");
        add(child);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
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
