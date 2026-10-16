# ADR 070: Access letters come from the translated text

- **Status:** Proposed, 2026-10-16. The owner asked for the access letters of menus, buttons and
  labels to follow the language, and for this record before the code.
- **Date:** 2026-10-16
- **Scope:**
  - the underlined letter of a menu title and a menu item;
  - the underlined letter of a button, a check box, a radio button, and a label that names a
    control;
  - when each letter is drawn, and on which system;
  - what pressing it does.
- **New public API:** an `&` before a letter in a text marks it. The API that carries the letter
  apart from the text is removed: `MenuItem.setMnemonic(char)` and `MenuBar.addMenu(title, char,
  menu)`.
- **Compatibility:** breaking inside the repository only (there are no consumers yet). Text with
  a literal `&` before a letter or digit writes it `&&`.

---

## 1. Why

The Showcase's menus, translated into 21 languages, lost their access letters. A letter was
given in English (`'F'` for File) next to a title that a language translates. "Arquivo" has no F,
so in Portuguese only "Editar" kept its mark.

A letter belongs to a word, and the word belongs to the translation. Windows, GTK and Qt
therefore all carry the letter inside the translated string ("&File", "_File"), and the
translator picks it for each language.

There is a second reason. The mark was drawn at all times and on every system, macOS included,
where no native application has access letters.

## 2. What the systems do

| | Windows | Linux (GTK, KDE) | macOS |
|---|---|---|---|
| Access letters | Menus, buttons, check boxes, radio buttons, labels of fields | The same | None, anywhere |
| Where the letter comes from | `&` in the localized string | `_` or `&` in the localized string | — |
| When the mark is drawn | While Alt is held, and in a menu opened from the keyboard (the system default since Windows 2000) | While Alt is held | Never |
| Alt+letter on a button | Presses it | Presses it | Option+letter types a character |
| Alt+letter on a field's label | Focuses the field | Focuses the field | — |
| CJK languages | The English letter in parentheses: "ファイル(&F)" | The same | — |
| Accelerators (Ctrl+S, ⌘S) | Unrelated to the letter; not localized | The same | The same |

Navigating menus from the keyboard on macOS is Ctrl+F2 followed by typing an item's name, and
that needs nothing from the application.

## 3. Decision

### 3.1 The letter is in the text

- **`&` before a letter or a digit marks it**: "&Arquivo", "Sa&lvar", "ファイル(&F)".
  - The `&` is not shown.
  - `&&` is one literal ampersand.
  - An `&` before anything else (a space, the end of the text) is a literal ampersand, so
    "Terms & conditions" needs no escaping.
  - "AT&T" does need it: write "AT&&T".
- **Only the first mark counts.** A second one is drawn as text and logged once, as a mistake in
  the string.
- **Read wherever the text is set**, as a `String` or an `I18nString`. A language change
  re-reads it, so each language carries its own letter.

### 3.2 Which widgets

| Widget | The letter does, on Alt+letter |
|---|---|
| Menu bar title | Opens that menu (as today) |
| Menu item | With the menu open, the letter alone chooses it (as today) |
| `Button` | Presses it, as a click would |
| `Checkbox` (both variants), `RadioButton` | Toggles or selects it, and focuses it |
| `Label` with `setLabelFor` | Focuses the control it names |
| Dialog buttons | Are `Button`s, so as above |

- **A `Label` without `setLabelFor`** has no access letter. It shows its text as written, so an
  `&` there is literal: a plain label of text must not need escaping because some other widget
  parses it.
- **Tabs, expanders, segmented controls and combo boxes** are out of this record. Windows gives
  them letters in a few places only. Their own keys (arrows, Ctrl+Tab, typing in a list) already
  reach them.

### 3.3 When the mark is drawn

- **Windows and Linux:** while Alt is held, and in a menu opened from the keyboard (Alt, F10,
  Alt+letter, the Menu key). Released, it goes. This is what both systems do by default.
  - A setting to always show it (Windows has one, under keyboard accessibility) is followed
    where the system answers; otherwise the default holds.
- **macOS:** never.
  - Option+letter keeps typing its character, and nothing is activated.
  - A CJK string's trailing "(&F)" is dropped whole, parentheses included, as Japanese and
    Chinese Mac applications write "ファイル" bare.

### 3.4 Which control a letter reaches

- **The scope is the window, or the topmost modal dialog in it.** A letter never reaches past a
  modal to the window beneath.
- **The menu bar's titles come first**, then the controls, in focus order. This is the order the
  bar already resolves Alt+letter against accelerators.
- **Two controls with one letter:**
  - Each press moves focus to the next of them, and none is activated, as in Windows dialogs.
  - The duplicate is logged once, so a translator's collision is found in development.
- **Hidden and disabled controls** are skipped.

### 3.5 Readers

The letter is published as the control's access key, where each bridge has one:
- UI Automation `AccessKey` ("Alt+A");
- AT-SPI's key binding.

macOS has none. The tray menu keeps passing the letter to the native menu, which marks it the
system's way.

## 4. The Showcase

- Its menus, buttons and form labels carry `&` in English, and the 21 translations are given
  their letters by the same pass that translated them, each letter unique within its menu or
  form.
- Japanese, Korean and Chinese use "(&F)".
- A test holds every bundle to one mark per string, and to no duplicate letter within one menu.

## 5. Not done

- **Plain letters (no Alt) in dialogs**, which Windows accepts when focus is not in a text field.
  Alt+letter is the one rule here, and it never collides with typing in a list or a field.
- **Key tips (letters over ribbon buttons).** Limn has no ribbon.
- **Accelerators.** They stay as ADR 060 and the menus define them: one per command, unchanged by
  language.

## 6. Tests

- **Parsing:**
  - `&`, `&&`, a trailing `&`, `&` before a space;
  - two marks;
  - "(&F)" on macOS and elsewhere.
- **Drawing:** the mark is drawn only while Alt is held or a menu was opened from the keyboard,
  and never on macOS.
- **Each widget's action on Alt+letter,** and the scope:
  - a modal dialog's letter does not reach the window beneath it;
  - two controls with one letter cycle focus;
  - hidden and disabled controls are skipped.
- **A language change** moves the letter.
- **The bridges' access key.**
- **The Showcase's bundles** hold one letter per string and none repeated in a menu.
