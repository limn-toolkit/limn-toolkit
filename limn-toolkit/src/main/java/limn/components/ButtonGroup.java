package limn.components;

import limn.concurrent.Ui;
import limn.internal.lang.Checks;
import limn.scene.Change;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Coordinates a set of {@link RadioButton}s so that exactly one is selected at a
 * time. Selecting a member deselects the previous one; when the <em>user</em> did the selecting
 * each radio's own {@code onChange} fires for both the leaving and the entering member and then
 * the group's {@link #onSelect} fires with the new index, and when code did it none of the three
 * handlers runs -- the two members announce their {@code VALUE} to their watchers either way.
 *
 * <p><b>A group is not a widget, so it announces nothing of its own.</b> It has no box, no
 * parent and no scene, so there is no node a watcher could attach its change to; the change
 * reaches the channel entire through the two members, which are widgets: the leaver's
 * {@code VALUE} and then the enterer's, both carrying the origin of whatever entered the swap,
 * and only then either member's handler. The whole swap settles before the first announcement,
 * because a half-swapped group is unreadable.
 *
 * <p><b>A group is one tab stop, not one per radio.</b> Only the selected member (or the first
 * enabled one, before anything is selected) can take focus, and the arrow keys move the
 * selection and the focus together, wrapping at the ends and stepping over disabled members. That
 * is the Windows and GTK convention, it is what macOS Full Keyboard Access does inside a group,
 * and it is the pattern {@code TabbedPane} already uses for its headers. Without it a settings
 * form of six groups of four options costs twenty-four tab stops instead of six.
 */
public final class ButtonGroup {

    private final List<RadioButton> members = new ArrayList<>();
    private IntConsumer onSelect;
    private RadioButton current;

    /**
     * Adds a radio to the group. If it is already selected, it becomes the group's selection.
     *
     * <p>Membership is what a screen reader speaks as "2 of 4", and adding a member changes that
     * number for every member while painting nothing: the roving-focus pass only invalidates the
     * tree when a focusable flag actually flips, and the first member's does not flip at all. So
     * every member tells the tree itself, which costs nothing for a member not yet in a scene and
     * is not per-frame work.
     */
    public ButtonGroup add(RadioButton radio) {
        Ui.checkUiThread();
        Objects.requireNonNull(radio, "radio");
        members.add(radio);
        radio.attachToGroup(this);
        if (radio.isSelected()) {
            // Keep a single selection invariant even if two pre-selected radios are added.
            if (current != null && current != radio) {
                current.setSelectedSilently(false);
            }
            current = radio;
        }
        applyRovingFocus();
        for (RadioButton member : members) {
            member.invalidateAccessible();
        }
        return this;
    }

