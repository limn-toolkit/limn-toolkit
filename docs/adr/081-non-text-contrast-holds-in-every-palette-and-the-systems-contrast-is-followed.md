# ADR 081: Non-text contrast holds in every palette, and the system's contrast is followed

- **Status:** **Accepted and implemented 2026-10-24**: the owner took every recommendation of §6
  (§7 records the answers); §8 records what was built, what of §1.4 was not, and the live reading on
  the guests, taken the same day. Proposed 2026-10-23 at the owner's request, an ADR to
  understand how to implement contrast as the platforms' own preferences ask, its scope WCAG 1.4.11
  and EN 301 549 and nothing beyond; §0.3 holds the component audit and §0.4 what the four guests
  answered, both on 2026-10-23.
- **Date:** 2026-10-23, decided 2026-10-24
- **Scope:** two requirements and nothing else.
  - **WCAG 2.1 success criterion 1.4.11, Non-text Contrast (level AA)**, which EN 301 549 repeats
    for software as clause 11.1.4.11: the visual information needed to identify a user interface
    component and its states, and the parts of a graphic needed to understand it, have a contrast
    of at least 3:1 against the colours next to them. Inactive (disabled) components are exempt.
  - **EN 301 549 clause 11.7, User preferences**, for colour and contrast: software that has a user
    interface offers a mode of operation that follows the platform's settings for colour and
    contrast.
  Clause 11.7 also names font type, font size and the focus cursor; those are not part of this
  record (§5).
- **Changes:** ADR 047 §1.4, which says the toolkit reports the system's request for more contrast
  and does nothing with it, if §6 question 5 is answered as recommended.
- **Compatibility:** additive in the API. In behaviour, the palettes' control borders get darker or
  lighter (§1.1), and an application that does nothing changes look while the user has a contrast
  setting on (§1.2).

## 0. What is known, and what must be measured first

### 0.1 What ADR 047 decided

ADR 047 made the three backends read the system's request for more contrast and report it as
`SystemPreferences.increaseContrast()`: macOS `accessibilityDisplayShouldIncreaseContrast`, Windows
`SPI_GETHIGHCONTRAST`, the Linux portal's `contrast`. Its §1.4 says:

> The toolkit ships a High Contrast palette, and `ThemeAudit` holds every shipped palette to
> contrast bars. Whether a system that asks for more contrast should move an application onto that
> palette is the application's choice, as in §1.2.

So today a Limn application that does nothing looks the same with contrast on as with it off, and
the one shipped `High Contrast` palette is dark with fixed colours (black canvas, white text, yellow
accent), whatever the user chose on the system.

### 0.2 Measured today: every palette but High Contrast draws control borders at 2:1

Read on 2026-10-23 from `Theme.builtins()` with `Color.contrastRatio`, the WCAG 2.1 formula:

| Palette | outline on surface | outline on canvas | outline on raised | focus ring on surface | focus ring on accent |
| --- | --- | --- | --- | --- | --- |
| Light | 2.00 | 1.75 | 1.75 | 11.53 | 2.01 |
| Dark | 2.00 | 2.28 | 1.71 | 9.78 | 2.01 |
| Limn | 2.05 | 3.00 | 1.58 | 8.89 | 2.02 |
| Limn Light | 2.22 | 2.57 | 1.89 | 14.68 | 2.20 |
| Darkling | 2.01 | 2.68 | 1.52 | 8.13 | 2.00 |
| Draculite | 2.01 | 2.43 | 1.56 | 9.78 | 2.00 |
| Nordic | 2.02 | 2.50 | 1.63 | 10.06 | 2.00 |
| Arch Dark | 2.02 | 2.50 | 1.66 | 7.35 | 2.00 |
| Onyx Dark | 2.00 | 2.43 | 1.59 | 9.82 | 2.01 |
| Monoko Pro | 2.00 | 2.38 | 1.64 | 4.32 | 2.00 |
| Grovebox Dark | 2.00 | 2.38 | 1.66 | 9.85 | 2.01 |
| Solaris Light | 2.01 | 2.39 | 1.72 | 9.09 | 2.02 |
| Solaris Dark | 2.01 | 2.46 | 1.60 | 7.82 | 2.00 |
| Octo Light | 2.01 | 2.34 | 1.76 | 9.85 | 2.01 |
| High Contrast | 5.57 | 6.25 | 4.90 | 6.58 | 2.00 |

