# ADR 026: What a modal blocks, and what it leaves alone

- **Status:** Accepted, 2026-08-11. Implemented in `NativeWindow.PopupKind`, `ChildPopups`,
  `ModalStack`, `LwjglBackend` and `Dialog`.
- **Date:** 2026-08-11
- **Scope:** four questions the modal stack could not answer about itself, the answers, and what
  each one changed.
- **Audience:** whoever maintains modality, and anyone writing a component that opens a window.

---

## 0. Why these were open

A review of the modal stack in 2026-08 produced ten questions of *intent*, places where the code
does something definite and nothing said whether it was meant. They blocked the work behind them,
because a contract cannot be pinned to behaviour nobody has claimed. Four decided the shape;
these are those four.

## 1. A palette under an in-scene modal: accident, and now fixed

An in-scene modal exempts its host window, so the host can keep drawing its own dropdowns and
menus, which are separate OS windows. That exemption reached **everything the host owned**, and
a non-modal dialog is owned by its host for lifetime and movement. So a floating palette stayed
clickable underneath a modal, where a native modal would have frozen it.

**Decided: an accident.** `NativeWindow.PopupKind` now distinguishes `TRANSIENT` (the owner's own
content reaching outside its frame) from `OWNED_WINDOW`, a window in its own right. The exemption
covers only the first. A dropdown stays usable; a palette is blocked like any other window, and
the two display modes now agree.

**Consequence for components:** do not depend on a `showNonModal` dialog staying live under a
modal. It does not any more.

## 2. Re-pushing a window already on the stack: throws

`ModalStack.push` removed any existing entry for the window and added a new one, which moved it to
the top **and adopted the new parent and owner-exception**, silently. The caller believed it had
pushed a second modal; the stack held one; popping either released the only entry.

**Decided: `IllegalStateException`.** A programming error, the way an out-of-range index is. No
shipped caller relied on move-to-top, and the alternative (ignoring the second push) would have
left a caller that meant to re-raise a modal with no way to find out it had not.

## 3. Ownership is one level deep: a decision, now asserted

Locking and exemption ask whether a window is *directly* owned. Popup movement, meanwhile,
propagates down a whole chain, because moving a popup fires its own position callback. The two
therefore disagree about a popup of a popup.

**Decided: intentional.** A menu keeps an entire cascade inside one window, so the shape that
would expose the difference, a component spending one window per cascade level, does not exist.
`ModalStackTest` now asserts non-transitivity instead of leaving it as folklore, so the first
component to try it finds out in the build.

## 4. A promoted dialog is answerable: a getter

An `IN_SCENE` dialog raised over an already-locked window is presented as a native one, because an
overlay can only come forward by raising its host, which would hide the dialog already there. The
promotion was announced in a `WARNING` log line and nowhere else, while the application may have
positioned or styled the card believing it was an overlay.

**Decided: a getter, not a callback.** `Dialog.displayMode()` answers what actually happened. A
callback was rejected: it adds an event ordering to define against `show`, for a case where
anything that cares can ask immediately afterwards.

## 5. What this is not

It is **not** the "Sealed Borders" refactor those questions were blocking. That is still open:
`ModalHandle` in place of push/pop, `OwnedWindowLease` unifying the closes of `Dialog`,
`PopupMenu` and `ComboBox`, `FloatingBand` and `ModalFeedback` as testable seams, a property
test, and the `ownerException` → `hostSurface` rename. What is done here is the part that had to
be decided before any of that could be pinned, and two of the four were behaviour changes worth
having on their own.

The remaining six questions are smaller and remain open. The note that listed them,
`artifacts/modal-stack-2026-08/parecer.md`, is not in this repository and no copy of it survives
here, so the list has to be retaken before it can be worked through: what is recorded is that six
remain, not which six.

## Note, 2026-10-20: a locked window's dim darkens only what the window shows

A window a native modal locks is dimmed by its own scene, a black veil at half alpha over the
frame. It was painted over the frame, so where the window shows nothing it put half-opaque black: a
dialog's own window is transparent round its rounded card, and a dialog with a second one open over
it showed a dark square at each corner of its card, outside the rounded edge (the Showcase's
customer form with its question over it, seen on macOS). The veil is now multiplied in
(`BlendMode.MULTIPLY`): every pixel darkens by the veil's share and keeps its coverage, so the
corners stay clear, and on an opaque window the pixels are the ones painting over gave.
`BlockedWindowVeilPixelTest` holds it on a whole frame and on a partial pass, on the GPU and on a
software renderer.

## Note, 2026-10-20 (later): a translucent locked window dims the desktop through it

Multiplying kept the corners clear, and it also left a translucent pixel's coverage alone: a
locked window that is see-through (an undecorated translucent window with a glassy background)
darkened only what it drew itself, and the desktop seen through it not at all. Measured over a
mid-grey desktop on a page of controls, with the veil at one half: a background at 0.6 alpha dimmed
the composite by 96.2 painted over and 73.9 multiplied in the light theme, 32.1 and 9.8 in the dark
one, where the dim almost disappeared (R6G-3 of the dialogs review). The owner decided (decision
36) that the veil keeps clear pixels clear, leaves opaque ones exactly as they were, and dims partly
covered ones. It is laid in a blend of its own, which the GL canvas offers to the scene internally
and no `BlendMode` names: colour `C(1 - a)` for the black veil, coverage `A(1 + a)` clamped to 1
(alpha factors `DST_ALPHA, ONE`). A clear pixel stays at 0, an opaque one at 1 with the
colour painting over gave, and coverage 0.6 becomes 0.9, so the desktop through it is dimmed again,
somewhat more than painting over dimmed it (0.1 of it shows where 0.2 did; a pixel already at
`1 / (1 + a)`, two thirds at the half veil, becomes opaque). Measured on the same page: at 0.6 the
composite dims by 107.8 light and 43.8 dark (96.2 and 32.1 painted over); an opaque window is byte
for byte what painting over gave, on the GPU and on the software renderer; a clear window's content
alone dims by 10.7 and 4.6, its clear pixels staying clear. A canvas without that blend multiplies
the locked window's veil, as before, and paints the dialog's over. `BlockedWindowVeilPixelTest` holds it light and dark, opaque, translucent and clear, on a
whole frame and on five partial passes in a row that touch nothing outside themselves, on the GPU
and on Apple's software renderer.

The in-scene dialog's own veil, the palette's scrim over the scene it opens in, is laid the same
way (R6G-5): a dialog in a window of its own with a question opened in its scene over one of its
fields put the same dark squares at that window's corners. The scrim is a palette tone an
application may colour, so the blend's colour factors are `DST_ALPHA, ONE_MINUS_SRC_ALPHA`: colour
`S·a·A + C(1 - a)` for a veil of colour `S`, which is `C(1 - a)` for black and, on an opaque pixel,
exactly painting over. `InSceneDialogVeilPixelTest` holds the corners clear, light and dark, opaque
and glass, on the GPU and on the software renderer.
