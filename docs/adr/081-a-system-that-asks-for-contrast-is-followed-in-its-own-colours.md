# ADR 081: A system that asks for contrast is followed, in its own colours

- **Status:** Proposed, 2026-10-23, at the owner's request ("a ADR para entender como implementar
  isso conforme as preferências nativas"). §6 holds the questions for the owner, each with a
  recommendation; §0.2 lists what must be measured on the guests before anything is built.
- **Date:** 2026-10-23
- **Scope:** what a Limn application looks like when the user has asked the system for more
  contrast: Windows' contrast themes, macOS's Increase Contrast, GNOME's High Contrast. What each
  platform hands an application, what the toolkit does with it by default, what an application can
  refuse, and what each component must draw so that it stays usable under it. Two neighbouring
  preferences of the same panels are proposed with it (§1.5): reduce transparency, and
  differentiate without colour.
- **Changes:** ADR 047 §1.4 ("increased contrast is reported, not acted on"), if §6 question 1 is
  answered as recommended. ADR 047 §1.2 (the toolkit does not switch the theme by itself) stays
  true for the appearance; this record makes contrast the one exception, and says why.
- **Compatibility:** additive in the API. In behaviour, an application that does nothing changes
  look while the user has a contrast setting on, which is the point; §1.4 is how it refuses.

## 0. What is known, and what must be measured first

### 0.1 Known, from ADR 047 and the code

- All three backends already read the preference and hear it change: macOS
  `accessibilityDisplayShouldIncreaseContrast`, Windows `SPI_GETHIGHCONTRAST` (`HCF_HIGHCONTRASTON`),
  Linux the portal's `org.freedesktop.appearance` `contrast` (1 for more). They reach the
  application as `SystemPreferences.increaseContrast()`. Read live on the four guests on 2026-09-29
  (ADR 047 §0): Windows, the Mac guest and Ubuntu GNOME report it; KDE Plasma has no such setting
  and its `contrast` stays 0.
- The toolkit acts on none of it (ADR 047 §1.4). It ships one `High Contrast` palette, dark only,
  with fixed colours: black canvas, white text, yellow accent.
- On Windows a contrast theme turns `SystemUsesLightTheme` dark and leaves `AppsUseLightTheme`
  light (ADR 047 §0), so the appearance alone says nothing about contrast, and an application
  following the appearance stays on its light palette.
- The Windows tray already follows a contrast theme: its monochrome icon is tinted with
  `GetSysColor(COLOR_WINDOWTEXT)` while one is on (`WindowsTray`). It is the only place that does.
