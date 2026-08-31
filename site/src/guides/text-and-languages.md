---
title: "Text and languages"
description: "How text is shaped, measured and drawn, how to translate an application, and what a screen in Arabic, Hebrew, Hindi or Thai gets today."
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
scene.root().markNeedsLayout();
```

The relayout is required for the same reason a theme switch needs one: translated text is a
different width, so everything has to be measured again.

The toolkit's own strings (the colour picker's labels, the theme names, the built-in
component text) already ship translated for a long list of language tags, so switching the
locale translates the parts you did not write as well. A language the toolkit has no file
for falls back to English, by the same rule your own bundles follow.

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

## Right to left is text, not layout

:::caution[The layout is not mirrored]
An application in Arabic or Hebrew gets correct text in a left-to-right window. Direction is
not a layout axis here: a run starts at the left edge of the box it is given, a `Label`'s
`HAlign.START` is the left edge however the text reads, icons stay on the side you put them,
and menus, dialogs, scrollbars and progress bars all run the way they do in English. It is
the first thing you will hit, and it is work that has not been done rather than a decision
against doing it.
:::

Mirroring a screen by hand works, because it is ordinary layout code and nothing in the
toolkit pulls the other way. Right-align a label by asking for the other end:

```java
label.setAlign(HAlign.END, VAlign.CENTER);
```

Build the row's children in the other order, put the leading icon on the trailing side,
swap the padding. What you cannot do is have the toolkit decide any of it from the locale.

Two more things stay on your side of the line:

- **Set the UI locale, not only your strings.** Line breaking asks the language `I18n` is
  currently in, so Thai wraps like Thai only when `I18n.setLocale` has actually been given
  Thai; the same sentence inside an English interface offers nowhere to break at all and is
  cut where it stops fitting.
- **Format numbers for the locale yourself.** The toolkit renders the digits it is given and
  substitutes nothing, so Arabic-Indic digits are `NumberFormat.getInstance(locale)` on your
  side. The result lays out correctly wherever you then put it.

## If you draw text yourself

A custom widget that calls `canvas.drawText(text, x, y, font, ink)` gets shaped text: the
canvas hands the string to the installed ruler and draws what comes back, so every script
above is correct without your doing anything. What it does not get is the *geometry* — where
a caret goes, which index is under an x — and it pays a cache lookup on every frame that
draws it. The shaped form is two calls, and the value is meant to be *held* rather than
rebuilt every frame, because shaping is the expensive half of drawing text:

```java
if (shaped == null || !shaped.matches(text, font, textRuler())) {
    shaped = textRuler().shape(text, font);
}
canvas.drawText(shaped, x, baseline, ink);
```

`matches` compares the three things that can invalidate it — the text, the font, and the
ruler's own state — so a theme change, a control-size step or a font arriving in the
background all re-shape, and nothing else does. The value answers everything else you would
otherwise measure a prefix for: where a caret goes, which index is under an x, which boxes
cover a selection, where to cut for an ellipsis.

## What it does not do

- **Mirrored layout**, as above: the single largest gap, and the one to weigh before you
  adopt the toolkit for a right-to-left market rather than after.
- **Vertical writing.** Mongolian and the vertical form of CJK need a second axis through
  the whole layout system, not a direction flag.
- **Justification and hyphenation.** Lines are broken, not fitted: the right edge of a
  wrapped paragraph is ragged.
- **Locale-aware sorting and case mapping.** `Collator` and the locale-sensitive
  `String` methods are in the JDK and are yours to call; nothing in the toolkit sorts a list
  for you.