    /**
     * The application's response to the user choosing a member: called with its index after a
     * click, an arrow key or an assistive technology's select, once both members' own handlers
     * have run. Never for {@link #setSelectedIndex}, {@link #clearSelection()} or a member's
     * {@link RadioButton#select()}, which are a caller's writes; to hear every change whatever
     * caused it, {@linkplain RadioButton#observeChanges watch} the members.
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this group
     * @throws IllegalStateException if a handler is already registered
     */
    public ButtonGroup onSelect(IntConsumer listener) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, listener, "ButtonGroup.onSelect");
        return this;
    }

    /**
     * @return the index of the selected radio, or -1 when none. A group is one of the two widgets
     *         in this set that genuinely has no-selection as a state: it starts there, and
     *         {@link #clearSelection()} goes back
     */
    public int selectedIndex() {
        return current == null ? -1 : members.indexOf(current);
    }

    /** @return the selected radio, or null when none. */
    public RadioButton selected() {
        return current;
    }

    /** @return an immutable view of the members, in the order they were added */
    public List<RadioButton> members() {
        return List.copyOf(members);
    }

    /**
     * Selects the member at {@code index}: a caller's write, so both members announce
     * {@code VALUE}/{@code CODE} and no handler runs. Selecting the member that is already
     * selected changes nothing and announces nothing. UI thread only.
     *
     * @param index a member in {@code [0, memberCount)}, in the order they were added
     * @throws IndexOutOfBoundsException if {@code index} is not a member; a group with no members
     *         has none, so every index throws there. {@code -1} is not the way to empty the
     *         group; {@link #clearSelection()} is.
     */
    public ButtonGroup setSelectedIndex(int index) {
        Ui.checkUiThread();
        Objects.checkIndex(index, members.size());
        members.get(index).select(Change.Origin.CODE);
        return this;
    }

    /**
     * Puts the group back in the state it had before anything was selected:
     * {@link #selectedIndex()} reports {@code -1} and the leaving member announces
     * {@code VALUE}/{@code CODE}; no handler runs, because a caller emptied the group. No-op when
     * nothing is selected. UI thread only.
     *
     * <p>A radio group is one choice out of many and offers no way to un-choose from the keyboard
     * or the mouse: this is the reset a form needs, and the only route back.
     */
    public ButtonGroup clearSelection() {
        Ui.checkUiThread();
        if (current == null) {
            return this;
        }
        RadioButton previous = current;
        current = null;
        previous.setSelectedSilently(false);
        applyRovingFocus();
        previous.announceSelection(Change.Origin.CODE);
        return this;
    }

    /**
     * A member's position, for the accessible tree: {@link #members()} answers the same question
     * with a copy per call, and a radio describes itself on every frame that damages it.
     *
     * @param radio a member
     * @return its zero-based index in the order the members were added, or {@code -1} when it is
     *         not a member
     */
    int indexOf(RadioButton radio) {
        return members.indexOf(radio);
    }

    /** @return how many members the group holds; the size of the set a radio reports itself in */
    int size() {
        return members.size();
    }

    // Called by a member watching its own ENABLED: the holder is chosen among the enabled
    // members, so a flag moving on any member can move it, and the group hears about that from
    // nowhere else.
    void memberEnabledChanged(RadioButton member) {
        applyRovingFocus();
    }

    /**
     * Called by {@link RadioButton#select(Change.Origin)}: the whole swap, in the one order that
     * is readable. Both flags, {@code current}, the roving focus and the focus that follows a
     * focused leaver settle first; then the leaver announces its {@code VALUE} and the enterer
     * announces its own; then, and only for {@code USER}, the leaver's handler, the enterer's
     * handler and this group's {@link #onSelect}, in that order. The two member handlers need no
     * origin test, because the handler half is a no-op at any other origin; the group's own slot
     * needs one, because it is a plain field and no base class holds the rule for it.
     */
    void select(RadioButton radio, Change.Origin origin) {
        if (current == radio) {
            return;
        }
        RadioButton previous = current;
        current = radio;
        if (previous != null) {
            previous.setSelectedSilently(false);
        }
        radio.setSelectedSilently(true);
        applyRovingFocus();
        // Focus follows the selection out of a focused member, and only then: a group selected
        // from code while the user is typing somewhere else must not steal the caret.
        if (previous != null && previous.isFocused()) {
            radio.focusFrom(origin);
        }
        if (previous != null) {
            previous.announceSelection(origin);
        }
        radio.announceSelection(origin);
        if (previous != null) {
            previous.runSelectionHandler(origin);
        }
        radio.runSelectionHandler(origin);
        if (origin == Change.Origin.USER && onSelect != null) {
            onSelect.accept(members.indexOf(radio));
        }
    }

    /**
     * Moves the selection {@code step} members from {@code from}, skipping disabled ones and
     * wrapping. Called by a member's arrow keys; selecting moves the focus with it.
     */
    void moveSelection(RadioButton from, int step) {
        int start = members.indexOf(from);
        if (start < 0 || members.isEmpty()) {
            return;
        }
        for (int hop = 1; hop <= members.size(); hop++) {
            RadioButton candidate = members.get(
                    Math.floorMod(start + step * hop, members.size()));
            if (candidate.isEnabled()) {
                // Focus before select: the swap only moves focus when the OLD member had it, and
                // a group arrowed into from a click on a label may have none of it yet. Both are
                // the user's: an arrow key is the gesture a radio group exists for, and reading
                // the public methods here would label it code.
                candidate.setFocusable(true);
                candidate.focusFrom(Change.Origin.USER);
                candidate.select(Change.Origin.USER);
                return;
            }
        }
    }

    /**
     * One tab stop: the selected member holds it while it is enabled, or the first enabled member
     * otherwise. The new holder is made focusable before the old one loses it, because
     * {@code setFocusable(false)} does not move focus away and the order stops a group from
     * briefly having no focusable member at all.
     *
     * <p>A selected member that has since been disabled is passed over exactly as it would have
     * been when joining: the selection stays where it is, only the tab stop moves, and it moves
     * back the moment the member is enabled again. Nothing here requests focus; a holder that was
     * focused when it was disabled had its focus revoked by {@code setEnabled} itself, and the
     * next Tab reaches the group through its new holder.
     */
    private void applyRovingFocus() {
        RadioButton holder = current != null && current.isEnabled() ? current : null;
        if (holder == null) {
            for (RadioButton member : members) {
                if (member.isEnabled()) {
                    holder = member;
                    break;
                }
            }
        }
        if (holder != null) {
            holder.setFocusable(true);
        }
        for (RadioButton member : members) {
            if (member != holder) {
                member.setFocusable(false);
            }
        }
    }
}