- A palette carries colour and one metric, `cornerScale` (`Theme`'s class comment). Whether a
  control draws a border is decided by each component, not by the palette: a primary button, a
  selected row or a filled chip is told from its neighbours by its fill alone.
- Shadows (`ElevationShadow`), frosted backdrops (`BackdropPanel`) and translucent popups are
  drawn in every palette.

### 0.2 What the three platforms mean by it, and what is owed a measurement

What the platforms document, and what the native toolkits of each do; every row is to be read on
the guest before it is relied on (turning a setting on is the owner's, as it was for ADR 047):

| Platform | What the user chose | What a native application does | What can be read |
| --- | --- | --- | --- |
| Windows 10 and 11 | A **contrast theme**: four shipped (named differently by version) and any the user edits. It is a full palette of the user's choosing, light or dark. | Draws every surface, text, border and selection from the theme's colours, never its own; keeps content pictures (photos, video) as they are. | The eight system colours of a contrast theme through `GetSysColor`: `COLOR_WINDOW`, `COLOR_WINDOWTEXT`, `COLOR_HOTLIGHT` (links), `COLOR_GRAYTEXT` (disabled), `COLOR_HIGHLIGHT` and `COLOR_HIGHLIGHTTEXT` (selection), `COLOR_BTNFACE` and `COLOR_BTNTEXT` (buttons). A change arrives as `WM_SYSCOLORCHANGE` and `WM_SETTINGCHANGE`. |
| macOS 11 and later | **Increase Contrast** (Accessibility, Display). Not a palette: the system keeps Light or Dark. | Keeps its colours, darkens or strengthens them, draws a border around every control and stronger separators; system colours resolve to their high-contrast variants. Often comes with Reduce Transparency. | The flag (read today). Each `NSColor` system colour under the high-contrast appearances, if the toolkit wants the system's own numbers. |
| GNOME (portal `contrast` = 1) | **High Contrast** (Settings, Accessibility). Keeps Light or Dark. | The platform's own widget library switches to a high-contrast style: borders on controls, stronger outlines, less translucency, same light or dark. | The flag (read today). No palette is published through the portal. |
| KDE Plasma | Nothing to switch: contrast is a colour scheme the user picks. | Applications take the scheme's colours. | Out of scope (§5): the portal says 0, and a colour scheme is a palette of its own. |

To measure on the guests before building, each recorded in this record:

1. **Windows:** the eight colours under each shipped contrast theme and one edited by hand; whether
   `WM_SYSCOLORCHANGE` arrives once per switch; whether the window's own frame and title bar
   follow without us (they are the system's).
2. **macOS:** what Increase Contrast changes in the system's own controls beside a Limn window (a
   capture of each, light and dark), to set the borders and strengths of §1.3 against what the
   user is used to; whether Reduce Transparency is reported apart.
3. **GNOME:** the same capture against the platform's own widgets with High Contrast on, light and
   dark.
4. **All:** that the native pieces Limn already uses (the macOS menu bar, tray menus, file and
   print dialogs, native popups) follow by themselves.

## 1. Decision (proposed)

### 1.1 Contrast is followed by default

Today an application that does nothing stays on its palette while the user has asked for contrast.
That fails the requirement that public procurement holds desktop software to: the application must
take the colour and contrast the user set on the platform (EN 301 549 clause 11.7, "user
preferences"; Section 508 502.2.2 and 503.2), and it is the one accessibility setting a user with
low vision cannot work around inside the application. The appearance is different: a dark
application on a light desktop is a choice, not a barrier, which is why ADR 047 §1.2 leaves it to
the application, and why this record leaves that as it is.

So, while `increaseContrast()` is true, every window paints with the **contrast palette** of §1.2
instead of the current theme, and goes back to the current theme when it turns off. `Theme.current()`
does not change: the application's choice is kept, and the contrast palette is laid over it the way
a printed page's palette is (`Theme.of(widget)` already resolves per scene for printing). An
application reads which one it got through `Theme.of(widget)`, as every component does.

### 1.2 The contrast palette is the system's colours where there are some

- **Windows:** the palette is built from the eight system colours, nothing of the application's
  palette kept: `WINDOW` for the canvas and every surface, `WINDOWTEXT` for text, muted text and
  every outline, `HIGHLIGHT`/`HIGHLIGHTTEXT` for selection and the primary accent, `HOTLIGHT` for
  links and the focus ring, `GRAYTEXT` for anything disabled, `BTNFACE`/`BTNTEXT` for buttons.
  Danger, success, warning and info have no system colour and become `WINDOWTEXT`: their meaning
  must then come from an icon or a word, which §1.3 requires anyway. Rebuilt on every change.
- **macOS and GNOME:** a **strengthened copy of the current palette**, same light or dark and same
  accent, derived by rule and held by `ThemeContrastTest` to the enhanced level (7:1 for text and
  muted text on every surface, 4.5:1 for the accent, outlines and focus ring against what they sit
  on, 3:1 between a control's fill and its surroundings): muted text moves to text, outlines move
  to text, surfaces move apart in lightness until they reach their bar, the scrim deepens. The
  shipped `High Contrast` palette stays as a palette an application may choose; it is not what the
  system gets, because it is dark whatever the user chose.
- **No setting at all, an application's own palette:** an application that ships its own palette
  gets the same strengthened copy on macOS and GNOME, and the system's colours on Windows.

### 1.3 What a component draws under contrast

A palette is colours; a contrast palette also has to change **how** some things are drawn. One
flag on the palette, `Theme.contrast()` (true for the palettes of §1.2 and for the shipped
`High Contrast`), and every component holds to these while it is set:

1. **Every control has a boundary**: a button, a field, a combo box, a chip, a tab, a toggle draws
   its outline in the outline colour, whatever its style; a filled control keeps its fill and adds
   the outline.
2. **State is never colour alone**: selection is the highlight pair (fill and text); hover adds an
   outline rather than a tint; checked, on and current carry their mark, as they already do;
   `Badge` and `Tone` keep their word or icon.
3. **The focus ring is two pixels wide and never translucent.**
4. **No shadow and no frosted backdrop**: `ElevationShadow` draws an outline instead, a
   `BackdropPanel` paints its tint opaque, popups and menus are opaque with an outline.
5. **Disabled is the disabled colour**, text and outline alike, never an alpha.
6. **Content is left as it is**: an image, a video, a 3D view, a chart's series, an SVG that is a
   picture. An icon that is a glyph (a monochrome SVG, a Tabler icon) is drawn in the text
   colour.

`ThemeAudit` and a capture gallery of every component under the Windows contrast themes and the
strengthened light and dark copies (as the gallery is captured today) hold these.

### 1.4 What an application can refuse

- `SystemPreferences`' contrast is still reported as today.
- `Theme.followSystemContrast(false)` (name to settle, §6 question 5) keeps the application's
  palette while contrast is on, for an application that brings its own contrast mode; the
  Javadoc says what that costs in conformance.
- A widget that paints content of its own (a canvas, a chart, a custom control) reads
  `Theme.of(this).contrast()` and the palette's colours, as it reads the palette today.

### 1.5 Two neighbours, proposed with it

- **Reduce transparency** (macOS `accessibilityDisplayShouldReduceTransparency`; Windows'
  "Transparency effects" off, `EnableTransparency` 0 under `Personalize`; GNOME has none): reported
  as `SystemPreferences.reduceTransparency()`, and while it is on, rule 4 of §1.3 holds in any
  palette (opaque backdrops and popups); shadows stay.
- **Differentiate without colour** (macOS `accessibilityDisplayShouldDifferentiateWithoutColor`;
  no counterpart elsewhere): reported, and rule 2 of §1.3 holds while it is on.

Both are read by the same readers that read contrast, and change through the same signals.

## 2. Alternatives considered

- **Keep ADR 047 §1.4 and document a recipe.** Cheapest; but almost no application would follow,
  and the toolkit would fail clause 11.7 on every Windows machine with a contrast theme.
- **Switch to the shipped `High Contrast` palette when contrast is on.** One line; but it is dark,
  with its own yellow, so a user who chose a light contrast theme on Windows gets black, and a Mac
  user who asked for more contrast gets a different application. It answers "high contrast", not
  "the user's contrast".
- **Strengthen by rule on Windows too, ignoring the system colours.** Consistent across
  platforms, but on Windows the user chose those colours, sometimes for a condition that makes
  others unreadable (a light-sensitive user on a dark theme with amber text); ignoring them is the
  barrier.

## 3. What it costs

- Windows: `GetSysColor` for eight colours and `WM_SYSCOLORCHANGE` in the reader that already hears
  `WM_SETTINGCHANGE`.
- The toolkit: a contrast palette resolved behind `Theme.of`, the derivation rule for the
  strengthened copy, `Theme.contrast()`, `followSystemContrast`, two more preferences.
- Every component's paint: §1.3's six rules, most of them a branch on one flag where the component
  already chooses a colour. Shadows, backdrops and popups have one place each.
- Tests: the derived palettes held to their bars in `ThemeContrastTest`, a headless test per rule,
  the capture gallery under contrast, and a live reading on the guests.

## 4. Verification, when built

- Headless: with contrast on, `Theme.of` answers the contrast palette and `Theme.current()` is
  unchanged; off again, the application's palette; a change is one repaint, never a relayout.
- `ThemeContrastTest`: the strengthened copy of every shipped palette, light and dark, meets §1.2's
  bars; the Windows palette built from each shipped contrast theme's colours (as measured in §0.2)
  is used as given, and the test only checks that every token was mapped.
- The capture gallery under each palette, looked at by the owner.
- Live, on each guest: turn the setting on with the Showcase open and see it follow, the native
  pieces of §0.2 item 4 included, and a screen reader still reading every control.

## 5. What this is not

- **Not a system text size.** Clause 11.7 also names font size; ADR 047 §3 left it to its own
  record, and it stays there.
- **Not KDE's colour schemes.** A scheme is a palette the user picked, read from the desktop's own
  files; a later record if wanted.
- **Not colour filters or inverted colours.** The system applies those to the whole screen, after
  the application has drawn.
- **Not a per-window choice.** Like the other preferences, contrast is the session's.

## 6. Questions for the owner

1. **Followed by default (§1.1)**, changing ADR 047 §1.4? *Recommended: yes.* It is what the
   conformance clauses ask, and what the platforms' own toolkits do without being asked.
2. **On Windows, the system's colours (§1.2)** rather than a strengthened copy of the
   application's palette? *Recommended: yes*, for the reason in §2's third alternative.
3. **The six drawing rules (§1.3)** as one flag on the palette, `Theme.contrast()`, rather than a
   second metric such as a border width? *Recommended: the flag*; the rules are about what is drawn,
   not by how much.
4. **Reduce transparency and differentiate without colour (§1.5)** in this record, or apart?
   *Recommended: in*, read by the same readers and served by two of the same rules.
5. **The name of the refusal (§1.4)**: `Theme.followSystemContrast(boolean)`, or a field of
   `SystemPreferences`' subscription? *Recommended: on `Theme`*, beside `apply()`, since it decides
   which palette paints.
6. **Before 1.0?** *Recommended: yes*: §1.1 changes what an application that does nothing looks
   like, which is cheaper to change before the compatibility promise than after it.