This is by design: `ThemeContrastTest.bordersAndElevationAreVisible` holds `outline` at 2:1 on the
surface, and the palettes were solved to that bar. The focus ring clears 3:1 on every surface in
every palette (4.32 the least).

What follows from it, as reasoning to be confirmed by §0.3's audit: wherever a control is
identified by its border alone (a text field whose fill is the surface's, an unchecked check box, a
slider's track, a radio button), the border is 1.5 to 2.3:1 against what is next to it, and 1.4.11
asks for 3:1. A divider between two rows or the edge of a card is not a control's boundary, and
1.4.11 does not ask anything of it. The focus ring passes where it is drawn outside the control,
next to the canvas; drawn on an accent-filled button, it is 2:1 against that fill.

### 0.3 What identifies each control and each state, audited on 2026-10-23

`NonTextContrastAuditTest` (limn-toolkit) holds the audit. It lists, for every widget the toolkit
ships, each state and the colour that carries it, as the component's paint writes it, with the
paint's file and line. Beside each colour it lists the colour that sits next to it, and measures
the pair in all 15 shipped palettes. A translucent colour is laid over what it sits on. A control
drawn inside another surface is measured against the worst of canvas, card and popover. Two rows of
one kind are alternatives: a field is identified by its border or by its fill. Disabled states are
exempt and are not measured. A control identified by its text alone (a tertiary button, a list with
no edge of its own) is listed and left to the text criteria. The test fails when a widget class
lands with no row and no stated reason, which a removed row was seen to do. It writes the ratios
to `build/reports/non-text-contrast.md` and asserts no bar yet: which rows must hold is §6's
answer.

**82 of 123 required component states clear 3:1 in every palette.** The ones that do not fall
into seven groups:

| Group | Components and states | Colour, and its worst ratio |
| --- | --- | --- |
| A control's boundary in `outline` | SECONDARY Button; Checkbox unchecked and switch off; RadioButton unselected; SegmentedControl; every field box (TextField, PasswordField, SearchField, TextArea, Spinner, ComboBox, DateField, DatePicker); ColorPicker's rails; ColorPickerButton; SplitPane's divider; an OUTLINED Card | `outline`, 1.52 (Darkling) to 2.22; a field's fill is the surface, 1.00 against a card |
| Rails and tracks | Slider and RangeSlider rail; ProgressBar track | `text` at 0.14 alpha, 1.26 (Solaris Light); ProgressBar's fill on it 2.97 (Arch Dark) |
| ScrollBar thumb | ScrollBar at rest | `textMuted` at 0.5 alpha, 1.94 (Octo Light) |
| Selection as a tint | Table and Tree selected rows; CalendarView's range band; ComboBox's chips | `primary` at 0.16 to 0.18 alpha, 1.23 |
| ListView selection | a selected row | an `outline` ring, 1.52 |
| Keyboard focus as a tint | PopupMenu's and ComboBox list's keyboard item; MenuBar's keyboard item and open menu; the active field of a focused time Spinner and DateField | `primary` at 0.20 to 0.28 alpha, 1.26 to 1.38; MenuBar's keyboard chip is `surfaceRaised`, 1.00 |
| Charts | every default series slot; the axis line | 1.45 (slot 3, Solaris Light) to 2.57; axis `outline` 1.52 |

An ELEVATED Card is its `surface` beside the canvas, 1.12 at worst, with its shadow as the only
other cue; the audit counts the fill alone.

What holds everywhere:
- every focus ring, 3.19 at worst (Table's focused cell, on the selection tint) and 3.54 elsewhere;
- every accent fill (a PRIMARY button, a checked box, a selected radio, a switch on, a selected day,
  a current tab), 3.01 at worst (Arch Dark), which passes but leaves no margin;
- validation borders and glyphs, 4.50;
- check marks, 5.69;
- badges' status dots, 4.50;
- disclosure, sort and paging arrows and the field chevrons, 4.50.

So the boundary token of §1.1 covers the first group, and four other groups need choices of their
own:
- a selected row or range: a stronger fill, or a mark beside the tint;
- the keyboard's item in a menu or a list: today a tint, the one place where focus is shown below
  3:1, and so also short of 2.4.7;
- the rails and the scroll bar;
- the charts.

§6 asks about each.

### 0.4 What the platforms hand an application (clause 11.7), measured on the guests

Measured on 2026-10-23 on the four guests, the Showcase from this branch open beside a native
window; every setting was turned back as it was found. The contrast setting was switched by script
on Windows (opening the theme file, as the Settings app does) and GNOME (`gsettings`), and by the
owner in the Display panel on macOS, which a script cannot switch (ADR 047 §0). Ratios are the WCAG
2.1 formula over the colours read, alpha blended over the colour beneath.

**Windows 11 ARM64.** A contrast theme is a whole palette, and the four shipped ones differ:

| Theme (file) | WINDOW | WINDOWTEXT | HOTLIGHT | GRAYTEXT | HIGHLIGHT / HIGHLIGHTTEXT | BTNFACE / BTNTEXT |
| --- | --- | --- | --- | --- | --- | --- |
| No. 1 (`hc1`) | #2D3236 | #FFFFFF | #70EBDE | #A6A6A6 | #A1BFDE / #212D3B | #2D3236 / #B6F6F0 |
| No. 2 (`hc2`) | #000000 | #FFFFFF | #8080FF | #A6A6A6 | #D6B4FD / #2B2B2B | #000000 / #FFEE32 |
| Black (`hcblack`) | #202020 | #FFFFFF | #75E9FC | #A6A6A6 | #8EE3F0 / #263B50 | #202020 / #FFFFFF |
| White (`hcwhite`) | #FFFAEF | #3D3D3D | #1C5E75 | #676767 | #903909 / #FFF5E3 | #FFFAEF / #202020 |
| No theme | #FFFFFF | #000000 | #0066CC | #6D6D6D | #0078D7 / #FFFFFF | #F0F0F0 / #000000 |

- Every pair a component would draw clears what it needs in all four: text on the window 10.4 to
  21:1, disabled text 5.3 to 8.6, links 6.4 to 11.5, the selection's text on it 7.0 to 8.0, the
  selection against the window 6.8 to 11.8, button text 10.8 to 17.5. Used as given, the user's
  colours are enough; nothing needs deriving.
- `BTNFACE` equals `WINDOW` in all four: a button is not told from the window by its fill, only by a
  boundary, which is §1.4's first rule.
- Each switch arrived at a top-level window as one `WM_SYSCOLORCHANGE`, one `WM_THEMECHANGED` and
  three or four `WM_SETTINGCHANGE`; `SPI_GETHIGHCONTRAST` answered on with the theme's name.
- The system redrew the window's frame in the theme's colours; the Showcase's content stayed in its
  palette in all four.
- Not measured: a theme the user edited by hand.

**Ubuntu 24.04 GNOME (X11).** The portal's `contrast` read 1 with High Contrast on and 0 with it off,
in light and in dark (`color-scheme` 0 and 1); the theme name stayed `Yaru`. The platform's own
widgets (a calculator, a dialog) kept light or dark and their fills, and gained a boundary on every
button, sampled at a key's edge:

| | Without High Contrast | With it |
| --- | --- | --- |
| Light | no boundary; the fill #D4D4D4 on #FAFAFA, 1.42:1 | #7E7E7E on #FAFAFA, 3.89:1 (2.69 against the fill) |
| Dark | no boundary; the fill #555555 on #2C2C2C, 1.87:1 | #A7A7A7 on #242424, 6.45:1 (3.35 against the fill) |

The accent (the calculator's equals key) became more saturated. The Showcase did not change.

**macOS 27 (the GPU-less guest, the Showcase on ANGLE).** With Increase Contrast on, the system
colours resolve to new values; the named accessibility appearances resolve to the ordinary ones
while the setting is off, so they can be read only with it on:

| Light (Aqua) | Off | On |
| --- | --- | --- |
| `labelColor` | black at 0.85 | black, opaque |
| `secondaryLabelColor` | black at 0.50 | black at 0.76 (10.86:1) |
| `separatorColor` | black at 0.10 (1.25:1) | black at 0.35 (2.43:1) |
| `gridColor` | #E6E6E6 | #A5A5A5 |
| `controlAccentColor` | #007AFF (4.02:1) | #0040DD (7.56:1) |
| `keyboardFocusIndicatorColor` | #0067F4 at 0.50 (2.15:1) | #0022AF, opaque (11.36:1) |

In dark the same moves: separators white at 0.30 on #202020 (2.71:1), the focus indicator #469AF5
opaque (5.59:1), the accent #1774DA. The system's own controls gained an outline (the calculator's
keys a light ring); window backgrounds did not change. The Showcase did not change. So macOS keeps
the palette and strengthens text, the accent and the focus ring, and leaves its separators under
3:1: a divider is not a control's boundary, as §0.2 reads 1.4.11.

**Fedora 44 KDE Plasma 6.7.** The portal's `contrast` reads 0, and the colour schemes installed are
Breeze Classic, Dark and Light, none a high-contrast one: nothing to follow (§5).

**What the readings settle.** On Windows the user's colours are a complete, already-sufficient
palette (§1.3 takes them as given). On macOS and GNOME the platform keeps the palette and adds
boundaries and strength, which is the strengthened copy of §1.3; GNOME's light boundary sits at
3.89:1, so §1.3's bar for boundaries under a contrast palette is set at that level's neighbour,
4.5:1, rather than above what the platform itself draws. Every native piece the probes showed (the
frame, the system's own windows) followed by itself; the Showcase followed nothing.

## 1. Decision

### 1.1 1.4.11 holds in every palette, all the time

1.4.11 is not a mode: it applies to the application as it ships. So every palette, the shipped ones
and the contrast palettes of §1.3, meets it, and `ThemeContrastTest` holds them to it:

- **A control's boundary at 3:1** against every surface a control is drawn on (canvas, surface,
  raised surface), for every component §0.3 finds identified by its border. Two ways to get there,
  for the owner (§6 question 1):
  - **(a) A new token for control boundaries** at 3:1, `outline` staying at 2:1 for what is not a
    control's boundary (dividers, card edges, a table's grid). Recommended: it changes the controls
    and leaves the rest of each palette's look as it is.
  - **(b) `outline` itself at 3:1** in every palette. One token fewer, and every divider and card
    edge gets heavier too.
- **Every state indicator at 3:1** against the colours next to it, as the audit lists them: the
  selected row's fill against its neighbours or a mark that clears it, a check mark against its
  box, a slider's filled track against the empty one, the focus ring against the canvas (met) and,
  where it is drawn on an accent fill, against that fill (2:1 today; the ring moves outside the fill
  or its colour moves).
- **Graphical objects needed to understand content at 3:1**: icons that carry meaning are drawn in
  text tones, which clear it; a chart's series colours clear 3:1 on the chart surface, or the
  chart draws its series with a mark that does (§6 question 2).

### 1.2 The system's request for contrast is followed by default

Clause 11.7 asks for a mode that follows the platform's colour and contrast settings. Offering it
only to applications that write the code would leave almost every Limn application without it, so
while `increaseContrast()` is true every window paints with the contrast palette of §1.3, and goes
back to its palette when the setting is off. `Theme.current()`, the application's choice, does not
change: the contrast palette is laid over it behind `Theme.of(widget)`, the way a printed page's
palette already is. This reverses ADR 047 §1.4 for contrast only; the appearance stays the
application's choice (ADR 047 §1.2).

An application can refuse (§6 question 6): an application that brings its own contrast mode
keeps its palette while the setting is on, and the Javadoc says that clause 11.7 is then its own to
meet.

### 1.3 The contrast palette

- **Windows: the user's colours.** The user chose a whole palette, so the palette is built from the
  eight system colours and nothing of the application's: `WINDOW` for the canvas and every surface,
  `WINDOWTEXT` for text, muted text and every boundary, `HIGHLIGHT` and `HIGHLIGHTTEXT` for
  selection and the accent, `HOTLIGHT` for links and the focus ring, `GRAYTEXT` for disabled,
  `BTNFACE` and `BTNTEXT` for buttons. The semantic tones (danger, success, warning, info) have no
  system colour and take `WINDOWTEXT`. Rebuilt on every change.
- **macOS and GNOME: a strengthened copy of the application's palette**, same light or dark and
  same accent, derived by rule: muted text moves to text, every boundary to text, surfaces apart
  until they reach their bar. Held to 7:1 for text on every surface and 4.5:1 for every boundary
  and indicator, against 1.4.11's 3:1, because the user asked for more than the default.
- The shipped `High Contrast` palette stays a palette an application may choose. It is not what the
  system gets, since it is dark whatever the user chose.

### 1.4 What a component draws under a contrast palette

Colour alone does not carry everything 1.4.11 asks for under a contrast palette: on Windows the
canvas, every surface and every button face may be the same colour, so a control the default
palettes tell apart by fill has nothing left but its border. One flag on the palette,
`Theme.contrast()`, true for the contrast palettes and for the shipped `High Contrast`, and while it
is set:

1. Every control draws its boundary, whatever its style, a filled control included.
2. Hover and pressed add a boundary rather than a tint, which may not survive the system's colours.
3. The focus ring is two pixels wide and opaque.
4. Shadows become a boundary, and frosted backdrops and translucent popups paint opaque: a
   translucent surface over content cannot promise any contrast.
5. Content is left as it is: an image, a video, a 3D view. A monochrome icon takes the text colour.

## 2. Alternatives considered

- **Keep ADR 047 §1.4 and document a recipe for 11.7.** Almost no application would follow it.
- **Move to the shipped `High Contrast` palette when contrast is on.** A user who chose a light
  contrast theme on Windows gets black, and a Mac user who asked for more contrast gets a different
  application.
- **Strengthen by rule on Windows too.** On Windows the user chose those colours, sometimes for a
  condition that makes others unreadable; 11.7 asks for theirs.
- **Meet 1.4.11 only under a contrast palette.** 1.4.11 is level AA for the application as it
  ships; a mode the user has to turn on does not meet it.

## 3. What it costs

- **The palettes:** one token (§1.1 a) or every `outline` (b) re-solved to 3:1 in fourteen palettes,
  and `ThemeContrastTest`'s border bar raised with it.
- **The audit (§0.3):** a component-by-state table as a test, and the fixes it finds.
- **Windows:** eight `GetSysColor` calls and `WM_SYSCOLORCHANGE` in the reader that already hears
  `WM_SETTINGCHANGE`.
- **The toolkit:** the contrast palette resolved behind `Theme.of`, the derivation rule,
  `Theme.contrast()` and its five rules in the components that choose those colours, the refusal.
- **Tests:** the derived palettes held to their bars, a headless test per rule, the capture gallery
  under the contrast palettes, a live reading on each guest.

## 4. Verification, when built

- `ThemeContrastTest`: every shipped palette meets §1.1; the strengthened copy of each meets §1.3;
  the Windows palette built from each measured contrast theme maps every token.
- The audit's test: every component and state has a row, and every row clears 3:1 in every palette.
- Headless: with contrast on, `Theme.of` answers the contrast palette and `Theme.current()` is
  unchanged; off again, the application's; a change is a repaint, not a relayout; the refusal keeps
  the application's palette.
- The capture gallery in each palette, contrast palettes included, looked at by the owner.
- Live, on each guest: the setting turned on with the Showcase open, followed at once.

## 5. What this is not

- Not the rest of clause 11.7: font type, font size and the focus cursor.
- Not other WCAG criteria (use of colour alone, text contrast, which `ThemeContrastTest` already
  holds at 4.5:1).
- Not the neighbouring system settings: reduce transparency, differentiate without colour.
- Not KDE's colour schemes.

## 6. Questions for the owner

1. **The control boundary (§1.1):** a new token at 3:1, keeping `outline` at 2:1 for dividers and
   card edges, or `outline` itself at 3:1? *Recommended: the new token.*
2. **Charts (§0.3, §1.1):** series colours that clear 3:1 on the chart surface, or a mark per
   series (a shape on each point, a dash per line) that does? *Recommended: colours that clear it*;
   the audit says how many slots move.
3. **Selection and the keyboard's item (§0.3)**, today tints at 0.16 to 0.28 alpha (1.23 to
   1.38:1): a fill strong enough to clear 3:1, which changes how every list, table, tree, menu and
   combo looks, or the tint kept and a 2 pt mark at the row's leading edge in `primary` (3.01 and
   up) added for selection, and the focus ring drawn round the keyboard's item as lists already do?
   *Recommended: the mark and the ring*; they keep the look and clear the bar in every palette.
