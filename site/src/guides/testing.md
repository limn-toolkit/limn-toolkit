---
title: "Testing"
description: "Driving a screen from a test with no display: clicks, typing and keys through limn-test, and what to check afterwards."
---

A Limn screen can be tested the way a user uses it, with no window, no GPU and no fonts. The
`limn-test` module drives a scene through the same input a window would deliver: a click
lands where the layout put the widget, text arrives one character at a time, and Enter is a
key. What the test then checks is what a user would see.

{% tabs build Gradle Maven %}
```kotlin title="build.gradle.kts"
dependencies {
    testImplementation("io.github.limn-toolkit:limn-test:{{version}}")
}
```
```xml title="pom.xml"
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-test</artifactId>
  <version>{{version}}</version>
  <scope>test</scope>
</dependency>
```
{% endtabs %}

It depends on `limn-toolkit` and on nothing else. It brings no test framework, so it works
under JUnit, TestNG or a `main` method alike.

## A form, filled in by a test

The screen here is a name field, a Save button and a status line. The two tests type a name,
save it once with the button and once with Enter, and read the status line back.

{% snippet guide:testing %}

Three things make that run anywhere:

- **`HeadlessUi`** installs the UI runtime on the test's own thread. Widgets can only be touched
  from the UI thread, and here that thread is the test. Closing it, which the `try` does,
  uninstalls it so the next test starts clean.
- **`TestRulers.FIXED`** measures text without fonts. Every character is ten points wide, so a
  layout comes out the same on every machine.
- **`layoutPass`** gives the scene a size and places every widget. After it, a widget has a
  position, and that position is where `click` aims.

## What the driver does

`drive(scene)` returns a driver for that scene. Each of its gestures is one whole user action,
and they can be chained:

| Gesture | What the scene receives |
| --- | --- |
| `click(widget)`, `click(id)` or `click(x, y)` | the pointer moving there, the left button down and up |
| `type(text)` | each character as the platform delivers typed text |
| `press(key)` or `press(key, modifiers)` | the key down and up, with the `Keys.MOD_*` bits held |
| `moveTo(x, y)` | the pointer moving there, which is a hover |
| `scroll(dx, dy, x, y)` | a wheel or trackpad scroll over that point |

Each gesture ends its input batch, which is when the scene dispatches: handlers have run by
the time the call returns. For a sequence a gesture does not cover, the driver also takes the
raw calls a backend makes, such as `keyEvent`, `mouseButton` and `windowFocusChanged`. Those
queue until `inputBatchEnded()`, exactly as one frame's input does.

Keys that are not text go through `press`: Enter, Tab, the arrows, Escape. Text goes through
`type`.

A key a widget acts on ends the wheel stream still arriving, so a `scroll` within 150 ms of the
previous one, after such a key, is dropped as the tail of that gesture. A test that scrolls
after a key builds its scene with a clock it advances, `new Scene(root, clock)`, and moves it
past 150 ms first.

A test that cannot reach a widget, because the screen builds it where the test cannot see, can
name it instead. The screen calls `button.setId("save")` and the test calls
`drive(scene).click("save")`. To check a widget afterwards, `scene.find("save")` returns it,
and `scene.find("save", Button.class)` returns it already typed. Nobody sees an id, and a screen
reader does not read one; what it reads is the accessible name.

The same id is what a tool that drives the running application from outside finds the widget
by: it is published as the automation id on Windows, the accessibility identifier on macOS and
the accessible id on Linux. A widget given no id is published under a number that holds while
it stays in the scene.

## Dialogs, menus and popups

A dialog, a menu or a date picker's calendar may open as a window of its own. With no backend,
there is nowhere for it to open. `HeadlessBackend` supplies one: every window it creates is a
`HeadlessWindow` the test can reach, with `windows()` listing them in the order they opened
and `scene()` answering the scene bound to each. `frame()` on a window runs one frame, and
`key(...)` presses a key in that window.

A frame is a turn of the loop, as on a desktop: `frame()` first runs what is waiting on the UI
queue, then draws. That is where the toolkit finishes what it leaves for the next turn. A test that
pins less motion with `TestPreferences.reduceMotion(true)`, the way to make the toolkit's dialogs
and popups open and close without a fade, presses Escape and frames the windows, and the dialog's
result is there: it was completed on the turn after the key. `AccessibleHarness.frame()` and each
step of its `advanceTime` take a turn the same way. A fade of your own that goes straight to its
target, with no window or under less motion, runs its `onArrive` after the seconds it asked for, on
the runtime's clock, which `new HeadlessUi(clock)` lets the test move.

## What a screen costs

A test can hold a screen to what it costs without a stopwatch, in counts that are the same on
every machine. `window.takeFrameRequests()` answers how many frames the scene asked a
`HeadlessWindow` for since it was last read: frame the window until that is zero, frame it some
more, and a screen nobody touches must still answer zero. `scene.metrics().paintedWidgets().last()`
is how many widgets the last frame painted, which for a list of a hundred thousand rows is the
rows on screen. `AllocationProbe.leastAllocatedBy(step, attempts)` is the bytes a step allocates.

```java
window.frame();
while (window.takeFrameRequests() > 0) {
    window.frame();
}
window.frame();
assertEquals(0, window.takeFrameRequests());
```

A transition runs on the wall clock unless the scene was built with a clock of its own, so a
screen that animates as it opens needs real time to come to rest.

## Accessibility

A widget of your own can be held to the same contracts the toolkit's widgets are. An
`AccessibleHarness` binds a widget to a scene whose accessibility bridge records every tree it
is handed. `tree()` and `node(name)` read what a screen reader would be told, and `perform`
invokes an action the way a platform does. The contracts in `limn.testing.a11y` check a whole
shape at once: a toggle, a text field, a list of rows, a grid, a menu.
