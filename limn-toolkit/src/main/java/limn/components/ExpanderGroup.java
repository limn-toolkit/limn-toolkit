package limn.components;

import limn.concurrent.Ui;
import limn.internal.lang.Checks;
import limn.scene.Change;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Keeps at most one of several {@link Expander}s open, an accordion: opening one closes the one
 * that was open. All of them may be closed; closing the open one leaves none open.
 *
 * <p>When the <em>user</em> opened one, the closing member and then the opening one announce
 * {@code EXPANDED}, then each runs its own {@code onChange}, and then the group's
 * {@link #onChange} runs with the open member's index. When code did it, both announce and no
 * handler runs. A group is not a widget: it has no box, no place in the tree and nothing to say
 * to a reader, which hears each section by itself.
 *
 * <p>Each header stays a tab stop of its own: a section is a button, and a form of five sections
 * is five things to reach, as on every platform.
 */
public final class ExpanderGroup {

    private final List<Expander> members = new ArrayList<>();
    private @Nullable Expander open;
    private @Nullable IntConsumer onChange;

    /**
     * Adds a section. If it is open it becomes the group's open member, and the one that was
     * open closes, saying so as an adjustment: the group closed it, not a caller.
     *
     * @param expander the section; one in another group leaves that one first
     * @return this group
     */
    public ExpanderGroup add(Expander expander) {
        Ui.checkUiThread();
        Objects.requireNonNull(expander, "expander");
        if (expander.group == this) {
            return this;
        }
        if (expander.group != null) {
            expander.group.remove(expander);
        }
        members.add(expander);
        expander.group = this;
        if (expander.isExpanded()) {
            Expander displaced = open;
            open = expander;
            if (displaced != null) {
                displaced.applyExpanded(false, Change.Origin.ADJUSTMENT);
                displaced.announceExpanded(Change.Origin.ADJUSTMENT);
            }
        }
        return this;
    }

    /**
     * Takes a section out of the group: it opens and closes by itself from then on, and stays as
     * it is. Nothing happens for one that is not a member.
     *
     * @param expander the section
     * @return this group
     */
    public ExpanderGroup remove(Expander expander) {
        Ui.checkUiThread();
        Objects.requireNonNull(expander, "expander");
        if (expander.group != this) {
            return this;
        }
        members.remove(expander);
        expander.group = null;
        if (open == expander) {
            open = null;
        }
        return this;
    }

    /** @return the members, in the order they were added */
    public List<Expander> members() {
        return List.copyOf(members);
    }

    /** @return the open member, or null when all are closed */
    public @Nullable Expander expanded() {
        return open;
    }

    /** @return the open member's index, or -1 when all are closed */
    public int expandedIndex() {
        return open == null ? -1 : members.indexOf(open);
    }

    /**
     * Opens the member at {@code index}, closing the one that was open: a caller's write, so both
     * announce {@code EXPANDED}/{@code CODE} and no handler runs.
     *
     * @param index a member in {@code [0, members().size())}
     * @return this group
     * @throws IndexOutOfBoundsException if {@code index} is not a member
     */
    public ExpanderGroup setExpandedIndex(int index) {
        Ui.checkUiThread();
        Objects.checkIndex(index, members.size());
        members.get(index).setExpanded(true, Change.Origin.CODE);
        return this;
    }

    /**
     * Closes the open member, if any: a caller's write, announced, with no handler.
     *
     * @return this group
     */
    public ExpanderGroup collapseAll() {
        Ui.checkUiThread();
        if (open != null) {
            open.setExpanded(false, Change.Origin.CODE);
        }
        return this;
    }

    /**
     * The application's response to the user opening or closing a member, with the index of the
     * one open afterwards, or -1 when the user closed the open one. Called after both members'
     * own handlers. Never for {@link #setExpandedIndex}, {@link #collapseAll()} or a member's
     * {@link Expander#setExpanded}.
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this group
     * @throws IllegalStateException if a handler is already registered
     */
    public ExpanderGroup onChange(@Nullable IntConsumer listener) {
        Ui.checkUiThread();
        this.onChange = Checks.handlerSlot(onChange, listener, "ExpanderGroup.onChange");
        return this;
    }

    /**
     * A member opened or closed: the whole change settles, then each announces, then the handlers
     * run, in the order a reader and an application can follow.
     */
    void toggled(Expander member, boolean value, Change.Origin origin) {
        Expander closing = null;
        if (value) {
            closing = open;
            open = member;
            if (closing != null) {
                closing.applyExpanded(false, origin);
            }
        } else if (open == member) {
            open = null;
        }
        member.applyExpanded(value, origin);
        if (closing != null) {
            closing.announceExpanded(origin);
        }
        member.announceExpanded(origin);
        if (closing != null) {
            closing.runExpandedHandler(origin);
        }
        member.runExpandedHandler(origin);
        if (origin == Change.Origin.USER && onChange != null) {
            onChange.accept(expandedIndex());
        }
    }
}