4. **Rails, tracks and the scroll bar (§0.3)**: the rail and the thumb in the boundary token of
   question 1 rather than translucent text? *Recommended: yes.*
5. **Contrast followed by default (§1.2)**, reversing ADR 047 §1.4? *Recommended: yes.*
6. **The refusal (§1.2)**, `Theme.followSystemContrast(boolean)`, beside `apply()`? *Recommended:
   yes.*
7. **On Windows, the user's colours (§1.3)** rather than a strengthened copy? *Recommended: yes.*
8. **Before 1.0?** *Recommended: yes*: §1.1 and §1.2 change what an application that does nothing
   looks like, which is cheaper before the compatibility promise than after it.

## 7. The owner's answers, 2026-10-24

Every question took its recommendation:

1. A new control-boundary token at 3:1 in every palette; `outline` stays at 2:1 for dividers, card
   edges and grids.
2. The default chart series colours are re-solved to clear 3:1 on canvas, card and popover in light
   and dark, and the axis line takes the control-boundary token.
3. Selection keeps its tint and gains a 2 pt mark in `primary` at the row's leading edge; the
   keyboard's item in menus, the menu bar and lists is drawn with the focus ring, as ListView's
   focused row already is.
4. Slider and RangeSlider rails, ProgressBar's track and the ScrollBar's thumb take the
   control-boundary token.
