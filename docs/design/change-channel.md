# The change channel

How a widget tells the world it changed, and why there are two ways of hearing it. ADR 040
decided the shape; this is what a contributor needs before adding a component or a seam.
The contract itself is Javadoc on `Widget#observeChanges`, `Widget#notifyChange` and
`Change`; nothing here repeats it.

## Two channels, one rule

Every widget has a **handler** and any number of **watchers**.

- The handler is the fluent `onX` slot (`onChange`, `onSelect`, `onAction`, …): the
  application's one response to *the user* operating the widget. It runs only for a change
  whose origin is `USER`.
- A watcher is registered with `observeChanges` and hears **every** change, with its origin:
  a value the caller wrote (`CODE`), a value the widget moved by itself (`ADJUSTMENT`), and
  the user's gestures (`USER`). A two-way binding, an inspector, a detail pane, a test and
  the accessibility bridge are watchers.

The rule that follows is the one every component obeys and the contract test pins: **a
public setter announces as `CODE` and reaches no handler; a gesture announces as `USER` and
reaches the handler after every watcher; the state already held announces nothing.** It is
why two controls bound to each other through their handlers cannot recurse at all, and why the
theme editor lost its `syncing` flag: a handler's write into another widget is a caller's
write.

## What a seam looks like

A component has one private seam per aspect, taking the origin, and every path into that
aspect goes through it:

```java
public Slider setValue(float value) {          // the caller's write
    Ui.checkUiThread();
    apply(value, Change.Origin.CODE);
    return this;
}

private void apply(float value, Change.Origin origin) {
    float next = clamp(value);
    if (next == this.value) {
        return;                                 // the state already held: nothing
    }
    this.value = next;
    invalidate();                               // marks first …
    notifyChange(Change.of(Change.Aspect.VALUE, origin)); // … the announcement last
}

@Override
protected void handleUserChange(Change.Aspect aspect) {
    if (aspect == Change.Aspect.VALUE && onChange != null) {
        onChange.accept(value);                 // the handler reads the accessor
        return;
    }
    super.handleUserChange(aspect);
}
```

The drag, the arrow key and the assistive technology's action all call `apply(…, USER)`.
The layout clamp, the first tab selected because a pane cannot have none, and a refresh that
dropped the selected row call it with `ADJUSTMENT`. Nothing else in the class names an
origin.

The handler slot is `Checks.handlerSlot`: one handler, `null` clears it, a second one
throws. Two parties that both want to hear a widget are both watchers.

## The traps

- **Announce last.** After the state, the layout marks, the focus move and the reveal: the
  announcement means "this call is finished", and a watcher may legally read layout or
  mutate the tree in response.
- **Never from a paint.** A paint is re-run when nothing changed, so an announcement from
  one reports a change nobody made; the base class throws rather than promises. A widget
  that polled inside `onPaint` (the media controls once did) polls from a tick instead.
- **A subclass that overrides `handleUserChange` chains `super`** for the aspects it does
  not recognise, or it has silently deleted its parent's handler. The contract test drives
  every subclass through its parent's gestures to catch one that does not.
- **The inherited setters are final.** `setEnabled`, `setVisible`, `setFocusable` and
  `setTooltip` announce from the base class. A component that used to override one to react
  watches itself instead, the way a radio button hears its own `ENABLED` to tell its group.
- **A compound announces every member before any handler runs.** A radio group's leaver and
  enterer are announced through `announceChange` and only then handed to `runHandler`; a
  seam that calls the announcing half owes the handler half for the same change.
- **A text edit's three numbers come from the model.** `TextEditModel` records the damage
  its one splice made and the widget hands it to `notifyTextEdit`, which builds a record only
  if something is watching. Two edits before a read compose to a covering range; that is the
  coarse answer every platform accepts and it cannot arise on the widgets' own paths.
- **The verb from code is a caller's verb.** `activate()` on a list, `setColor` on the
  colour button, `select()` on a radio button: each announces and reaches no handler. An application that
  wants its own code run calls its own code.
- **A late watcher is owed nothing.** No replay, no priming call: subscribe, then read.

## The scene and the axes

A scene has its own `observeChanges` and hears every widget in it after the widget's own
watchers; a widget outside any scene keeps its watchers and reaches no scene. A layout pass
that ran announces one `LAYOUT` as `ADJUSTMENT` at the root, and focus is announced loser
then gainer with the origin of the path that moved it, so Tab is `USER` and `requestFocus`
is `CODE`.

The scene's other observers (presses, window blur, window close) and the process-wide axes
(`Fonts`, `ControlSize`, `LayoutDirection`, `I18n`, `Theme`) share the same shape: a
registration hands back a `Subscription`, listeners run each inside their own `try` and a
throw is reported under `CrashPhase.OBSERVER` without stopping the others. `Theme` is the
one axis no scene subscribes to itself: `limn.scene` has never named a `limn.components`
type, a repaint was not worth being the first, and the caller that switched the palette
calls `invalidate()`.

## Adding a component

`NotificationContractTest` is enumerated from the component sources, so a new public
concrete widget with no row fails rather than being quietly absent. A row names the widget's
caller's write, the gesture that reaches the same aspect through the scene's input, and the
aspect both announce; the test then asserts the four obligations above, the paint silence,
and that `announceChange` and `runHandler` have no caller outside the base class and the
one compound. The quiet-frame tests under `AllocationProbe` hold because a `Change` is
interned: only a text edit allocates, one record per edit, and none when nobody watches.
