---
title: "Text and languages"
description: "How text is measured and drawn, how to translate an application, and which scripts are supported today."
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
locale translates the parts you did not write as well.

## What the text stack does

- **Measures what it draws.** Ellipsis, wrapping and hit-testing all use the same measured
  advances the renderer uses, so a label that says it fits, fits.
- **Falls back per character.** A string that mixes Latin, Greek, Cyrillic and CJK is drawn
  from whichever face covers each character, without you selecting fonts.
- **Edits by grapheme cluster.** Arrow keys, selection and backspace move by what a reader
  would call a character, so combining marks and multi-part emoji are never split in half.
- **Supports IME composition.** Japanese, Chinese and Korean input methods show their
  in-progress composition inside the field, underlined, with the candidate window positioned
  against the caret.

{% shot kitchen-ja "The same screen in Japanese, with the text stack picking a CJK face per character." %}

## What it does not do

There is no complex-script shaping. Arabic, Hebrew and the Indic scripts need contextual
joining and reordering that the toolkit does not implement, so they are **not supported**:
translations for them are deliberately not shipped rather than rendered incorrectly. There
is no right-to-left layout direction either.

If your application must serve those readers, this is the constraint to weigh before you
adopt the toolkit, not after.