5. The system's request for contrast is followed by default; ADR 047 §1.4 is reversed for contrast
   only, and the appearance stays the application's (ADR 047 §1.2).
6. The refusal is `Theme.followSystemContrast(boolean)`, beside `apply()`.
7. On Windows the contrast palette is the user's eight system colours, used as given.
8. Before 1.0.

Once built, `NonTextContrastAuditTest` asserts the bar: every BOUNDARY, STATE and GRAPHIC row at 3:1
in every palette, and the contrast palettes at §1.3's.

## 8. Built, 2026-10-24

- **1.4.11 in every palette (§1.1, answers 1 to 4).**
  - `Theme.controlBorder()` is the palette's outline moved toward its text, in steps of one hundredth, only until it clears 3:1 on canvas, card and popover. It is derived as `primaryText()` is, so an application's palette has it without a key in the theme file.
  - It draws the edge of every control the audit found identified by its edge:
    - the secondary button, the check box, the switch, the radio button and the segmented control;
    - every field box: text field, text area, spinner, combo box, date field, date picker;
    - the colour pickers, the split pane's divider, and a clickable card.
  - Rails and tracks: a slider's rail and a progress bar's track are an empty pill in it, with the accent against the page. A faint fill read 1.26:1, and an accent on a solid grey would not stand apart. The scroll bar's thumb is that colour.
  - Selection:
    - A selected row of a list, a table or a tree keeps its tint and gains a 2 pt bar in the accent at its leading edge (`Strokes.SELECTION_MARK`).
    - A calendar range gets a line under its band.
    - The active part of a focused time spinner or date field gets a line under it.
  - The keyboard's item in a menu, a combo box's list, the tab list and the menu bar is ringed in the focus colour.
  - Charts:
    - `ChartPalette.defaultFor` solves the eight slots per palette in OKLab lightness only, keeping hue and chroma, until each clears 3:1 on canvas, card and popover. It then pushes apart any neighbouring pair the move brought under the separation the slots were chosen for: OKLab ΔE × 100 of 15 under normal vision and 8 under simulated protanopia and deuteranopia (Machado 2009).
    - `ChartPaletteTest` checks both in all fifteen palettes with an arithmetic of its own. The axis line takes the control edge.
  - `NonTextContrastAuditTest` now asserts the bar. All 120 required component states clear 3:1 in 34 palettes: the fifteen shipped, each one's strengthened copy, and the four made of Windows' contrast themes.
