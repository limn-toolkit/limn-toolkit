# ADR 068: One anchored popup, and a tab list that closes tabs

- **Status:** Accepted and implemented, 2026-10-15; the amendments of section 4 built 2026-10-16.
  The owner chose a list of its own for the tabs, and the shared popup extracted and adopted by
  the three surfaces in one round.
- **Date:** 2026-10-15
- **Scope:** the surfaces that hang from a widget inside it: a combo box's list, a date picker's
  calendar, and the list of every tab a `TabbedPane` opens when its tabs do not fit.
- **New public API:** none. The shared popup is internal; the tab list follows the closing ADR 067
  §2 already gives tabs.
- **Compatibility:** no public signature changes. Two behaviours are made the same for both
  surfaces that had them differently (section 3).

---

## 1. Why

ADR 067 made tabs closable. The list of every tab, opened from the strip's chevron when the tabs
do not fit, was a menu, and a menu can only choose: a tab out of sight could be closed only after
being chosen. A menu row has no button of its own, and it should not have one: the menus of a
window's bar and of the tray are the platform's own on macOS, where nothing but text and a mark
goes in a row. And a list of tabs is not a menu: closing one should leave the list open and
showing the rest.

So the tab list becomes a list in a popup of its own, as a combo box's list is. The toolkit had
two such popups, the combo box's and the date picker's, each a full copy of the same work: a
window of its own placed against its owner where the platform allows, drawn in the scene where it
does not (Wayland, a fullscreen owner), faded in and out, closed when the application loses the
keyboard or a press lands elsewhere, grafted into the owner's accessible tree. A third copy was
refused; the owner chose to extract the shared part and move both existing popups onto it now.

## 2. Decision

**`limn.components.internal.AnchoredPopup`** holds what the three share, and a host, the
component, answers what is its own:

| The popup does | The host says |
|---|---|
| choose a window of its own or the scene, by the request and `FloatingSurfaces` | the widget it hangs from, and the opener its content inherits from and a reader is told |
| create the window on a later turn, place it below or above, sized by the host | its size, given the room below and above |
| frame it for a shadow where the compositor casts none, graft its scene, fade it in | a fresh panel for each presentation |
| push the overlay in scene, place it the same way, fade it | the keys and characters that reach the layer or the window |
| close on the application losing the keyboard, a press elsewhere, the owner's fullscreen changing | whether a press is inside it, and its own close with the origin |
| tear down: withdraw the verbs, fade, destroy, give the keyboard back | what the layer is to a reader; what to do once the surface is gone |

`PopupMenu` stays as it is. A menu cascades, opens modal or not, and is the platform's own in the
bar and the tray; none of that is a popup hanging from a field.

## 3. What becomes the same for both

- **Right to left, a window lines up with the leading edge** of what it hangs from, the right
  one, as the overlay already did. The calendar's window was hung from the left edge in both
  directions. (Corrected 2026-10-16: this point said "trailing"; the code lines a list and a
  calendar up with the anchor's leading edge in both directions, as `AnchoredPopup` says, and only
  the tab list, whose button ends the strip, lines up with its trailing edge.)
- **In the scene, the keyboard goes back as the fade starts**, and not when it ends. The calendar
  already did this; the combo box's list left the focus published on a layer about to go for the
  length of its fade. (Noted 2026-10-16, after the first hearing; the decision stands.) It puts the
  focus coming back and the combo's own collapse and new item in one publish, and Orca 50.3 handles
  a batch's focus before its state and property changes, so it read the combo once for the focus
  and three more times for the changes, where the jar of 2026-10-12, whose focus came back at the
  fade's end, was read once. The Linux bridge now sends a node's name, description, value and open
  state as its cache item, not as events, in the publish the focus comes to it, no longer says
  that focus a second time as the cursor the list had, and holds a publish's signals until its frame
  ends, since a reader that handled the first half of the list opening alone read "expanded" on the
  combo before the list.
- **The overlay is placed by the same rule as the window:** below unless it does not fit and above
  has more room, eight points from the edge, clamped into the scene.

Everything else stays the host's: the combo box announces it closed before the teardown and the
calendar after, the combo box keeps its field's caret over its list, the calendar hands the grid
back to the picker when the card is gone.

## 4. The tab list

- A row per tab, in the strip's order: a mark on the selected one, the tab's icon when it has one,
  the caption, and on a closable tab a close button at the row's end, shown on the row under the
  pointer and on the cursor's. When any tab has an icon the list keeps a column for it, so the
  captions line up (amended 2026-10-16 at the owner's request: a row without the icon the strip
  shows was harder to find).
- **Opened from the keyboard** with Alt+Down on the focused tab, the chord that opens a combo
  box's list and a calendar, while the strip overflows; the list button stays out of the tab
  order, so the strip remains one stop (added 2026-10-16, the owner's choice, when the readers'
  script found the list unreachable without a pointer).
- **Keys:** Up and Down move the cursor, Home and End go to the ends, Enter selects the tab under
  the cursor and closes the list, Delete or Ctrl+W (Cmd+W on macOS) closes that tab, Escape closes
  the list. A typed letter moves to the next tab whose caption starts with it.
- **Closing a tab from the list asks the pane's close handler** as every other close does, and the
  list stays open, without the closed row; with no tab left it closes.
- **A reader** hears a list named as its button, "All tabs", whose row under the cursor is the
  selected one, as in a combo box's list and a menu, the tab the pane shows described as
  "Current tab", and on a closable one a button named as the strip's is, "Close tab". (Amended
  2026-10-16 at the owner's word, after the first hearing: with the shown tab as the selected
  row, Orca read "not selected" on every row walked, and VoiceOver heard no list at all, its
  node having no name.)

## 5. Tests

The combo box's and the date picker's suites stand as they are, the window, the scene, Wayland,
fullscreen, the fades and the accessible tree; a change of behaviour in section 3 is a test that
changes with it, said where it changes. The tab list is tested as the other surfaces are. (Amended
2026-10-16: its button runs the popup-owner contract of ADR 045; the list itself does not run the
rows contract, which reads one selection per tree while the strip's tabs are a second, and is held
by its own tests instead.) The
combo box and the calendar are heard again by the lab's screen readers, since what moved is the
code every reading of them goes through, and the tab list is heard for the first time.
