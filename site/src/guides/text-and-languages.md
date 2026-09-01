---
title: "Text and languages"
description: "How text is shaped, measured and drawn, how to translate and mirror an application, and what a screen in Arabic, Hebrew, Hindi or Thai gets today."
---

## Text as a value, not a string

Anywhere a component takes a `String` it also takes an `I18nString`, a piece of text that
knows its key and can re-resolve itself when the language changes:

```java
private static final I18nString SAVE = new I18nString("editor.save", "Save");
...
toolbar.addItem(new Button(SAVE));
```

The second argument is both the default text and the fallback for every key no bundle
answers, so an application that never registers a translation behaves exactly as it would
with plain strings. A missing translation degrades to English rather than showing a key
name on screen.

Declare them `static final`. The resolution is cached per key for the whole process, so a
list showing five hundred rows of the same label resolves once per language change rather
than five hundred times.

## Adding a language

Translations are ordinary `.properties` files on the classpath, one per language tag:

```
src/main/resources/i18n/app.properties
src/main/resources/i18n/app_pt-BR.properties
src/main/resources/i18n/app_ja.properties
```

Register the family once, at startup:

```java
I18n.addBundle(PropertyBundle.family("i18n/app"));
```

Then switch languages at runtime:

```java
I18n.setLocale(Locale.forLanguageTag("pt-BR"));
```

The switch re-lays-out every open window on its own, for the same reason a theme switch
does: translated text is a different width, so everything has to be measured again.

The toolkit's own strings (the colour picker's labels, the theme names, the built-in
component text) already ship translated for a long list of language tags, so switching the
locale translates the parts you did not write as well. A language the toolkit has no file
for falls back to English, by the same rule your own bundles follow.

## A subtree can hold its own language

The locale does not have to be process-wide. A widget can declare one for itself and
everything below it:

```java
codePane.setLocale(Locale.ENGLISH);
```

Inside that subtree, everything that resolves text resolves in that language — string
lookups, message formats, digits, line breaking — while the rest of the window stays in the
process locale. The value inherits the way the control size does: a widget's own declared
locale, else its nearest declaring ancestor's, else the window's (`scene.setLocale(…)`),
else the process one, and a popup or dialog follows the widget that opened it.
`setLocale(null)` returns a subtree to inheriting.

The locale matters beyond translations, wherever it is set. Line breaking asks the
effective language for its break opportunities, so Thai wraps between its words only where
the locale actually says Thai; the same sentence inside an English interface offers nowhere
to break at all and is cut where it stops fitting.

## What the text stack does

- **Shapes a line before it measures or draws it.** The glyphs are chosen by the font's own
  layout tables instead of one glyph per character, which is the difference between a script
  rendering and a script rendering *correctly*: Arabic letters join and take their
  contextual forms, a Devanagari conjunct is one glyph made of three characters, a
  Devanagari vowel sign draws before the consonant it follows in the string, Hebrew and Thai
  marks are placed on the letter they belong to, and Latin picks up the ligatures and
  kerning its face was built with.
- **Measures what it draws.** Ellipsis, wrapping, the caret and hit-testing all come off the
  same shaped line the renderer paints, so a label that says it fits, fits, and a click
  lands on the character under the pointer even where the glyphs are not in the order the
  characters are.
- **Resolves a face per run, not per character.** A string that mixes Latin, Greek, Cyrillic,
  CJK and Arabic is cut into runs of one script and one direction, and each run picks its
  face once, before any glyph exists. It has to be that way round: a word split in half by a
  per-character decision would shape as two words. None of it asks you to name a font.
- **Orders mixed text by the Unicode bidirectional algorithm.** `Total: 42 ريال (SAR)` is
  three runs — left to right, right to left, left to right — and they are ordered on the
  line without you marking anywhere that the direction changed. A paragraph's own direction
  is read from its first strong character.
- **Edits by cluster, and moves by what is on the screen.** Arrow keys, selection and
  backspace move by what a reader would call a character, so combining marks, multi-part
  emoji and conjuncts are never split in half. Left and Right step to what is next
  *visually*; Home, End and the word jumps step through the *string*, so in right-to-left
  text Left and Ctrl+Left move opposite ways — which is what Windows and GTK do, and is
  forced by the fact that a selection has to be one range of the text. A selection crossing a
  direction boundary is painted as the two or more boxes it actually covers, never as one
  rectangle over the text between them.
- **Breaks lines with the reading rules of the UI language.** Wrapping asks the language for
  its break opportunities instead of looking for spaces, so Chinese and Japanese wrap where
  they should and Thai, which writes without spaces at all, breaks between its words.
- **Supports IME composition.** Japanese, Chinese and Korean input methods show their
  in-progress composition inside the field, underlined, with the candidate window positioned
  against the caret.

{% shot kitchen-ja "The same screen in Japanese: the CJK face is resolved for the run, and the application named no font." %}

## Which scripts render

Latin, Greek and Cyrillic come from the default family. Everything else comes from a Noto
face the backend carries and parses in the background the first time a character needs one:
Chinese, Japanese and Korean from the pan-CJK face, Arabic, Hebrew, Devanagari and Thai from
one small face each, and emoji in colour. There is nothing to install, nothing to register
and nothing to choose; a code point that no face at all covers is the only thing that draws
as an empty box.

