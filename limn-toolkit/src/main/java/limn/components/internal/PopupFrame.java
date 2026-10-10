package limn.components.internal;

import limn.backend.NativeWindow;
import limn.components.Elevation;
import limn.components.Theme;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.MouseEvent;

import java.util.Objects;

/**
 * The root of a popup that is a window of its own on a system whose compositor casts no shadow
 * for such a window: the window is larger than the popup's panel by the shadow's reach on every
 * side, this lays the panel out inset by that margin, and the panel draws its shadow into the
 * margin, which is otherwise transparent. A press in the margin is a press beside the popup and
 * closes it.
 *
 * <p>It declares nothing and paints nothing, so it is not in the accessible tree: the panel
 * stands in its place there.
 *
 * <p>Not API: see the package.
 */
public final class PopupFrame extends Widget<PopupFrame> {

    private final Widget<?> panel;
    private final float margin;
    private final Runnable pressedBeside;

    /**
     * @param panel         the popup's panel
     * @param margin        the transparent margin around it, in points
     * @param pressedBeside what a press in the margin does
     */
    public PopupFrame(Widget<?> panel, float margin, Runnable pressedBeside) {
        this.panel = Objects.requireNonNull(panel, "panel");
        this.margin = margin;
        this.pressedBeside = Objects.requireNonNull(pressedBeside, "pressedBeside");
        add(panel);
    }

    /**
     * The margin a popup window of a level needs around its panel for the shadow the theme gives
     * that level, in whole points: zero where the compositor casts the shadow itself, for a
     * theme that casts none, and for a window with no backend to say.
     *
     * @param owner the window the popup is opened from
     * @param theme the theme in effect
     * @param level the popup's level
     * @return the margin
     */
    public static int marginFor(NativeWindow owner, Theme theme, Elevation level) {
        if (owner == null || owner.backend() == null || owner.backend().compositorShadowsPopups()) {
            return 0;
        }
        return (int) Math.ceil(theme.elevation(level).reach());
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
    }

    @Override
    protected void onLayout() {
        float w = Math.max(0, width() - 2 * margin);
        float h = Math.max(0, height() - 2 * margin);
        panel.measure(Constraints.tight(w, h));
        panel.layoutBox(margin, margin, w, h);
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        // What reaches here was not the panel's: the margin's.
        if (event.type() == MouseEvent.Type.PRESS) {
            pressedBeside.run();
            event.consume();
        }
    }
}
