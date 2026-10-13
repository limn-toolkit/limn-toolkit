# ADR 063: A widget's id is what a tool outside finds it by

- **Status:** Accepted and implemented, 2026-10-12. Decision 162 had put it on the road map.
- **Date:** 2026-10-12
- **Scope:** what the three accessibility bridges publish as an element's identifier.
- **New public API:** `AccessibleNode.identifier()`.
- **Compatibility:** a widget given an id is no longer published under its node's number. A
  widget given none is published as before.

---

## 1. Decision

`Widget.setId` names a widget for the code that looks it up: `Scene.find`, and a test's driver.
The same id is now what a tool that drives the running application from outside finds the
widget by, through the identifier each system's accessibility already has a place for:

| System | Where the id is read |
|---|---|
| Windows | UI Automation's `AutomationId` |
| macOS | the element's accessibility identifier (`AXIdentifier`) |
| Linux | the `AccessibleId` property of AT-SPI's `Accessible` |

All three were already published, with the node's own number in them. `AccessibleNode.identifier()`
answers the widget's id where the application gave one and that number where it gave none, and
the three bridges publish what it answers. A change of id is a change of the tree, published as
any other.

Nobody hears an id: no screen reader speaks any of the three. An id is as unique as the
application made it, and a lookup by a tool answers the first match, as `Scene.find` does.

## 2. What is not done

- **No `id` object attribute on Linux.** The backlog named one. Read on the Ubuntu guest, no
  application of the desktop's own toolkits running there publishes an accessible id or an `id`
  attribute, so there was no convention to copy, and `AccessibleId` is the property the
  interface defines for it.
- **A row of a list, a table or a tree has no id of its own**: it is no widget. A tool finds
  the container by id and the row by its name or its place.

## 3. Seen on the guests

The Showcase gives three widgets an id: its orders table (`orders`), the search field over it
(`orders-search`) and the form's save button (`order-save`). On each guest a client of the
system's own interface looked them up, 2026-10-12:

| Guest | Client | Found |
|---|---|---|
| Windows | a UI Automation client, `FindFirst` by `AutomationId` | an edit, a data grid and a button; an id nobody gave, nothing |
| macOS | the system's scripting of the accessibility interface, reading `AXIdentifier` | a text field, a table and a button |
| Ubuntu, X11 | an AT-SPI client, reading the accessible id | an entry, a table and a push button |

## 4. Found along the way

On macOS the window still published a menu bar and its titles, with no box, while the bar was
shown in the system's: ADR 060 said it published none, and nothing held it to that. The
accessible walk now skips a widget that something outside the scene is showing, a test holds
it, and the guest shows the window without it.
