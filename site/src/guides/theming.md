---
title: "Theming"
description: "Palettes, density and how to make the toolkit look like your product rather than like the demo."
---

## Picking a palette

A theme is a palette plus one shape metric. Switching one is a single call, plus a relayout
so the sizes are re-measured:

```java
Theme.setCurrent(Theme.limnLight());
scene.root().markNeedsLayout();
```

Fifteen palettes ship with the toolkit. `Theme.limn()` and `Theme.limnLight()` are the
project's own dark and light pair (the ones every screenshot on this site is rendered in),
and `Theme.light()` and `Theme.dark()` are neutral. Among the rest are palettes in the
spirit of ones you will recognise: Darkling, Draculite, Nordic, Arch Dark, Onyx Dark,
Monoko Pro, Grovebox Dark, Solaris in both directions, Octo Light, and a high-contrast
palette. `Theme.builtins()` returns them all in display order, which is everything a theme
picker needs.

Every one of the fifteen is held to the same contrast floor as the toolkit's own: body
text, muted text, the accent's own label and the focus ring each clear WCAG AA on the
surface they land on, in every state, hovered, pressed and disabled included. Meeting that
moved real tones in the borrowed palettes, so they are named for where they came from
rather than after it: a palette that has had its ink darkened and its accent deepened is no
longer the palette it was borrowed from, and shipping it under the original name would be a
claim about someone else's work.

The palette is process-wide. Nothing is notified when it changes, so call
`markNeedsLayout()` on each live scene's root afterwards; that one line is the whole
migration for a running application.

## Building your own

Picking one of the built-ins is the starting point, not the ceiling. A palette is something
an application builds:

```java
Theme brand = Theme.builder("Acme", false)
        .background(Color.WHITE)
        .primary(Color.rgb(0x0F6E4F))
        .deriveAccentStates()
        .cornerScale(1.6f)
        .build();
```

`deriveAccentStates()` is the shortcut worth knowing: give it an accent and it solves the
hover, pressed and label tones around it, so the three states of a button stay legible
without you picking six colours to get one.

To change a few tones of a palette you already like, start from it:

```java
Theme softer = Theme.limnLight().toBuilder().cornerScale(2.2f).build();
```

### Every tone, by name

`Theme.Token` enumerates every colour a palette is made of, so code that treats them alike (a picker,
an importer, a contrast audit) does not have to name them one at a time:

```java
for (Theme.Token token : Theme.Token.values()) {
    System.out.println(token.key() + " = " + token.read(theme));
}
```

`key()` is the stable identifier, spelled exactly like the field it names. It is what a
saved palette writes, so it is API: it does not change.

### Shape

`cornerScale` is the one metric a palette carries: a multiplier on every radius, 0 for
square, 1 for the shipped ramp, higher for softer, and far enough up for a pill.

It lives with the colours rather than with the sizes because it is the only metric
**nothing measures from**: no `onMeasure`, `onLayout` or `paintOutset` in the toolkit reads
a radius, so changing the shape of a palette is a repaint, exactly like changing a colour.

:::note[Read a radius through the size row]
`theme.tokens(step).radiusMedium()`, and its `radiusSmall()` / `radiusLarge()` siblings, is
how a radius is read, because that is the value `cornerScale` has already been applied to.
There is no `theme.radiusMedium` field to reach for by mistake.
:::

## Saving and loading a palette

`ThemeFormat` writes a palette as text and reads it back:

```java
Files.writeString(file, ThemeFormat.write(brand));
Theme loaded = ThemeFormat.load(file);
```

The extension is `.limntheme` (`ThemeFormat.EXTENSION`). Loading is one line and needs
nothing but `limn-toolkit`, which is the point of the split below.

## Letting your users build one

`limn-theme-editor` is an optional module containing `ThemeEditor`, the screen that authors
a palette, with a live preview and a contrast audit beside it. Add it if your application
wants a theme editor in it; leave it out and you pay nothing, because **nothing in the
toolkit depends on it**.

```kotlin
implementation("io.github.limn-toolkit:limn-theme-editor:0.4.0")
```

The dependency points one way only: the editor reads `Theme` and `Theme.Builder`, and no
part of the widget set reads the editor. So a palette your designer saves from the editor
loads in an application that has never heard of it.

## What is in a palette

| | |
| --- | --- |
| `background` `surface` `surfaceRaised` | the canvas, a card, a popover |
| `primary` `primaryHover` `primaryPressed` `onPrimary` | the accent and the ink that sits on it |
| `text` `textMuted` | body text and the quieter kind |
| `outline` `focusRing` | borders and the focus ring |
| `disabledFill` `disabledText` | unavailable controls |
| `danger` `success` `warning` `info` | the four states |
| `cornerScale` | how round the corners are, the one metric a palette carries |

Read them from `Theme.current()` when you paint, never at construction time: a colour
copied in a constructor is a colour that will not follow the next theme switch:

```java
@Override
protected void onPaint(Canvas canvas) {
    canvas.fillRoundRect(0, 0, width(), height(), 8, Theme.current().surface);
}
```

Every tone in the two Limn palettes was solved against the tone beneath it for a contrast
target, and those targets are asserted by the toolkit's own tests. If you build a palette of
your own, hold it to the same bars: body text against all three surfaces, the accent's label
across its whole hover-and-press ramp, and the focus ring against the accent it surrounds.

## Density

Control size is the other half of theming, and it is inherited down the tree rather than
being a property of the palette:

```java
scene.setControlSize(ControlSize.SMALL);
```

Five steps, from `XSMALL` to `XLARGE`, and every component honours all of them: padding,
type, corner radii and hit targets move together. Set it on a subtree and only that subtree
changes, so a dense inspector panel beside a normal editor is one call on the panel:

{% snippet guide:control-size %}

{% shot control-size "XSMALL at the top down to XLARGE at the bottom. Nothing is given a width, a font or a padding." %}

`ControlSize.setProcessDefault(…)` sets it for everything at once, which is the shape of an
application-wide "compact mode" setting.

:::caution[Never read a step in a constructor]
A widget has no parent while it is being constructed, so a size, a font or a spacing token
read there is the process default captured forever, and it will not follow the step the widget
is eventually placed under. Read those in `onMeasure` or `onPaint`.
:::

## Following the operating system

The toolkit does not read the platform's light/dark preference for you. Read it however your
platform layer already does, and set the matching palette:

```java
Theme.setCurrent(systemPrefersDark ? Theme.limn() : Theme.limnLight());
```

## Fonts

`Fonts.setDefaultFamily("…")` changes the face the whole toolkit uses;
`Fonts.available()` lists what the backend found. Individual widgets take a `Font` where it
matters, as `label.setFont(…)` does, but reach for the theme's roles first: a `Label` set to
`Role.TITLE` gets the right size for its control size step, in every step, without you
picking a number.
