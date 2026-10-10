# ADR 057: A surface says how far it is lifted, and the theme says what that looks like

- **Status:** Accepted, 2026-10-10, with the owner's answers in §4; being built in the four
  steps of §5: all four are built; what step 3 could not see, under Wayland and on Windows, is open.
- **Date:** 2026-10-10
- **Scope:** elevation: how a surface that lies over another is told apart from it. A small
  scale of levels, what each level draws (a shadow, a tone, an outline), where the numbers live
  (the theme), the one drawing primitive it needs (a soft shadow of a rounded rectangle), and
  which of the toolkit's own surfaces take a level.
- **Compatibility:** additive, except that a theme gains tokens and `.limntheme` files a key
  with a default.
- **Asked for by:** the owner, on seeing the order's panel slide in beside the demo's table with
  nothing between the two but a gap: "senti falta de uma sombra entre o painel e a tabela, para
  dar profundidade".

---

## 0. What exists

**No 2D shadow anywhere.** `Canvas` has no shadow call and the backend's batch has no blurred
shape. Depth is said in two ways today:

- **Tone.** A theme has three surfaces, `background`, `surface` and `surfaceRaised`, each a
  step lighter (dark palettes) or darker (light ones) than the one under it, with the step
  asserted by the theme's tests in L*.
- **Outline.** Cards, fields and popups draw a one-point border in `outline`.

That is enough for surfaces that lie *beside* each other. It is thin for the ones that lie
*over* something:

