---
title: "Animations"
description: "Moving things in a Limn window: a value that eases to where it is sent, a colour that does, a pulse that repeats, a ticker for motion of your own, pausing and slowing, what a user who asked for less motion gets, and how to test any of it."
---

Nothing in a Limn window moves by a timer of your own. A widget holds the number it wants to
animate in a `Transition`, says where that number should go, and reads it back when it paints;
the scene draws the frames in between and stops drawing them when the number has arrived. A
window with nothing moving renders nothing.

The components animate themselves this way already: a button's hover, a focus ring, a tab's
indicator, a list's scroll bar. This page is for a widget of your own, and for motion that is
not a move from one value to another.

## A value that eases

A `Transition` is one animated number. Configure it once, send it somewhere with `to`, and read
`value()` in `onPaint`:

{% snippet guide:animation-transition %}

`to` only says where the number is going. While it is on its way the transition asks the scene
for frames and repaints its widget on each; when it arrives it stops asking. Sent somewhere else
on the way, it goes on from where it is, so a lamp switched off in the middle of switching on
does not jump.

`duration` is in seconds and `easing` is the curve: `EASE_OUT`, the default, starts fast and
settles; `EASE_IN`, `EASE_IN_OUT` and `LINEAR` are the other plain ones, and `RUBBER` and
`BOUNCE` overshoot or bounce at the end. An `Easing` is a function from 0..1 to 0..1, so a curve
of your own is a lambda. `snap(value)` puts the number somewhere at once, with no animation.

A widget that is in no scene has no frames to animate over, so there `to` jumps. That is why a
state set before a widget is shown is simply there when it appears.

## A colour

`ColorTransition` is the same thing for a `Color`, blending from the colour it is at to the one
it is sent to:

{% snippet guide:animation-color %}

The durations the components use are on the theme, so that motion of your own keeps their pace:
`animHover` for a hover, `animFocus` for a focus ring, `animFade` for something appearing or a
value settling, `animTab` for an indicator sliding, `animExpand` for a section opening or
closing, `animWindow` for a popup or a dialog, and `animEasing` for the curve.

## Something that repeats

With `repeat(true)` a transition goes to its target and back for as long as it is left to, which
is a pulse or a breath:

{% snippet guide:animation-repeat %}

A repeating transition asks for a frame on every refresh while it runs. Stop it when what it
stands for is over; inside a hidden container it stops by itself and starts again when it is
painted.

## Motion of your own: a ticker

A transition goes from one value to another. For anything else, something that turns, falls,
follows the pointer or runs a simulation, register a ticker with the scene. It is called once a
frame with the seconds that went by since the last one, and it goes on being called for as long
as it answers `true`:

{% snippet guide:animation-ticker %}

Three things in that example are the contract. The ticker changes the number and then calls
`invalidate()`, because a ticker repaints nothing by itself. It answers `false` when there is
nothing left to animate, because a ticker that answers `true` keeps the window rendering sixty
or more times a second for nothing. And the first call hands it zero seconds, and no call ever
hands it more than a quarter of a second: a window that was covered or a machine that stalled
does not make the hand leap when it comes back.

## What a frame of animation costs

An animation repaints its widget and nothing else, so keep the widget that owns the transition
as small as what moves: the indicator and not the whole strip. Where the part that moves is
drawn by a large widget itself, a row's highlight in a list, give the transition
`damages(() -> invalidate(x, y, width, height))` and each frame repaints that rectangle only.
[Performance](/docs/performance/) says how to see what a frame repainted.

An animation must not change a size. Animate what is painted, an offset, an opacity, a colour,
and leave `markNeedsLayout()` for when something really is a different size: a layout on every
frame measures the screen on every frame.

## Pausing and slowing

A scene has a clock of its own that the application can stop or slow:

{% snippet guide:animation-time %}

A ticker registered with `addTicker` runs on that clock. A `Transition` does not, unless it is
given `sceneTime(true)`: a hover or a focus ring is the window answering the user, and a window
that stops answering while the application paused its own motion looks hung. So content, the
thing the application is showing move, goes on scene time, and feedback stays on the wall clock.
`addRealTimeTicker` is the ticker for the second kind.

## A user who asked for less motion

Every system has a setting for less motion, and the toolkit reads it. While it is on, a
`Transition` and a `ColorTransition` jump to where they are sent, and menus, popups and dialogs
open and close without a fade. Nothing in your code changes for that.

Motion that a ticker drives is yours to reduce. Motion that only decorates should stop:
`Motion.reduced()` says whether to. Motion that says something, a spinner, a progress sweep, a
dial, should go on saying it without being smooth: `Motion.stepped(dt, held)`, used in the dial
above, moves it four times a second instead of on every frame. `Motion.duration(seconds)`
answers zero while motion is reduced, for a fade you time yourself.

## Testing an animation

A test moves the clock itself. Build the scene with one, `new Scene(root, clock)`, where the
clock is a supplier of nanoseconds the test holds, then advance it and draw a frame:

{% snippet guide:animation-test %}

With no clock given, a scene's animations run on the wall clock, and a test that waits for one
has to wait in real time. [Testing](/docs/testing/) has the rest: the headless window, the
driver, and how to hold a screen to asking for no frame once it has settled.
