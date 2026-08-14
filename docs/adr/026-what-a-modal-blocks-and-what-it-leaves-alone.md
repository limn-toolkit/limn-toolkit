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

The remaining six questions are smaller and remain open; they are in
`artifacts/modal-stack-2026-08/parecer.md`.
