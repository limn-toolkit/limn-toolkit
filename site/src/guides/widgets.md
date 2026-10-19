---
title: "Widgets and scenes"
description: "The four ideas the rest of the guide is built on: widgets, scenes, the UI thread, and control size."
---

Four ideas carry the whole toolkit. Once these are in place, everything else in this guide
is detail.

## 1. Everything on screen is a widget

A `Button` is a widget. So is a `Column`, and so is the padding around it. There is no
separate category of "layout manager" or "container": a layout is simply a widget whose
job is to place its children.

```java
Column column = new Column();
column.gap(12);
column.add(new Label("Name"));
column.add(new TextField());
```

That means composition is the only structuring tool you need, and a "screen" is a method
that returns a widget. Extract a group of fields into a method and you have a reusable
component; there is no registration step.

## 2. A scene binds a widget tree to a window

`Scene` is what turns a tree into a running interface: it measures, lays out, dispatches
input and paints.

```java
Scene scene = new Scene(root);
scene.bind(window);
```

You mutate widgets normally after that (`label.setText(…)`, `column.add(…)`), and the
scene works out what has to be measured and repainted again. You never call a repaint
yourself.

## 3. There is exactly one UI thread

It is the thread that created the backend, and it is the only thread allowed to touch
widgets. Calling a widget setter from a worker thread throws, immediately and with a clear
message, rather than corrupting the frame three seconds later.

To get back onto it from anywhere:

```java
Ui.post(() -> status.setText("Done"));
```

Anything that takes real time, such as a file, a network call or a decode, must not run on this
thread, or the window stops responding for as long as it takes.
[Background work](/docs/background-work/) is the whole answer to that, and it is worth
reading before you write your first loading screen.

## 4. Density is a setting, not a size

Every component reads its metrics from a **control size**: one of `XSMALL`, `SMALL`,
`MEDIUM` (the default), `LARGE`, `XLARGE`. It is inherited down the tree, so setting it
once on a scene changes the padding, the type and the hit targets of everything in it:

```java
scene.setControlSize(ControlSize.SMALL);
```

Use it for a compact mode, a dense inspector panel, or a touch-friendly kiosk build. You
almost never set a pixel size on a component yourself; the step decides.

## Statuses, people and button styles

Three looks for a button, and a tone for what destroys something. The filled `PRIMARY` button is
the one action a view is for; `SECONDARY` is outlined, for the others beside it; `TERTIARY` has no
fill or border until the pointer is over it, for a quiet action that should not compete. Any of
them takes `setDestructive(true)` for a button that deletes or discards, which draws it in the
palette's danger colour. The box is the same at every style, so a row of them lines up.

{% snippet guide:button-styles %}

A `Badge` says a status in a few letters: a pill tinted in a `Tone`, a dot or an icon in the
tone at its leading end, and the word in the palette's text colour, so it reads whatever the
tone. `Badge.ofCount(n)` shows a number, "99+" past 99, in the digits of the language shown;
`Badge.dot(name)` is a dot alone, with a name a screen reader says in its place.

{% snippet guide:badge %}

An `Avatar` is a person as a circle: their picture with `setImage`, or their initials over a
colour of their own, both from the name. An `AvatarGroup` overlaps them and ends in "+k" past the
most it shows, with the hidden names in its tooltip. To a reader an avatar is an image named by
the person (take it out with `setAccessibleIgnored(true)` beside the name it repeats), and a
group is one group described by every name, the hidden ones included.

{% snippet guide:avatars %}

## Where null is allowed

Every public signature says whether it takes or answers `null`, in
[JSpecify](https://jspecify.dev/)'s annotations: the packages are `@NullMarked`, so a type
without a mark is never null, and `@Nullable` names the places that are. `setIcon(null)`
clears an icon because its parameter says `@Nullable Icon`; `widget.parent()` can be `null`
because its return type says so; `new Scene(null)` is a mistake the signature already
warned you about.

```java
@Nullable Widget<?> parent = widget.parent();   // null while it is not in a tree
button.setIcon(null);                            // allowed: the parameter is @Nullable
```

Nothing enforces this at run time, and nothing needs adding for the annotations to be there:
they come with the toolkit's dependency, and no code reads them once the program runs; a
modular application may even leave the jar off its module path. An IDE reads them and warns
at the call; Kotlin turns them into `Widget<*>?` and `Icon?`; a checker such as
[NullAway](https://github.com/uber/NullAway) turns them into build errors, for your own code
as well once you mark it `@NullMarked` too.

## Writing your own widget

When no combination of the existing ones will do, extend `Widget` and implement two
methods: `onMeasure` says how big you want to be given the constraints you are handed, and
`onPaint` draws you onto a `Canvas`. A widget names itself as its type argument, so that the
setters every widget has return your type.

```java
class Dot extends Widget<Dot> {
    @Override
    protected Size onMeasure(Constraints constraints) {
        return constraints.constrain(16, 16);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        canvas.fillCircle(width() / 2, height() / 2, 8, Theme.of(this).primary());
    }
}
```

Where a widget is only a type in your code — a field that holds any widget, a list of them —
write `Widget<?>`. [Your own widgets](/docs/custom-widgets/) takes a real one all the way:
input, what to mark when it changes, the two change channels, and what a screen reader is told.
