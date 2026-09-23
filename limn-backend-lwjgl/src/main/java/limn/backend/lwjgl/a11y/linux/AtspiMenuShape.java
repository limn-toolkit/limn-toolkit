package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;

/**
 * The {@code MENU} shape's half of the AT-SPI bridge: the one place this bridge diverges from
 * the model on purpose, the first declared exception to that rule, kept here under the shape's
 * name rather than in the three places it was read.
 *
 * <p>The model goes on publishing {@code EXPANDABLE} for every node that carries an expand facet,
 * because that is a fact about the widget. What this bridge does not put on the bus, for a menu row
 * alone, is that axis — {@code expandable}, {@code expanded}, and the {@code collapsed}
 * {@link AtspiStates#setOf} derives from the pair — because a native GTK 3 menu title carries none
 * of the three, open or closed, and Orca is built for what that desktop ships (read on the
 * Fedora 44 guest, {@code readings/phase5-fedora/native-gtk3-menu-states.txt}). What says a menu is
 * open here is what says it there: {@code selected} on the title, which this toolkit already
 * publishes from the same bar's selection facet. The same exception, on the action list: a native
 * GTK 3 menu node publishes {@code actions=['click']} and nothing else, so a title that offered an
 * expand verb would hand an Orca user something no menu on that desktop has. {@code EXPAND} is the
 * one that costs nothing to drop — the widget calls it a synonym of {@code SHOW_MENU} and publishes
 * both on a closed title, so the route stays open under the name a menu really uses.
 * {@code COLLAPSE} stays, deliberately: an open title publishes it alone, so removing it would
 * leave a menu a Linux reader can open and cannot close, while a native title can always be clicked
 * shut. The remaining divergence is the verb's name, "show menu" and "collapse" against the native
 * "click", measured and left open beside the role. And an expand change on such a row is not
 * signalled: a client told "expanded 1" about an object whose state set never says expandable would
 * hold a bit it can never see cleared; what a reader hears instead is the selected flip on the open
 * title and showing on its children, which is what a native GTK 3 menu sends and travels through
 * the ordinary paths.
 *
 * <p>The row is found by the role family ({@link AtspiRoles#isMenuRow}), which is what
 * {@code Shape.MENU} is decided by too: a combo box, a tree row and a calendar's
 * title are not menus and keep the axis. A menu bar and a menu are of the shape but are not
 * rows, and the exception does not touch them.
 */
final class AtspiMenuShape {

    private AtspiMenuShape() {
    }

    /**
     * @param node a node
     * @return whether the exception applies: the node is a row of a menu
     */
    static boolean isMenuRow(AccessibleNode node) {
        return AtspiRoles.isMenuRow(node.role());
    }

    /**
     * The state set of a menu row: the node's states with the expand axis left off.
     *
     * @param node a menu row
     * @return the AT-SPI state bits
     */
    static long states(AccessibleNode node) {
        return AtspiStates.setOf(s -> s != Accessible.State.EXPANDABLE
                && s != Accessible.State.EXPANDED && node.has(s));
    }

    /**
     * @param node a node
     * @param verb a verb the node accepts
     * @return whether the action list leaves the verb off: {@code EXPAND} on a menu row
     */
    static boolean dropsVerb(AccessibleNode node, Accessible.Action verb) {
        return verb == Accessible.Action.EXPAND && isMenuRow(node);
    }

    /**
     * @param node the node an expand change happened on
     * @return whether the change is not signalled at all, because the row's state set never
     *     carries the axis
     */
    static boolean silencesExpandChange(AccessibleNode node) {
        return isMenuRow(node);
    }
}