- **The system's contrast followed (§1.2, §1.3, answers 5 to 7).**
  - `SystemPreferences.contrastColors()` carries a Windows contrast theme's eight system colours, read by `GetSysColor` while one is on.
  - `Theme.of(widget)` answers the contrast palette while `increaseContrast()` is true, and `Theme.current()` is unchanged:
    - on Windows, the user's colours as given (`Theme.fromSystemColours`);
    - elsewhere, the palette strengthened (`Theme.strengthened`): text and muted text at 7:1, outline, focus ring, accent and semantic tones at 4.5:1, and an accent's text taking whichever of the canvas or the text reads better on it.
  - `SystemPreferences.report` repaints every scene when the request or the colours change.
  - `Theme.followSystemContrast(false)` refuses.
  - `ContrastPaletteTest` holds the strengthened copies to those bars and the Windows palette to its eight colours, and checks that a widget follows the request, the refusal and the withdrawal.
- **§1.4's drawing rules, under `Theme.contrast()`** (true for the contrast palettes and the shipped High Contrast):
  - nothing casts a shadow;
  - every button and every elevated card wears its edge;
  - a `BackdropPanel` paints opaque.
- **Not built, and why:**
  - **Rule 2 (hover as an edge).** 1.4.11 asks nothing of hover, and each hover tint stays.
  - **Rule 3 (a 2 pt ring everywhere).** The check box's, radio button's and list's rings stay at their locked 1.5 pt, which clears 3:1 in every palette. A thicker ring is 2.4.13's, at level AAA.
  - **Popups.** Their fills keep their 0.94 to 0.98 alpha, which keeps their contrast within a hundredth.
- **The live reading of §4, 2026-10-24**, with the Showcase built from this record's commits, each setting turned on and put back:
  - **Windows 11 ARM64.** The contrast theme was switched by opening its file, as the Settings app does.
    - Under contrast theme No. 2, the Showcase painted black with white text, and its selection bar was the theme's lilac highlight.
    - Under the white theme, it painted the #FFFAEF canvas, #3D3D3D text and a #903909 bar.
    - The frame followed as before, and the theme was put back.
  - **Ubuntu GNOME.** The setting was switched by `gsettings`. With High Contrast on, the portal said 1, and 58,469 pixels inside the Showcase's window changed. A field's edge went from #717985 to #9198A2 (the dark palette strengthened).
  - **macOS 27, the GPU-less guest, on ANGLE.** The owner switched Increase Contrast on, and the System Settings toggle was switched back off by UI automation. With it on:
    - muted text (a count, the table's header, a field's placeholder) took the text colour;
    - field edges darkened.
  - Every guest was left as it was found.