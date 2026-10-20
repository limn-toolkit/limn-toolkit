# ADR 076: A dialog asks before it is cancelled

- **Status:** Accepted and implemented, 2026-10-20. The owner decided that `Dialog` has a close
  request before 1.0 (decision 28 of the 2026-10-19 review); the shape below is this record's.
- **Date:** 2026-10-20
- **Scope:** the ways a user closes a `Dialog` without choosing anything, and how an application
  keeps it open, now or after asking something of its own.
- **New public API:** `Dialog.onCancel(Consumer<Dialog.CancelRequest>)`; `Dialog.CancelRequest`
  with `reason()`, `allow()`, `refuse()` and `isAnswered()`; `Dialog.CancelRequest.Reason`.
- **Compatibility:** additive. A dialog without a handler closes as it did. One change for every
  dialog in a window of its own: closing that window from the system (a decorated window's close
  button, Alt+F4, Cmd+W) now fades it out as the dialog's other answers do, where the window used to
  disappear at once.

---

## 1. Why

A form in a dialog (a customer, an order line) is closed with Cancel or Escape. When the user has
changed something, an application asks "Save changes?" first, over the form that is still open,
and the form closes only when that question is answered. A `Dialog` could not do this: every one
of its buttons, Escape, the scrim and a screen reader's dismiss resolved it on the spot, so the
question could only come after the form had gone. The Showcase showed the stack with a timer
instead, and the owner read it, rightly, as a dialog that asked by itself (D4 of the 2026-10-20
review).

A window has had this since ADR 048 (`NativeWindow.setCloseRequestHandler`), and a tab since
ADR 067 (`TabbedPane.onTabClose`). The dialog was the one surface a user closes that an
application could not stop.

## 2. Decision

```java
dialog.onCancel(request -> {
    if (!form.changed()) {
        request.allow();
        return;
    }
    new Dialog("Save changes?", "Your changes are lost if you don't save them.")
            .addButton("Don't save", "discard")
            .addButton("Cancel", "cancel")
            .addPrimaryButton("Save", "save")
            .setCancelResult("cancel")
            .show(scene)
            .thenAccept(answer -> {
                if ("cancel".equals(answer)) {
                    request.refuse();          // back to the form, as it was left
                } else {
                    request.allow();           // the form closes with its cancel result
                }
            });
});
```

### 2.1 What is asked, and what is not

The handler is asked whenever **the user** gives the dialog its cancel result (`setCancelResult`),
the answer that means "nothing chosen".
`CancelRequest.reason()` says which way:

