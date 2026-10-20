package limn.scene.layout;

import limn.concurrent.Ui;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.internal.OpacityAccess;
import limn.scene.internal.WindowAnimation;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Draws one child, and everything under it, at an opacity, as one picture: 1, the default, draws
 * the child as it always was; 0 draws nothing; between, the child is drawn into an offscreen layer
 * and laid over what is beneath with the opacity once, so the parts of it that overlap do not show
 * through each other (a row's highlight through its icon, a panel's shadow through the panel). One container inside another multiplies its opacity: a child at one half
 * inside one at one half shows at a quarter, the inner picture composited into the outer one.
 *
 * <p>A container, as {@link Padding} is for space round a child, and not a property of every
 * widget: the group is where it is written, the cost is only where the container is, and the order
 * of two effects on one child is the order their containers are nested in.
 *
 * <p><b>Transparent to everything but the picture.</b> It measures, lays out and aligns on a
 * baseline as its child alone does, the child given its whole box; it has no node of its own in the
 * accessible tree, which holds the child in its place; and it declares the child's own reach past
 * the box to partial rendering, since the child covers the same box. Inside a {@link Row} or a
 * {@link Column} the container is the flex child: to fade an {@link Expanded}'s content, put the
 * container inside the {@code Expanded}.
 *
 * <p><b>A picture, not a presence.</b> At every value, 0 included, the child is laid out, takes the
 * pointer, takes focus and is read by a screen reader, as on a web page. To take something away,
 * hide it: {@link #setVisible setVisible(false)} wins over any opacity, and the opacity is kept for
 * when the container is shown again. A fade that ends at 0 hides it in its {@code onArrive}, or
 * leaves something nobody can see that a click or a Tab still reaches.
 *
 * <p><b>What it costs</b>, as measured: nothing at 1, nothing drawn at 0, and between the two a layer
 * the size of what the child and its subtree paint, as far as it can be seen, on every frame that
 * repaints it: on a GPU a few hundredths of a millisecond for a popup-sized child, on a software
 * renderer a percent or two of the frame. A change damages the same rectangle and lays nothing out.
 * Where a parent's paint has left a {@link limn.graphics.BlendMode} other than normal in force, the
 * child is one picture blended once in that mode at every opacity, 1 included, so a fade arriving
 * at 1 does not jump.
 *
 * <p><b>A blend mode inside it</b>, a descendant that draws in {@link limn.graphics.BlendMode#MULTIPLY}
 * or {@link limn.graphics.BlendMode#ADDITIVE}, blends below 1 with what the subtree itself drew
 * beneath it, and where it drew nothing is drawn as itself, since the picture starts transparent,
 * as an isolated group of a web page does; at 1 there is no picture, and it blends with what is
 * beneath the container. A multiplied tint in a card fading in therefore shows as itself over the
 * page during the fade and multiplies the page on the fade's last frame, the jump a page's own
 * fade makes too. Where that jump matters, draw what blends with the page outside the container,
 * or end the fade just below 1.
 *
 * <p><b>A watcher hears it</b> as {@link Change.Aspect#OPACITY} with origin {@code CODE} on this
 * container: every time {@link #setOpacity} changes the value and never when it does not, and
 * twice for a {@link #fadeTo fade}, as it starts and as it arrives or is superseded.
 */
public final class Opacity extends Widget<Opacity> {

    private final Widget<?> child;

    /**
     * Wraps {@code child} at an opacity of 1.
     *
     * @param child the widget drawn at this container's opacity, with everything under it
     */
    public Opacity(Widget<?> child) {
        this.child = Objects.requireNonNull(child, "child");
        add(child);
        OpacityAccess.makeGroup(this);
    }

    /**
     * Sets the opacity the child is drawn at, at once.
     *
     * <p>A {@link #fadeTo fade} in flight is superseded: it stops where it is and announces its
     * end first, and its {@code onArrive} never runs; so does the {@code onArrive} of one gone
     * straight there that still waits. A watcher hears {@link Change.Aspect#OPACITY}
     * every time this changes the value and never when it does not.
     *
     * <p>UI thread only; refused from inside a paint, which is re-run per pass and must not move what
     * it paints.
     *
     * @param opacity the opacity; clamped to 0..1
     * @return this
     * @throws IllegalArgumentException if {@code opacity} is NaN
     * @throws IllegalStateException    off the UI thread, or if called from inside a paint
     */
    public Opacity setOpacity(float opacity) {
        Ui.checkUiThread();
        OpacityAccess.set(this, opacity);
        return this;
    }

    /**
     * This container's own opacity, as {@link #setOpacity} or a {@link #fadeTo fade} last left it,
     * and not the product with an enclosing container's: nothing needs that number.
     *
     * @return the opacity, 0 to 1
     */
    public float opacity() {
        return OpacityAccess.opacity(this);
    }

    /**
     * {@link #fadeTo(float, double, Runnable)} over the theme's window animation, the time the
     * toolkit's own popups and dialogs fade in, with nothing run at the end.
     *
     * @param target the opacity to arrive at; clamped to 0..1
     * @return this
     * @throws IllegalArgumentException if {@code target} is NaN
     * @throws IllegalStateException    off the UI thread, or if called from inside a paint
     */
    public Opacity fadeTo(float target) {
        return fadeTo(target, WindowAnimation.of(this), null);
    }

    /**
     * Moves the {@link #opacity()} to {@code target} over {@code seconds}, eased as the toolkit
     * eases a window's fade, one step a frame on the scene's wall-time ticker, which ticks only
     * while the fade moves and which pausing the scene does not freeze (an {@code onArrive} often
     * hides or removes something, and a paused application must still be able to). One ticker per
     * container, however many fades are asked for between two frames.
     *
     * <p><b>Straight to the target</b>, as one {@link #setOpacity} (one announcement if the value
     * changed, none if not), while the system asks for less motion, for no time, for the value the
     * container already has, or with no frames to step it: a container in no scene, or in a scene
     * with no window. Its {@code onArrive} runs then after the {@code seconds} asked for, as a
     * task {@link Ui#postDelayed} runs, so that what follows it keeps the pace the call asked for;
     * for no time, on the next turn of the UI thread's queue.
     *
     * <p><b>{@code onArrive} never runs inside this call</b>, whether the fade moves or goes
     * straight there, so the next step can be asked for in it: a sequence (fade out, swap the
     * content, fade back in), or a pulse, each fade asking for the next in its {@code onArrive}.
     * Such a chain keeps the pace its calls ask for, where the fades go straight there too, and the
     * UI thread sleeps between its steps. It goes on, one step a period whether the container is
     * shown or not, for as long as the code keeps asking, so a pulse stops itself: when what it
     * stands for is over, or when its container is no longer {@link #isShowing() showing}.
     *
     * <p><b>A newer {@code fadeTo} or a {@link #setOpacity} supersedes one in flight</b>, or one
     * gone straight there whose {@code onArrive} still waits: it stops where it is,
     * and its {@code onArrive} never runs. A fade on a container that leaves its scene
     * (removed from the tree, moved into another window, or its window closed) settles at its target
     * as it leaves, announcing its end and running its {@code onArrive} then; one that went
     * straight there and still waits for its seconds runs its {@code onArrive} then too. That
     * {@code onArrive} runs once the container is out of the scene it left, so a fade it asks for
     * follows the container: straight there when it is in no scene, or on the frames of the scene
     * it went to, never on those of the window it left. An {@code onArrive} that throws on a frame
     * is logged and reported as a throwing ticker is, and changes nothing for the container's
     * later fades, nor for one it asked for before it threw.
     *
     * <p><b>A watcher hears a fade twice</b>, as {@link Change.Aspect#OPACITY} with origin
     * {@code CODE}: when it starts, and when it arrives or is superseded. The steps between set the
     * value and repaint without announcing, so a fade of any length wakes a watcher twice and never
     * once a frame.
     *
     * <p>A fade out does not hide: the child at 0 still takes the pointer and the keyboard, so a
     * fade meant to take it away hides the container in {@code onArrive}.
     *
     * <p>UI thread only; refused from inside a paint.
     *
     * @param target   the opacity to arrive at; clamped to 0..1
     * @param seconds  how long the fade takes, when the system does not ask for less motion
     * @param onArrive run once when the fade arrives, or after {@code seconds} when it goes straight
     *                 there (on the next turn of the UI thread's queue for none); never inside this
     *                 call, never when it is superseded; may be null
     * @return this
     * @throws IllegalArgumentException if {@code target} is NaN, or {@code seconds} is negative,
     *                                  NaN or infinite
     * @throws IllegalStateException    off the UI thread, or if called from inside a paint
     */
    public Opacity fadeTo(float target, double seconds, @Nullable Runnable onArrive) {
        Ui.checkUiThread();
        OpacityAccess.fadeTo(this, target, seconds, onArrive);
        return this;
    }

    /**
     * The child's own reach past its box, which is this box: the container paints nothing of its
     * own, so how far its subtree may paint past it is exactly how far the child declares, a card's
     * shadow or a focus ring at its edge. A number of the theme's here, as other wrappers declare,
     * would be wrong both ways: short of a shadow, a repaint of the shadow's band alone skipped the
     * child, and past a plain child, every repaint of the container grew for nothing. O(1), as the
     * child's own answer is.
     */
    @Override
    protected float paintOutset() {
        return OpacityAccess.paintOutsetOf(child);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        Size size = child.measure(constraints);
        return constraints.constrain(size.width(), size.height());
    }

    @Override
    protected void onLayout() {
        child.layoutBox(0, 0, width(), height());
    }

    /** The child's: the container aligns on a baseline as the child alone does. */
    @Override
    protected float baselineOffset() {
        return baselineOffsetOf(child);
    }
}