So the answer to "can I put this language in a `Label`" is now yes for every script above.
What is left to weigh is everything around the text, which is the rest of this page.

## Right to left is text, and layout

An application in Arabic or Hebrew gets correctly shaped text in a mirrored interface, and
the mirroring is one line:

```java
scene.setLayoutDirection(LayoutDirection.RTL);
```

Direction is an inherited axis, resolved per subtree the way the control size and the
locale are, and everything that is about reading order follows it: rows lay their children
from the right, menus, popups, scrollbars and sliders mirror, a horizontal scroll starts at
the reading edge, and the arrow keys keep meaning what is on the screen.
Your own layout code follows without changing, because the alignment API was already
logical: `HAlign.START` and `MainAlignment.END` name the reading edges, so a dialog whose
buttons sat at `END` finds them at the other side of the window with no edit at all.

:::note[Direction is not the locale]
The toolkit never decides a direction from a language. A Hebrew interface usually holds at
least one left-to-right island — a code editor, a log pane, a URL bar — and a subtree in
another language does not stop reading the way its surroundings do. So the two are declared
separately, one line each, and `LayoutDirection.forLocale(locale)` is the bridge an
application calls once at startup when they do move together:

```java
scene.setLocale(locale);
scene.setLayoutDirection(LayoutDirection.forLocale(locale));
...
codePane.setLayoutDirection(LayoutDirection.LTR);
```
:::

Some things deliberately do not mirror, because they are not about reading order: a check
mark stays a tick, a media transport keeps its tape-deck arrangement and its left-to-right
scrub bar, a time axis keeps the newest sample at the right, a pan control's left end is
still the left speaker, and `Ctrl+→` still names the key with that arrow printed on it.

Icons are your word, not the toolkit's. Only the call site knows whether an arrow means
"back" (which mirrors), "download" (which does not), or is a shape inside a logo (which
must not), so directional icons are marked where you place them:

```java
back.setIcon(TablerArrows.ARROW_LEFT.icon(), Icon.Mirroring.IN_RTL);
```

Everything else is drawn as authored, so a brand mark can never come back flipped.

## Numbers, order and case follow the language

The numbers the toolkit itself renders — a spinner's value, a chart's axis and tooltip
labels, the media player's clock — take the locale's digits at the moment they are
formatted. Under Arabic a spinner shows `٤٢` and its editor accepts Arabic-Indic
keystrokes; under Hebrew the digits stay Latin, because that is how Hebrew writes numbers;
`I18n.setNumberingSystem(…)` overrides the choice for a deployment that wants the other
convention. Your own strings are never rewritten — a part number keeps exactly the
characters you authored — and the same seam is public for your own formatting:
`I18n.localizeDigits(text)` on the way out, `I18n.toAsciiDigits(text)` on the way back in.

Ordering and case mapping ask the language too. The toolkit shows lists in the order you
hand them — order is content — so sorting stays one call on your side, through a collator
in the language the user is reading:

```java
names.sort(I18n.collator());
```

`I18n.toUpperCase` and `I18n.toLowerCase` case whole strings the same way, which is what
`ComboBox` type-ahead already does: "stras" finds "Straße", and a Turkish i finds İstanbul
on purpose rather than by accident. When the content is not in the interface's language —
an English interface listing Swedish names must still put ä after z —
`I18n.setTextLocale(…)` declares the language the text is in, and order and case follow it
instead.

## If you draw text yourself

A custom widget that calls `canvas.drawText(text, x, y, font, ink)` gets shaped text: the
canvas hands the string to the installed ruler and draws what comes back, so every script
above is correct without your doing anything. What it does not get is the *geometry* —
where a caret goes, which index is under an x — it pays a cache lookup on every frame that
draws it, and a string with no strong character of its own (a price, a count, a clock face)
falls back to left to right instead of reading the way the interface around it reads. The
shaped form fixes all three, and the value is meant to be *held* rather than rebuilt every
frame, because shaping is the expensive half of drawing text:

```java
ShapedText.Direction base = ShapedText.Direction.of(text, neutralBase());
if (shaped == null || !shaped.matches(text, font, base, textRuler())) {
    shaped = shapeText(text, font);
}
canvas.drawText(shaped, x, baseline, ink);
```

`shapeText` shapes the line the way your widget reads: the first-strong rule still decides
for any string that can decide for itself, and the widget's resolved direction answers for
the rest. `matches` compares the four things that can invalidate the held value — the text,
the font, the direction, and the ruler's own state — so a theme change, a control-size
step, a direction change or a font arriving in the background all re-shape, and nothing
else does. The value answers everything else you would otherwise measure a prefix for:
where a caret goes, which index is under an x, which boxes cover a selection, where to cut
for an ellipsis.

## What it does not do

- **Vertical writing.** Mongolian and the vertical form of CJK need a second axis through
  the whole layout system, not a direction flag.
- **Justification and hyphenation.** Lines are broken, not fitted: a wrapped paragraph is
  ragged at the end of its lines.
- **Plural rules.** "1 item" and "2 items" are separate keys your code chooses between;
  there is no plural engine behind `I18nString`.
- **Localized separators in number input.** A spinner shows and accepts the locale's
  digits, but its editable value keeps the ASCII decimal point (a comma is tolerated), not
  the locale's separator.