| `Reason` | The user |
|---|---|
| `ESCAPE` | pressed Escape |
| `SCRIM` | clicked outside the card, in a dialog that `setDismissOnScrim(true)` |
| `BUTTON` | pressed a button whose result is the cancel result, by any means: a click, Space, Return on it, Return answered by the default button, a reader's press |
| `WINDOW_CLOSE` | closed the dialog's own window from the system: its close button in a decorated window (`WindowStyle.DECORATED`), Alt+F4, Cmd+W |
| `ASSISTIVE_TECHNOLOGY` | asked a screen reader to dismiss the dialog (the card's `CANCEL` verb) |

A 1.x minor release may add a reason.

**Which buttons count is decided by their result, at the moment they are pressed**: a button whose
result equals `setCancelResult`'s (`Objects.equals`, so two nulls are equal) cancels, whatever its
style and whenever the cancel result was set. Every other button is an answer and is never asked
about: the default button (Save, OK), any secondary one, and a **destructive** one ("Delete",
"Discard"), whose answer is the opposite of nothing chosen. A dialog that gives a destructive
button the cancel result is asked about it, as the rule says, and is a dialog that should not.

### 2.2 What is never asked

- **`Dialog.dismiss(result)`**, with any result, the cancel result included. It is the application's
  own close, and the handler is the application's: asking it would be asking itself. It is also
  what a handler that wants out uses.
- **The dialog's surface going away**: its owner window closing, the window it is drawn in dying,
  the OS destroying its own window. Nothing can be kept open there; the dialog resolves with the
  cancel result, as before.
- **The application ending.** A dialog in a window of its own lets its window close when the
  application ends (`CloseRequest.Reason.APPLICATION_END`): the question an application asks
  before ending belongs to its windows' handlers (ADR 048), which a modal form's owner has.

### 2.3 The request

As a window's and a tab's: **answered once, now or later**. `allow()` closes the dialog with the
cancel result, by the same path the user's gesture would have taken: the fade with motion,
straight there under less motion, `result()` completing as it always does. `refuse()` keeps it
open: nothing moves, the keyboard stays where it was, `result()` is not touched. A second answer
throws `IllegalStateException`, as `CloseRequest` and `TabCloseRequest` do.

**Keeping it is not answering it.** There is no `hold()`: a handler that returns without calling
either holds the request, and answers from whatever it opened. That is the idiom of both existing
requests, and a third method would add a third state with nothing that needs it.

### 2.4 The answers that are not asked

Rules for the time a request is unanswered:

1. **No second request.** A second Escape, a click on Cancel or a reader's dismiss asks nothing
   new, as a second click on a window's close button does not. A reader's `CANCEL` is then
   answered `false`: the dialog did not begin closing.
2. **The dialog is not frozen by the request.** If the handler opened nothing over it, its other
   buttons still answer it; a dialog opened over it freezes it, as any stacked dialog does.
3. **An answer that arrives another way settles the request.** If the dialog is answered by another
   button, dismissed by the application or loses its surface while a request is held, the request
   is over: `allow()` and `refuse()` then do nothing, `isAnswered()` turns true, and nothing throws,
   since the application answering a question it asked is not its mistake.
4. **The keyboard is not moved by the request.** A dialog opened over the form takes it, and gives
   it back to the form when it closes, which is what stacking already does.

### 2.5 A handler that throws

**Has refused.** The dialog stays open, and the exception reaches the crash handler as any
exception from application code on the UI thread does. That is `CloseRequestHandler`'s rule, and
the safe side for what the hook exists to protect: a handler that failed to decide must not lose
the user's changes. The user is not trapped: the dialog's other buttons still answer it, and the
next Escape asks again. A dialog whose only way out is its cancel result, with a handler that
always throws, cannot be closed by the user; that is a defect of the application, reported on
every attempt.

### 2.6 Both display modes, and less motion

The same in a window of its own and in the scene: the request is asked, synchronously, in the
input event, before anything fades, so less motion changes nothing about it. Only `WINDOW_CLOSE`
belongs to one mode, there being no close button on an overlay.

### 2.7 The slot

One handler (`onCancel(null)` clears it); a second one throws, as every `on…` slot does (ADR 040).
It can be set before or after `show`, and is read when the user cancels. UI thread only.

## 3. Alternatives

- **`onClose`, asked for every close.** It would put Save through the handler, which then has to
  sort the answers it means to guard from the ones it does not; and it reads, next to
  `Popover.onClose`, as a notification after the fact. Refused: the hook guards losing work, and
  only the cancel result loses it.
- **An explicit `hold()`.** See 2.3.
- **A predicate (`Predicate<Reason>`), answered on the spot.** It cannot ask: the whole point is a
  question over the open form, answered later.
- **Letting `dismiss` through the handler.** See 2.2.
- **A handler that throws allows.** Loses the user's changes on a bug in the code meant to keep
  them; refused for the reason 2.5 gives.
- **A destructive button through the handler too.** "Discard" is the user deciding to lose the
  changes; asking "Save changes?" after it asks the question twice.

## 4. What changes in this repository

- `Dialog`: the slot, the request, the five reasons, every cancel path through one private
  `cancel(Reason)`; a dialog's window of its own refuses the system's close and asks the dialog.
- The Showcase's Dialogs page: "Edit customer…" with a changed field asks "Save changes to Harbor
  Supply?" over the open form on Cancel or Escape; Don't save and Save close both, Cancel goes back
  to the form as it was left.
- The menus and dialogs guide gains the section; `DialogCancelRequestTest` holds the rules in both
  modes, `ShowcaseCustomerDialogTest` the Showcase.
