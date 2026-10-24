---
title: "Theming"
description: "Palettes, density and how to make the toolkit look like your product rather than like the demo."
---

## Picking a palette

A theme is a palette plus one shape metric. Switching one is a single call, which makes it
current, applies its font, and repaints every window on the palette's own background:

```java
Theme.limnLight().apply();
```

Made before the first window opens, the same call is how an application starts in a palette
other than the default: a scene clears to the background of the palette in effect from its
first frame, so there is nothing to copy into it.

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

### Letting the user choose

A theme picker is a combo box over `Theme.builtins()` whose choice calls `apply`:

{% snippet guide:theme-picker %}

`apply` does what a picker needs: it makes the palette current, which repaints every window,
each clearing to the palette's `background` from its next frame, and it applies the palette's
own font. The palette is process-wide, and so is the repaint: a window opened later, or one you
never mention, follows it too. A scene keeps a background you chose for it yourself with
`scene.setBackground(colour)`, as a popup that clears to transparent does; `setBackground(null)`
hands it back to the palette.

Nothing is re-measured by a palette switch, because a palette carries colours and corner radii
and nothing in the toolkit measures from either. The palette's font is the one part that
re-measures, and `apply` sends it through the font axis, which lays every scene out again by
itself.

:::caution[`apply`, not `setCurrent`]
`Theme.setCurrent(palette)` switches the palette and repaints every window, but never applies
the palette's font. It is the half of `apply` meant for a colour well that changes the palette
on every frame of a drag, where reading a font file inside a frame is not wanted. A theme
picker, a settings screen and a "dark mode" switch all want `apply`.
:::

Language, direction and control size work the same way: setting any of them lays out every
scene again with nothing more to call, as a palette repaints them. `Theme.observeChanges` is
there for whatever else of yours has to hear of a palette switch.

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

## Building one in the theme editor

