package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.internal.a11y.PopupOwnerAccessibility;
import limn.input.Keys;
import limn.scene.Constraints;
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
    public static Widget<?> attach(Widget<?> content, Supplier<Menu> source) {
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
    public static void showAt(Widget<?> anchor, Menu menu, float localX, float localY) {
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
    public static void showForFocus(Widget<?> anchor, Menu menu) {
        Objects.requireNonNull(anchor, "anchor");
        if (menu == null || menu.items().isEmpty()) {
            return;
        }
        Widget<?> from = anchor;
        if (anchor.scene() != null && anchor.scene().focusedWidget() != null) {
            from = anchor.scene().focusedWidget();
        }
        // Resolved once, after the fallback has chosen which widget the menu drops from, and in
        // an event-driven call rather than at construction: this runs with the tree complete.
        boolean rtl = from.isRightToLeft();
        float cornerX = rtl ? from.localToSceneX() + from.width() : from.localToSceneX();
        new PopupMenu(menu).showAt(from, cornerX, from.localToSceneY() + from.height());
    }

    /**
     * The wrapper {@link #attach} returns: one child, no picture of its own, and the two
     * gestures. It is not focusable: giving it focus would put a Tab stop around content
     * that may already have several, and the keyboard route works from the content's own
     * focus by bubbling.
     *
     * <p><b>It is a node, and the child it wraps is not annotated.</b> ADR 039 §7's row asks for
     * the opposite — the wrapper deleted as scaffolding, with the state and the verb written onto
     * the child through {@code onAccessibilityChild} — and that is unimplementable rather than
     * merely different. The walk records the <em>owner</em> of every node as the widget the node
     * came from, and the scene dispatches an action strictly to that owner with no fallback to a
     * parent, so a verb the region wrote onto its child would be dispatched to the child: an
     * arbitrary application widget whose action hook is {@code Widget}'s and answers false. That
     * publishes a context menu refused on all three platforms rather than one absent, which is
     * worse than saying nothing. Nor would the row save a node: writing a state and a verb onto a
     * {@code Column} or a {@code Padding} makes that scaffold survive §1.6's predicate, so the
     * node appears anyway, one level deeper, carrying a verb nothing can perform. The row's own
     * true sentence — that the region lays out to exactly its content's box — is the argument for
     * this shape: the two rectangles are the same rectangle, and only one of the two nodes can
     * open the menu.
     */
    private static final class ContextRegion extends Widget<ContextRegion> {

        private final Widget<?> content;
        private final Supplier<Menu> source;

        ContextRegion(Widget<?> content, Supplier<Menu> source) {
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

        /**
         * One group over the content's rectangle, saying that asking here may open a menu, and
         * offering the ask.
         *
         * <p>No role of its own: {@code GROUP} is what the builder resets to, and what keeps this
         * node out of §1.6's deletion is the state and the verb rather than a role, so declaring
         * one would say nothing the default does not. No name either, and that is a decision. The
         * region holds no {@code I18nString}, derives nothing, and must not borrow the content's,
         * which already names its own node. Three things still name it, all of them after this
         * hook and all of them free: {@code setAccessibleName} on the widget {@link #attach}
         * returned, a caption bound to it with {@code setAccessibleLabelledBy}, and a tooltip on
         * that same widget, which the walk takes as a name when nothing else supplied one. The
         * wrapper is what {@code attach} hands back, so that hatch is public and per instance, and
         * it is the answer to a reader landing on an unnamed group.
         *
         * <p><b>{@link Accessible.State#HAS_POPUP} unconditionally, and never "if the supplier
         * would answer a menu".</b> The supplier is an application callback that <em>builds</em> a
         * {@code Menu}, and this class's whole contract is that it is asked at the moment of the
         * gesture; asking it from here would run application code inside the publish step and
         * allocate a menu on every damaged frame, to conclude that nothing had moved. It is the
         * same answer {@code ColorPickerButton} gives for the same state. The honest reading of
         * the unconditional bit is "asking at this rectangle may open a menu", which is exactly
         * what the pointer route already promises: a right-click where the supplier answers
         * {@code null} opens nothing.
         *
         * <p>Nothing published here can change — both facts are constants of the class, and the
         * box moves only through a layout that republishes anyway — so the region invalidates
         * nothing, caches no string and needs no revision counter, and a quiet frame over an
         * attached region compares two primitives.
         *
         * @param a the node being described
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            // The POPUP_OWNER shape, written once (ADR 045 §3), in its menu form: a popup and
            // the verb that raises it, with no open state of its own. Nothing else: FOCUS and
            // SCROLL_INTO_VIEW are the walk's, and only for a focusable widget, which this
            // deliberately is not.
            PopupOwnerAccessibility.describeMenuOwner(a);
        }

        /**
         * Raises the region's own menu, from the region's own lower leading corner.
         *
         * <p>The supplier is asked exactly once and the menu it answered is the one handed over,
         * so a request from an assistive technology costs the application the same single call a
         * right-click costs it. An empty or absent menu is the documented "not here", and the
         * boolean says so rather than reporting an operation that opened nothing.
         *
         * <p><b>Not {@code showForFocus}.</b> The keyboard route may anchor on whatever holds
         * focus because a {@code KeyEvent} reaches this region only by bubbling out of a focusable
         * widget inside it. A verb carries no such guarantee: it is addressed to <em>this</em>
         * node, and taking the focused widget instead would drop the menu at a corner of something
         * unrelated and — worse — hand {@code PopupMenu} that widget as its host, which is what
         * the walk turns into the popup's {@code POPUP_FOR} and the opener's
         * {@code CONTROLLER_FOR}. Anchoring on this widget puts that pair on the very node that
         * declared {@link Accessible.State#HAS_POPUP}, so a reader can get from the group to the
         * menu it just opened. The corner is {@code showForFocus}'s own rule applied to this node:
         * the bottom left reading left to right, the bottom right reading right to left, in this
         * widget's own coordinates, which is what {@link #showAt} documents its point to be.
         *
         * <p>No enabled check of its own. The node acted on is this widget, so the scene's gate
         * has already walked it and every ancestor for enabled, checked that it is showing, that
         * the window is not modal-blocked and that it is inside the layer that owns input.
         *
         * @param action what is being asked
         * @param arg    unread; this verb takes none
         * @return whether a menu was opened
         */
        @Override
        protected boolean onAccessibilityAction(Accessible.Action action,
                                                Accessible.Argument arg) {
            if (action != Accessible.Action.SHOW_MENU) {
                return false;
            }
            Menu menu = source.get();
            if (menu == null || menu.items().isEmpty()) {
                return false;
            }
            boolean rtl = isRightToLeft();
            showAt(this, menu, rtl ? width() : 0, height());
            return true;
        }
    }
}
