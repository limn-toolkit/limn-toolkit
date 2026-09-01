package limn.components;

import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Gives any widget a context menu, and answers what a request for one looks like.
 *
 * <pre>{@code
 * column.add(ContextMenus.attach(fileList, () -> new Menu()
 *         .addItem("Rename…", this::rename)
 *         .addItem("Delete",  this::delete)));
 * }</pre>
 *
 * <p><b>It wraps rather than sets, and that is not a style choice.</b> A widget has no
 * context-menu property to assign because a {@link Widget} cannot name a {@link Menu}: the
 * two live on opposite sides of a dependency boundary, and the toolkit side is the one that
 * depends on nothing. So the menu is held here, one layer above the widget, which is the
 * nearest place the type exists. {@link #attach} therefore returns the widget to add to the
 * tree, not the one passed in.
 *
 * <p><b>The gesture is more than the right button.</b> A keyboard user asks for the menu with
 * the Menu key or Shift+F10 and never presses a mouse button at all; a hand-rolled
 * right-press check leaves them with no route to the same commands, which is the failure this
 * class exists to stop repeating. {@link #isRequest(MouseEvent)} and
 * {@link #isRequest(KeyEvent)} are the same question for code that already has its own
 * {@code onMouseEvent} and only wants the answer.
 */
public final class ContextMenus {

    private ContextMenus() {
    }

    /**
     * Wraps {@code content} so that a context request anywhere inside it opens the menu
     * {@code source} supplies. The wrapper is invisible: it measures, lays out and paints as
     * {@code content} alone would, and adds nothing to the picture.
     *
     * <p><b>{@code source} is asked at the moment of the gesture</b>, never at attach time,
     * because the interesting menus depend on what is under the pointer or what is selected
     * when it happens. Returning {@code null} (or a menu with no rows) opens nothing, which
     * is how a region says "not here" for a particular spot without the caller writing a
     * second gesture check.
     *
     * <p><b>A child that answers the gesture itself keeps it.</b> Events bubble and stop at
     * the first widget that consumes them, so a {@link TextField} inside an attached region
     * still raises its own Cut/Copy/Paste menu rather than the region's. That is the desired
     * order and not a leak: the specific widget is the better answer.
     *
     * @param content the widget to give a menu to; it becomes the wrapper's only child
     * @param source  consulted per gesture, on the UI thread, and may answer {@code null}
     * @return the widget to put in the tree in {@code content}'s place
     */
    public static Widget attach(Widget content, Supplier<Menu> source) {
        return new ContextRegion(Objects.requireNonNull(content, "content"),
                Objects.requireNonNull(source, "source"));
    }

    /**
     * Whether this event is the pointer's request for a context menu, for a widget that has
     * its own {@code onMouseEvent} and wants the answer rather than the wrapper.
     *
     * <p>True only for the press, never the release or the click: a menu that waited for the
     * release would open under a button the user has already let go of, and every desktop
     * raises this one on the way down.
     */
    public static boolean isRequest(MouseEvent event) {
        Objects.requireNonNull(event, "event");
        return event.type() == MouseEvent.Type.PRESS && event.button() == Keys.MOUSE_RIGHT;
    }

    /**
     * Whether this event is the keyboard's request for a context menu: the dedicated Menu key
     * that sits between the right Alt and Control on most keyboards, or Shift+F10 for the
     * many that do not have one.
     *
     * <p>True only on the press, and false for a repeat: holding the key must not raise a
     * stack of menus.
     */
    public static boolean isRequest(KeyEvent event) {
        Objects.requireNonNull(event, "event");
        if (!event.isPressed() || event.isRepeat()) {
            return false;
        }
        return event.key() == Keys.MENU
                || (event.key() == Keys.F10 && (event.modifiers() & Keys.MOD_SHIFT) != 0);
    }

    /**
     * Opens {@code menu} with its corner at a point in {@code anchor}'s <b>own</b>
     * coordinates (what {@link MouseEvent#x()} reports) rather than the scene coordinates a
     * popup is placed in. The conversion is the whole reason this exists: it is two field
     * reads, it is wrong in a way that only shows up on a scrolled or nested widget, and
     * every place that hand-rolled a context menu wrote it out again.
     *
     * <p>A {@code null} or empty menu opens nothing, so a caller that computes its rows can
     * hand the result straight over.
     *
     * <p><b>The point is not mirrored, and that is the whole of the direction story here.</b> A
     * menu raised at the pointer lands on the pointer reading either way; which corner of the
     * column meets that point is {@link PopupMenu}'s decision and is already taken there.
     * Reflecting the point as well would move the menu away from the spot the user aimed at.
     */
    public static void showAt(Widget anchor, Menu menu, float localX, float localY) {
        Objects.requireNonNull(anchor, "anchor");
        if (menu == null || menu.items().isEmpty()) {
            return;
        }
        new PopupMenu(menu).showAt(anchor,
                anchor.localToSceneX() + localX, anchor.localToSceneY() + localY);
    }

    /**
     * Opens {@code menu} for a request that carries no point: the keyboard route. It drops
     * from the lower <b>leading</b> corner of whatever currently holds focus — the bottom left
     * reading left to right and the bottom right reading right to left — so the menu appears at
     * the row or field the user was on rather than at a corner of the region containing it.
     *
     * <p>Falls back to {@code anchor}'s own lower leading corner when nothing in the scene has
     * focus, which is the only place left that is still related to the request.
     *
     * <p><b>The direction is the focused widget's, not the region's</b> — for the corner and for
     * the cascade both. A right-to-left field inside a left-to-right form starts reading at its
     * own right edge, and that is the corner the user's eye is at when the key arrives; taking
     * the region's direction instead would drop the menu at the end of a field the user is not
     * reading from. The cascade must agree: a menu whose corner is the field's right edge but
     * whose column grows as the region reads opens away from the field it dropped from, so the
     * popup is anchored on the same widget the corner came from, and its growth, its step and
     * its corner are one answer.
     */
    public static void showForFocus(Widget anchor, Menu menu) {
        Objects.requireNonNull(anchor, "anchor");
        if (menu == null || menu.items().isEmpty()) {
            return;
        }
        Widget from = anchor;
        if (anchor.scene() != null && anchor.scene().focusedWidget() != null) {
            from = anchor.scene().focusedWidget();
        }
        // Resolved once, after the fallback has chosen which widget the menu drops from, and in
        // an event-driven call rather than at construction: this runs with the tree complete.
        boolean rtl = from.layoutDirection() == LayoutDirection.RTL;
        float cornerX = rtl ? from.localToSceneX() + from.width() : from.localToSceneX();
        new PopupMenu(menu).showAt(from, cornerX, from.localToSceneY() + from.height());
    }

    /**
     * The wrapper {@link #attach} returns: one child, no picture of its own, and the two
     * gestures. It is not focusable: giving it focus would put a Tab stop around content
     * that may already have several, and the keyboard route works from the content's own
     * focus by bubbling.
     */
    private static final class ContextRegion extends Widget {

        private final Widget content;
        private final Supplier<Menu> source;

        ContextRegion(Widget content, Supplier<Menu> source) {
            this.content = content;
            this.source = source;
            add(content);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return content.measure(constraints);
        }

        @Override
        protected void onLayout() {
            content.layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (isRequest(event)) {
                event.consume();
                showAt(this, source.get(), event.x(), event.y());
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (isRequest(event)) {
                event.consume();
                showForFocus(this, source.get());
            }
        }
    }
}