The theme editor is a program, not a dependency: it authors a palette with a live preview and
a contrast audit beside it, and saves it as a `.limntheme` file. Besides the colours it edits
the corner roundness, the typeface and, at the end of its list, the shadow of each level of
[elevation](#elevation).

{% tabs os "Windows / Linux" macOS %}
```bash
jbang theme-editor@limn-toolkit/limn-toolkit
```
```bash
jbang --java-options=-XstartOnFirstThread theme-editor@limn-toolkit/limn-toolkit
```
{% endtabs %}

[Install](/docs/install/) says why macOS needs its flag. Your application ships the file and loads it with `ThemeFormat`, which
is in `limn-toolkit`; nothing of the editor goes with it. What the two share is the value, a
`Theme` and the text `ThemeFormat` writes it as, so a palette your designer saves in the editor
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
| `scrim` | the veil a modal dialog lays over what it blocks |
| `cornerScale` | how round the corners are |
| `elevation(level)` | the shadow of a raised surface, of one that covers and of a dialog: see [Elevation](#elevation) |
| `fontFamily` | the face the palette prefers; applied by `apply`, or by `applyFontFamily()` |

Each is an accessor on the palette. Read the palette through `Theme.of(this)` when you paint,
never at construction time: a colour copied in a constructor will not follow the next theme
switch, and asking through the widget is what lets a palette per subtree be added later without
your widget changing:

```java
@Override
protected void onPaint(Canvas canvas) {
    canvas.fillRoundRect(0, 0, width(), height(), 8, Theme.of(this).surface());
}
```

A line of text in one of those colours is the same rule from the other side. `Label.setColor`
pins the colour it is given, and a colour read while the screen is built is the palette of that
moment, so it stays behind when the palette changes. Say what the text means instead, and the
label asks the palette each time it paints:

{% snippet guide:theme-tone %}

`Tone` names six: `NEUTRAL` (the muted text), `ACCENT` (the primary), and the four states,
`INFO`, `SUCCESS`, `WARNING` and `DANGER`. `Tone.colorIn(theme)` answers the colour, for a
widget of your own that draws in one. A [field](/docs/forms/) draws the line under its control
in the tone of its state, so a form's messages follow the palette with nothing written for it.

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

The backend reads the user's light or dark appearance from the platform, and
`SystemPreferences` holds it. The toolkit does not switch your palette by itself, because which
palette to use is your call, and it may be your brand. Following the system takes a few lines:

```java
Runnable follow = () -> {
    switch (SystemPreferences.current().colorScheme()) {
        case DARK -> Theme.limn().apply();
        case LIGHT -> Theme.limnLight().apply();
        case NO_PREFERENCE -> { } // the desktop says nothing: keep your own default
    }
};
follow.run();
SystemPreferences.observeChanges(follow);
```

`NO_PREFERENCE` is a real answer: it is Ubuntu's default, and what a desktop without the
settings portal gives. The same record says whether they asked for less motion
(`reduceMotion()`), which the toolkit acts on by itself: animations finish at once, and menus,
lists and dialogs open and close without fading.

### More contrast

When the user asks the system for more contrast (`increaseContrast()`), the toolkit follows by
itself, and your palette choice is kept: `Theme.current()` does not change, and
`Theme.of(widget)`, which every widget paints with, answers a contrast palette instead.

- **On Windows** a contrast theme is a whole palette the user chose, light or dark, and the
  toolkit paints with it as given: the window's colour for every surface, its text colour for all
  text and every edge, the highlight pair for selection and the accent, the link colour for the
  focus ring. `SystemPreferences.contrastColors()` holds those colours.
- **On macOS and GNOME** more contrast keeps the light or dark and strengthens it, as the
  platforms' own widgets do: your palette with its text at 7:1 and every edge, ring and accent at
  4.5:1 on its surfaces.

In a contrast palette (`theme.contrast()`, true there and for the shipped High Contrast) nothing
casts a shadow, every button and every clickable card has its edge, and glass is drawn opaque.
`Theme.followSystemContrast(false)` keeps your palette while more contrast is asked for; take it
only if your application brings its own contrast mode, since following the platform's colour and
contrast settings is what EN 301 549 clause 11.7 asks of software.

Every palette, the one you build included, also draws a control's edge at 3:1 on its surfaces, as
WCAG 2.1 1.4.11 asks: `controlBorder()` is your outline, moved toward the text colour only as far
as that needs, and `outline()` stays the quieter edge of what groups or divides.

## Fonts

`Fonts.setDefaultFamily("…")` changes the face the whole toolkit uses;
`Fonts.available()` lists what the backend found. Individual widgets take a `Font` where it
matters, as `label.setFont(…)` does, but reach for the theme's roles first: a `Label` set to
`Role.TITLE` gets the right size for its control size step, in every step, without you
picking a number.

## Elevation

A surface that lies over another says so with a shadow, and how deep the shadow is depends on
what the surface is, not on a number each widget picks. There are four levels:

| level | for |
|---|---|
| `FLAT` | a pane, the window's content: casts nothing |
| `RAISED` | a card with no edge, a bar that floats |
| `OVERLAY` | what covers the page for a while: a menu, a list, a calendar, a panel that slides in |
| `MODAL` | what covers the window until answered: a dialog |

The toolkit's own menus, lists, calendars, tooltips and dialogs already cast theirs, and so does
a `Card`, raised unless its style says otherwise (see [Cards](/docs/layout/#cards)). For a
surface of your own, `Surface` draws the fill and the shadow of its level around one child,
and a palette changes what a level looks like:

{% snippet guide:elevation %}

A shadow is two layers straight down, since the light is above: a wide one, offset, that says
how far the surface is lifted, and a tight one under the surface's own edge. A palette gives
five numbers a level, the wide layer's offset, blur and opacity and the tight layer's blur and
opacity; the theme editor has them under Elevation, and a `.limntheme` file writes them as
`elevation.raised`, `elevation.overlay` and `elevation.modal`. A dark palette's shadows are
darker by default and still add little: on a dark canvas it is the surface's lighter tone
that lifts it, so keep the step between `surface` and `surfaceRaised`.

The shadow is painted outside the surface's box. Whatever holds a `Surface` has to leave it
room: a parent that clips to the surface's own box clips the shadow away.

## Glass

A `BackdropPanel` is a container whose background is what the frame has already drawn behind
it, put through an effect: blurred, washed, refracted. It is for a surface that lies over
content the user should still sense: a bar that stays while a page scrolls under it, a panel
of controls over a picture or a video.

`BackdropPanel.frosted(content)` is the one most applications want: frosted glass in the
theme's own tone. What is behind it is blurred, calmed and laid over with the palette's surface
colour, light over a light palette and dark over a dark one, and it changes with the palette
by itself. `setTint(colour, amount)` leans it toward a colour of yours, from 0, the theme's tone
alone, to 1, the colour alone. `setCover(share)` is how much of that tone is laid over what is
behind: 0.6 unless you say, which reads over anything, and less where the panel sits over a
picture you chose for it and more of the picture should show:

{% snippet guide:glass-frosted %}

For anything else, a refracting rim, a pixelated redaction, a wash with no blur, give the panel
its effects by hand with `setEffects`. They are drawn in order and each reads what the one
before it wrote; an effect given by hand holds the colour it was given, so it does not follow
the theme.

A bar over what scrolls is a `Stack` of two layers, the content and then the bar:

{% snippet guide:glass-bar %}

A panel that floats is the same stack with another alignment:

{% snippet guide:glass-floating %}

Three things to know before reaching for it. The panel goes after what it covers, because it
samples what is already drawn; added first, it shows the window's background. It costs a copy
of its own rectangle on every frame that repaints anything under it, which is a bar's worth of
work and not a list row's: one panel over a list, never one per row. And where a renderer has
no backdrop support the panel is filled with its tint, at the same size, so choose a tint that
works as a plain colour too.

Glass hides nothing from a screen reader. What is under a panel is still read from its own
widget, so a blurred or pixelated field is hidden from the eye only; to withhold something,
hide the widget that holds it.
