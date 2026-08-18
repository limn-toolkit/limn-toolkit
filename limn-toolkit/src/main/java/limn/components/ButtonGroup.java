package limn.components;

import limn.concurrent.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Coordinates a set of {@link RadioButton}s so that exactly one is selected at a
 * time. Selecting a member deselects the previous one (each radio's own
 * {@code onChange} fires for both the leaving and the entering member) and fires
 * the group's {@link #onSelect} with the new index.
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
    private Consumer<Integer> onSelect = index -> {
    };
    private RadioButton current;

    /** Adds a radio to the group. If it is already selected, it becomes the group's selection. */
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
        return this;
    }

    /**
     * Called with the index of the newly selected radio, or with {@code -1} when
     * {@link #clearSelection()} empties the group. A click, an arrow key and a
     * {@link #setSelectedIndex} from code all arrive here.
     */
    public ButtonGroup onSelect(Consumer<Integer> listener) {
        Ui.checkUiThread();
        this.onSelect = Objects.requireNonNull(listener, "listener");
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
     * Selects the member at {@code index} and fires {@link #onSelect}; code, a click and an arrow
     * key take the same path, so a listener sees every change either way. Selecting the member
     * that is already selected changes nothing and fires nothing; that early return is what keeps
     * two controls bound to each other from recursing, so do not remove it. UI thread only.
     *
     * @param index a member in {@code [0, memberCount)}, in the order they were added
     * @throws IndexOutOfBoundsException if {@code index} is not a member; a group with no members
     *         has none, so every index throws there. {@code -1} is not the way to empty the
     *         group; {@link #clearSelection()} is.
     */
    public ButtonGroup setSelectedIndex(int index) {
        Ui.checkUiThread();
        Objects.checkIndex(index, members.size());
        members.get(index).select();
        return this;
    }

    /**
     * Puts the group back in the state it had before anything was selected:
     * {@link #selectedIndex()} reports {@code -1}, the leaving member's own {@code onChange} fires
     * with {@code false}, and {@link #onSelect} fires with {@code -1}. No-op when nothing is
     * selected. UI thread only.
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
        previous.fireChange(false);
        applyRovingFocus();
        onSelect.accept(-1);
        return this;
    }

    // Called by RadioButton.select(): swap the selection and notify.
    void select(RadioButton radio) {
        if (current == radio) {
            return;
        }
        RadioButton previous = current;
        current = radio;
        if (previous != null) {
            previous.setSelectedSilently(false);
            previous.fireChange(false);
        }
        radio.setSelectedSilently(true);
        radio.fireChange(true);
        applyRovingFocus();
        // Focus follows the selection out of a focused member, and only then: a group selected
        // from code while the user is typing somewhere else must not steal the caret.
        if (previous != null && previous.isFocused()) {
            radio.requestFocus();
        }
        onSelect.accept(members.indexOf(radio));
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
                // requestFocus before select: select() only moves focus when the OLD member had
                // it, and a group arrowed into from a click on a label may have none of it yet.
                candidate.setFocusable(true);
                candidate.requestFocus();
                candidate.select();
                return;
            }
        }
    }

    /**
     * One tab stop: the selected member holds it, or the first enabled member before anything is
     * selected. The new holder is made focusable before the old one loses it, because
     * {@code setFocusable(false)} does not move focus away and the order stops a group from
     * briefly having no focusable member at all.
     */
    private void applyRovingFocus() {
        RadioButton holder = current;
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
