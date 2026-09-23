---
title: "Your own widgets"
description: "Extending Widget: measuring and painting, what to mark when something changes, input, the two change channels, the UI thread, and what a screen reader is told."
---

The components are final, so a new kind of control is a class of your own that extends
`Widget`. Most screens never need one, because a composition of the existing components is
usually enough. This page builds one that could not be composed: a rating of zero to five dots.
It measures and paints itself, takes a click and the arrow keys, tells the rest of the
application what changed, and a screen reader reads it as a value. The code below is compiled
and tested with the toolkit, and the test also holds the widget to the contract every toolkit
value widget is held to.

## Measuring and painting

A widget names itself as its type argument, `class Rating extends Widget<Rating>`, so the
setters every widget has return your type: `new Rating().setTooltip("Stars")` is still a
`Rating`. Two methods make it visible:

{% snippet guide:custom-widget-draw %}

`onMeasure` is handed the constraints its parent allows and answers the size it wants within
them; `constrain` clamps to what was allowed. `onPaint` draws in the widget's own coordinates,
from `(0, 0)` to `width()` by `height()`.

Sizes, spacing and fonts come from the theme's tokens for the widget's control size, read in
`onMeasure` and `onPaint`. Do not read them in the constructor: a widget being constructed has
no parent yet, so the value it reads is the process default and never follows the control size
it is placed under. Colours come from the theme the same way, which is what makes the dots
follow a palette switch without a line of code.

## State, and who hears it

{% snippet guide:custom-widget-state %}

Four rules are in those few lines, and every toolkit component follows them:

- **A public mutator checks the UI thread first.** `Ui.checkUiThread()` throws on any other
  thread, so a write from a worker fails at the call instead of racing the frame.
- **A change that changes nothing says nothing.** Setting the value the widget already holds
  marks nothing and announces nothing.
- **The announcement comes last.** `notifyChange` runs after the state, the marks and anything
  else the change moved, because the watchers it reaches read the widget and must find it settled.
- **The origin decides who hears it.** Every change reaches the watchers registered with
  `observeChanges`. Only a change the user made, `Change.Origin.USER`, also reaches the
  handler, after the watchers. `setValue` is the application talking to itself, so it passes
  `CODE`, and a handler that writes the value back cannot loop.

`handleUserChange` is where the handler runs. Switch on the aspect, and hand any aspect you do
not own to `super`, because a widget you extend may have handlers of its own. A handler slot
holds one handler, and a second registration throws rather than silently replacing the first;
`null` clears it. Code that needs several listeners uses `observeChanges`.

## What to mark when something changes

The scene repaints and lays out only what was marked, so a change marks exactly what it
affected:

| What changed | Call |
| --- | --- |
| the picture, in the same box | `invalidate()` |
| a known part of the picture | `invalidate(x, y, width, height)`, in local coordinates |
| the size, and the parent should place everything again | `markNeedsLayout()` |
| the size, for a reason of the widget's own, such as text that changed width | `markNeedsLayoutInPlace()` |
| something a screen reader cares about that paints nothing | `invalidateAccessible()` |

`markNeedsLayoutInPlace()` re-measures the ancestors only as far as the first whose size did not
change, and repaints only the children that moved; `markNeedsLayout()` makes no such claim and
costs a full frame. A widget that paints outside its own box widens the damage with
`Scene.damage(rect)`.

Never change anything from `onPaint`. A paint runs again whenever the scene needs the pixels,
whether or not anything changed, so a change announced from one reports a change nobody made;
the base class throws instead. Work that has to happen later runs from `Ui.postDelayed`, and a
value that eases from one state to another from a `Transition`, never from a paint.

## Input

{% snippet guide:custom-widget-input %}

Mouse events arrive in the widget's own coordinates and bubble from the widget under the pointer
to its ancestors; key events go to the focused widget first and then to its ancestors. Call
`consume()` on an event you handled, and it goes no further. A widget takes the keyboard only if
it is `setFocusable(true)`, and taking focus on a press is what makes the arrows work after a
click.

A right-to-left subtree mirrors the reading direction, so the rating's first dot sits on the
right there, and Left and Right swap along with it. Up and Down do not swap, because they are a
direction of the value, not of the screen. `isRightToLeft()` answers for the widget's own
subtree.

## What a screen reader is told

{% snippet guide:custom-widget-a11y %}

`onAccessibility` describes the widget each time it is published: a role, and the facets that
carry its behaviour. The value facet publishes the number, its range and its step, and the platform
bridges turn that into what each platform's readers expect. The verbs it lists are what a reader
may ask for, and `onAccessibilityAction` performs them. A request from a reader is the user
acting, so it passes `USER` and the handler runs, exactly as for a key.

Several things are not the widget's to do. The scene adds focusing and scrolling into view to
every focusable node itself. It withdraws every verb while the widget or an ancestor is disabled,
hidden, or blocked by a modal. And the name comes from the application, through
`setAccessibleName` or a bound `Label`, as it does for every component. `onAccessibility` runs on
every publish, so it must not allocate: hand it the values you hold, not new objects.

Other shapes use other facets: a toggle, text, a selection, an expandable node, a plain action.
`Accessibility` in the [API reference](/api/) has a setter for each.

## Testing it

The example's test clicks the third dot and checks the value and the handler, writes the value
from code and checks that a watcher heard it and the handler did not, and presses the arrows in
both directions. It also runs `ValueContract` from `limn-test`: the same checks every value
widget in the toolkit is held to, covering the published number, the step verbs and their
bounds, a refused non-number, a disabled widget and a stable identity. `ToggleContract`,
`TextContract`, `RowsContract`, `GridContract`, `MenuContract`, `LeafActionContract` and
`PopupOwnerContract` do the same for the other shapes. [Testing](/docs/testing/) shows the
driver the checks run on.

## A layout of your own

A layout extends `Container`, which adds and removes children in public, and takes its own type
the same way: `class Grid extends Container<Grid>`. Its `onMeasure` measures each child with
`child.measure(constraints)` and answers its own size from theirs. Its `onLayout` then places each
child with `child.layoutBox(x, y, width, height)`, in the container's own coordinates. The
container's children are in `children()`.
