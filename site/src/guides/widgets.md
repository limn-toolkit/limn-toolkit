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

## Writing your own widget

When no combination of the existing ones will do, extend `Widget` and implement two
methods: `onMeasure` says how big you want to be given the constraints you are handed, and
`onPaint` draws you onto a `Canvas`.

```java
class Dot extends Widget {
    @Override
    protected Size onMeasure(Constraints constraints) {
        return constraints.constrain(16, 16);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        canvas.fillCircle(width() / 2, height() / 2, 8, Theme.current().primary);
    }
}
```

Input is the same shape: override `onMouseEvent` or `onKeyEvent` and call `consume()` on
the event when you have handled it.

:::caution[One rule for custom widgets]
Do not read a size, a font or a spacing token in your constructor. A widget has no parent
while it is being constructed, so the value you would capture is the process default, and
it will not follow the control size the widget is eventually placed under. Read those in
`onMeasure` or `onPaint`, where the answer is correct.
:::