| surface | where it is drawn | what lifts it today |
|---|---|---|
| a menu, a combo box's list, a tooltip, as a window of its own | a system window | the compositor's shadow where the system gives one to such a window, nothing where it does not |
| the same, where the platform cannot place a window, drawn in the scene | the owner's scene | tone and outline |
| a date picker's calendar | always in the scene | tone and outline |
| a dialog in the scene | the owner's scene | the scrim, tone and outline |
| a panel that slides over or beside content (the demo's) | the scene | nothing |

A widget may already paint outside its box: `paintOutset()` grows the region a repaint
damages, which is what a focus ring uses and what a shadow needs.

## 1. What the published design systems do

Read on 2026-10-10 from the three systems the owner named; where a figure could not be read
from the publisher's own page it is marked.

**Material Design 3.**
- Six levels, 0 to 5, standing for 0, 1, 3, 6, 8 and 12 dp (the pairing as remembered, not
  re-read today).
- Depth is said **by tone first**: the surface-container roles are five tones, and since 2023
  they are "no longer tied to elevation". A shadow is kept for surfaces that overlap others.
- Resting surfaces use levels 0 to 3; 4 and 5 are for a surface being hovered or dragged.
- A shadow is two layers, a tight *key* one and a wide *ambient* one.
- A side sheet that shares the screen with content is level 0 with a divider; one that comes
  over content is level 1 with a scrim (as remembered).

**Fluent 2.**
- Six shadow tokens named by their blur: 2, 4, 8, 16, 28, 64. Each is two layers; offset is
  half the number, blur the number.
- Low ramp (2 to 16): both layers 14 % in light, the first 28 % in dark. High ramp (28, 64):
  24 % and 20 % in light, 28 % and 20 % in dark, the second layer a fixed tight blur.
- Uses: 2 and 4 for cards with no edge, 8 for raised bars and tooltips, 16 for callouts, **28
  for side navigation and bottom sheets**, 64 for dialogs and panels.
- "Windows uses strokes instead of key shadows to outline an object": on a desktop the edge is
  a line and the shadow is only the soft part.
- Shadows are stronger in dark themes, and are not to be used over coloured surfaces.

**Apple's guidelines.**
- Depth inside a window is said by **material**, a translucent layer over content, and not by
  a drawn shadow: a sidebar or an inspector is a pane of material, parted from its neighbour by
  a hairline or by floating over it.
- Shadows belong to what floats over the window: the window itself, a popover, a menu, a
  sheet. They are the system's, and an application does not size them.
- Nothing was found that gives numbers for a shadow inside a window.

**What the three agree on.**
1. Three instruments: tone, a line at the edge, a shadow. No system uses the shadow alone.
2. Surfaces side by side take tone and a line. A shadow is for a surface that **covers**
   something, and grows with how temporary and how much in front the surface is.
3. Few levels, five or six, and half of them are never used by an application directly.
4. A shadow is two layers, one tight and one wide, both straight down: light comes from above.
5. Dark themes cannot rely on the shadow, which black on near-black does not show, and say
   depth by a lighter tone and a line, keeping the shadow stronger for what little it adds.

## 2. Proposed

### 2.1 Four levels, named for what the surface is

| level | what it is | the toolkit's own surfaces at it |
|---|---|---|
| `FLAT` | lies in the plane of what is around it | panes, the window's content |
| `RAISED` | a part of the page set a little above it | a card, a tool bar that floats |
| `OVERLAY` | covers what is under it, for a while | a menu, a list of a combo box, a calendar, a tooltip, a panel that slides in |
| `MODAL` | covers the window until answered | a dialog in the scene |

Four and not six: the two systems with six keep two for states an application never sets, and
a level nobody can tell from its neighbour on a desktop screen is a level to get wrong. Named
and not numbered: a widget says what it *is*, and the theme owns what that looks like, as a
control says its size step and not its height.

### 2.2 What a level draws, and where the numbers are

A theme holds, per level, a **shadow**: two layers, each an offset down, a blur and an opacity,
in one colour. The built-in palettes take the second system's proportions, which are the only
published ones with numbers for both light and dark:

| level | key layer (y, blur, opacity light / dark) | ambient layer |
|---|---|---|
| `FLAT` | none | none |
| `RAISED` | 1, 2, 14 % / 28 % | 0, 2, 12 % / 14 % |
| `OVERLAY` | 8, 16, 14 % / 28 % | 0, 2, 12 % / 14 % |
| `MODAL` | 14, 28, 24 % / 28 % | 0, 8, 20 % / 20 % |

Tone and outline stay what they are: `surface`, `surfaceRaised` and `outline` already exist and
are already asserted. A level does not pick the tone; the widget keeps choosing its fill. What
is added is the shadow, and nothing else changes for a theme that sets none.

### 2.3 The primitive

`Canvas.drawShadow(RoundRect shape, Shadow shadow)`: the soft shadow of a rounded rectangle,
computed in the fragment shader from the shape's distance field, with no offscreen pass and no
blur kernel. One quad per layer. A shadow is drawn outside its shape only, so a translucent
surface does not darken itself.

### 2.4 Who draws it

- A widget that is a surface over others calls it in its own paint and reports the shadow's
  reach through `paintOutset()`, so partial rendering repaints what the shadow touches.
- The toolkit's in-scene overlays (menu, list, calendar, tooltip, dialog) do it themselves.
- An application's own surface gets a small container, working name `Surface`, with a level,
  a corner radius and a fill, for a panel like the demo's.

### 2.5 What is left out

- **Windows of their own** keep the compositor's shadow. Drawing ours there means a window
  larger than its content, transparent around it, with the pointer let through the margin:
  a change to every popup's geometry on four systems. Measured first, decided after.
- **Material** (blur of what is behind) is `BackdropPanel`'s and is not elevation here.
- **Coloured shadows**, and shadows that follow a light other than straight above.

## 3. Costs and risks

- A shader path in the batch, and its software-renderer and GLES twins.
- The shadow's reach is damage: an overlay that moves repaints a region larger by its blur.
- Light palettes gain most; on the dark ones the shadow is nearly invisible by nature, which is
  what the tone step is for.
- Theme files gain a section; the editor needs a way to show and change it.

## 4. The owner's answers (2026-10-10)

1. **The scale**: four named levels.
2. **The panel beside the table**: it comes over the table, with the shadow.
3. **How far this goes now**: the popup windows too, not only the surfaces drawn in a scene.
   §2.5's first item is therefore in, and is the step with the most to measure.
4. **The theme editor**: shows and edits the shadows now.

## 5. Steps, and where it stands

1. **The base** (done): `limn.graphics.Shadow` and `Canvas.drawShadow`, drawn by the GL canvas
   as the error function of the shape's distance field, one quad a layer; `Elevation`;
   `ElevationShadow`, five numbers a level, in `Theme` with a default by mode and in the theme
   file as `elevation.raised`, `elevation.overlay` and `elevation.modal`; the `Surface`
   container; the demo's panel over its table. The shadow is black: a colour of its own is not
   a token. A shadow is drawn whole and the surface painted over it, so §2.3's "outside its
   shape only" is not what was built: a translucent surface would show its own shadow through
   itself, and none of the toolkit's is translucent.
2. **The overlays drawn in a scene** (done): the list of a combo box, a date picker's calendar
   and a menu, each when it is drawn in the owner's scene, and a tooltip, cast the shadow of
   `OVERLAY`; the card of a dialog in the scene casts `MODAL`'s, fading in with it. Each counts
   the shadow's reach in what its repaints damage, the tooltip's being the scene's to count
   since the scene paints it. In a window of their own they draw none: that shadow is step 3.
3. **The popup windows**, measured before it is decided how. Read on 2026-10-10 from a picture
   of each guest's screen with the demo's size list open as a window of its own
   (`-Dlimn.demo.popup=true`):

   | system | around a popup window of ours |
   |---|---|
   | macOS 27 | the system's shadow, soft and whole |
   | Ubuntu 24.04, GNOME, X11 | nothing: the list's own outline on the page |
   | Fedora 44, KDE, Wayland | nothing seen; the picture came out dimmed and is to be retaken |
   | Windows 11 | not measured: the guest did not come up |

   So the compositor's shadow, which §2.5 meant to keep, exists on one system of the three
   read. Decided from that: where the compositor casts one (macOS) the toolkit draws none, or
   there would be two; elsewhere the popup's window is made larger than its panel by the
   shadow's reach on every side, the panel is laid out inset in it and the shadow drawn in the
   margin, which is transparent. A backend says which case it is. A press in the margin closes
   the popup, as a press outside it does, and is not passed to what is under it: no system
   lets a client pass part of a window's presses on without a call of its own per system, and a
   press beside an open list has always been one that closes it.

   Built (2026-10-10): `Backend.compositorShadowsPopups()`, true by default; a frame widget of
   `limn.components.internal` that lays a popup's panel out inset by the margin and closes the
   popup on a press in it; and the margin in the size and the position of the windows of the
   combo box's list (where it opens and where it is resized as it is filtered), the date
   picker's calendar and the menu, whose one window already spanned its cascade and now spans
   each column's shadow too. **Seen on Ubuntu under X11**: a list, a menu with its check and
   submenu arrow, and a calendar, each with its shadow and where it was before. **Not seen
   under Wayland**: the Fedora guest's screen was asleep and every picture of it came back
   blank, so the backend answers for X11 only and a Wayland session keeps popups with no
   shadow, as before. **Not seen on Windows**: the guest did not come up. A dialog that is a
   window of its own is not part of this: it is a system window with the system's shadow where
   it is decorated, and where it is not, it is still to do.
4. **The theme editor** (done): a section, after the colours, with the five numbers of each
   level, opacity in whole percent, and `ThemeEditor.setElevation` for an application that
   drives it; its preview draws the card with `RAISED`'s shadow and two surfaces beside the
   field with `OVERLAY`'s and `MODAL`'s, from the palette being edited.

Seen on the demo's panel (macOS guest, light and dark): the shadow is there and soft, and
faint at the side of a panel that meets the window's top and bottom, since the light is above
and only the blur's spread reaches sideways. Whether the default for a surface that covers
should be stronger is the owner's eye to say.
